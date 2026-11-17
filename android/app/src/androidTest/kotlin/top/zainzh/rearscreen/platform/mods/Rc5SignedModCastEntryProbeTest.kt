package top.zainzh.rearscreen.platform.mods

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.graphics.Region
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.MainActivity
import top.zainzh.rearscreen.R
import java.util.concurrent.CopyOnWriteArrayList

/** Real signed-Mod Cast flow using only the exact pre-launched Physical A task and host UI. */
@RunWith(AndroidJUnit4::class)
class Rc5SignedModCastEntryProbeTest {
    private val modId = "rc5.thirdparty.cast-demo.r20"
    private val assetPath = "platform/mods/rc5-thirdparty-cast-demo-r20.zip"
    private var fixtureMainRequest = 0
    private var interactiveWindows: AutoCloseable? = null

    @Before fun enableInteractiveWindowsForFixture() {
        Log.i(TAG, "stage=fixture_runner_entered run=${fixtureRunId()}")
        interactiveWindows = Rc5FixtureWindows.enableInteractiveWindows(
            InstrumentationRegistry.getInstrumentation().uiAutomation,
        )
    }

    @After fun restoreInteractiveWindowsForFixture() {
        interactiveWindows?.close()
        interactiveWindows = null
    }

    @Test fun signedModCastUsesExactMainTaskConfirmationAndOwnerHandleLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as Application
        val archive = instrumentation.context.assets.open(assetPath).use { it.readBytes() }
        val verified = ModPackageReader.read(archive.inputStream())
        assertEquals(modId, verified.id)
        assertTrue(verified.requiredCapabilities.any { it.name == "rear.cast.virtual" })

