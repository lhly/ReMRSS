package top.zainzh.rearscreen.platform

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidHostCastTargetResolverTest {
    private companion object {
        const val PICKER_COMPONENT = "com.example.host/top.zainzh.rearscreen.platform.mods.ModCastTargetPickerActivity"
    }
    private val source = FakeSource()
    private var now = 100L
    private val resolver = AndroidHostCastTargetResolver(FakeContext(), source, clock = { now })
    private val owner = SessionOwner(11001, "com.example.host", "host-cert",
        SessionPrincipal.SignedMod("author", "demo", "stable-session"))

    @Test fun currentForegroundResolvesExactFocusedTaskAndRearIdentity() {
        val target = resolver.resolve(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
            CastMode.VIRTUAL, CastRegion.FULL, 1))
        assertNotNull(target)
        assertEquals(42, target!!.taskId)
        assertEquals("example.app/example.app.MainActivity", CastComponentIdentity.normalize(target.baseComponent))
        assertEquals("rear:77", target.rearIdentity)
        assertEquals(1, target.contentRotation)
        assertTrue(resolver.isCurrent(target))
    }

    @Test fun rejectsTaskMovedAwayAndComponentMismatch() {
        source.onMain = false
        assertNull(resolver.resolve(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
            CastMode.VIRTUAL, CastRegion.SAFE, 0)))
        source.onMain = true
        assertNull(resolver.resolve(owner, CastTargetRequest(
            CastTargetSelector.Component("example.app/example.app.OtherActivity"), CastMode.VIRTUAL,
            CastRegion.SAFE, 0)))
    }

    @Test fun componentNormalizationKeepsExactClassIdentity() {
        assertEquals("example.app/example.app.MainActivity", CastComponentIdentity.normalize("example.app/.MainActivity"))
        assertNotEquals(CastComponentIdentity.normalize("example.app/.MainActivity"),
            CastComponentIdentity.normalize("example.app/.OtherActivity"))
        assertNull(CastComponentIdentity.normalize("../bad"))
    }

    @Test fun directPhysicalIsSystemManagedSafeAndUnrotatedOnly() {
        val target = resolver.resolve(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
            CastMode.DIRECT_PHYSICAL))
        assertNotNull(target)
        assertEquals(CastRegion.SAFE, target!!.region)
        assertEquals(0, target.contentRotation)
        assertNull(target.requestedDensityDpi)
        assertNull(resolver.resolve(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
            CastMode.DIRECT_PHYSICAL, CastRegion.FULL, 0)))
        assertNull(resolver.resolve(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
            CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 1)))
        assertNull(resolver.resolve(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
            CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, 220)))
    }

    @Test fun modPickerUsesOwnerBoundOpaqueChoiceInsteadOfRawTaskId() {
        val choice = resolver.listChoices(owner).single()
        assertTrue(choice.opaqueToken.isNotBlank())
        assertEquals("example.app", choice.packageName)
        val request = CastTargetRequest(CastTargetSelector.ChoiceToken(choice.opaqueToken),
            CastMode.VIRTUAL, CastRegion.SAFE, 0)
        assertEquals(42, resolver.resolve(owner, request)?.taskId)
        assertNull("choice tokens are single use", resolver.resolve(owner, request))
    }

    @Test fun foregroundPickerKeepsSingleAppLabelAndDisambiguatesOnlyRepeatedSamePackageTasks() {
        assertEquals(listOf("Example App", "Other App"), foregroundChoiceLabels(listOf(
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Example App", "MainActivity", 42),
            ForegroundChoiceLabelCandidate("other.app", "Other App", "Other App", "MainActivity", 43),
        )))
        assertEquals(listOf("Example App · Main screen", "Example App · Controller"), foregroundChoiceLabels(listOf(
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Main screen", "MainActivity", 42),
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Controller", "ControllerActivity", 43),
        )))
    }

    @Test fun repeatedOrApplicationActivityLabelsFallBackToShortComponentAndStableOrdinal() {
        assertEquals(listOf("Example App · MainActivity", "Example App · ControllerActivity"), foregroundChoiceLabels(listOf(
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Example App", "MainActivity", 42),
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Example App", "ControllerActivity", 43),
        )))
        val reversed = listOf(
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Same", "MainActivity", 43),
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Same", "MainActivity", 42),
        )
        assertEquals(listOf("Example App · MainActivity · 2", "Example App · MainActivity · 1"),
            foregroundChoiceLabels(reversed))
    }

    @Test fun finalDisplayLabelCollisionGetsStableRowSuffixOnlyForCollidingChoices() {
        val candidates = listOf(
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Example App", "MainActivity", 42),
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Example App", "MainActivity", 43),
            ForegroundChoiceLabelCandidate("example.app", "Example App", "MainActivity · 1", "OtherActivity", 44),
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Settings", "SettingsActivity", 45),
        )
        assertEquals(listOf(
            "Example App · MainActivity · 1 · 行1",
            "Example App · MainActivity · 2",
            "Example App · MainActivity · 1 · 行2",
            "Example App · Settings",
        ), foregroundChoiceLabels(candidates))
        assertEquals("a single target keeps its original app label", listOf("Example App"), foregroundChoiceLabels(listOf(
            ForegroundChoiceLabelCandidate("example.app", "Example App", "Example App", "MainActivity", 42),
        )))
    }

    @Test fun explicitChoicePreparesHiddenExactTaskOnlyUnderCurrentPickerAndBindsTopActivity() {
        val choice = resolver.listForegroundChoices(owner).single()
        source.visible = false
        source.requested = false
        source.pickerTaskId = 91
        source.focusedTask = 91
        val request = CastTargetRequest(CastTargetSelector.ForegroundChoiceToken(
            choice.opaqueToken, 91, PICKER_COMPONENT),
            CastMode.VIRTUAL, CastRegion.SAFE, 0)
        val target = resolver.resolve(owner, request)
            ?: throw AssertionError("the visible authenticated picker should prepare its selected hidden target")
        assertEquals(42, target.taskId)
        assertEquals("example.app/example.app.MainActivity", CastComponentIdentity.normalize(target.baseComponent))
        assertEquals("example.app/example.app.DetailActivity", target.expectedTopComponent?.let(CastComponentIdentity::normalize))
        assertEquals(91, target.confirmationTaskId)
        assertTrue(resolver.isCurrent(target))
        source.topComponent = "example.app/example.app.OtherActivity"
        assertFalse("a same-package activity swap invalidates the prepared choice", resolver.isCurrent(target))
    }

    @Test fun explicitChoiceIsOwnerBoundOneShotAndRejectsPickerLoss() {
        val choice = resolver.listForegroundChoices(owner).single()
        source.pickerTaskId = 91
        source.focusedTask = 91
        val request = CastTargetRequest(CastTargetSelector.ForegroundChoiceToken(
            choice.opaqueToken, 91, PICKER_COMPONENT), CastMode.VIRTUAL, CastRegion.SAFE, 0)
        val unauthorized = owner.copy(principal = SessionPrincipal.AndroidClient(
            11001, owner.transportPackageName, owner.transportSigningCertificateDigest, 99L))
        assertNull(resolver.resolve(unauthorized, request))
        assertNotNull("wrong owner cannot consume the valid owner's token", resolver.resolve(owner, request))
        assertNull("the explicit token is one-shot", resolver.resolve(owner, request))

        val secondChoice = resolver.listForegroundChoices(owner).single()
        val secondRequest = request.copy(selector = CastTargetSelector.ForegroundChoiceToken(
            secondChoice.opaqueToken, 91, PICKER_COMPONENT))
        source.focusedTask = 42
        assertNull("preparation requires the exact picker to remain foreground",
            resolver.resolve(owner, secondRequest))
    }

    @Test fun explicitChoiceDoesNotConfuseSamePackageTaskDecoys() {
        source.secondComponent = "example.app/.ControllerActivity"
        val choices = resolver.listForegroundChoices(owner)
        assertEquals(2, choices.size)
        val main = choices.first()
        source.visible = false
        source.requested = false
        source.pickerTaskId = 91
        source.focusedTask = 91
        val request = CastTargetRequest(CastTargetSelector.ForegroundChoiceToken(
            main.opaqueToken, 91, PICKER_COMPONENT), CastMode.VIRTUAL, CastRegion.SAFE, 0)
        val target = resolver.resolve(owner, request)
        assertEquals(42, target?.taskId)
        assertEquals("example.app/example.app.MainActivity", target?.baseComponent?.let(CastComponentIdentity::normalize))
    }

    @Test fun foregroundChoicesRejectUnknownMixedSleepingAndIdentityMismatchRows() {
        source.visible = null
        source.requested = false
        assertTrue(resolver.listForegroundChoices(owner).isEmpty())
        assertTrue(resolver.listChoices(owner).isEmpty())
        source.visible = false
        source.requested = true
        assertTrue(resolver.listForegroundChoices(owner).isEmpty())
        assertTrue(resolver.listChoices(owner).isEmpty())
        source.visible = true
        source.requested = true
        source.sleeping = true
        assertTrue(resolver.listForegroundChoices(owner).isEmpty())
        assertTrue(resolver.listChoices(owner).isEmpty())
        source.sleeping = false
        source.topComponent = "other.app/other.app.OtherActivity"
        assertTrue(resolver.listForegroundChoices(owner).isEmpty())
        source.topComponent = "example.app/example.app.DetailActivity"
        source.duplicateTaskRow = true
        assertTrue("duplicate task identities cannot create an ambiguous focus token",
            resolver.listForegroundChoices(owner).isEmpty())
    }

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example.host"
    }

    private class FakeSource : HostCastTaskSource {
        var onMain = true
        var visible: Boolean? = true
        var requested: Boolean? = true
        var sleeping: Boolean? = false
        var component = "example.app/.MainActivity"
        var topComponent = "example.app/example.app.DetailActivity"
        var duplicateTaskRow = false
        var secondComponent: String? = null
        var displayId = 0
        var focusedTask = 42
        var pickerTaskId: Int? = null
        private fun json(value: Boolean?) = value?.toString() ?: "null"
        private fun taskRow(task: Int, base: String, top: String) = """{"task":$task,"base":"example.app","top":"example.app","baseComponent":"$base","topComponent":"$top","visible":${json(visible)},"requested":${json(requested)},"sleeping":${json(sleeping)}}"""
        override fun mainTaskSnapshot(): JSONObject? {
            if (!onMain) return null
            val rows = mutableListOf(taskRow(42, component, topComponent))
            secondComponent?.let { rows += taskRow(43, it, it) }
            if (duplicateTaskRow) rows += taskRow(42, component, topComponent)
            pickerTaskId?.let { rows += """{"task":$it,"base":"com.example.host","top":"com.example.host","baseComponent":"$PICKER_COMPONENT","topComponent":"$PICKER_COMPONENT","visible":true,"requested":true,"sleeping":false}""" }
            return JSONObject("""{"status":"ok","source":"activity_task","display":0,"power":2,"powerBefore":2,"powerAfter":2,"focusedTask":$focusedTask,"tasks":[${rows.joinToString(",")}]}""")
        }
        override fun rearIdentity() = 77 to "rear:77"
        override fun taskBaseComponent(taskId: Int, displayId: Int) = when {
            displayId != 0 -> null
            taskId == 42 -> component
            taskId == 43 -> secondComponent
            taskId == pickerTaskId -> PICKER_COMPONENT
            else -> null
        }
        override fun taskIdByPackage(packageName: String) = if (packageName == "example.app") 42 else -1
        override fun isTaskOnDisplay(taskId: Int, displayId: Int) = (taskId in 42..43 || taskId == pickerTaskId) && displayId == this.displayId && onMain
        override fun isExplicitTargetCurrent(target: HostCastTarget): Boolean =
            target.confirmationTaskId == pickerTaskId && focusedTask == pickerTaskId &&
                target.taskId == 42 &&
                CastComponentIdentity.normalize(target.baseComponent) == CastComponentIdentity.normalize(component) &&
                target.expectedTopComponent?.let(CastComponentIdentity::normalize) == CastComponentIdentity.normalize(topComponent)
    }
}
