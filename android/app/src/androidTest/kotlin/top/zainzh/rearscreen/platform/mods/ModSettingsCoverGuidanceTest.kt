package top.zainzh.rearscreen.platform.mods

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Uses only the M12 test-host's normal app store and a unique signed fixture. */
@RunWith(AndroidJUnit4::class)
class ModSettingsCoverGuidanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun uninstallShowsItsConsequencesAndCancelLeavesTheModInstalled() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        val archive = instrument.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(archive.inputStream())
        check(ModPackageStore.installed(app, pkg.id) == null)
        val previouslyTrusted = ModPackageStore.isAuthorTrusted(app, pkg.authorFingerprint)
        ModPackageStore.install(app, archive,
            ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        try {
            ActivityScenario.launch(ModSettingsActivity::class.java).use {
                openDetail(pkg.id)
                compose.onNodeWithTag("mod_detail_scroll").performScrollToNode(hasText("卸载…"))
                compose.onNodeWithText("卸载…").performScrollTo().performClick()
                compose.onNodeWithText("卸载 Mod？").assertIsDisplayed()
                compose.onNodeWithText("此操作不能撤销", substring = true).assertIsDisplayed()
                compose.onNodeWithText("卸载并删除数据").assertIsDisplayed()
                compose.onNodeWithText("取消").assertIsDisplayed()
                val screen = requireNotNull(instrument.uiAutomation.takeScreenshot())
                val image = Bitmap.createScaledBitmap(screen, 460, screen.height * 460 / screen.width, true)
                val buffer = ByteArrayOutputStream()
                try {
                    check(image.compress(Bitmap.CompressFormat.JPEG, 86, buffer))
                    instrument.addResults(Bundle().apply {
                        putString("shared_interaction_png_mod-uninstall", Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP))
                    })
                } finally {
                    if (image !== screen) image.recycle()
                    screen.recycle()
                }
                compose.onNodeWithText("取消").performClick()
                compose.onNodeWithText("卸载 Mod？").assertDoesNotExist()
                assertTrue(ModPackageStore.installed(app, pkg.id) != null)
            }
        } finally {
            ModPackageStore.uninstall(app, pkg.id)
            if (!previouslyTrusted) ModPackageStore.revokeAuthorTrust(app, pkg.authorFingerprint)
        }
    }

    @Test fun grantedRearSceneShowsConditionalCoverGuidanceBesideAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val archive = instrumentation.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val verified = ModPackageReader.read(ByteArrayInputStream(archive))
        assertEquals("platform.sceneinfo", verified.id)
        assertTrue(verified.requiredCapabilities.any { it.name == "rear.scene" })
        val beforeMods = ModPackageStore.list(context)
        val authorWasTrusted = ModPackageStore.isAuthorTrusted(context, verified.authorFingerprint)
        assertEquals("the isolated host starts without this fixture", null, ModPackageStore.installed(context, verified.id))
        assertTrue("no other test-host Mod shares this fixture author", beforeMods.none { it.authorFingerprint == verified.authorFingerprint })

        val installed = ModPackageStore.install(
            context,
            archive,
            ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()),
        )
        val scenario: ActivityScenario<ModSettingsActivity>
        try {
            ModPackageStore.setEnabled(context, installed.id, true)
            val localizedNote = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
                },
            ).getString(R.string.mod_scene_cover_behavior_note)
            assertTrue(localizedNote.contains("开启遮挡处理后"))
            assertTrue(localizedNote.contains("全黑不等于硬件息屏"))
            scenario = ActivityScenario.launch(Intent(context, ModSettingsActivity::class.java))
            scenario.use {
                lateinit var visibleNote: String
                scenario.onActivity { activity ->
                    visibleNote = activity.getString(R.string.mod_scene_cover_behavior_note)
                }
                openDetail(verified.id)
                compose.onNodeWithTag("mod_detail_scroll").performScrollToNode(hasText(visibleNote))
                compose.onNodeWithText(visibleNote).assertIsDisplayed()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                assertTrue("captured the rendered native settings screen", bitmap.width > 0 && bitmap.height > 0)
                val output = File(context.cacheDir, "mod-settings-cover-guidance-m12.png")
                FileOutputStream(output).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        } finally {
            runCatching { ModPackageStore.uninstall(context, installed.id) }
            if (!authorWasTrusted) runCatching { ModPackageStore.revokeAuthorTrust(context, verified.authorFingerprint) }
            assertEquals("normal test-host inventory is restored after fixture teardown", beforeMods, ModPackageStore.list(context))
            assertEquals(authorWasTrusted, ModPackageStore.isAuthorTrusted(context, verified.authorFingerprint))
        }
    }

    private fun openDetail(id: String) {
        val entry = "mod_detail_entry_$id"
        compose.onNodeWithTag("mod_management_scroll").performScrollToNode(hasTestTag(entry))
        compose.onNodeWithTag(entry).performScrollTo().performClick()
    }
}
