package top.zainzh.rearscreen.platform.mods

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.graphics.Point
import android.graphics.Rect
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.MainActivity
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.platform.RearCapabilityCore
import top.zainzh.rearscreen.platform.SceneEvent
import top.zainzh.rearscreen.platform.SceneLaunchContext
import top.zainzh.rearscreen.platform.SceneObserver
import top.zainzh.rearscreen.platform.SceneCloseReason
import top.zainzh.rearscreen.platform.SceneWindowReport
import top.zainzh.rearscreen.platform.mods.ModPackageStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Debug AndroidTest for the signed third-party author ZIP. Installs only its fixed identity,
 * opens the real host UI/Scene flow, and observes the viewport accepted by the production core.
 * Main-display setup uses scoped Accessibility actions; secondary-display taps are supplied only
 * by the guarded runner after a fresh, same-window Compose-bounds revalidation challenge.
 * Cast controls are deliberately not invoked, so host target/confirmation remains untouched.
 */
@RunWith(AndroidJUnit4::class)
class Rc5SignedModNativeSceneProbeTest {
    private val modId = "rc5.thirdparty.scene-full90.r37"
    private val assetPath = "platform/mods/rc5-thirdparty-scene-full90-r37.zip"
    private var fixtureMainRequest = 0
    private var interactiveWindows: AutoCloseable? = null
    private var inputChallengeFiles: Map<String, File> = emptyMap()
    private val ownedInputChallengeFiles = linkedMapOf<File, File>()

    @Before fun enableInteractiveWindowsForFixture() {
        interactiveWindows = Rc5FixtureWindows.enableInteractiveWindows(
            InstrumentationRegistry.getInstrumentation().uiAutomation,
        )
    }

    @After fun restoreInteractiveWindowsForFixture() {
        interactiveWindows?.close()
        interactiveWindows = null
    }

    @Test fun signedModUsesRealSafeAndFull90SceneViewports() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runId = fixtureRunId()
        val app = context.applicationContext as Application
        val archive = instrumentation.context.assets.open(assetPath).use { it.readBytes() }
        val verified = ModPackageReader.read(archive.inputStream())
        assertEquals(modId, verified.id)
        require(verified.requiredCapabilities.map { it.name }.toSet().containsAll(setOf(
            "rear.scene", "rear.scene.keep_visible", "rear.region", "rear.scene.orientation", "rear.cast.virtual"
        )))

        val existing = ModPackageStore.installed(context, modId)
        require(existing == null) {
            "保留现有 $modId 安装与配置；请先由用户决定是否移除后再运行此私有探针"
        }
        val before = ModPackageStore.productionSnapshotForTest(context)
        val trustedBefore = ModPackageStore.isAuthorTrusted(context, verified.authorFingerprint)
        Log.i(TAG, "stage=fixture_baseline trusted_author=$trustedBefore")
        require(before.entries.none { it.authorFingerprint == verified.authorFingerprint }) {
            "测试作者已有其他已安装 Mod；为避免影响其状态，停止自动安装"
        }
        val core = RearCapabilityCore.get(context)