        require(ModPackageStore.installed(context, modId) == null) {
            "保留已安装的 $modId 及其配置；不得覆盖真实用户 Mod"
        }
        val baseline = ModPackageStore.productionSnapshotForTest(context)
        val authorWasTrusted = ModPackageStore.isAuthorTrusted(context, verified.authorFingerprint)
        Log.i(TAG, "stage=fixture_baseline trusted_author=$authorWasTrusted")
        require(baseline.entries.none { it.authorFingerprint == verified.authorFingerprint }) {
            "测试签名作者已有其他 Mod；停止以避免改动其持久状态"
        }
        val installed = ModPackageStore.install(context, archive,
            ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()))
        val mainActivities = CopyOnWriteArrayList<MainActivity>()
        val resumedMainActivities = CopyOnWriteArrayList<MainActivity>()
        val settingsActivities = CopyOnWriteArrayList<ModSettingsActivity>()
        val configActivities = CopyOnWriteArrayList<ModConfigActivity>()
        val foregroundChoices = CopyOnWriteArrayList<ModCastForegroundTargetActivity>()
        val targetPickers = CopyOnWriteArrayList<ModCastTargetPickerActivity>()
        val resumedSettings = CopyOnWriteArrayList<ModSettingsActivity>()
        val resumedConfig = CopyOnWriteArrayList<ModConfigActivity>()
        val resumedForegroundChoices = CopyOnWriteArrayList<ModCastForegroundTargetActivity>()
        val resumedTargetPickers = CopyOnWriteArrayList<ModCastTargetPickerActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                when (activity) {
                    is MainActivity -> mainActivities += activity
                    is ModSettingsActivity -> settingsActivities += activity
                    is ModConfigActivity -> configActivities += activity
                    is ModCastForegroundTargetActivity -> foregroundChoices += activity
                    is ModCastTargetPickerActivity -> targetPickers += activity
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                when (activity) {
                    is MainActivity -> resumedMainActivities += activity
                    is ModSettingsActivity -> resumedSettings += activity
                    is ModConfigActivity -> resumedConfig += activity
                    is ModCastForegroundTargetActivity -> resumedForegroundChoices += activity
                    is ModCastTargetPickerActivity -> resumedTargetPickers += activity
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) resumedMainActivities.remove(activity)
                if (activity is ModSettingsActivity) resumedSettings.remove(activity)
                if (activity is ModConfigActivity) resumedConfig.remove(activity)
                if (activity is ModCastForegroundTargetActivity) resumedForegroundChoices.remove(activity)
                if (activity is ModCastTargetPickerActivity) resumedTargetPickers.remove(activity)
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) {
                    mainActivities.remove(activity)
                    resumedMainActivities.remove(activity)
                }
                if (activity is ModSettingsActivity) {
                    settingsActivities.remove(activity)
                    resumedSettings.remove(activity)
                }
                if (activity is ModConfigActivity) {
                    configActivities.remove(activity)
                    resumedConfig.remove(activity)
                }
                if (activity is ModCastForegroundTargetActivity) {
                    foregroundChoices.remove(activity)
                    resumedForegroundChoices.remove(activity)
                }
                if (activity is ModCastTargetPickerActivity) {
                    targetPickers.remove(activity)
                    resumedTargetPickers.remove(activity)
                }
            }
        }
        var registered = false
        var primaryFailure: Throwable? = null
        try {
            assertTrue("only this private fixture is enabled", ModPackageStore.setEnabled(context, installed.id, true).enabled)
            app.registerActivityLifecycleCallbacks(callbacks)
            registered = true
            Log.i(TAG, "stage=mod_cast_fixture_enabled mod=$modId")

            var config = openExactModConfig(context, instrumentation, verified.name,
                resumedMainActivities, resumedSettings, resumedConfig)
            val invokingConfig = config
            val startRevision = ModPackageStore.model(context, modId)?.revision
                ?: error("测试 Mod 的初始模型未提交")
            top.zainzh.rearscreen.Rc5TargetTaskSnapshotProbe.requireFixtureTaskPair(TAG)
            Log.i(TAG, "stage=fixture_target_pair_verified controllerTask=provided targetTask=provided")
            assertConfigVisible(instrumentation, context.packageName, listOf(MOD_TITLE, START_LABEL))
            waitForAction(instrumentation, context.packageName, Display.DEFAULT_DISPLAY,
                listOf(MOD_TITLE), START_LABEL, "Mod 设置中的真实 cast.start 点击")
            Log.i(TAG, "stage=mod_cast_start_requested")

            val foregroundPicker = waitForActivity(resumedForegroundChoices) { activity ->
                activity.hasWindowFocus() && activity.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY
            }
            try {
                awaitExactForegroundTarget(instrumentation, context.packageName, foregroundPicker)
            } catch (failure: Throwable) {
                // Diagnostics are best-effort and must never replace the fixture's original failure.
                runCatching { top.zainzh.rearscreen.Rc5TargetTaskSnapshotProbe.log(TAG) }
                throw failure
            }
            // Re-read the exact fixture pair immediately before the chooser action. The row is
            // selected by its visible activity identity, not by the package label alone.
            top.zainzh.rearscreen.Rc5TargetTaskSnapshotProbe.requireFixtureTaskPair(TAG)
            val selectedChoiceLabel = clickExactForegroundChoice(instrumentation, context.packageName,
                foregroundPicker, TARGET_ACTIVITY_MARKER, TARGET_PACKAGE)
                ?: error("拒绝继续：没有唯一可点击的 $TARGET_ACTIVITY_MARKER/$TARGET_PACKAGE 同行目标")
            Log.i(TAG, "stage=exact_main_target_selected")

            val confirmationPicker = waitForActivity(resumedTargetPickers) { activity ->
                activity.hasWindowFocus() && activity.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY
            }
            val expectedConfirmation = listOf(
                "目标应用：$selectedChoiceLabel", "方式：虚拟投放", "区域：FULL 全域", "内容方向：90°", "确认并投放"
            )
            waitForExactRoots(instrumentation, context.packageName, Display.DEFAULT_DISPLAY, expectedConfirmation)
            check(confirmationPicker.hasWindowFocus() && !confirmationPicker.isFinishing &&
                confirmationPicker.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY) {
                "拒绝确认：透明宿主确认页已失去主屏焦点或显示身份"
            }
            Log.i(TAG, "stage=exact_target_confirmation_visible")
            var confirmationActionStartedAtMs = 0L
            waitForAction(instrumentation, context.packageName, Display.DEFAULT_DISPLAY,
                expectedConfirmation.dropLast(1), "确认并投放", "宿主二次确认中的精确目标/方式/区域/方向",
                requiredFocusedActivity = confirmationPicker,
                beforeClick = { confirmationActionStartedAtMs = System.currentTimeMillis() })

            // The signed reducer commits its result independently of the settings
            // Activity's visibility. Require this start's fresh terminal revision
            // first; verify the original Activity and buttons separately.
            var readyState: FixtureCastState? = null
            try {
                waitFor("本次签名 Mod 投放的持久终态", timeoutMs = 26_000L) {
                    val model = ModPackageStore.model(context, modId) ?: return@waitFor false
                    if (model.revision < startRevision + 2) return@waitFor false
                    val state = model.state ?: return@waitFor false
                    if (state.optString("status") == "投放未就绪") {
                        val token = extractCastFailureToken(listOf(state.optString("detail"))) ?: "E_UNKNOWN"
                        error("signed Cast returned failure: $token")
                    }
                    if (state.optString("status") != "VIRTUAL 已就绪") return@waitFor false
                    readyState = fixtureCastState(model) ?: error("READY 模型缺少完整会话句柄")
                    true
                }
            } catch (failure: Throwable) {
                runCatching {
                    val model = ModPackageStore.model(context, modId)
                    val state = when (model?.state?.optString("status")) {
                        "等待宿主选择与确认" -> "pending"
                        "VIRTUAL 已就绪" -> "ready"
                        "投放未就绪" -> "rejected"
                        else -> if (model == null) "missing" else "other"
                    }
                    Log.i(TAG, "stage=mod_cast_start_model state=$state " +
                        "fresh_terminal=${model != null && model.revision >= startRevision + 2} " +
                        "original_resumed=${invokingConfig in resumedConfig} " +
                        "picker_resumed=${confirmationPicker in resumedTargetPickers}")
                }
                runCatching { top.zainzh.rearscreen.Rc5TargetTaskSnapshotProbe
                    .logControlledVirtualStartDiagnostic(TAG, confirmationActionStartedAtMs) }
                throw failure
            }
            val confirmed = requireNotNull(readyState)
            val readyConfig = invokingConfig
            val handle = confirmed.handle
            Log.i(TAG, "stage=mod_cast_ready_committed")
            // Physical frame is checked separately from the persisted result.
            Log.i(TAG, "stage=mod_cast_ready_capture_window duration_ms=8000 run=${fixtureRunId()}")
            SystemClock.sleep(8_000)

            val query = try {
                waitForOwnedCastAction(instrumentation, readyConfig, resumedConfig, handle, QUERY_LABEL)
            } catch (failure: Throwable) {
                runCatching {
                    val activityState = readFixtureCastState(instrumentation, readyConfig)
                    val modelMatches = activityState?.let { handle.matches(it.handle) } == true
                    Log.i(TAG, "stage=mod_cast_query_window original_resumed=${readyConfig in resumedConfig} " +
                        "picker_resumed=${confirmationPicker in resumedTargetPickers} model_matches=$modelMatches")
                }
                throw failure
            }
            val readyResult = waitForOwnedCastResult(instrumentation, readyConfig, query, "查询：")
            assertEquals("查询：READY", readyResult.status)
            assertEquals(EXPECTED_CAST_VIEWPORT, readyResult.detail)
            Log.i(TAG, "stage=mod_cast_query_ready")

            val close = waitForOwnedCastAction(instrumentation, readyConfig, resumedConfig, handle, CLOSE_LABEL)
            val closeResult = waitForOwnedCastResult(instrumentation, readyConfig, close, "关闭：")
            check(closeResult.status == "关闭：CLOSING" || closeResult.status == "关闭：CLOSED")
            Log.i(TAG, "stage=mod_cast_close_submitted")
            finishHostActivities(instrumentation, mainActivities, settingsActivities, configActivities)
            waitForTargetWindow(instrumentation)
            assertTargetReturnedAndPickerGone(instrumentation, context.packageName)

            config = reopenExactModConfig(context, instrumentation, verified.name,
                resumedMainActivities, resumedSettings, resumedConfig)
            waitForFinalClosedQuery(instrumentation, config, resumedConfig, handle)
            Log.i(TAG, "stage=mod_cast_closed_and_target_returned")
            Log.i(TAG, "stage=mod_cast_resources_released")
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
            settingsActivities.toList().forEach { activity ->
                cleanup { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() } }
            }
            configActivities.toList().forEach { activity ->
                cleanup { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() } }
            }
            foregroundChoices.toList().forEach { activity ->
                cleanup { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() } }
            }
            targetPickers.toList().forEach { activity ->
                cleanup { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() } }
            }
            if (registered) cleanup { app.unregisterActivityLifecycleCallbacks(callbacks) }
            cleanup { ModBrokerProvider.get(context).invalidateAndWait(installed.id, "E_MOD_UNINSTALLED", "Cast 入口夹具收尾") }
            cleanup {
                top.zainzh.rearscreen.platform.RearCapabilityCore.get(context).modDataOwner(installed.id)?.let { owner ->
                    top.zainzh.rearscreen.platform.ControlledCastRuntimeProvider.get(context).coordinator.revokeOwner(owner)
                }
            }
            cleanup { ModPackageStore.uninstall(context, installed.id) }
            if (!authorWasTrusted) cleanup { ModPackageStore.revokeAuthorTrust(context, verified.authorFingerprint) }
            cleanup {
                val after = ModPackageStore.productionSnapshotForTest(context)
                assertEquals("只删除夹具自身安装记录", baseline.entries, after.entries)
                assertEquals("作者信任恢复到测试前基线", baseline.trustedAuthors, after.trustedAuthors)
                assertEquals("测试 Mod 的 publisher binding 保持生产语义",
                    baseline.publishedModOwners + (modId to verified.authorFingerprint), after.publishedModOwners)
                Log.i(TAG, "stage=mod_cast_fixture_cleanup_verified mod=$modId")
            }
            cleanupFailure?.let { failure ->
                val primary = primaryFailure
                if (primary == null) throw failure else primary.addSuppressed(failure)
            }
        }
    }

    private fun readModId(activity: ModConfigActivity): String? = runCatching {
        activity.javaClass.getDeclaredField("modId").apply { isAccessible = true }.get(activity) as? String
    }.getOrNull()

    private fun openExactModConfig(
        context: android.content.Context,
        instrumentation: android.app.Instrumentation,
        modName: String,
        resumedMainActivities: List<MainActivity>,
        resumedSettings: List<ModSettingsActivity>,
        resumedConfig: List<ModConfigActivity>,
    ): ModConfigActivity {
        val request = ++fixtureMainRequest
        val runId = fixtureRunId()
        Log.i(TAG, "stage=fixture_needs_main request=$request run=$runId")
        val main = waitForActivity(resumedMainActivities) {
            it.hasWindowFocus() && !it.isFinishing && it.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY
        }
        assertEquals("main host activity is on the main display", Display.DEFAULT_DISPLAY,
            main.window.decorView.display?.displayId)
        val navModules = context.getString(R.string.ui_nav_modules)
        val zipMods = context.getString(R.string.zip_mods_title)
        var mainRoot = waitForMainRoot(instrumentation, context.packageName, navModules, zipMods)
        if (findTexts(mainRoot, zipMods).isEmpty()) {
            check(clickMainAction(instrumentation, context.packageName, navModules)) {
                "主屏 Modules 导航控件不可用"
            }
            waitForMainAction(instrumentation, context.packageName, zipMods)
            mainRoot = waitForMainRoot(instrumentation, context.packageName, navModules, zipMods)
        }
        assertEquals("ZIP Mods entry is unique on the single visible host window", 1, findTexts(mainRoot, zipMods).size)
        check(clickMainAction(instrumentation, context.packageName, zipMods)) { "主屏 ZIP Mods 入口不可用" }
        Log.i(TAG, "stage=fixture_main_ready request=$request display=${main.window.decorView.display?.displayId} run=$runId")
        val settings = waitForActivity(resumedSettings) {
            it.hasWindowFocus() && it.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY
        }
        assertEquals(Display.DEFAULT_DISPLAY, settings.window.decorView.display?.displayId)
        waitForHostTexts(instrumentation, context.packageName, listOf("Mod 管理", modName))
        requireUniqueModEntry(instrumentation, context.packageName, modName)
        waitForAction(instrumentation, context.packageName, Display.DEFAULT_DISPLAY,
            listOf(modName), "设置", "只打开签名测试 Mod 的原生设置")
        val config = waitForActivity(resumedConfig) {
            it.hasWindowFocus() && it.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY && readModId(it) == modId
        }
        assertEquals(Display.DEFAULT_DISPLAY, config.window.decorView.display?.displayId)
        return config
    }

    private fun reopenExactModConfig(
        context: android.content.Context,
        instrumentation: android.app.Instrumentation,
        modName: String,
        resumedMainActivities: List<MainActivity>,
        resumedSettings: List<ModSettingsActivity>,
        resumedConfig: List<ModConfigActivity>,
    ) = openExactModConfig(context, instrumentation, modName, resumedMainActivities, resumedSettings, resumedConfig)

    private fun assertConfigVisible(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        texts: List<String>,
    ) = waitForExactRoots(instrumentation, hostPackage, Display.DEFAULT_DISPLAY, texts)

    private fun waitForAction(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
        identityTexts: List<String>,
        actionText: String,
        description: String,
        timeoutMs: Long = 25_000,
        requiredFocusedActivity: Activity? = null,
        beforeClick: (() -> Unit)? = null,
    ) = waitFor(description, timeoutMs) {
        requiredFocusedActivity?.let { activity ->
            check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
                activity.window.decorView.display?.displayId == displayId) {
                "拒绝点击：$description 对应的宿主确认 Activity 已失去焦点/显示身份"
            }
        }
        if (clickScopedAction(instrumentation, hostPackage, displayId, identityTexts, actionText,
                requiredFocusedActivity = requiredFocusedActivity, beforeClick = beforeClick)) true
        else {
            // Scrolling only reveals content; it is not evidence that the requested action fired.
            scrollExactWindow(instrumentation, hostPackage, displayId, identityTexts)
            false
        }
    }

    /** Inspect only the verified first-stage picker; bounded scroll never activates a row. */
    private fun awaitExactForegroundTarget(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        pickerActivity: ModCastForegroundTargetActivity,
        timeoutMs: Long = 25_000,
    ) {
        val title = "选择主屏应用"
        val emptyState = "没有可核实的主屏应用"
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var scrollAttempts = 0
        var lastRootCount = 0
        var lastPickerTitleHits = 0
        var lastActivityMarkerHits = 0
        var lastTargetRowHits = 0
        var lastEmptyHits = 0
        var lastScrollableCount = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            check(!pickerActivity.isFinishing && !pickerActivity.isDestroyed && pickerActivity.hasWindowFocus() &&
                pickerActivity.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY) {
                "拒绝继续：精确主屏任务选择器已失去主屏焦点/显示身份"
            }
            val roots = visibleHostRoots(instrumentation, hostPackage)
            val pickers = roots.filter { findTexts(it, title).isNotEmpty() }
            check(pickers.size <= 1) { "拒绝检查：display0 上出现多个精确主屏任务选择器" }
            lastRootCount = roots.size
            lastPickerTitleHits = pickers.sumOf { findTexts(it, title).size }
            check(lastPickerTitleHits <= 1) { "拒绝检查：精确主屏任务选择器标题不唯一" }
            val root = pickers.singleOrNull()
            if (root != null) {
                lastActivityMarkerHits = findComponentMarkerNodes(root, TARGET_ACTIVITY_MARKER).size
                lastTargetRowHits = foregroundChoiceRows(root, TARGET_ACTIVITY_MARKER, TARGET_PACKAGE).size
                lastEmptyHits = findTexts(root, emptyState).size
                check(lastTargetRowHits <= 1) {
                    "拒绝继续：精确组件 $TARGET_PACKAGE/$TARGET_ACTIVITY_MARKER 对应的可点击行不唯一"
                }
                if (lastTargetRowHits == 1) return
                val scrollables = allNodes(root).filter { it.isScrollable }
                lastScrollableCount = scrollables.size
                check(scrollables.size <= 1) { "拒绝滚动：精确选择器内存在多个可滚动列表" }
                val list = scrollables.singleOrNull()
                if (scrollAttempts < MAX_PICKER_SCROLLS && list != null) {
                    scrollAttempts++
                    list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            SystemClock.sleep(250)
        }
        Log.i(TAG, "stage=foreground_target_diagnostic display=${Display.DEFAULT_DISPLAY} picker_title_hits=$lastPickerTitleHits " +
            "host_roots=$lastRootCount activity_marker_hits=$lastActivityMarkerHits exact_target_rows=$lastTargetRowHits " +
            "empty_state_hits=$lastEmptyHits scrollable_count=$lastScrollableCount scroll_attempts=$scrollAttempts " +
            "picker_resumed_focus_verified=true picker_focus=${pickerActivity.hasWindowFocus()} " +
            "picker_display=${pickerActivity.window.decorView.display?.displayId}")
        error("超时：主屏任务选择器未展示唯一 $TARGET_PACKAGE/$TARGET_ACTIVITY_MARKER 可点击行；诊断值已记录标题、精确行、空态与滚动容器命中数")
    }

    private fun waitForExactRoots(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
        texts: List<String>,
        timeoutMs: Long = 30_000,
    ): List<AccessibilityNodeInfo> {
        var selected: List<AccessibilityNodeInfo> = emptyList()
        waitFor("精确 UI 证据 $texts", timeoutMs) {
            val roots = matchingRoots(instrumentation, hostPackage, displayId, texts)
            check(roots.size <= 1) { "拒绝继续：display=$displayId 有多个匹配的 host UI 窗口" }
            if (roots.size == 1) { selected = roots; true } else false
        }
        return selected
    }

    private fun waitForTargetWindow(
        instrumentation: android.app.Instrumentation,
        timeoutMs: Long = 25_000,
    ) = waitFor("display0 上精确 RC5 Physical A 可见窗口", timeoutMs) {
        val roots = matchingRoots(instrumentation, TARGET_PACKAGE, Display.DEFAULT_DISPLAY,
            listOf(TARGET_PAGE_MARKER))
        check(roots.size <= 1) { "拒绝继续：display0 有多个 $TARGET_PACKAGE/$TARGET_PAGE_MARKER 窗口" }
        roots.size == 1
    }

    private fun assertExactTargetVisible(instrumentation: android.app.Instrumentation) {
        val roots = matchingRoots(instrumentation, TARGET_PACKAGE, Display.DEFAULT_DISPLAY,
            listOf(TARGET_PAGE_MARKER))
        assertEquals("the exact selected MainActivity sample remains visible on the main display", 1, roots.size)
    }

    private fun waitForFinalClosedQuery(
        instrumentation: android.app.Instrumentation,
        config: ModConfigActivity,
        resumedConfig: List<ModConfigActivity>,
        handle: FixtureCastHandle,
    ) {
        val deadline = SystemClock.elapsedRealtime() + FINAL_QUERY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val query = waitForOwnedCastAction(instrumentation, config, resumedConfig, handle, QUERY_LABEL, 8_000)
            val result = waitForOwnedCastResult(instrumentation, config, query, "查询：")
            assertEquals(EXPECTED_CAST_VIEWPORT, result.detail)
            if (result.status == "查询：CLOSED") return
            check(result.status == "查询：CLOSING") { "同一 handle 终态查询返回非 CLOSED 状态：${result.status}" }
            SystemClock.sleep(350)
        }
        error("超时：同一 owner handle 未报告 CLOSED")
    }

    /**
     * Query/close the existing handle through the real settings buttons. The rear
     * presenter may own global focus. Bind to this resumed Activity's actual
     * accessibility window on the unlocked main display, not a title-only match.
     * Start, target selection, and approval keep their separate focus gates.
     */
    private fun waitForOwnedCastAction(
        instrumentation: android.app.Instrumentation,
        config: ModConfigActivity,
        resumedConfig: List<ModConfigActivity>,
        handle: FixtureCastHandle,
        label: String,
        timeoutMs: Long = 15_000,
    ): FixtureCastState {
        require(label == QUERY_LABEL || label == CLOSE_LABEL)
        var submitted: FixtureCastState? = null
        waitFor("主屏原 Mod 的 $label", timeoutMs) {
            val original = ownedCastWindow(instrumentation, config, resumedConfig, handle)
                ?: return@waitFor false
            val target = ownedCastButton(original, label)
            val current = ownedCastWindow(instrumentation, config, resumedConfig, handle)
                ?: return@waitFor false
            if (current.window.id != original.window.id) return@waitFor false
            val fresh = ownedCastButton(current, label)
            if (target == null && fresh == null) {
                val scrollables = allNodes(current.root).filter { it.isScrollable && current.exposes(it) }
                check(scrollables.size <= 1) { "拒绝滚动：主屏原 Mod 存在多个可见滚动容器" }
                scrollables.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return@waitFor false
            }
            if (fresh == null || fresh != target) return@waitFor false
            val durable = ModPackageStore.model(instrumentation.targetContext, modId)
                ?.let(::fixtureCastState) ?: return@waitFor false
            if (!handle.matches(durable.handle) || current.state.revision != durable.revision) return@waitFor false
            check(fresh.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "未提交 $label；不重复点击" }
            submitted = durable
            Log.i(TAG, "stage=mod_cast_button_dispatched action=${if (label == QUERY_LABEL) "query" else "close"}")
            true
        }
        return requireNotNull(submitted)
    }

    private class FixtureCastHandle(val sessionId: String, val generation: Long) {
        fun matches(other: FixtureCastHandle) = sessionId == other.sessionId && generation == other.generation
    }

    private data class FixtureCastState(
        val handle: FixtureCastHandle,
        val label: String,
        val revision: Long,
        val status: String,
        val detail: String,
    )

    // Read the exact settings renderer's model; scrolling can clip the top labels.
    // Only the fixture's own Mod is read. Never log the full opaque handle.
    private val fixtureModelReader by lazy {
        ModConfigActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible = true }
    }
    private val fixtureVisibilityReader by lazy {
        ModConfigActivity::class.java.getDeclaredMethod("getSceneVisible").apply { isAccessible = true }
    }
    private val fixtureErrorReader by lazy {
        ModConfigActivity::class.java.getDeclaredMethod("getError").apply { isAccessible = true }
    }

    private fun fixtureCastState(config: ModConfigActivity): FixtureCastState? {
        if (config.isDestroyed || config.isFinishing || readModId(config) != modId) return null
        val model = fixtureModelReader.invoke(config) as? ModPersistedModel ?: return null
        return fixtureCastState(model)
    }

    private fun fixtureCastState(model: ModPersistedModel): FixtureCastState? {
        val state = model.state ?: return null
        val id = state.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
        val generation = state.optLong("generation").takeIf { it > 0 } ?: return null
        return FixtureCastState(FixtureCastHandle(id, generation), state.optString("handleLabel"),
            model.revision, state.optString("status"), state.optString("detail"))
    }

    private fun readFixtureCastState(
        instrumentation: android.app.Instrumentation,
        config: ModConfigActivity,
    ): FixtureCastState? {
        var result: FixtureCastState? = null
        instrumentation.runOnMainSync { result = fixtureCastState(config) }
        return result
    }

    private fun waitForOwnedCastResult(
        instrumentation: android.app.Instrumentation,
        config: ModConfigActivity,
        submission: FixtureCastState,
        prefix: String,
    ): FixtureCastState {
        val query = prefix == "查询："
        val rejected = if (query) "查询失败" else "关闭失败"
        val pending = if (query) "正在查询" else "正在关闭"
        val action = if (query) "query" else "close"
        var result: FixtureCastState? = null
        try {
            waitFor("本次 $prefix 的新结果", QUERY_RESULT_TIMEOUT_MS) {
                val durable = ModPackageStore.model(instrumentation.targetContext, modId)
                    ?.let(::fixtureCastState) ?: return@waitFor false
                check(submission.handle.matches(durable.handle)) { "Mod 投放句柄在等待结果时发生变化" }
                if (durable.revision < submission.revision + 2) return@waitFor false
                if (durable.status == rejected) {
                    val code = extractCastFailureToken(listOf(durable.detail)) ?: "E_UNKNOWN"
                    error("signed Cast $action returned failure: $code")
                }
                if (!durable.status.startsWith(prefix)) return@waitFor false
                val rendered = readFixtureCastState(instrumentation, config) ?: return@waitFor false
                if (!durable.handle.matches(rendered.handle) || rendered.revision != durable.revision ||
                    rendered.status != durable.status || rendered.detail != durable.detail) return@waitFor false
                result = rendered
                true
            }
        } catch (failure: Throwable) {
            runCatching {
                val durable = ModPackageStore.model(instrumentation.targetContext, modId)?.let(::fixtureCastState)
                val stage = when {
                    durable == null -> "missing"
                    !submission.handle.matches(durable.handle) -> "handle_changed"
                    durable.revision <= submission.revision -> "unchanged"
                    durable.status == pending -> "pending"
                    durable.status == rejected -> "rejected"
                    durable.status.startsWith(prefix) -> "committed"
                    else -> "other"
                }
                instrumentation.runOnMainSync {
                    val visible = fixtureVisibilityReader.invoke(config) == true
                    val submitRejected = (fixtureErrorReader.invoke(config) as? String)
                        ?.startsWith("操作暂未提交：") == true
                    Log.i(TAG, "stage=mod_cast_action_result action=$action durable=$stage " +
                        "settings_visible=$visible submit_rejected=$submitRejected")
                }
            }
            throw failure
        }
        return requireNotNull(result)
    }

    private data class OwnedCastWindow(
        val window: AccessibilityWindowInfo,
        val root: AccessibilityNodeInfo,
        val above: List<AccessibilityWindowInfo>,
        val state: FixtureCastState,
    ) {
        fun exposes(node: AccessibilityNodeInfo): Boolean {
            if (!node.isVisibleToUser || !node.isEnabled || node.windowId != window.id) return false
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.isEmpty) return false
            val x = bounds.centerX()
            val y = bounds.centerY()
            fun covers(w: AccessibilityWindowInfo) = Region().also(w::getRegionInScreen).contains(x, y)
            return covers(window) && above.none(::covers)
        }
    }

    private fun ownedCastWindow(
        instrumentation: android.app.Instrumentation,
        config: ModConfigActivity,
        resumedConfig: List<ModConfigActivity>,
        handle: FixtureCastHandle,
    ): OwnedCastWindow? {
        var windowId: Int? = null
        var state: FixtureCastState? = null
        instrumentation.runOnMainSync {
            val decor = config.window.decorView
            if (resumedConfig.singleOrNull() === config && !config.isFinishing && !config.isDestroyed &&
                readModId(config) == modId && decor.isAttachedToWindow && decor.isShown &&
                decor.windowVisibility == View.VISIBLE && decor.display?.displayId == Display.DEFAULT_DISPLAY &&
                decor.display?.state == Display.STATE_ON &&
                config.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) {
                val model = fixtureCastState(config) ?: return@runOnMainSync
                if (!handle.matches(model.handle)) return@runOnMainSync
                state = model
                @Suppress("DEPRECATION")
                windowId = AccessibilityNodeInfo.obtain(decor).windowId.takeIf { it >= 0 }
            }
        }
        val id = windowId ?: return null
        val windows = Rc5FixtureWindows.onDisplay(instrumentation.uiAutomation, Display.DEFAULT_DISPLAY)
            .filter { it.displayId == Display.DEFAULT_DISPLAY }
        if (windows.any { it.id != id && it.isFocused }) return null
        val window = windows.singleOrNull { it.id == id && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: return null
        val root = window.root ?: return null
        if (root.packageName?.toString() != config.packageName || !root.isVisibleToUser) return null
        val model = requireNotNull(state)
        val visibleHandles = allText(root).filter { it.startsWith("Handle：") }.distinct()
        if (visibleHandles.any { it != model.label }) return null
        return OwnedCastWindow(window, root, windows.filter { it.id != id && it.layer >= window.layer }, model)
    }

    private fun ownedCastButton(window: OwnedCastWindow, label: String): AccessibilityNodeInfo? {
        val targets = findTexts(window.root, label)
            .mapNotNull { clickableAncestorWithin(window.root, it) }.distinct()
        check(targets.size <= 1) { "拒绝点击：主屏原 Mod 的 $label 不唯一" }
        return targets.singleOrNull()?.takeIf(window::exposes)
    }

    private fun assertTargetReturnedAndPickerGone(instrumentation: android.app.Instrumentation, hostPackage: String) {
        assertExactTargetVisible(instrumentation)
        val pickers = matchingRoots(instrumentation, hostPackage, Display.DEFAULT_DISPLAY,
            listOf("选择主屏应用")) + matchingRoots(instrumentation, hostPackage,
            Display.DEFAULT_DISPLAY, listOf("选择要投放的主屏应用"))
        assertTrue("both Cast selector windows have exited before returning the exact target task", pickers.isEmpty())
    }

    private fun finishHostActivities(
        instrumentation: android.app.Instrumentation,
        mains: List<MainActivity>,
        settings: List<ModSettingsActivity>,
        configs: List<ModConfigActivity>,
    ) {
        (settings + configs + mains).forEach { activity ->
            runCatching {
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
        }
    }

    private fun <T : Activity> waitForActivity(
        activities: List<T>, timeoutMs: Long = 15_000,
        predicate: (T) -> Boolean,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            activities.toList().asReversed().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("超时：精确主屏 Mod Activity 未就绪")
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
            window.root?.takeIf { it.packageName?.toString() == hostPackage }
        }

    private fun fixtureRunId(): String {
        val runId = InstrumentationRegistry.getArguments().getString("fixtureRun")
            ?: error("缺少 fixtureRun instrumentation 参数")
        require(Regex("[0-9a-f]{32}").matches(runId)) { "fixtureRun 必须是 32 位小写十六进制 UUID" }
        return runId
    }

    private fun clickScopedAction(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
        identityTexts: List<String>,
        actionText: String,
        requiredFocusedActivity: Activity? = null,
        beforeClick: (() -> Unit)? = null,
    ): Boolean {
        val focusedWindow = if (requiredFocusedActivity != null) {
            checkPickerFocus(requiredFocusedActivity, displayId, actionText)
            uniqueFocusedHostWindow(instrumentation, hostPackage, displayId)
        } else null
        val roots = if (requiredFocusedActivity != null) {
            listOfNotNull(focusedWindow?.root?.takeIf { root ->
                (identityTexts + actionText).all { findTexts(root, it).isNotEmpty() }
            })
        } else matchingRoots(instrumentation, hostPackage, displayId, identityTexts + actionText)
        check(roots.size <= 1) { "拒绝点击：display=$displayId 上有多个匹配 host/Mod 窗口" }
        val root = roots.singleOrNull() ?: return false
        identityTexts.forEach { text ->
            check(findTexts(root, text).size == 1) { "拒绝点击：Mod 身份文本不唯一：$text" }
        }
        val targets = findTexts(root, actionText).mapNotNull { action ->
            clickableAncestorWithin(root, action)?.takeIf { it.isVisibleToUser }
        }.distinct()
        check(targets.size <= 1) { "拒绝点击：身份 $identityTexts 下有多个 '$actionText'" }
        val target = targets.singleOrNull() ?: return false
        if (requiredFocusedActivity != null) {
            checkPickerFocus(requiredFocusedActivity, displayId, actionText)
            val currentWindow = uniqueFocusedHostWindow(instrumentation, hostPackage, displayId)
                ?: error("拒绝点击：$actionText 执行前主屏 focused host window 已消失")
            check(currentWindow.window.id == focusedWindow?.window?.id && currentWindow.root.packageName == root.packageName) {
                "拒绝点击：$actionText 执行前 focused AccessibilityWindow 已变化"
            }
        }
        beforeClick?.invoke()
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Select only the unique clickable card containing the exact package and short activity identity. */
    private fun clickExactForegroundChoice(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        pickerActivity: ModCastForegroundTargetActivity,
        componentMarker: String,
        packageName: String,
    ): String? {
        check(!pickerActivity.isFinishing && !pickerActivity.isDestroyed && pickerActivity.hasWindowFocus() &&
            pickerActivity.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY) {
            "拒绝点击：主屏任务选择器已失去焦点或显示身份"
        }
        val focusedWindow = uniqueFocusedHostWindow(instrumentation, hostPackage, Display.DEFAULT_DISPLAY) ?: return null
        val root = focusedWindow.root
        checkPickerFocus(pickerActivity, Display.DEFAULT_DISPLAY, "选择精确主屏应用")
        check(findTexts(root, "选择主屏应用").size == 1) { "拒绝点击：主屏 focused window 不是唯一精确任务选择器" }
        val rows = foregroundChoiceRows(root, componentMarker, packageName)
        check(rows.size <= 1) { "拒绝点击：$componentMarker/$packageName 同行目标不唯一" }
        val row = rows.singleOrNull() ?: return null
        if (!row.isVisibleToUser) return null
        val label = findComponentMarkerNodes(row, componentMarker).flatMap { node ->
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        }.distinct().singleOrNull() ?: return null
        checkPickerFocus(pickerActivity, Display.DEFAULT_DISPLAY, "选择精确主屏应用")
        val currentWindow = uniqueFocusedHostWindow(instrumentation, hostPackage, Display.DEFAULT_DISPLAY)
            ?: error("拒绝点击：精确主屏任务选择器已失去焦点")
        check(currentWindow.window.id == focusedWindow.window.id && currentWindow.root.packageName == root.packageName) {
            "拒绝点击：执行前 focused AccessibilityWindow 已变化"
        }
        return label.takeIf { row.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private data class FocusedHostWindow(val window: AccessibilityWindowInfo, val root: AccessibilityNodeInfo)

    /** Return only the unique focused host window on this display; callers never click an unfocused sibling. */
    private fun uniqueFocusedHostWindow(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
    ): FocusedHostWindow? {
        val focused = Rc5FixtureWindows.onDisplay(instrumentation.uiAutomation, displayId)
            .filter { it.displayId == displayId && it.isFocused }
        check(focused.size <= 1) { "拒绝点击：display=$displayId 有多个 focused AccessibilityWindow" }
        val window = focused.singleOrNull() ?: return null
        val root = window.root ?: return null
        if (root.packageName?.toString() != hostPackage || !root.isVisibleToUser) return null
        return FocusedHostWindow(window, root)
    }

    private fun checkPickerFocus(activity: Activity, displayId: Int, actionText: String) {
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
            activity.window.decorView.display?.displayId == displayId) {
            "拒绝点击：$actionText 对应的精确 picker Activity 已失去焦点或 display 身份"
        }
    }

    /** Resolve a clickable action only through its own AccessibilityWindow root. */
    private fun clickableAncestorWithin(root: AccessibilityNodeInfo, node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            if (current == root) return null
            current = current.parent
        }
        return null
    }

    private fun foregroundChoiceRows(
        root: AccessibilityNodeInfo,
        componentMarker: String,
        packageName: String,
    ): List<AccessibilityNodeInfo> = findComponentMarkerNodes(root, componentMarker).mapNotNull { labelNode ->
        var ancestor: AccessibilityNodeInfo? = labelNode
        while (ancestor != null) {
            if (ancestor.isClickable && findTexts(ancestor, packageName).size == 1) return@mapNotNull ancestor
            ancestor = ancestor.parent
        }
        null
    }.distinct()

    private fun matchingRoots(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
        requiredTexts: List<String>,
    ): List<AccessibilityNodeInfo> = Rc5FixtureWindows.onDisplay(instrumentation.uiAutomation, displayId).mapNotNull { window ->
        if (window.displayId != displayId) return@mapNotNull null
        val root = window.root ?: return@mapNotNull null
        root.takeIf { it.packageName?.toString() == hostPackage && requiredTexts.all { text -> findTexts(root, text).isNotEmpty() } }
    }

    /** The picker may append stable row numbers, so match the component token, not the composed label. */
    private fun findComponentMarkerNodes(root: AccessibilityNodeInfo, marker: String): List<AccessibilityNodeInfo> = buildList {
        val texts = listOfNotNull(root.text?.toString(), root.contentDescription?.toString())
        if (texts.any { value -> value.split(" · ").any { it == marker } }) add(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(findComponentMarkerNodes(it, marker)) }
    }

    private fun findTexts(root: AccessibilityNodeInfo, value: String): List<AccessibilityNodeInfo> = buildList {
        if (root.text?.toString() == value || root.contentDescription?.toString() == value) add(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(findTexts(it, value)) }
    }

    private fun allText(root: AccessibilityNodeInfo): List<String> = buildList {
        root.text?.toString()?.let(::add)
        root.contentDescription?.toString()?.let(::add)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(allText(it)) }
    }

    private fun scrollExactWindow(
        instrumentation: android.app.Instrumentation,
        hostPackage: String,
        displayId: Int,
        identityTexts: List<String>,
    ): Boolean {
        val roots = matchingRoots(instrumentation, hostPackage, displayId, identityTexts)
        check(roots.size <= 1) { "拒绝滚动：display=$displayId 存在多个匹配 host/Mod 窗口" }
        val root = roots.singleOrNull() ?: return false
        val scrollables = allNodes(root).filter { it.isScrollable }
        check(scrollables.size <= 1) { "拒绝滚动：精确窗口内存在多个可滚动容器" }
        return scrollables.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(allNodes(it)) }
    }

    private fun waitFor(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        error("超时：$description")
    }

    private fun extractCastFailureToken(texts: List<String>): String? = texts.asSequence()
        .flatMap { CAST_ERROR_TOKEN.findAll(it).map { match -> match.value } }
        .firstOrNull { it in CAST_FAILURE_TOKENS }

    companion object {
        private const val TAG = "Rc5ModCastProbe"
        private const val MOD_TITLE = "RC5 外部 Mod 验收"
        private const val START_LABEL = "开始虚拟投放"
        private const val QUERY_LABEL = "查询我的投放"
        private const val CLOSE_LABEL = "关闭我的投放"
        private const val EXPECTED_CAST_VIEWPORT = "VIRTUAL / FULL / 90° / MANAGED_VIEWPORT"
        private const val TARGET_ACTIVITY_MARKER = "MainActivity"
        private const val TARGET_PAGE_MARKER = "RC5 · A PHYSICAL TEST"
        private const val TARGET_PACKAGE = "dev.remrss.sample.rc5physicala"
        private const val QUERY_RESULT_TIMEOUT_MS = 8_000L
        private const val MAX_PICKER_SCROLLS = 4
        private const val FINAL_QUERY_TIMEOUT_MS = 35_000L
        private val CAST_ERROR_TOKEN = Regex("(?<![A-Za-z0-9_])E_[A-Z0-9_]{1,64}(?![A-Za-z0-9_])")
        private val CAST_FAILURE_TOKENS = setOf(
            "E_OWNER_STALE", "E_OWNER_MISMATCH", "E_OWNER_UNVERIFIED", "E_TARGET_UNAVAILABLE", "E_TARGET_CHANGED",
            "E_BACKEND_UNAVAILABLE", "E_OCCUPANCY_UNKNOWN", "E_OCCUPANCY_CHANGED", "E_DISPLAY_BUSY",
            "E_TICKET_STALE", "E_ACTIVATION_INVALID", "E_BACKEND_BUSY", "E_BACKEND_FAILED", "E_BACKEND_NOT_READY"
        )
    }
}