        val installed = ModPackageStore.install(context, archive,
            ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()))

        val mainActivities = CopyOnWriteArrayList<MainActivity>()
        val resumedMainActivities = CopyOnWriteArrayList<MainActivity>()
        val createdActivities = CopyOnWriteArrayList<ModRearSceneActivity>()
        val resumedScenes = CopyOnWriteArrayList<ModRearSceneActivity>()
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                if (activity is MainActivity) mainActivities += activity
                if (activity is ModRearSceneActivity) createdActivities += activity
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumedMainActivities += activity
                if (activity is ModRearSceneActivity) resumedScenes += activity
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) resumedMainActivities.remove(activity)
                if (activity is ModRearSceneActivity) resumedScenes.remove(activity)
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) {
                    mainActivities.remove(activity)
                    resumedMainActivities.remove(activity)
                }
                if (activity is ModRearSceneActivity) createdActivities.remove(activity)
                if (activity is ModRearSceneActivity) resumedScenes.remove(activity)
            }
        }
        var lifecycleRegistered = false
        val observedRoutes = CopyOnWriteArrayList<SceneLaunchContext>()
        var fixtureOwner: top.zainzh.rearscreen.platform.SessionOwner? = null
        var confirmationTouchObserver: AutoCloseable? = null
        var primaryFailure: Throwable? = null
        try {
            reserveInputChallengeFiles(context, runId)
            assertTrue("test-only fixture is explicitly enabled", ModPackageStore.setEnabled(context, installed.id, true).enabled)
            check("rear.scene.keep_visible" in installed.grantedCapabilities) {
                "本次隔离验收必须只为私有签名夹具明确授权保持显示能力"
            }
            // Same exact owner tuple as ModSettingsPage.openRear(); capture before any Scene action
            // so finally can close an accepted lease even if its Activity route was never observed.
            fixtureOwner = core.modDataOwner(installed.id)
                ?: error("启用且获信任的 R37 fixture 未能解析精确 Mod owner")
            app.registerActivityLifecycleCallbacks(lifecycle)
            lifecycleRegistered = true
            Log.i(TAG, "stage=fixture_enabled")
            openModSettingsFromMain(context, instrumentation, verified.name, resumedMainActivities)
            waitFor("test Mod 的宿主设置行") {
                clickScopedAction(instrumentation, context.packageName, Display.DEFAULT_DISPLAY,
                    listOf("Mod 管理", verified.name), "显示到背屏")
            }
            Log.i(TAG, "stage=real_scene_action")

            val safeActivity = waitForSceneActivity(createdActivities) { route, activity ->
                activity in resumedScenes && !activity.isFinishing && !activity.isDestroyed &&
                    activity.window.decorView.display?.displayId?.let { it > Display.DEFAULT_DISPLAY } == true &&
                    isTestModRoute(route, verified.authorFingerprint, context.packageName) &&
                    route.request.region == top.zainzh.rearscreen.platform.RearRegion.CAMERA_SAFE
            }
            val safeRoute = requireNotNull(sceneRoute(safeActivity))
            check(safeRoute.owner == fixtureOwner) { "SAFE Scene owner 与启用前捕获的 exact R37 Mod owner 不匹配" }
            assertTrue("R37 fixture explicitly requests visible SAFE Scene", safeRoute.request.keepVisible)
            observedRoutes += safeRoute
            val safeViewport = awaitViewport(core, safeRoute)
            assertEquals("SAFE", safeViewport.region.name)
            assertTrue(safeViewport.revision > 0)
            assertExactVisibleScenePresentation(core, safeRoute)
            Log.i(TAG, "stage=safe_viewport_ready revision=${safeViewport.revision} rotation=${safeViewport.contentRotation} display=${safeViewport.displayId}")

            val rearDisplayId = safeActivity.window.decorView.display?.displayId
                ?: error("真实 Mod Scene 没有 display 身份")
            require(rearDisplayId == safeViewport.displayId && rearDisplayId != Display.DEFAULT_DISPLAY) {
                "测试 Mod Scene 没有运行在其已报告的背屏 display"
            }
            val safeWindowId = safeActivity.window.decorView.windowId
            val safeTaskId = safeActivity.taskId
            check(pendingOpenEffect(safeActivity) == null) {
                "SAFE Scene 在物理输入阶段前已有未完成的宿主确认请求"
            }
            val full90Target = awaitComposeActionBounds(safeActivity, rearDisplayId, REAR_SCENE_TITLE,
                "FULL 90", requiredWindow = "scene")
            Log.i(TAG, "stage=mod_scene_safe_capture_window run=${fixtureRunId()} task=${safeActivity.taskId} component=${safeActivity.componentName.flattenToString()} window=${windowIdKey(full90Target.window.decorView)} activityWindow=${windowIdKey(safeActivity.window.decorView)} display=$rearDisplayId bounds=${full90Target.bounds} coordSpace=logical_display_px")
            logPhysicalInputStage("mod_scene_safe_full90_input_required", safeActivity, safeRoute, full90Target,
                "title=$REAR_SCENE_TITLE viewportRevision=${safeViewport.revision} viewportRegion=${safeViewport.region.name} viewportRotation=${safeViewport.contentRotation} viewportDisplay=${safeViewport.displayId} windowPx=${safeViewport.windowPx.width}x${safeViewport.windowPx.height} tapRoot=${full90Target.rootKind} bounds=${full90Target.bounds} coordSpace=logical_display_px action=FULL_90")
            val openNonce = awaitInputChallenge("open", timeoutMs = 45_000)
            check(pendingOpenEffect(safeActivity) == null) { "open challenge 到达时 SAFE Scene 已有宿主 Effect 请求" }
            val openRouteNow = requireNotNull(sceneRoute(safeActivity))
            check(safeActivity in resumedScenes && safeActivity.taskId == safeTaskId &&
                !safeActivity.isFinishing && !safeActivity.isDestroyed && safeActivity.window.decorView.isAttachedToWindow &&
                safeActivity.window.decorView.windowId == safeWindowId && safeActivity.window.decorView.display?.displayId == rearDisplayId &&
                openRouteNow.sessionId == safeRoute.sessionId && openRouteNow.generation == safeRoute.generation &&
                isTestModRoute(openRouteNow, verified.authorFingerprint, context.packageName)) {
                "open challenge 到达时 exact signed SAFE Activity/task/window/display/route 已变化"
            }
            val openTargetNow = requireNotNull(findComposeActionBounds(safeActivity, rearDisplayId,
                REAR_SCENE_TITLE, "FULL 90", requiredWindow = "scene")) {
                "open challenge 到达时 SAFE Scene 按钮已不再具备唯一且可见的 Compose bounds"
            }
            assertSamePhysicalInputTarget(full90Target, openTargetNow, safeActivity, safeTaskId, safeWindowId, rearDisplayId)
            logInputRevalidated(runId, "open", openNonce, safeActivity, openTargetNow)
            waitFor("用户输入产生的 FULL+90° 宿主确认请求", timeoutMs = 45_000) {
                safeActivity in resumedScenes && !safeActivity.isFinishing && !safeActivity.isDestroyed &&
                    safeActivity.window.decorView.isAttachedToWindow &&
                    safeActivity.window.decorView.windowId == safeWindowId &&
                    safeActivity.window.decorView.display?.displayId == rearDisplayId &&
                    sceneRoute(safeActivity)?.let { it.sessionId == safeRoute.sessionId && it.generation == safeRoute.generation &&
                        isTestModRoute(it, verified.authorFingerprint, context.packageName) } == true &&
                    pendingOpenEffect(safeActivity)?.let { effect ->
                        effect.id == "scene-full90" && effect.scene == "rear" && effect.region == "FULL" && effect.contentRotation == 1 &&
                        effect.requiresUserActivation && effect.keepVisible
                    } == true
            }
            val pendingEffect = requireNotNull(pendingOpenEffect(safeActivity))
            logPendingConfirmationDiagnostic(safeActivity, rearDisplayId, safeTaskId,
                windowIdKey(safeActivity.window.decorView), pendingEffect)
            val confirmationTargets = try {
                awaitStableDialogActionBounds(safeActivity, rearDisplayId,
                    "Mod 请求打开背屏场景", "确认打开", "取消")
            } catch (failure: Throwable) {
                logPendingConfirmationDiagnostic(safeActivity, rearDisplayId, safeTaskId,
                    windowIdKey(safeActivity.window.decorView),
                    pendingEffect, stage = "mod_scene_confirmation_diagnostic_timeout")
                throw failure
            }
            val confirmTarget = requireNotNull(confirmationTargets["确认打开"])
            val modelRevisionBeforeConfirm = ModPackageStore.model(context, modId)?.revision
                ?: error("确认前无法读取私有 R37 fixture 的 reducer revision")
            confirmationTouchObserver = ScopedDialogTouchObserver(confirmTarget.window, runId, safeTaskId, rearDisplayId)
            logPhysicalInputStage("mod_scene_host_confirmation_input_required", safeActivity, safeRoute, confirmTarget,
                "effect=${pendingEffect.id} scene=${pendingEffect.scene} effectRegion=${pendingEffect.region} effectRotation=${pendingEffect.contentRotation} requiresUserActivation=${pendingEffect.requiresUserActivation} dialogTitle=Mod请求打开背屏场景 bounds=${confirmTarget.bounds} tapRoot=${confirmTarget.rootKind} coordSpace=logical_display_px action=确认打开")
            val confirmNonce = awaitInputChallenge("confirm", timeoutMs = 45_000)
            val pendingEffectNow = pendingOpenEffect(safeActivity)
            check(pendingEffectNow == pendingEffect) {
                "confirm challenge 到达时 exact 宿主 Effect 请求已变化"
            }
            val confirmRouteNow = requireNotNull(sceneRoute(safeActivity))
            check(safeActivity in resumedScenes && safeActivity.taskId == safeTaskId &&
                !safeActivity.isFinishing && !safeActivity.isDestroyed && safeActivity.window.decorView.isAttachedToWindow &&
                safeActivity.window.decorView.windowId == safeWindowId && safeActivity.window.decorView.display?.displayId == rearDisplayId &&
                confirmRouteNow.sessionId == safeRoute.sessionId && confirmRouteNow.generation == safeRoute.generation &&
                isTestModRoute(confirmRouteNow, verified.authorFingerprint, context.packageName)) {
                "confirm challenge 到达时 exact signed SAFE Activity/task/window/display/route 已变化"
            }
            val confirmationTargetsNow = findComposeActionTargets(safeActivity, rearDisplayId,
                "Mod 请求打开背屏场景", setOf("确认打开", "取消"), requiredWindow = "dialog")
            val confirmTargetNow = confirmationTargetsNow?.targets?.get("确认打开")
            val cancelTargetNow = confirmationTargetsNow?.targets?.get("取消")
            check(confirmTargetNow != null && cancelTargetNow != null && confirmTargetNow.window === cancelTargetNow.window) {
                "confirm challenge 到达时 exact Activity 的宿主确认 Dialog 未同时显示确认与取消按钮"
            }
            assertSamePhysicalInputTarget(confirmTarget, confirmTargetNow, safeActivity, safeTaskId, safeWindowId, rearDisplayId)
            check(cancelTargetNow.bounds == confirmationTargets["取消"]?.bounds &&
                cancelTargetNow.window === confirmationTargets["确认打开"]?.window) {
                "confirm challenge 到达时宿主确认 Dialog 或取消按钮 bounds 已变化"
            }
            logInputRevalidated(runId, "confirm", confirmNonce, safeActivity, confirmTargetNow)
            Log.i(TAG, "stage=mod_scene_full_route_waiting run=$runId task=$safeTaskId display=$rearDisplayId " +
                "modId=$modId effect=${pendingEffect.id} result=awaiting revisionBefore=$modelRevisionBeforeConfirm")
            val handoff = awaitSceneHandoff(context, core, safeActivity, safeRoute, verified.authorFingerprint,
                context.packageName, createdActivities, resumedScenes, safeTaskId, rearDisplayId,
                safeViewport.revision, modelRevisionBeforeConfirm)
            val effectOutcome = handoff.effectOutcome
            val resultKind = effectOutcome?.let(::sceneEffectResultKind) ?: "not_observed"
            val resultLabel = effectOutcome?.let(::sceneEffectResultLabel) ?: "none"
            val resultDetail = effectOutcome?.detail ?: "none"
            Log.i(TAG, "stage=mod_scene_host_confirmation_result run=$runId task=$safeTaskId display=$rearDisplayId " +
                "modId=$modId effect=${pendingEffect.id} status=$resultKind resultLabel=$resultLabel detail=$resultDetail " +
                "revision=${handoff.modelSnapshot.revision} route=${if (handoff.activity != null) "observed" else "missing"} " +
                "pendingEffect=${pendingOpenEffect(safeActivity) != null}")
            check(handoff.activity != null) {
                "确认后未出现 exact FULL Scene Activity；hostResult=$resultKind detail=$resultDetail " +
                    "modelStatus=${sceneModelStatusLabel(handoff.modelSnapshot.status)}"
            }
            val fullActivity = requireNotNull(handoff.activity)
            Log.i(TAG, "stage=full90_confirmed run=${fixtureRunId()} task=${fullActivity.taskId} display=${fullActivity.window.decorView.display?.displayId}")
            val fullRoute = requireNotNull(sceneRoute(fullActivity))
            val committedWindow = requireNotNull(handoff.committedWindow)
            assertTrue("FULL route remains the exact SAFE Activity", safeActivity === fullActivity)
            assertEquals("FULL route remains the exact SAFE task", safeTaskId, fullActivity.taskId)
            assertEquals("Coordinator committed a newer viewport", true, committedWindow.viewport.revision > safeViewport.revision)
            assertEquals("Coordinator committed FULL region", top.zainzh.rearscreen.platform.RearRegion.FULL, fullRoute.request.region)
            assertEquals("Coordinator committed quarter-turn", 1, fullRoute.request.contentRotation)
            assertEquals("Coordinator commit targets the exact task", safeTaskId, committedWindow.taskId)
            assertEquals("Coordinator commit targets the actual rear display", rearDisplayId, committedWindow.viewport.displayId)
            check(fullRoute.owner == fixtureOwner) { "FULL Scene owner 与启用前捕获的 exact R37 Mod owner 不匹配" }
            assertTrue("R37 FULL Effect retains the keep_visible grant", fullRoute.request.keepVisible)
            observedRoutes += fullRoute
            val fullViewport = awaitViewport(core, fullRoute)
            assertEquals("FULL", fullViewport.region.name)
            assertEquals(1, fullViewport.contentRotation)
            assertTrue("real, accepted native-window revision", fullViewport.revision > 0)
            assertExactVisibleScenePresentation(core, fullRoute)
            assertEquals("FULL viewport remains on the exact rear display", rearDisplayId, fullViewport.displayId)
            assertTrue("measured window dimensions are positive", fullViewport.windowPx.width > 0 && fullViewport.windowPx.height > 0)
            assertTrue("reported content bounds are non-empty", fullViewport.contentBoundsPx.right > fullViewport.contentBoundsPx.left &&
                fullViewport.contentBoundsPx.bottom > fullViewport.contentBoundsPx.top)
            assertEquals("FULL viewport covers the measured window width", fullViewport.windowPx.width,
                fullViewport.contentBoundsPx.right - fullViewport.contentBoundsPx.left)
            assertEquals("FULL viewport covers the measured window height", fullViewport.windowPx.height,
                fullViewport.contentBoundsPx.bottom - fullViewport.contentBoundsPx.top)
            assertEquals(0f, fullViewport.inputTransform.scaleX, 0f)
            assertEquals(1f, fullViewport.inputTransform.skewX, 0f)
            assertEquals(-1f, fullViewport.inputTransform.skewY, 0f)
            assertEquals(0f, fullViewport.inputTransform.scaleY, 0f)
            Log.i(TAG, "stage=full90_viewport_ready revision=${fullViewport.revision} region=FULL rotation=${fullViewport.contentRotation} display=${fullViewport.displayId} window=${fullViewport.windowPx.width}x${fullViewport.windowPx.height}")
            Log.i(TAG, "stage=mod_scene_full90_capture_window run=${fixtureRunId()} task=${fullActivity.taskId} display=${fullViewport.displayId} duration_ms=8000")
            SystemClock.sleep(8_000)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                }
            }
            ownedInputChallengeFiles.forEach { (file, expectedCanonicalPath) ->
                cleanup {
                    if (file.exists()) {
                        check(file.canonicalFile == expectedCanonicalPath) {
                            "拒绝删除不再指向本 fixture 私有 cache 路径的 challenge 文件"
                        }
                        check(file.delete()) { "无法清理本 fixture challenge 文件" }
                    }
                }
            }
            ownedInputChallengeFiles.clear()
            inputChallengeFiles = emptyMap()
            confirmationTouchObserver?.let { observer -> cleanup { observer.close() } }
            confirmationTouchObserver = null
            cleanup { ModBrokerProvider.get(context).invalidateAndWait(installed.id, "E_MOD_UNINSTALLED", "测试夹具收尾") }
            // This coordinator operation synchronously closes every accepted session/ticket for the exact owner,
            // including leases for which no SceneLaunchContext reached the fixture observer.
            fixtureOwner?.let { owner ->
                cleanup { core.displaySessions.closeOwnerSessions(owner, SceneCloseReason.CLIENT_CLOSED) }
            }
            val fixtureRoutes = (observedRoutes + createdActivities.mapNotNull { activity ->
                sceneRoute(activity)?.takeIf { isTestModRoute(it, verified.authorFingerprint, context.packageName) }
            }).filter { it.owner == fixtureOwner && isTestModRoute(it, verified.authorFingerprint, context.packageName) }
                .distinctBy { it.sessionId to it.generation }
            val fixtureActivities = createdActivities.mapNotNull { activity ->
                sceneRoute(activity)?.takeIf { isTestModRoute(it, verified.authorFingerprint, context.packageName) }
                    ?.takeIf { it.owner == fixtureOwner }
                    ?.let { activity to it }
            }
            fixtureActivities.forEach { (activity, _) ->
                cleanup { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finishAndRemoveTask() } }
            }
            fixtureRoutes.forEach { route ->
                cleanup {
                    waitFor("精确 R37 Scene 已关闭且 Activity 已销毁", timeoutMs = 15_000) {
                        !core.ownsActiveSceneSession(route.owner, route.sessionId) &&
                            core.displaySessions.queryScenePresentation(route.owner, route.sessionId, route.generation) == null &&
                            createdActivities.none { activity ->
                                sceneRoute(activity)?.let {
                                    isTestModRoute(it, verified.authorFingerprint, context.packageName) &&
                                        it.sessionId == route.sessionId && it.generation == route.generation
                                } == true && !activity.isDestroyed
                            }
                    }
                }
            }
            mainActivities.toList().forEach { activity ->
                cleanup { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() } }
            }
            if (lifecycleRegistered) cleanup { app.unregisterActivityLifecycleCallbacks(lifecycle) }
            cleanup { ModPackageStore.uninstall(context, installed.id) }
            if (!trustedBefore) cleanup { ModPackageStore.revokeAuthorTrust(context, verified.authorFingerprint) }
            cleanup {
                val after = ModPackageStore.productionSnapshotForTest(context)
                assertEquals("test fixture install/model/package rows are removed", before.entries, after.entries)
                assertEquals("author trust returns to its pre-test baseline", before.trustedAuthors, after.trustedAuthors)
                assertEquals("signed fixture publisher binding returns to baseline",
                    before.publishedModOwners + (modId to verified.authorFingerprint), after.publishedModOwners)
            }
            cleanupFailure?.let { failure ->
                val primary = primaryFailure
                if (primary == null) throw failure else primary.addSuppressed(failure)
            }
            if (cleanupFailure == null) {
                Log.i(TAG, "stage=fixture_cleanup_verified fixture=$modId sceneInactive=true keepVisibleLeaseReleased=true")
            }
        }
    }

    private fun clickScopedAction(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
        identityTexts: List<String>,
        actionText: String,
        allowRootIdentityBinding: Boolean = false,
    ): Boolean {
        val displayWindows = Rc5FixtureWindows.onDisplay(instrumentation.uiAutomation, displayId)
        val matchingWindows = displayWindows.mapNotNull { window ->
            if (window.displayId != displayId) return@mapNotNull null
            val root = window.root ?: return@mapNotNull null
            if (root.packageName?.toString() != hostPackage ||
                identityTexts.any { findVisibleTexts(root, it).isEmpty() } || findVisibleTexts(root, actionText).isEmpty()) null
            else root
        }
        check(matchingWindows.size <= 1) {
            "拒绝点击：display=$displayId 上存在多个匹配 host/Mod 身份的窗口"
        }
        val root = matchingWindows.singleOrNull() ?: return false
        identityTexts.forEach { text -> findVisibleTexts(root, text).also {
            check(it.size == 1) { "拒绝点击：窗口内可见身份文本不唯一：$text" }
        } }
        val actionNodes = findVisibleTexts(root, actionText)
        check(actionNodes.size <= 1) { "拒绝点击：窗口内可见 '$actionText' 按钮不唯一" }
        val scopedTargets = actionNodes.mapNotNull { actionNode ->
            val actionAncestors = ancestors(actionNode)
            // AccessibilityNodeInfo wrappers returned by window.root and getParent()
            // may differ by reference while representing the same logical root node.
            // Resolve that logical root once, then use its exact chain wrapper below.
            val chainRoot = actionAncestors.singleOrNull { it == root } ?: return@mapNotNull null
            val rootBoundedAncestors = actionAncestors.takeWhileInclusive(chainRoot) ?: return@mapNotNull null
            val scope = rootBoundedAncestors.firstOrNull { candidate ->
                candidate !== chainRoot && identityTexts.all { text -> containsText(candidate, text) }
            } ?: if (allowRootIdentityBinding && identityTexts.all { text -> findVisibleTexts(root, text).size == 1 }) chainRoot
                else return@mapNotNull null
            // Both identity scope and clickable target must stay inside this captured
            // chain, bounded at the actual window root; never climb into another window.
            rootBoundedAncestors.takeWhileInclusive(scope)?.firstOrNull { it.isClickable }
        }.distinct()
        check(scopedTargets.size <= 1) {
            "拒绝点击：身份 $identityTexts 下有多个 '$actionText' 控件"
        }
        val target = scopedTargets.singleOrNull() ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun openModSettingsFromMain(
        context: android.content.Context,
        instrumentation: android.app.Instrumentation,
        modName: String,
        resumedMainActivities: List<MainActivity>,
    ): MainActivity {
        val request = ++fixtureMainRequest
        val runId = fixtureRunId()
        Log.i(TAG, "stage=fixture_needs_main request=$request run=$runId")
        val main = waitForMainActivity(resumedMainActivities) {
            it.hasWindowFocus() && !it.isFinishing && it.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY
        }
        assertEquals("MainActivity is resumed and visible on the main display", Display.DEFAULT_DISPLAY,
            main.window.decorView.display?.displayId)
        val navModules = context.getString(R.string.ui_nav_modules)
        val zipMods = context.getString(R.string.zip_mods_title)
        var root = waitForMainRoot(instrumentation, context.packageName, navModules, zipMods)
        if (findTexts(root, zipMods).isEmpty()) {
            check(clickMainAction(instrumentation, context.packageName, navModules)) {
                "主屏 Modules 导航控件不可用"
            }
            waitForMainAction(instrumentation, context.packageName, zipMods)
            root = waitForMainRoot(instrumentation, context.packageName, navModules, zipMods)
        }
        assertEquals("ZIP Mods entry is unique on the visible host window", 1, findTexts(root, zipMods).size)
        check(clickMainAction(instrumentation, context.packageName, zipMods)) { "主屏 ZIP Mods 入口不可用" }
        Log.i(TAG, "stage=fixture_main_ready request=$request display=${main.window.decorView.display?.displayId} run=$runId")
        waitForHostTexts(instrumentation, context.packageName, listOf("Mod 管理", modName))
        requireUniqueModEntry(instrumentation, context.packageName, modName)
        check(main in resumedMainActivities && main.hasWindowFocus() &&
            main.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY)
        return main
    }

    private fun waitForMainActivity(
        activities: List<MainActivity>,
        timeoutMs: Long = 30_000,
        predicate: (MainActivity) -> Boolean,
    ): MainActivity {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            activities.toList().asReversed().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("超时：等待 root 通过普通主屏入口打开并恢复 MainActivity")
    }

    private fun waitForMainRoot(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        navModules: String,
        zipMods: String,
    ): AccessibilityNodeInfo {
        var selected: AccessibilityNodeInfo? = null
        try {
            waitFor("唯一主屏导航窗口") {
                val roots = visibleHostRoots(instrumentation, hostPackage)
                check(roots.size <= 1) { "拒绝继续：主屏 display 上有多个宿主窗口" }
                val root = roots.singleOrNull() ?: return@waitFor false
                if (findTexts(root, navModules).isEmpty() && findTexts(root, zipMods).isEmpty()) return@waitFor false
                selected = root
                Log.i(TAG, "stage=main_root_probe display=${Display.DEFAULT_DISPLAY} host_roots=${roots.size} nav_label_hits=${findTexts(root, navModules).size} zip_label_hits=${findTexts(root, zipMods).size}")
                true
            }
        } catch (failure: Throwable) {
            val roots = visibleHostRoots(instrumentation, hostPackage)
            val navHits = roots.sumOf { findTexts(it, navModules).size }
            val zipHits = roots.sumOf { findTexts(it, zipMods).size }
            Log.i(TAG, "stage=main_root_diagnostic display=${Display.DEFAULT_DISPLAY} host_roots=${roots.size} nav_label_hits=$navHits zip_label_hits=$zipHits")
            throw failure
        }
        return requireNotNull(selected)
    }

    private fun waitForMainAction(instrumentation: android.app.Instrumentation, hostPackage: String, text: String) {
        waitFor("主屏入口 $text") {
            val roots = visibleHostRoots(instrumentation, hostPackage)
            check(roots.size <= 1) { "拒绝继续：主屏 display 上有多个宿主窗口" }
            roots.singleOrNull()?.let { findTexts(it, text).isNotEmpty() } ?: false
        }
    }

    private fun waitForHostTexts(instrumentation: android.app.Instrumentation, hostPackage: String, texts: List<String>) {
        waitFor("唯一 Mod 管理窗口 $texts") {
            val roots = visibleHostRoots(instrumentation, hostPackage)
            check(roots.size <= 1) { "拒绝继续：主屏 display 上有多个宿主窗口" }
            roots.singleOrNull()?.let { root -> texts.all { findTexts(root, it).isNotEmpty() } } ?: false
        }
    }

    /** Reject ambiguous visible Mod labels before any row action; identity is also checked on resumed Activity. */
    private fun requireUniqueModEntry(instrumentation: android.app.Instrumentation, hostPackage: String, modName: String) {
        val roots = visibleHostRoots(instrumentation, hostPackage)
        check(roots.size == 1) { "拒绝导航：主屏必须恰有一个可见宿主窗口" }
        val root = roots.single()
        check(findTexts(root, "Mod 管理").isNotEmpty()) { "拒绝导航：当前不是 Mod 管理列表" }
        val matches = findTexts(root, modName)
        check(matches.size == 1) { "拒绝导航：Mod 标签 '$modName' 命中 ${matches.size} 项，拒绝选择" }
    }

    private fun clickMainAction(instrumentation: android.app.Instrumentation, hostPackage: String, text: String): Boolean {
        val roots = visibleHostRoots(instrumentation, hostPackage)
        check(roots.size <= 1) { "拒绝点击：主屏 display 上有多个宿主窗口" }
        val root = roots.singleOrNull() ?: return false
        val nodes = findTexts(root, text)
        check(nodes.size <= 1) { "拒绝点击：主屏入口 '$text' 不唯一" }
        var target: AccessibilityNodeInfo? = nodes.singleOrNull()
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun visibleHostRoots(instrumentation: android.app.Instrumentation, hostPackage: String): List<AccessibilityNodeInfo> =
        Rc5FixtureWindows.onDisplay(instrumentation.uiAutomation, Display.DEFAULT_DISPLAY).mapNotNull { window ->
            if (window.displayId != Display.DEFAULT_DISPLAY) return@mapNotNull null
            window.root?.takeIf { it.packageName?.toString() == hostPackage }
        }

    private fun fixtureRunId(): String {
        val runId = InstrumentationRegistry.getArguments().getString("fixtureRun")
            ?: error("缺少 fixtureRun instrumentation 参数")
        require(Regex("[0-9a-f]{32}").matches(runId)) { "fixtureRun 必须是 32 位小写十六进制 UUID" }
        return runId
    }

    private fun reserveInputChallengeFiles(context: android.content.Context, runId: String) {
        check(inputChallengeFiles.isEmpty() && ownedInputChallengeFiles.isEmpty())
        require(Regex("[0-9a-f]{32}").matches(runId))
        val cache = context.cacheDir.canonicalFile
        val candidates = listOf("open", "confirm").associateWith { phase ->
            File(cache, "rc5-scene-input-$runId-$phase")
        }
        val canonical = candidates.mapValues { (_, file) -> file.canonicalFile }
        require(canonical.values.all { it.parentFile == cache }) { "challenge 路径必须直接位于 app 私有 cacheDir" }
        require(candidates.values.none { it.exists() }) {
            "当前 fixtureRun 的 Scene challenge 文件已存在；拒绝覆盖或消费旧 challenge"
        }
        candidates.forEach { (phase, file) -> ownedInputChallengeFiles[file] = canonical.getValue(phase) }
        inputChallengeFiles = candidates
    }

    /** Poll only for existence; once present, read and delete this exact run/phase file once. */
    private fun awaitInputChallenge(phase: String, timeoutMs: Long): String {
        require(phase == "open" || phase == "confirm")
        val file = inputChallengeFiles[phase] ?: error("尚未为 $phase challenge 预留私有 cache 文件")
        val expectedCanonicalPath = requireNotNull(ownedInputChallengeFiles[file])
        var nonce: String? = null
        waitFor("root guarded runner 的 $phase one-shot challenge", timeoutMs) {
            if (!file.exists()) return@waitFor false
            check(file.isFile && file.canonicalFile == expectedCanonicalPath) {
                "拒绝读取非本 fixture 私有 cache 中的 challenge 文件"
            }
            val content = file.readText(StandardCharsets.US_ASCII)
            check(file.delete()) { "读取后无法删除 $phase challenge 文件" }
            require(Regex("[0-9a-f]{32}").matches(content)) {
                "$phase challenge 内容必须是 32 位小写十六进制 nonce"
            }
            nonce = content
            true
        }
        return requireNotNull(nonce)
    }

    private fun assertSamePhysicalInputTarget(
        before: PhysicalInputTarget,
        after: PhysicalInputTarget,
        activity: ModRearSceneActivity,
        expectedTaskId: Int,
        expectedActivityWindowId: android.view.WindowId,
        expectedDisplayId: Int,
    ) {
        val ownerDecor = activity.window.decorView
        val inputDecorBefore = before.window.decorView
        val inputDecorAfter = after.window.decorView
        check(activity.taskId == expectedTaskId && !activity.isFinishing && !activity.isDestroyed)
        check(ownerDecor.isAttachedToWindow && ownerDecor.windowId == expectedActivityWindowId &&
            ownerDecor.display?.displayId == expectedDisplayId)
        check(before.window === after.window && inputDecorBefore.windowId == inputDecorAfter.windowId)
        check(before.rootKind == after.rootKind && before.bounds == after.bounds)
        check(windowIdKey(inputDecorBefore) == windowIdKey(inputDecorAfter))
        check(inputDecorAfter.isAttachedToWindow && inputDecorAfter.isShown &&
            inputDecorAfter.hasWindowFocus() && inputDecorAfter.display?.displayId == expectedDisplayId)
    }

    private fun logInputRevalidated(
        runId: String,
        phase: String,
        challenge: String,
        activity: ModRearSceneActivity,
        target: PhysicalInputTarget,
    ) {
        require(phase == "open" || phase == "confirm")
        require(Regex("[0-9a-f]{32}").matches(challenge))
        val ownerDecor = activity.window.decorView
        val inputDecor = target.window.decorView
        val displayId = inputDecor.display?.displayId ?: error("复核后的输入窗口已脱离 display")
        Log.i(TAG, "stage=mod_scene_input_revalidated run=$runId phase=$phase challenge=$challenge task=${activity.taskId} component=${activity.componentName.flattenToString()} window=${windowIdKey(inputDecor)} activityWindow=${windowIdKey(ownerDecor)} display=$displayId bounds=${target.bounds} coordSpace=logical_display_px tapRoot=${target.rootKind}")
    }

    /** Emit a one-shot handoff; the guarded runner verifies the physical window before injecting input. */
    private fun logPhysicalInputStage(
        stage: String,
        activity: ModRearSceneActivity,
        route: SceneLaunchContext,
        target: PhysicalInputTarget,
        evidence: String,
    ) {
        val principal = route.owner.principal as? top.zainzh.rearscreen.platform.SessionPrincipal.SignedMod
            ?: error("物理输入阶段必须绑定 signed Mod Scene")
        val ownerDecor = activity.window.decorView
        val inputDecor = target.window.decorView
        val displayId = inputDecor.display?.displayId ?: error("物理输入目标窗口已脱离 display")
        check(ownerDecor.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed)
        check(inputDecor.isAttachedToWindow && inputDecor.isShown && displayId == ownerDecor.display?.displayId)
        Log.i(TAG, "stage=$stage run=${fixtureRunId()} task=${activity.taskId} component=${activity.componentName.flattenToString()} window=${windowIdKey(inputDecor)} activityWindow=${windowIdKey(ownerDecor)} display=$displayId signedMod=${principal.modId} region=${route.request.region.name} rotation=${route.request.contentRotation} decor=${ownerDecor.width}x${ownerDecor.height} $evidence")
    }

    /** Read the confirmation request only; this fixture never invokes it or its callback. */
    private fun pendingOpenEffect(activity: ModRearSceneActivity): ModRearOpenEffect? {
        var pending: ModRearOpenEffect? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            pending = activity.javaClass.getDeclaredMethod("getEffectRequest").apply { isAccessible = true }
                .invoke(activity) as? ModRearOpenEffect
        }
        return pending
    }

    private data class SceneModelSnapshot(val status: String, val detail: String, val revision: Long)
    private data class SceneEffectOutcome(val status: String, val detail: String, val revision: Long)
    private data class SceneHandoff(
        val activity: ModRearSceneActivity?,
        val committedWindow: SceneWindowReport?,
        val effectOutcome: SceneEffectOutcome?,
        val modelSnapshot: SceneModelSnapshot,
    )

    /** Poll the exact resumed FULL route and reducer result together; the route is the acceptance evidence. */
    private fun awaitSceneHandoff(
        context: android.content.Context,
        core: RearCapabilityCore,
        ownerActivity: ModRearSceneActivity,
        ownerRoute: SceneLaunchContext,
        authorFingerprint: String,
        hostPackage: String,
        createdActivities: List<ModRearSceneActivity>,
        resumedActivities: List<ModRearSceneActivity>,
        safeTaskId: Int,
        rearDisplayId: Int,
        safeViewportRevision: Long,
        revisionBeforeConfirmation: Long,
        timeoutMs: Long = 15_000,
    ): SceneHandoff {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var snapshot = sceneModelSnapshot(context)
        var outcome: SceneEffectOutcome? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            snapshot = sceneModelSnapshot(context)
            if (snapshot.revision > revisionBeforeConfirmation && snapshot.status in setOf("Scene 已接受", "Scene 未启动")) {
                outcome = SceneEffectOutcome(snapshot.status, safeSceneEffectDetail(snapshot.detail), snapshot.revision)
            }
            val exactFullActivity = findExactResumedFullSceneActivity(createdActivities, resumedActivities,
                rearDisplayId, ownerRoute.owner, authorFingerprint, hostPackage)
            val committedWindow = core.sceneViewportTransitions?.queryCommittedSceneWindow(
                ownerRoute.owner, ownerRoute.sessionId, ownerRoute.generation)
            if (exactFullActivity === ownerActivity && exactFullActivity.taskId == safeTaskId &&
                committedWindow != null && committedWindow.component == exactFullActivity.componentName &&
                committedWindow.taskId == safeTaskId && committedWindow.viewport.displayId == rearDisplayId &&
                committedWindow.viewport.revision > safeViewportRevision &&
                committedWindow.viewport.region == dev.remrss.scene.geometry.SceneRegion.FULL &&
                committedWindow.viewport.contentRotation == 1) {
                return SceneHandoff(exactFullActivity, committedWindow, outcome, snapshot)
            }
            if (outcome?.status == "Scene 未启动" && isDefinitiveNoSceneResult(outcome.detail)) {
                logSceneHandoffFailure("mod_scene_full_route_rejected", fixtureRunId(), ownerActivity,
                    authorFingerprint, hostPackage, createdActivities, resumedActivities, outcome)
                return SceneHandoff(null, null, outcome, snapshot)
            }
            SystemClock.sleep(100)
        }
        val finalStatus = if (snapshot.revision > revisionBeforeConfirmation && snapshot.status in setOf("Scene 已接受", "Scene 未启动")) {
            SceneEffectOutcome(snapshot.status, safeSceneEffectDetail(snapshot.detail), snapshot.revision)
        } else null
        logSceneHandoffFailure("mod_scene_full_route_timeout", fixtureRunId(), ownerActivity,
            authorFingerprint, hostPackage, createdActivities, resumedActivities, finalStatus)
        return SceneHandoff(null, null, finalStatus, snapshot)
    }

    private fun sceneModelSnapshot(context: android.content.Context): SceneModelSnapshot {
        val model = ModPackageStore.model(context, modId)
        return SceneModelSnapshot(
            model?.state?.optString("status", "unknown") ?: "missing-state",
            safeSceneEffectDetail(model?.state?.optString("detail", "missing-detail") ?: "missing-state"),
            model?.revision ?: -1L,
        )
    }

    private fun safeSceneEffectDetail(detail: String): String {
        if (detail == "由宿主返回 Scene 请求结果。") return "host_result"
        val allowedCodes = setOf(
            "E_STALE_SESSION", "E_ACTIVATION_REQUIRED", "E_SCENE_NOT_VISIBLE", "E_SCENE_ACTIVITY_CLOSED",
            "E_USER_CANCELLED", "E_EFFECT_REPLACED", "E_SCENE_OPEN",
        ) + top.zainzh.rearscreen.platform.SceneError.values().map { "E_${it.name}" }
        return detail.takeIf { it in allowedCodes } ?: "other"
    }

    private fun sceneEffectResultKind(outcome: SceneEffectOutcome): String =
        if (outcome.status == "Scene 已接受") "accepted" else "rejected"

    private fun sceneEffectResultLabel(outcome: SceneEffectOutcome): String = when (outcome.status) {
        "Scene 已接受" -> "Scene_已接受"
        "Scene 未启动" -> "Scene_未启动"
        else -> "other"
    }

    private fun sceneModelStatusLabel(status: String): String = when (status) {
        "正在请求 Scene" -> "requesting"
        "Scene 已接受" -> "accepted"
        "Scene 未启动" -> "rejected"
        else -> "other"
    }

    private fun isDefinitiveNoSceneResult(detail: String): Boolean =
        detail == "host_result" || detail == "E_SCENE_OPEN" || detail == "E_ACTIVATION_REQUIRED" ||
            detail == "E_SCENE_NOT_VISIBLE" || detail == "E_USER_CANCELLED" || detail == "E_EFFECT_REPLACED" ||
            detail in top.zainzh.rearscreen.platform.SceneError.values().map { "E_${it.name}" }

    private fun findExactResumedFullSceneActivity(
        createdActivities: List<ModRearSceneActivity>,
        resumedActivities: List<ModRearSceneActivity>,
        rearDisplayId: Int,
        owner: top.zainzh.rearscreen.platform.SessionOwner,
        authorFingerprint: String,
        hostPackage: String,
    ): ModRearSceneActivity? {
        val exact = createdActivities.filter { activity ->
            val route = sceneRoute(activity) ?: return@filter false
            activity in resumedActivities && !activity.isFinishing && !activity.isDestroyed &&
                activity.window.decorView.display?.displayId == rearDisplayId &&
                route.owner == owner && isTestModRoute(route, authorFingerprint, hostPackage) &&
                route.request.region == top.zainzh.rearscreen.platform.RearRegion.FULL &&
                route.request.contentRotation == 1
        }
        check(exact.size <= 1) { "拒绝验收：同一精确 R37 owner 同时有多个 resumed FULL + 90° Scene Activity" }
        return exact.singleOrNull()
    }

    /** Preserve only exact fixture-owned task/window facts; no user content or tap coordinates are logged. */
    private fun logSceneHandoffFailure(
        stage: String,
        runId: String,
        ownerActivity: ModRearSceneActivity,
        authorFingerprint: String,
        hostPackage: String,
        createdActivities: List<ModRearSceneActivity>,
        resumedActivities: List<ModRearSceneActivity>,
        effectOutcome: SceneEffectOutcome?,
    ) {
        val owner = sceneRoute(ownerActivity)?.owner
        val candidates = createdActivities.mapNotNull { activity ->
            val route = sceneRoute(activity) ?: return@mapNotNull null
            if (!isTestModRoute(route, authorFingerprint, hostPackage) || route.owner != owner) return@mapNotNull null
            val decor = activity.window.decorView
            "task=${activity.taskId},display=${decor.display?.displayId ?: -1},resumed=${activity in resumedActivities}," +
                "finishing=${activity.isFinishing},destroyed=${activity.isDestroyed},attached=${decor.isAttachedToWindow}," +
                "shown=${decor.isShown},focus=${decor.hasWindowFocus()},region=${route.request.region.name}," +
                "rotation=${route.request.contentRotation},keepVisible=${route.request.keepVisible}," +
                "active=${runCatching { RearCapabilityCore.get(activity).ownsActiveSceneSession(route.owner, route.sessionId) }.getOrDefault(false)}"
        }
        Log.i(TAG, "stage=$stage run=$runId effect=scene-full90 " +
            "effectStatus=${effectOutcome?.let(::sceneEffectResultKind) ?: "not_observed"} " +
            "effectLabel=${effectOutcome?.let(::sceneEffectResultLabel) ?: "none"} " +
            "effectDetail=${effectOutcome?.detail ?: "none"} modelRevision=${effectOutcome?.revision ?: -1L} " +
            "exactOwnedActivityCount=${candidates.size} candidates=${candidates.joinToString(";").ifBlank { "none" }}")
    }

    /** Notify the guarded runner before the dialog-bounds wait; diagnostics never perform input. */
    private fun logPendingConfirmationDiagnostic(
        activity: ModRearSceneActivity,
        displayId: Int,
        taskId: Int,
        windowId: String,
        effect: ModRearOpenEffect,
        stage: String = "mod_scene_pending_confirmation_diagnostic",
    ) {
        val runId = fixtureRunId()
        Log.i(TAG, "stage=$stage run=$runId task=$taskId component=${activity.componentName.flattenToString()} " +
            "window=$windowId activityWindow=${windowIdKey(activity.window.decorView)} display=$displayId " +
            "effect=${effect.id} scene=${effect.scene} region=${effect.region} rotation=${effect.contentRotation} " +
            "requiresUserActivation=${effect.requiresUserActivation}")
        val summary = confirmationWindowSummary(activity, displayId)
        Log.i(TAG, "stage=${stage}_conditions run=$runId task=$taskId display=$displayId $summary")
    }

    /** Fixed condition bits distinguish a missing dialog, wrong owner/focus, label, or bounds gate. */
    private fun confirmationWindowSummary(activity: ModRearSceneActivity, displayId: Int): String {
        var result = "diagnostic=unavailable"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching {
                val owner = activity.window.decorView
                val ownerToken = owner.applicationWindowToken
                val sceneVisible = runCatching {
                    activity.javaClass.getDeclaredMethod("isSceneContentVisible").apply { isAccessible = true }
                        .invoke(activity) as? Boolean
                }.getOrNull()
                val roots = WindowInspector.getGlobalWindowViews()
                val composeViews = roots.flatMap { globalRoot ->
                    mutableListOf<View>().also { collectComposeViews(globalRoot, it) }
                        .map { globalRoot to it }
                }
                val windowBits = composeViews.mapIndexedNotNull { index, (globalRoot, composeView) ->
                    runCatching {
                        val root = composeView.rootView
                        val dialog = dialogWindowProvider(composeView)?.window
                        val nodes = semanticsOwner(composeView)?.let(::semanticsRoot)?.let(::semanticsNodes).orEmpty()
                        val identityCount = nodes.count { semanticsText(it) == "Mod 请求打开背屏场景" }
                        val actionNodes = nodes.filter { semanticsText(it) == "确认打开" }
                        val clickable = actionNodes.mapNotNull(::clickableSemanticsAncestor)
                        val enabled = clickable.filterNot { it.config.contains(SemanticsProperties.Disabled) }
                        val rectValid = enabled.count { hasPositiveBounds(it) }
                        val rectVisible = enabled.count { node ->
                            dialog != null && hasVisiblePhysicalBounds(node, dialog, displayId)
                        }
                        "w$index=${listOf(
                            "display=${composeView.display?.displayId ?: -1}",
                            "rootIdentity=${composeView.rootView === globalRoot}",
                            "rootIsDialog=${dialog != null && root === dialog.decorView}",
                            "provider=${dialog != null}",
                            "attached=${composeView.isAttachedToWindow}",
                            "shown=${composeView.isShown}",
                            "focus=${dialog?.decorView?.hasWindowFocus() == true}",
                            "attrsTokenMatchesOwner=${dialog != null && ownerToken != null && dialog.attributes.token == ownerToken}",
                            "decorAppTokenMatchesOwner=${dialog != null && ownerToken != null && dialog.decorView.applicationWindowToken == ownerToken}",
                            "attrsTokenNull=${dialog?.attributes?.token == null}",
                            "title=$identityCount", "confirm=${actionNodes.size}", "clickable=${clickable.size}",
                            "enabled=${enabled.size}", "rect=$rectValid", "visibleRect=$rectVisible",
                        ).joinToString(",")}"
                    }.getOrNull()
                }
                val dialogCount = composeViews.count { dialogWindowProvider(it.second)?.window != null }
                (listOf(
                    "sceneVisible=${sceneVisible?.toString() ?: "unknown"}",
                    "ownerAttached=${owner.isAttachedToWindow}", "ownerShown=${owner.isShown}",
                    "ownerFocus=${owner.hasWindowFocus()}", "ownerDisplay=${owner.display?.displayId ?: -1}",
                    "ownerToken=${ownerToken != null}", "roots=${roots.size}",
                    "compose=${composeViews.size}", "dialogs=$dialogCount",
                ) + windowBits).joinToString(" ")
            }.getOrElse { "diagnosticError=${it.javaClass.simpleName}" }
        }
        return result
    }

    private fun clickableSemanticsAncestor(
        node: SemanticsNode,
    ): SemanticsNode? {
        var current: SemanticsNode? = node
        while (current != null && !current.config.contains(SemanticsActions.OnClick)) {
            if (current.isRoot) return null
            current = current.parent
        }
        return current
    }

    private fun hasPositiveBounds(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        return bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() && bounds.bottom.isFinite() &&
            bounds.right > bounds.left && bounds.bottom > bounds.top
    }

    private fun hasVisiblePhysicalBounds(node: SemanticsNode, window: Window, displayId: Int): Boolean {
        val bounds = node.boundsInWindow
        if (!hasPositiveBounds(node) || window.decorView.display?.displayId != displayId) return false
        val screenOrigin = IntArray(2)
        val windowOrigin = IntArray(2)
        window.decorView.getLocationOnScreen(screenOrigin)
        window.decorView.getLocationInWindow(windowOrigin)
        val rect = Rect(
            floor(bounds.left + screenOrigin[0] - windowOrigin[0]).toInt(),
            floor(bounds.top + screenOrigin[1] - windowOrigin[1]).toInt(),
            ceil(bounds.right + screenOrigin[0] - windowOrigin[0]).toInt(),
            ceil(bounds.bottom + screenOrigin[1] - windowOrigin[1]).toInt(),
        )
        val visible = Rect()
        if (!window.decorView.getGlobalVisibleRect(visible)) return false
        visible.offset(screenOrigin[0], screenOrigin[1])
        if (!rect.intersect(visible)) return false
        val realSize = Point()
        window.decorView.display?.getRealSize(realSize) ?: return false
        return rect.intersect(Rect(0, 0, realSize.x, realSize.y)) && !rect.isEmpty
    }

    private data class PhysicalInputTarget(val window: Window, val rootKind: String, val bounds: String)
    private data class PhysicalInputActionSnapshot(val window: Window, val targets: Map<String, PhysicalInputTarget>)

    /** Observe only the already-verified exact Dialog Window; delegate every event unchanged. */
    private inner class ScopedDialogTouchObserver(
        private val window: Window,
        private val runId: String,
        private val taskId: Int,
        private val displayId: Int,
    ) : AutoCloseable {
        private lateinit var original: Window.Callback
        private lateinit var wrapper: Window.Callback
        private var observedWindowId: String = "unavailable"
        private val received = mapOf("DOWN" to AtomicInteger(), "UP" to AtomicInteger(), "CANCEL" to AtomicInteger())
        private val handled = mapOf("DOWN" to AtomicInteger(), "UP" to AtomicInteger(), "CANCEL" to AtomicInteger())

        init {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                original = requireNotNull(window.callback) { "exact Scene confirmation Dialog has no Window.Callback" }
                observedWindowId = windowIdKey(window.decorView)
                wrapper = object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val action = when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> "DOWN"
                            MotionEvent.ACTION_UP -> "UP"
                            MotionEvent.ACTION_CANCEL -> "CANCEL"
                            else -> null
                        }
                        action?.let { received.getValue(it).incrementAndGet() }
                        val wasHandled = original.dispatchTouchEvent(event)
                        if (action != null) {
                            if (wasHandled) handled.getValue(action).incrementAndGet()
                            Log.i(TAG, "stage=mod_scene_confirmation_window_touch run=$runId task=$taskId display=$displayId " +
                                "window=$observedWindowId action=$action received=true handled=$wasHandled")
                        }
                        return wasHandled
                    }
                }
                check(window.callback === original) { "exact Scene confirmation Window changed before touch observer attach" }
                window.callback = wrapper
            }
        }

        override fun close() {
            var restored = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                restored = when (window.callback) {
                    wrapper -> { window.callback = original; true }
                    original -> true
                    else -> false
                }
            }
            Log.i(TAG, "stage=mod_scene_confirmation_window_touch_summary run=$runId task=$taskId display=$displayId " +
                "window=$observedWindowId down=${received.getValue("DOWN").get()} " +
                "up=${received.getValue("UP").get()} cancel=${received.getValue("CANCEL").get()} " +
                "handledDown=${handled.getValue("DOWN").get()} handledUp=${handled.getValue("UP").get()} " +
                "handledCancel=${handled.getValue("CANCEL").get()} callbackRestored=$restored")
            check(restored) { "exact Scene confirmation Window.Callback changed while scoped touch observer was installed" }
        }
    }

    /** Wait until both dialog actions have full, unclipped bounds stable across real animation time. */
    private fun awaitStableDialogActionBounds(
        activity: ModRearSceneActivity,
        displayId: Int,
        identityText: String,
        confirmText: String,
        cancelText: String,
        timeoutMs: Long = 15_000,
    ): Map<String, PhysicalInputTarget> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous: String? = null
        var stableSince = 0L
        var stableSamples = 0
        var last: PhysicalInputActionSnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = findComposeActionTargets(activity, displayId, identityText,
                setOf(confirmText, cancelText), requiredWindow = "dialog")
            if (snapshot != null) {
                val confirm = snapshot.targets[confirmText]
                val cancel = snapshot.targets[cancelText]
                if (confirm != null && cancel != null && confirm.window === cancel.window) {
                    val key = "${windowIdKey(snapshot.window.decorView)}:${confirm.bounds}:${cancel.bounds}"
                    val now = SystemClock.elapsedRealtime()
                    if (key == previous) {
                        stableSamples++
                    } else {
                        previous = key
                        stableSince = now
                        stableSamples = 1
                    }
                    last = snapshot
                    if (stableSamples >= 4 && now - stableSince >= 500) return snapshot.targets
                } else {
                    previous = null
                    stableSamples = 0
                }
            } else {
                previous = null
                stableSamples = 0
            }
            SystemClock.sleep(100)
        }
        val lastSummary = last?.let { snapshot ->
            "window=${windowIdKey(snapshot.window.decorView)} confirm=${snapshot.targets[confirmText]?.bounds} cancel=${snapshot.targets[cancelText]?.bounds}"
        } ?: "no-single-visible-pair"
        error("超时：宿主确认 Dialog 的两个按钮未在 ${timeoutMs}ms 内稳定为完整可见 bounds；last=$lastSummary")
    }

    /** Read only the exact owned Compose windows on main; never enters Espresso or waits for idle. */
    private fun awaitComposeActionBounds(
        activity: ModRearSceneActivity,
        displayId: Int,
        identityText: String,
        actionText: String,
        requiredWindow: String,
    ): PhysicalInputTarget {
        var found: PhysicalInputTarget? = null
        waitFor("目标 Compose 窗口中的唯一 $actionText 按钮 bounds", timeoutMs = 15_000) {
            found = findComposeActionBounds(activity, displayId, identityText, actionText, requiredWindow)
            found != null
        }
        return requireNotNull(found)
    }

    private fun findComposeActionBounds(
        activity: ModRearSceneActivity,
        displayId: Int,
        identityText: String,
        actionText: String,
        requiredWindow: String,
    ): PhysicalInputTarget? {
        return findComposeActionTargets(activity, displayId, identityText, setOf(actionText), requiredWindow)
            ?.targets?.get(actionText)
    }

    /** Return all requested exact action bounds from one main-thread accessibility/Compose snapshot. */
    private fun findComposeActionTargets(
        activity: ModRearSceneActivity,
        displayId: Int,
        identityText: String,
        actionTexts: Set<String>,
        requiredWindow: String,
    ): PhysicalInputActionSnapshot? {
        if (actionTexts.isEmpty()) return null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var found: PhysicalInputActionSnapshot? = null
        instrumentation.runOnMainSync {
            val activityDecor = activity.window.decorView
            if (!activityDecor.isAttachedToWindow || !activityDecor.isShown ||
                (requiredWindow == "scene" && !activityDecor.hasWindowFocus()) ||
                activityDecor.display?.displayId != displayId) return@runOnMainSync
            val globalRoots = WindowInspector.getGlobalWindowViews().filter {
                it.isAttachedToWindow && it.isShown && it.display?.displayId == displayId
            }
            val candidateWindows = globalRoots.mapNotNull { globalRoot ->
                val composeViews = mutableListOf<View>()
                collectComposeViews(globalRoot, composeViews)
                composeViews.mapNotNull { composeView ->
                    val inputWindow = inputWindowForRoot(composeView, activity, displayId, requiredWindow)
                        ?: return@mapNotNull null
                    if (composeView.rootView !== globalRoot) return@mapNotNull null
                    val owner = semanticsOwner(composeView) ?: return@mapNotNull null
                    val root = semanticsRoot(owner) ?: return@mapNotNull null
                    val nodes = semanticsNodes(root)
                    val identities = nodes.filter { semanticsText(it) == identityText }
                    if (identities.size != 1 || actionTexts.any { text -> nodes.count { semanticsText(it) == text } != 1 }) {
                        return@mapNotNull null
                    }
                    val targets = linkedMapOf<String, PhysicalInputTarget>()
                    for (actionText in actionTexts) {
                        val actionNode = nodes.single { semanticsText(it) == actionText }
                        var clickable: androidx.compose.ui.semantics.SemanticsNode? = actionNode
                        while (clickable != null && !clickable.config.contains(SemanticsActions.OnClick)) {
                            if (clickable.isRoot) return@mapNotNull null
                            clickable = clickable.parent
                        }
                        val target = clickable ?: return@mapNotNull null
                        if (target.config.contains(SemanticsProperties.Disabled)) return@mapNotNull null
                        val bounds = target.boundsInWindow
                        if (!bounds.left.isFinite() || !bounds.top.isFinite() || !bounds.right.isFinite() || !bounds.bottom.isFinite()) {
                            return@mapNotNull null
                        }
                        val inputDecor = inputWindow.decorView
                        val screenOrigin = IntArray(2)
                        val windowOrigin = IntArray(2)
                        inputDecor.getLocationOnScreen(screenOrigin)
                        inputDecor.getLocationInWindow(windowOrigin)
                        val boundsOnScreen = Rect(
                            floor(bounds.left + screenOrigin[0] - windowOrigin[0]).toInt(),
                            floor(bounds.top + screenOrigin[1] - windowOrigin[1]).toInt(),
                            ceil(bounds.right + screenOrigin[0] - windowOrigin[0]).toInt(),
                            ceil(bounds.bottom + screenOrigin[1] - windowOrigin[1]).toInt(),
                        )
                        if (boundsOnScreen.isEmpty) return@mapNotNull null
                        val visibleWindow = Rect()
                        if (!inputDecor.getGlobalVisibleRect(visibleWindow)) return@mapNotNull null
                        visibleWindow.offset(screenOrigin[0], screenOrigin[1])
                        if (!visibleWindow.contains(boundsOnScreen)) return@mapNotNull null
                        val realSize = Point()
                        inputDecor.display?.getRealSize(realSize) ?: return@mapNotNull null
                        if (!Rect(0, 0, realSize.x, realSize.y).contains(boundsOnScreen)) return@mapNotNull null
                        targets[actionText] = PhysicalInputTarget(inputWindow, if (requiredWindow == "dialog") "dialog" else "scene",
                            "${boundsOnScreen.left},${boundsOnScreen.top},${boundsOnScreen.right},${boundsOnScreen.bottom}")
                    }
                    PhysicalInputActionSnapshot(inputWindow, targets)
                }.singleOrNull()
            }.distinctBy { windowIdKey(it.window.decorView) }
            found = candidateWindows.singleOrNull()
        }
        return found
    }

    private fun collectComposeViews(view: View, output: MutableList<View>) {
        if (hasSemanticsOwner(view)) output += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectComposeViews(view.getChildAt(index), output)
        }
    }

    private fun hasSemanticsOwner(view: View): Boolean = view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView"

    /** AndroidComposeView is an implementation detail; reflection stays test-only and read-only. */
    private fun semanticsOwner(view: View): Any? = runCatching {
        val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?: view.javaClass.declaredMethods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?: return null
        method.isAccessible = true
        method.invoke(view)
    }.getOrNull()

    private fun semanticsRoot(owner: Any): androidx.compose.ui.semantics.SemanticsNode? = runCatching {
        val method = owner.javaClass.methods.firstOrNull { it.name == "getUnmergedRootSemanticsNode" && it.parameterCount == 0 }
            ?: owner.javaClass.declaredMethods.firstOrNull { it.name == "getUnmergedRootSemanticsNode" && it.parameterCount == 0 }
            ?: return null
        method.isAccessible = true
        method.invoke(owner) as? androidx.compose.ui.semantics.SemanticsNode
    }.getOrNull()

    private fun semanticsNodes(root: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        buildList {
            fun visit(node: androidx.compose.ui.semantics.SemanticsNode) {
                add(node)
                node.children.forEach(::visit)
            }
            visit(root)
        }

    private fun semanticsText(node: androidx.compose.ui.semantics.SemanticsNode): String? =
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        } else null

    private fun inputWindowForRoot(
        composeRoot: View,
        activity: ModRearSceneActivity,
        displayId: Int,
        requiredWindow: String,
    ): Window? {
        if (!composeRoot.isAttachedToWindow || !composeRoot.isShown || composeRoot.display?.displayId != displayId) return null
        val ownerDecor = activity.window.decorView
        if (requiredWindow == "scene") {
            return activity.window.takeIf {
                composeRoot.rootView === ownerDecor && composeRoot.windowId == ownerDecor.windowId && ownerDecor.isAttachedToWindow &&
                    ownerDecor.isShown && ownerDecor.hasWindowFocus() && ownerDecor.display?.displayId == displayId
            }
        }
        val dialog = dialogWindowProvider(composeRoot)?.window ?: return null
        val dialogDecor = dialog.decorView
        return dialog.takeIf {
            composeRoot.rootView === dialogDecor && contextChainContainsActivity(composeRoot.context, activity) &&
                dialogDecor.isAttachedToWindow && dialogDecor.isShown &&
                dialogDecor.hasWindowFocus() && dialogDecor.display?.displayId == displayId
        }
    }

    /** Compose Dialog wraps the host Activity context; prove the exact Activity by identity. */
    private fun contextChainContainsActivity(context: Context, owner: Activity): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        var current: Context? = context
        while (current != null && seen.add(current)) {
            if (current === owner) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun dialogWindowProvider(view: View): DialogWindowProvider? {
        var current: View? = view
        while (current != null) {
            if (current is DialogWindowProvider) return current
            current = current.parent as? View
        }
        return null
    }

    private fun windowIdKey(view: View): String = Integer.toHexString(view.windowId.hashCode())

    private fun findTexts(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> = buildList {
        if (root.text?.toString() == text || root.contentDescription?.toString() == text) add(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(findTexts(it, text)) }
    }

    private fun findVisibleTexts(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> =
        findTexts(root, text).filter { it.isVisibleToUser }

    private fun ancestors(start: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        var node: AccessibilityNodeInfo? = start
        while (node != null) { add(node); node = node.parent }
    }

    private fun List<AccessibilityNodeInfo>.takeWhileInclusive(end: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
        val endIndex = indexOfFirst { it === end }
        return if (endIndex < 0) null else take(endIndex + 1)
    }

    private fun containsText(root: AccessibilityNodeInfo, text: String): Boolean = findTexts(root, text).isNotEmpty()

    private fun waitFor(description: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(200)
        }
        error("超时：$description")
    }

    private fun waitForSceneActivity(
        activities: List<ModRearSceneActivity>,
        timeoutMs: Long = 45_000,
        predicate: (SceneLaunchContext, ModRearSceneActivity) -> Boolean
    ): ModRearSceneActivity {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            activities.toList().asReversed().forEach { activity ->
                val route = sceneRoute(activity)
                if (route != null && predicate(route, activity)) return activity
            }
            SystemClock.sleep(200)
        }
        error("超时：未观察到真实 Mod Scene viewport ready")
    }

    private fun isTestModRoute(route: SceneLaunchContext, authorFingerprint: String, hostPackage: String): Boolean {
        val principal = route.owner.principal as? top.zainzh.rearscreen.platform.SessionPrincipal.SignedMod ?: return false
        return principal.modId == modId && principal.authorFingerprint == authorFingerprint &&
            route.request.component == ComponentName(hostPackage, ModRearSceneActivity::class.java.name)
    }

    private fun sceneRoute(activity: ModRearSceneActivity): SceneLaunchContext? = runCatching {
        activity.javaClass.getDeclaredField("launch").apply { isAccessible = true }.get(activity) as? SceneLaunchContext
    }.getOrNull()

    private fun assertExactVisibleScenePresentation(
        core: RearCapabilityCore,
        route: SceneLaunchContext,
    ) {
        val snapshot = core.displaySessions.queryScenePresentation(route.owner, route.sessionId, route.generation)
        assertNotNull("production presentation snapshot belongs to the exact ready Scene", snapshot)
        requireNotNull(snapshot).also {
            assertEquals(route.sessionId, it.sessionId)
            assertEquals(route.generation, it.generation)
            assertTrue("the exact Scene is visible after its Ready viewport", it.visible)
            assertTrue("the exact Scene presentation snapshot has a positive revision", it.revision > 0)
        }
    }

    private fun awaitViewport(core: RearCapabilityCore, route: SceneLaunchContext): top.zainzh.rearscreen.platform.RearViewportSnapshot {
        // The Activity attaches its own observer while completing window ready. Wait for
        // its authoritative visibility report before attaching the fixture observer.
        waitFor("生产宿主已接受精确 Scene 的可见性") {
            core.displaySessions.queryScenePresentation(route.owner, route.sessionId, route.generation)
                ?.let { it.sessionId == route.sessionId && it.generation == route.generation &&
                    it.visible && it.revision > 0 } == true
        }
        val latch = CountDownLatch(1)
        var result: top.zainzh.rearscreen.platform.RearViewportSnapshot? = null
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
                if (sessionId == route.sessionId && generation == route.generation && event is SceneEvent.Ready) {
                    result = event.viewport
                    latch.countDown()
                }
            }
        }
        val attached = core.displaySessions.attachSceneObserver(route.owner, route.sessionId, route.generation, observer)
        assertTrue("production core accepts observer for the exact fixture lease", attached.success)
        assertTrue("production core reports viewport from the actual Scene window", latch.await(12, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private companion object {
        const val TAG = "Rc5SignedModSceneProbe"
        const val REAR_SCENE_TITLE = "R29"
    }
}
