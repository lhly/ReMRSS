package top.zainzh.rearscreen

import android.app.LocaleManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import top.zainzh.rearscreen.platform.actions.RegisteredActionsActivity
import top.zainzh.rearscreen.platform.actions.RegisteredActionInvocationContent
import top.zainzh.rearscreen.platform.actions.PlatformRegisteredActionRegistry
import top.zainzh.rearscreen.platform.actions.queryShortcutProviders
import top.zainzh.rearscreen.platform.mods.ModConfigActivity
import top.zainzh.rearscreen.platform.mods.ModConfigCastState
import top.zainzh.rearscreen.platform.mods.ModCastActivationSource
import top.zainzh.rearscreen.platform.mods.ModCastActivationSources
import top.zainzh.rearscreen.platform.mods.ModInstallDecision
import top.zainzh.rearscreen.platform.mods.ModPackageReader
import top.zainzh.rearscreen.platform.mods.ModPackageStore
import top.zainzh.rearscreen.platform.mods.ModPreviewActivity
import top.zainzh.rearscreen.platform.mods.ModRearDecisionDialog
import top.zainzh.rearscreen.platform.mods.ModRearCastRequestContent
import top.zainzh.rearscreen.platform.mods.ModRearSceneRequestContent
import top.zainzh.rearscreen.platform.mods.ModSettingsActivity
import top.zainzh.rearscreen.platform.mods.configDecisionTestArchive
import top.zainzh.rearscreen.platform.mods.optionalRearSceneTestArchive
import top.zainzh.rearscreen.platform.mods.wallpaperNavigationTestArchive

/** Captures settled production surfaces on the disposable Android device; no network or OEM writes. */
class VisualCoverageComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrument.targetContext
    private lateinit var folder: String
    private val misses = mutableListOf<String>()
    private lateinit var host: ComponentActivity
    private var landscape = false
    private fun text(key: String) = NativeStrings.get(host, key)
    private fun res(key: Int) = host.getString(key)
    private fun tag(name: String) = compose.onNodeWithTag(name)
    private fun label(name: String) = compose.onNodeWithText(name)
    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString().trim() }

    @Test fun mainLight() = configuration("audit-light", false) { mainPages() }
    @Test fun mainDarkLargePortrait() = configuration("audit-large", true) { mainPages() }
    @Test fun toolsLight() = configuration("audit-tools", false) { toolPages() }
    @Test fun toolsDarkLargePortrait() = configuration("audit-stress-tools", true) { toolPages() }

    @Test fun mainLandscape() = configuration("audit-landscape", true, rotate = true) { mainPages() }
    @Test fun toolsLandscape() = configuration("audit-landscape-tools", true, rotate = true) { toolPages() }
    @Test fun modalLandscape() = configuration("audit-landscape-states", true, rotate = true) { modalPages() }
    @Test fun modalStatesAndRearControls() = configuration("audit-states", true) { modalPages() }

    @Test fun mainTaskRecentsVisibilityTogglesInSystemOverview() {
        val prefix = "remrss-validation-20261122-hide-in-recents-r01"
        configuration(prefix, stress = false) {
            val phases = listOf("default-hidden" to false, "hide-off" to true, "hide-on-again" to false)
            for ((index, phase) in phases.withIndex()) {
                val (name, shown) = phase
                val application = android.content.ComponentName(target, MainActivity::class.java)
                openIntent<MainActivity>(Intent.makeMainActivity(application)) {
                    val manager = target.getSystemService(android.app.ActivityManager::class.java)
                    val appLabel = target.applicationInfo.loadLabel(target.packageManager).toString()
                    val id = host.taskId
                    tab("nav_settings")
                    toTag("settings_list", "hide_in_recents")
                    val toggle = tag("hide_in_recents")
                    label("在最近任务中隐藏").assertIsDisplayed()
                    if (index < 2) toggle.assertIsOn() else toggle.assertIsOff()
                    if (index > 0) toggle.performTouchInput { click() }
                    if (shown) toggle.assertIsOff() else toggle.assertIsOn()
                    val task = manager.appTasks.mapNotNull { it.taskInfo }.single {
                        it.taskId == id && it.baseIntent.component == application
                    }
                    check((task.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0) == shown)
                    check(NativePreferences(target).showInRecents() == shown)
                    capture("$prefix-settings-$name")

                    fun overviewText(): List<String> {
                        val root = instrument.uiAutomation.rootInActiveWindow ?: return emptyList()
                        fun visit(node: android.view.accessibility.AccessibilityNodeInfo): List<String> =
                            listOfNotNull(node.viewIdResourceName, node.text?.toString(), node.contentDescription?.toString()) +
                                (0 until node.childCount).flatMap { i -> node.getChild(i)?.let(::visit).orEmpty() }
                        return visit(root)
                    }
                    // The system may surface the most recent excluded task. Switch to another
                    // app, then open Overview from Home after the launcher has settled.
                    shell("am start -W -a android.settings.SETTINGS")
                    compose.waitUntil(6_000) {
                        instrument.uiAutomation.rootInActiveWindow?.packageName == "com.android.settings"
                    }
                    val launcher = requireNotNull(target.packageManager.resolveActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)).activityInfo.packageName
                    shell("input keyevent KEYCODE_HOME")
                    compose.waitUntil(6_000) {
                        instrument.uiAutomation.rootInActiveWindow?.packageName == launcher
                    }
                    instrument.uiAutomation.waitForIdle(250, 5_000)
                    shell("input keyevent KEYCODE_APP_SWITCH")
                    try {
                        compose.waitUntil(6_000) {
                            overviewText().any { it.contains(":id/overview") || it.contains(":id/task_icon") }
                        }
                    } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                        capture("$prefix-overview-$name-timeout")
                        throw AssertionError("Overview not ready for $name: ${overviewText()}", failure)
                    }
                    val overview = overviewText()
                    check(overview.any { it == appLabel || it.startsWith("$appLabel,") } == shown) {
                        "Expected Recents card $appLabel: $shown; visible nodes: $overview"
                    }
                    capture("$prefix-overview-$name")
                    shell("input keyevent KEYCODE_HOME")
                }
            }
        }
    }

    @Test fun recentsSwitchSurvivesRecreationAndFitsDarkLargeLandscape() {
        val prefix = "remrss-validation-20261122-hide-in-recents-r01-large"
        configuration(prefix, stress = true, rotate = true) {
            open(MainActivity::class.java, {
                tab("nav_settings")
                toTag("settings_list", "hide_in_recents")
                val toggle = tag("hide_in_recents").assertIsOn()
                toggle.performTouchInput { click() }
                toggle.assertIsOff()
                check(NativePreferences(target).showInRecents())
                check(toggle.fetchSemanticsNode().let { it.boundsInRoot.height + 1f >= it.size.height })
                capture("$prefix-settings")
            })
            open(MainActivity::class.java, {
                tab("nav_settings")
                toTag("settings_list", "hide_in_recents")
                val toggle = tag("hide_in_recents").assertIsOff()
                val task = target.getSystemService(android.app.ActivityManager::class.java).appTasks
                    .mapNotNull { it.taskInfo }.single { it.taskId == host.taskId }
                check(task.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0)
                toggle.performTouchInput { click() }
                toggle.assertIsOn()
                check(!NativePreferences(target).showInRecents())
            })
        }
    }

    @Test fun mainTabEndRowsStayClearOfFloatingNavigation() {
        val prefix = "remrss-validation-20261122-floating-nav-r01"
        val archives = listOf("config-derived-counter.zip", "generic-counter.zip", "preview-lifecycle.zip", "scene-info.zip")
            .map { name ->
                val bytes = instrument.context.assets.open("platform/mods/$name").use { it.readBytes() }
                bytes to ModPackageReader.read(bytes.inputStream())
            }
        try {
            for ((bytes, mod) in archives) {
                check(ModPackageStore.installed(target, mod.id) == null)
                ModPackageStore.install(target, bytes,
                    ModInstallDecision(mod.authorFingerprint, mod.requiredCapabilities.map { it.name }.toSet()))
            }
            val lastMod = ModPackageStore.list(target).last()
            val tabs = listOf(
                Triple("screen_title", "home_list", "screenshot_settings_entry"),
                Triple("notifications_title", "notifications_list", "notification_rules_entry"),
                Triple("nav_modules", "modules_tab", "mod_row_${lastMod.id}"),
                Triple("nav_settings", "settings_list", "about_entry"),
            )
            for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
                configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    open(FixtureWallpaperSettingsActivity::class.java, {
                        for ((tabKey, listKey, last) in tabs) {
                            tab(tabKey)
                            val rail = tag("main_navigation").assertIsDisplayed()
                            val list = tag(listKey).assertIsDisplayed()
                            val railBounds = rail.fetchSemanticsNode().boundsInRoot
                            val viewport = list.fetchSemanticsNode().boundsInRoot
                            check(viewport.bottom > railBounds.bottom && viewport.left < railBounds.left &&
                                viewport.right > railBounds.right) {
                                "$listKey must draw under and around the navigation capsule in $mode: $viewport, $railBounds"
                            }
                            val minGap = 8f * host.resources.displayMetrics.density - 2f
                            if (tabKey == "nav_settings") {
                                capture("$prefix-$mode-more")
                                list.performTouchInput {
                                    swipe(Offset(centerX, height * .63f), Offset(centerX, height * .48f), 380)
                                }
                                capture("$prefix-$mode-more-scrolling")
                            }
                            toTag(listKey, last)
                            // Finish with a physical swipe instead of using only the scroll semantics.
                            list.performTouchInput {
                                swipe(Offset(centerX, height * .65f), Offset(centerX, height * .18f), 250)
                            }
                            val item = tag(last).assertIsDisplayed().fetchSemanticsNode()
                            check(item.boundsInRoot.height + 1f >= item.size.height &&
                                rail.fetchSemanticsNode().boundsInRoot.top - item.boundsInRoot.bottom >= minGap) {
                                "$last cannot fully clear the rail in $mode"
                            }
                            capture("$prefix-$mode-$listKey-end".replace('_', '-'))
                            if (tabKey == "nav_settings") {
                                tag(last).performTouchInput { click() }
                                tag("about_details_back").assertIsDisplayed().performTouchInput { click() }
                                rail.assertIsDisplayed()
                                tag(last).assertIsDisplayed()
                            }
                        }
                    })
                }
            }
        } finally {
            for ((_, mod) in archives) {
                ModPackageStore.uninstall(target, mod.id)
                ModPackageStore.revokeAuthorTrust(target, mod.authorFingerprint)
            }
        }
    }

    @Test fun moreTabShowsPreferencesFirstAndPreservesNestedReturn() {
        val prefix = "remrss-validation-20261122-more-tab-r01"
        for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
            configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                open(MainActivity::class.java, {
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    tab("nav_settings")
                    val tab = compose.onNodeWithContentDescription("更多").assertIsSelected()
                    val list = tag("settings_list").assertIsDisplayed()
                    val pageTitle = compose.onNode(hasText("更多") and hasAnyAncestor(hasTestTag("settings_list")))
                    pageTitle.assertIsDisplayed()
                    val toggle = tag("hide_in_recents").assertIsDisplayed().fetchSemanticsNode()
                    val section = label("应用偏好").assertIsDisplayed().fetchSemanticsNode()
                    val title = pageTitle.fetchSemanticsNode().boundsInRoot
                    val rail = tag("main_navigation").fetchSemanticsNode().boundsInRoot
                    check(title.bottom <= section.boundsInRoot.top && section.boundsInRoot.bottom <= toggle.boundsInRoot.top)
                    check(toggle.boundsInRoot.height + 1f >= toggle.size.height && toggle.boundsInRoot.bottom <= rail.top) {
                        "App preferences must fit below the title and above the rail in $mode"
                    }
                    check(list.scrollPosition() == 0f) { "App preferences should not require scrolling" }
                    capture("$prefix-$mode-more")
                    if (!rotate && !dark) {
                        inList("settings_list", text("tutorial"))
                        val backToMore = tag("tutorial_back").assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                        toTag("tutorial_scroll", "tutorial_header_permissions")
                        tag("tutorial_header_permissions").performTouchInput { click() }
                        toTag("tutorial_scroll", "tutorial_permissions")
                        tag("tutorial_permissions").performTouchInput { click() }
                        tag("permissions_back").assertIsDisplayed().performTouchInput { click() }
                        backToMore.assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                        backToMore.performTouchInput { click() }
                        list.assertIsDisplayed()
                        tab.assertIsSelected()
                    }
                })
            }
        }
    }

    @Test fun captureUnifiedPageShells() {
        for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
            configuration("audit-unified-pages", stress = rotate, rotate = rotate, dark = dark) {
                open(MainActivity::class.java, {
                    val name = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    val config = host.resources.configuration
                    check(config.orientation == if (rotate) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    check(config.fontScale == if (rotate) 1.6f else 1f)
                    check((config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) == dark)
                    inList("home_list", text("nav_wake"))
                    val wakeBack = tag("wake_settings_back").assertIsDisplayed()
                    val backBounds = wakeBack.fetchSemanticsNode().boundsInRoot
                    val backTop = backBounds.top
                    capture("$name-wake")
                    if (!rotate && !dark) {
                        wakeBack.performTouchInput { down(center) }
                        try {
                            org.junit.Assert.assertEquals(backBounds, wakeBack.fetchSemanticsNode().boundsInRoot)
                        } finally {
                            wakeBack.performTouchInput { cancel() }
                        }
                    }
                    wakeBack.performTouchInput { click() }
                    tab("nav_settings")
                    inList("settings_list", text("tutorial"))
                    tag("tutorial_back").assertIsDisplayed()
                    val guide = tag("tutorial_scroll").assertIsDisplayed()
                    org.junit.Assert.assertEquals(backTop, tag("tutorial_back").fetchSemanticsNode().boundsInRoot.top, 1f)
                    capture("$name-tutorial")
                    guide.performScrollToIndex(1)
                    tag("tutorial_header_setup").performTouchInput { click() }
                    guide.performTouchInput {
                        swipe(Offset(centerX, height * .75f), Offset(centerX, height * .35f), durationMillis = 400)
                    }
                    org.junit.Assert.assertEquals(backTop, tag("tutorial_back").fetchSemanticsNode().boundsInRoot.top, 1f)
                    capture("$name-tutorial-scrolled")
                    tag("tutorial_back").performTouchInput { click() }
                    tag("settings_list").assertIsDisplayed()
                    compose.onNodeWithContentDescription(text("nav_settings")).assertIsSelected()
                })
            }
        }
    }

    @Test fun captureUnifiedSettingsRoutes() {
        for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
            configuration("audit-unified-routes", stress = rotate, rotate = rotate, dark = dark) {
                open(MainActivity::class.java, {
                    val name = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    val config = host.resources.configuration
                    check(config.orientation == if (rotate) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    check(config.fontScale == if (rotate) 1.6f else 1f)
                    check((config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) == dark)
                    val prefs = RearCastMode.preferences(host)
                    val original = prefs.all[RearCastMode.VIRTUAL] as? Boolean
                    try {
                        prefs.edit().putBoolean(RearCastMode.VIRTUAL, true).commit()
                        inList("home_list", text("nav_screenshots"))
                        tag("screenshot_back").assertIsDisplayed()
                        capture("$name-screenshot")
                        tag("screenshot_back").performTouchInput { click() }
                        inList("home_list", text("nav_cast"))
                        toTag("rear_settings_10_list", "density_compatibility_entry")
                        tag("density_compatibility_entry").performTouchInput { click() }
                        tag("density_app_search").assertIsDisplayed()
                        compose.waitUntil(10_000) { has(hasTestTag("density_enabled_group")) || has(hasTestTag("density_disabled_group")) }
                        capture("$name-density")
                        tag("density_back").performTouchInput { click() }
                        tag("cast_settings_back").assertIsDisplayed()
                        tag("density_compatibility_entry").assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                        tag("cast_settings_back").performTouchInput { click() }
                        tab("nav_settings")
                        inList("settings_list", res(R.string.ui_permissions_title))
                        tag("permissions_back").assertIsDisplayed()
                        capture("$name-permissions")
                        val permissionGuide = tag("permissions_scroll")
                        permissionGuide.performTouchInput {
                            swipe(Offset(centerX, height * .80f), Offset(centerX, height * .29f), durationMillis = 410)
                        }
                        val scrollAxis = permissionGuide.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                        check(scrollAxis.value() > 0f) { "Permissions list must scroll before its edge is captured" }
                        capture("$name-permissions-scrolled")
                        tag("permissions_back").performTouchInput { click() }
                        inList("settings_list", text("tutorial"))
                        toTag("tutorial_scroll", "tutorial_header_permissions")
                        tag("tutorial_header_permissions").performTouchInput { click() }
                        toTag("tutorial_scroll", "tutorial_permissions")
                        tag("tutorial_permissions").performTouchInput { click() }
                        tag("permissions_back").assertIsDisplayed()
                        back()
                        tag("tutorial_back").assertIsDisplayed()
                        tag("tutorial_permissions").assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                        tag("tutorial_back").performTouchInput { click() }
                        tag("settings_list").assertIsDisplayed()
                        compose.onNodeWithContentDescription(text("nav_settings")).assertIsSelected()
                    } finally {
                        val restore = prefs.edit()
                        if (original == null) restore.remove(RearCastMode.VIRTUAL) else restore.putBoolean(RearCastMode.VIRTUAL, original)
                        restore.commit()
                    }
                })
            }
        }
    }

    @Test fun captureTutorialScrollFade() {
        for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
            configuration("audit-tutorial-fade", stress = rotate, rotate = rotate, dark = dark) {
                open(TutorialActivity::class.java, {
                    val name = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    val config = host.resources.configuration
                    val expectedOrientation = if (rotate) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT
                    check(config.orientation == expectedOrientation && config.fontScale == if (rotate) 1.6f else 1f)
                    check((config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) == dark)
                    val guide = tag("tutorial_scroll").assertIsDisplayed()
                    val heading = label(text("tutorial")).assertIsDisplayed()
                    val titleBounds = heading.fetchSemanticsNode().boundsInRoot
                    val guideBounds = guide.fetchSemanticsNode().boundsInRoot
                    check(guideBounds.top >= titleBounds.bottom) { "Body crosses the pinned heading" }
                    capture("$name-top")

                    guide.performScrollToIndex(1)
                    tag("tutorial_header_setup").performTouchInput { click() }
                    val bodyTop = tag("tutorial_body_setup").assertExists().fetchSemanticsNode().boundsInRoot.top
                    val list = (host as TutorialActivity).guide.list
                    val cut = (bodyTop - guideBounds.top + 24 * host.resources.displayMetrics.density).roundToInt()
                    compose.runOnIdle { list.requestScrollToItem(1, cut) }
                    capture("$name-scrolled")
                    org.junit.Assert.assertEquals(titleBounds, heading.fetchSemanticsNode().boundsInRoot)
                    val index = compose.runOnIdle { list.firstVisibleItemIndex }
                    val offset = compose.runOnIdle { list.firstVisibleItemScrollOffset }
                    guide.performTouchInput {
                        swipe(Offset(centerX, height * .82f), Offset(centerX, height * .34f), durationMillis = 380)
                    }
                    compose.runOnIdle {
                        check(list.firstVisibleItemIndex > index ||
                            list.firstVisibleItemIndex == index && list.firstVisibleItemScrollOffset > offset)
                    }
                    guide.performScrollToIndex(0)
                    label(res(R.string.tutorial_intro)).assertIsDisplayed()
                    tag("tutorial_back").performTouchInput { click() }
                    check(host.isFinishing)
                })
            }
        }
    }

    @Test fun standaloneTutorialKeepsPermissionChildAndReturnsWithNativeGesture() {
        val prefix = "remrss-validation-20261122-standalone-navigation-r01-tutorial"
        configuration(prefix, stress = true, dark = true) {
            ActivityScenario.launch<TutorialActivity>(Intent(target, TutorialActivity::class.java)).use { scenario ->
                scenario.onActivity { host = it }
                toTag("tutorial_scroll", "tutorial_header_permissions")
                tag("tutorial_header_permissions").performTouchInput { click() }
                toTag("tutorial_scroll", "tutorial_permissions")
                val list = tag("tutorial_scroll")
                val readingPosition = list.scrollPosition()
                check(readingPosition > 0f)
                tag("tutorial_permissions").performTouchInput { click() }
                tag("permissions_back").assertIsDisplayed()
                tag("main_navigation").assertDoesNotExist()
                scenario.recreate()
                scenario.onActivity { host = it }
                tag("permissions_back").assertIsDisplayed()
                checkWallpaperEdgeGesture(
                    compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true),
                    "$prefix-permissions-return",
                    onCommit = {
                        tag("tutorial_back").assertIsDisplayed()
                        check(list.scrollPosition() == readingPosition)
                        tag("tutorial_permissions").assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                        check(!host.isFinishing)
                    },
                )
                tag("tutorial_back").performTouchInput { click() }
                compose.waitUntil(5_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
        }
    }

    @Test fun captureWallpaperNavigation() {
        for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
            val prefix = "remrss-validation-20261122-wallpaper-route-followup-r01"
            configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                open(FixtureWallpaperSettingsActivity::class.java, {
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    val config = host.resources.configuration
                    check(config.fontScale == if (rotate) 1.6f else 1f)
                    check((config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) == dark)
                    val rail = tag("main_navigation").assertIsDisplayed()
                    toTag("home_list", "ai_rear_wallpaper_entry")
                    capture("$prefix-$mode-home")
                    tag("ai_rear_wallpaper_entry").performTouchInput { click() }
                    val studio = tag("ai_rear_editor").assertIsDisplayed()
                    val titleBack = tag("ai_rear_back").assertIsDisplayed()
                    val titleBounds = titleBack.fetchSemanticsNode().boundsInRoot
                    val positionAtTop = studio.scrollPosition()
                    check(studio.fetchSemanticsNode().boundsInRoot.top > titleBounds.bottom)
                    rail.assertDoesNotExist()
                    capture("$prefix-$mode-studio")

                    toTag("ai_rear_editor", "ai_rear_edit_shortcuts")
                    val positionBeforePush = studio.scrollPosition()
                    tag("ai_rear_edit_shortcuts").performTouchInput { click() }
                    val child = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
                    val shortcutBack = tag("ai_rear_shortcuts_back").assertIsDisplayed()
                    rail.assertDoesNotExist()
                    check(shortcutBack.fetchSemanticsNode().boundsInRoot.top == titleBounds.top)
                    capture("$prefix-$mode-shortcuts")
                    if (!rotate && !dark) {
                        checkWallpaperEdgeGesture(child, "$prefix-$mode-child", onCommit = {
                            studio.assertIsDisplayed()
                            rail.assertDoesNotExist()
                        })
                    } else shortcutBack.performTouchInput { click() }

                    studio.assertIsDisplayed()
                    check(studio.scrollPosition() == positionBeforePush) { "Returning changed the studio scroll position" }
                    toTag("ai_rear_editor", "ai_rear_theme_selector")
                    tag("ai_rear_theme_selector").performTouchInput { click() }
                    tag("ai_rear_theme_back").assertIsDisplayed()
                    rail.assertDoesNotExist()
                    capture("$prefix-$mode-themes")
                    if (!rotate && !dark) {
                        checkWallpaperEdgeGesture(child, "$prefix-$mode-themes", onCommit = {
                            studio.assertIsDisplayed()
                            rail.assertDoesNotExist()
                        })
                        toTag("ai_rear_editor", "ai_rear_theme_selector")
                        tag("ai_rear_theme_selector").performTouchInput { click() }
                    }
                    tag("ai_rear_theme_builtin").performTouchInput { click() }
                    studio.assertIsDisplayed()
                    toTag("ai_rear_editor", "ai_rear_allow_unlocked")
                    check(studio.scrollPosition() > positionAtTop)
                    check(titleBack.fetchSemanticsNode().boundsInRoot == titleBounds) { "Scrolling moved the heading" }
                    capture("$prefix-$mode-studio-scrolled")

                    if (!rotate && !dark) {
                        checkWallpaperEdgeGesture(child, "$prefix-$mode-root", onCommit = {
                            rail.assertIsDisplayed()
                            tag("home_list").assertIsDisplayed()
                        })
                    } else titleBack.performTouchInput { click() }
                    rail.assertIsDisplayed()
                    tag("home_list").assertIsDisplayed()
                })
            }
        }
    }

    @Test fun wallpaperWorksRestoredInSettingsReturnsThroughStudio() {
        val prefix = "remrss-validation-20261122-wallpaper-route-followup-r01"
        configuration(prefix, stress = true, dark = true) {
            open(FixtureWallpaperSettingsActivity::class.java, {
                val rail = tag("main_navigation")
                tag("ai_rear_works").assertIsDisplayed()
                label(res(R.string.ai_rear_connect)).assertIsDisplayed()
                rail.assertDoesNotExist()
                val child = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
                checkWallpaperEdgeGesture(child, "$prefix-dark-large-works", onCommit = {
                    tag("ai_rear_editor").assertIsDisplayed()
                    rail.assertDoesNotExist()
                })
                tag("ai_rear_back").performTouchInput { click() }
                rail.assertIsDisplayed()
            }) { putExtra("restore_works_route", true) }
        }
    }

    @Test fun wallpaperRecreationKeepsChildAndStudioReadingPosition() {
        configuration("remrss-validation-20261122-wallpaper-ui-unification-r04", stress = true, dark = true) {
            val intent = Intent(target, FixtureWallpaperSettingsActivity::class.java)
            ActivityScenario.launch<FixtureWallpaperSettingsActivity>(intent).use { scenario ->
                toTag("home_list", "ai_rear_wallpaper_entry")
                tag("ai_rear_wallpaper_entry").performTouchInput { click() }
                val studio = tag("ai_rear_editor").assertIsDisplayed()
                studio.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                    it(0f, 96f)
                }
                val before = studio.scrollPosition()
                check(before > 0f) { "Set a nonzero reading position before testing restoration" }
                tag("ai_rear_edit_shortcuts").performTouchInput { click() }
                tag("ai_rear_shortcuts_back").assertIsDisplayed()
                scenario.recreate()
                tag("ai_rear_shortcuts_back").assertIsDisplayed().performTouchInput { click() }
                studio.assertIsDisplayed()
                check(studio.scrollPosition() == before) { "Recreation lost the position of the actual parent" }
                tag("main_navigation").assertDoesNotExist()
                tag("ai_rear_back").performTouchInput { click() }
                tag("main_navigation").assertIsDisplayed()
            }
        }
    }

    private fun SemanticsNodeInteraction.scrollPosition(): Float =
        fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun checkWallpaperEdgeGesture(
        child: SemanticsNodeInteraction,
        name: String,
        onCommit: () -> Unit,
    ) {
        val atRest = child.fetchSemanticsNode().boundsInRoot.left
        val decor = host.window.decorView
        val y = decor.height * .48f
        val reach = decor.width * .38f
        val cancel = SystemClock.uptimeMillis()
        try {
            startEdgeDrag(cancel, y, reach)
            compose.waitUntil(5_000) { child.fetchSemanticsNode().boundsInRoot.left > atRest + 12f }
            capture("$name-edge-preview")
        } finally {
            sendEdgeTouch(MotionEvent.ACTION_CANCEL, cancel, reach, y)
        }
        compose.waitUntil(5_000) {
            kotlin.math.abs(child.fetchSemanticsNode().boundsInRoot.left - atRest) < 1f
        }
        val commit = SystemClock.uptimeMillis()
        startEdgeDrag(commit, y, decor.width * .72f)
        sendEdgeTouch(MotionEvent.ACTION_UP, commit, decor.width * .72f, y)
        compose.waitForIdle()
        onCommit()
    }

    private fun startEdgeDrag(downTime: Long, y: Float, reach: Float) {
        sendEdgeTouch(MotionEvent.ACTION_DOWN, downTime, 1f, y)
        (1..10).forEach { step ->
            SystemClock.sleep(26)
            sendEdgeTouch(MotionEvent.ACTION_MOVE, downTime, reach * step / 10f, y)
        }
    }

    private fun sendEdgeTouch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(instrument.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    @Test fun modulesTabOpensSystemZipPickerAndReturnsOnCancel() {
        val prefix = "remrss-validation-20261122-remove-remodule-r02-picker"
        configuration(prefix, stress = false) {
            open(MainActivity::class.java, {
                tab("nav_modules")
                val tabLabel = text("nav_modules")
                compose.onNodeWithContentDescription(tabLabel).assertIsSelected()
                val opener = tag("mod_import_zip").assertIsDisplayed()
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                val pickerPackage = requireNotNull(target.packageManager.resolveActivity(
                    picker, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)).activityInfo.packageName
                opener.performTouchInput { click() }
                compose.waitUntil(8_000) {
                    instrument.uiAutomation.rootInActiveWindow?.packageName == pickerPackage
                }
                back()
                opener.assertIsDisplayed()
                compose.onNodeWithContentDescription(tabLabel).assertIsSelected()
                label("审核 Mod 作者与能力").assertDoesNotExist()
                label("尚未安装 Mod").assertIsDisplayed()
            })
        }
    }

    @Test fun captureModRearAvailabilityInDetails() {
        val prefix = "remrss-validation-20261122-mod-rear-availability-r02"
        fun asset(name: String) = instrument.context.assets.open("platform/mods/$name").use { it.readBytes() }
        val unsupportedZip = asset("generic-counter.zip")
        val supportedZip = optionalRearSceneTestArchive(asset("config-derived-counter.zip"))
        val unsupported = ModPackageReader.read(unsupportedZip.inputStream())
        val supported = ModPackageReader.read(supportedZip.inputStream())
        val capability = "rear.scene"
        val title = "显示到背屏"
        val grantTitle = "背屏显示"
        val detailTag = "mod_detail_scroll"
        check(unsupported.rearScene != null && (unsupported.requiredCapabilities + unsupported.optionalCapabilities)
            .none { it.name == capability })
        val grant = supported.optionalCapabilities.single { it.name == capability }

        try {
            for ((zip, mod) in listOf(unsupportedZip to unsupported, supportedZip to supported)) {
                check(ModPackageStore.installed(target, mod.id) == null)
                ModPackageStore.install(target, zip, ModInstallDecision(mod.authorFingerprint, emptySet()))
            }
            for (rotate in listOf(false, true)) {
                configuration(prefix, stress = rotate, rotate = rotate) {
                    val mode = if (rotate) "dark-landscape-large" else "light-portrait"
                    open(FixtureWallpaperSettingsActivity::class.java, {
                        tab("nav_modules")
                        openModDetail("modules_tab", unsupported.id)
                        toTag(detailTag, "mod_config_${unsupported.id}")
                        label(title).assertDoesNotExist()
                        capture("$prefix-$mode-unsupported")
                        tag("mod_detail_back").performTouchInput { click() }

                        openModDetail("modules_tab", supported.id)
                        val details = tag(detailTag)
                        val rearTag = "mod_show_rear_${supported.id}"
                        val rear = tag(rearTag)
                        fun showRear() = toTag(detailTag, rearTag)
                        fun scrollToTitle(text: String) = toLabelIn(details, text)
                        fun waitForGrant(granted: Boolean) {
                            compose.waitUntil(8_000) {
                                val installed = ModPackageStore.installed(target, supported.id)
                                installed != null && (capability in installed.grantedCapabilities) == granted
                            }
                        }

                        showRear()
                        rear.assertIsDisplayed().assertHasNoClickAction()
                        label("在「能力与权限」开启「背屏显示」，再启用 Mod。").assertIsDisplayed()
                        capture("$prefix-$mode-needs-both")
                        scrollToTitle(grantTitle)
                        label("$grantTitle v1").assertDoesNotExist()
                        label(grantTitle).assertIsOff().performTouchInput { click() }
                        waitForGrant(true)
                        label(grantTitle).assertIsOn()

                        showRear()
                        rear.assertHasNoClickAction()
                        label("打开上方「启用」开关。").assertIsDisplayed()
                        scrollToTitle("启用")
                        label("启用").performTouchInput { click() }
                        compose.waitUntil(8_000) { ModPackageStore.installed(target, supported.id)?.enabled == true }
                        showRear()
                        rear.assertIsDisplayed().assertHasClickAction().assertIsEnabled()
                        capture("$prefix-$mode-ready")

                        scrollToTitle(grantTitle)
                        label("保持显示").assertExists()
                        label("保持显示 v1").assertDoesNotExist()
                        label(grantTitle).performTouchInput { click() }
                        waitForGrant(false)
                        showRear()
                        rear.assertHasNoClickAction()
                        label("在「能力与权限」开启「背屏显示」。").assertIsDisplayed()
                        capture("$prefix-$mode-needs-grant")
                        tag("mod_detail_back").performTouchInput { click() }
                        tag("main_navigation").assertIsDisplayed()
                    })
                }
                ModPackageStore.setEnabled(target, supported.id, false)
                check(grant.minimumVersion == 1)
            }
        } finally {
            for (mod in listOf(unsupported, supported)) {
                ModPackageStore.uninstall(target, mod.id)
                ModPackageStore.revokeAuthorTrust(target, mod.authorFingerprint)
            }
        }
    }

    @Test fun captureCompactModListAndDetail() {
        val prefix = "remrss-validation-20261122-remove-remodule-r02-compact"
        val fixtures = listOf("scene-info.zip", "config-derived-counter.zip", "preview-lifecycle.zip").map { name ->
            val bytes = instrument.context.assets.open("platform/mods/$name").use { it.readBytes() }
            bytes to ModPackageReader.read(bytes.inputStream())
        }
        check(fixtures.map { it.second.id }.distinct().size == fixtures.size)
        for ((bytes, mod) in fixtures) {
            check(ModPackageStore.installed(target, mod.id) == null)
            ModPackageStore.install(target, bytes,
                ModInstallDecision(mod.authorFingerprint, mod.requiredCapabilities.map { it.name }.toSet()))
        }
        val counter = fixtures[1].second
        try {
            for (rotate in listOf(false, true)) {
                configuration(prefix, stress = rotate, rotate = rotate) {
                    val mode = if (rotate) "dark-landscape-large" else "light-portrait"
                    open(FixtureWallpaperSettingsActivity::class.java, {
                        tab("nav_modules")
                        val list = tag("modules_tab").assertIsDisplayed()
                        val rail = tag("main_navigation").assertIsDisplayed()
                        if (!rotate) {
                            await(hasTestTag("mod_row_${fixtures.first().second.id}"))
                            for ((_, mod) in fixtures) {
                                val row = tag("mod_row_${mod.id}").assertIsDisplayed().fetchSemanticsNode()
                                check(row.boundsInRoot.height + 1f >= row.size.height &&
                                    row.boundsInRoot.bottom <= rail.fetchSemanticsNode().boundsInRoot.top) {
                                    "Three installed modules should fit on the portrait Modules tab"
                                }
                            }
                        }
                        toTag("modules_tab", "mod_detail_entry_${counter.id}")
                        capture("$prefix-$mode-list")
                        if (!rotate) {
                            val toggle = tag("mod_enabled_${counter.id}").assertIsOff()
                            val row = toggle.fetchSemanticsNode()
                            check(row.size.height >= (48 * host.resources.displayMetrics.density).roundToInt())
                            toggle.performTouchInput { click() }
                            compose.waitUntil(8_000) { ModPackageStore.installed(target, counter.id)?.enabled == true }
                            compose.waitUntil(8_000) {
                                has(hasTestTag("mod_enabled_${counter.id}") and isOn())
                            }
                            rail.assertIsDisplayed()
                            tag("mod_detail_back").assertDoesNotExist()
                            tag("mod_config_${counter.id}").assertDoesNotExist()
                        }
                        toTag("modules_tab", "mod_detail_entry_${counter.id}")
                        val position = list.scrollPosition()
                        if (rotate) check(position > 0)
                        tag("mod_detail_entry_${counter.id}").performTouchInput { click() }
                        val details = tag("mod_detail_scroll").assertIsDisplayed()
                        label(counter.name).assertIsDisplayed()
                        label("Mod 已启用").assertDoesNotExist()
                        rail.assertDoesNotExist()
                        capture("$prefix-$mode-detail")
                        toLabelIn(details, "卸载…")
                        if (rotate) {
                            val child = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
                            checkWallpaperEdgeGesture(child, "$prefix-$mode-list", onCommit = {
                                list.assertIsDisplayed()
                                rail.assertIsDisplayed()
                            })
                        } else {
                            tag("mod_detail_back").performTouchInput { click() }
                            rail.assertIsDisplayed()
                        }
                        check(list.scrollPosition() == position) { "Returning from details must preserve list position" }
                    })
                }
            }
        } finally {
            for ((_, mod) in fixtures) {
                ModPackageStore.uninstall(target, mod.id)
                ModPackageStore.revokeAuthorTrust(target, mod.authorFingerprint)
            }
        }
    }

    @Test fun captureModManagementNavigationAndConsent() {
        val prefix = "remrss-validation-20261122-remove-remodule-r02-import"
        val archive = instrument.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val fixture = java.io.File(target.cacheDir, "updates/$prefix-fixture.zip")
        check(fixture.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        fixture.writeBytes(archive)
        val verified = ModPackageReader.read(archive.inputStream())
        val uri = androidx.core.content.FileProvider.getUriForFile(target, target.packageName + ".updates", fixture)
        val browseRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                check(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.contains("application/zip") == true)
                browseRequested.set(true)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrument.addMonitor(monitor)
        try {
            for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
                configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    open(FixtureWallpaperSettingsActivity::class.java, {
                        val first = !rotate && !dark
                        val rail = tag("main_navigation").assertIsDisplayed()
                        tab("nav_modules")
                        val list = tag("modules_tab").assertIsDisplayed()
                        tag("mod_settings_back").assertDoesNotExist()
                        tag("zip_mods_entry").assertDoesNotExist()
                        rail.assertIsDisplayed()
                        val import = tag("mod_import_zip").assertIsDisplayed()
                        for (node in listOf(import, tag("mod_import_card")).map { it.fetchSemanticsNode() }) {
                            check(node.boundsInRoot.height + 1f >= node.size.height &&
                                node.boundsInRoot.bottom <= rail.fetchSemanticsNode().boundsInRoot.top) {
                                "ZIP import must be fully visible on the Modules tab without scrolling"
                            }
                        }
                        capture("$prefix-$mode-management")

                        browseRequested.set(false)
                        import.performTouchInput { click() }
                        await(hasText("审核 Mod 作者与能力"))
                        check(browseRequested.get())
                        label("审核 Mod 作者与能力").assertIsDisplayed()
                        check(ModPackageStore.installed(target, verified.id)?.enabled != true)
                        if (first) check(ModPackageStore.installed(target, verified.id) == null)
                        val decision = label(if (first) "信任此作者并安装" else "确认更新并保留数据")
                        val cancel = label("取消")
                        capture("$prefix-$mode-review")
                        for (action in listOf(decision, cancel)) {
                            val node = action.assertIsDisplayed().fetchSemanticsNode()
                            check(node.boundsInRoot.height + 1f >= node.size.height) {
                                "The decision buttons must fit in the window in $mode"
                            }
                        }
                        if (rotate) {
                            val details = tag("dialog_decision_scroll").assertIsDisplayed()
                            val before = listOf(decision, cancel).map { it.fetchSemanticsNode().boundsInRoot }
                            details.performTouchInput {
                                swipe(Offset(centerX, height * .86f), Offset(centerX, height * .14f), 180)
                            }
                            check(details.scrollPosition() > 0f) { "Author and capability disclosure must scroll" }
                            check(listOf(decision, cancel).map { it.fetchSemanticsNode().boundsInRoot } == before) {
                                "Scrolling a disclosure must not move the decision buttons"
                            }
                            capture("$prefix-$mode-review-scrolled")
                        }
                        if (first) {
                            decision.performTouchInput { click() }
                            await(hasText(verified.name))
                            val stored = checkNotNull(ModPackageStore.installed(target, verified.id))
                            check(!stored.enabled && stored.authorFingerprint == verified.authorFingerprint)
                        } else {
                            cancel.performTouchInput { click() }
                            label("审核 Mod 作者与能力").assertDoesNotExist()
                        }

                        openModDetail("modules_tab", verified.id)
                        val details = tag("mod_detail_scroll")
                        toLabelIn(details, "卸载…")
                        if (rotate) check(details.scrollPosition() > 0f) { "Landscape must scroll to the final actions" }
                        rail.assertDoesNotExist()
                        capture("$prefix-$mode-scrolled")
                        tag("mod_detail_back").performTouchInput { click() }
                        rail.assertIsDisplayed()

                        if (first) {
                            tab("screen_title")
                            toTag("home_list", "ai_rear_wallpaper_entry")
                            tag("ai_rear_wallpaper_entry").performTouchInput { click() }
                            tag("ai_rear_theme_selector").performTouchInput { click() }
                            val themeBack = tag("ai_rear_theme_back").assertIsDisplayed()
                            label(res(R.string.ai_rear_install_in_modules)).performScrollTo().performTouchInput { click() }
                            tag("mod_settings_back").assertIsDisplayed()
                            rail.assertDoesNotExist()
                            val child = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
                            checkWallpaperEdgeGesture(child, "$prefix-$mode-from-theme", onCommit = {
                                themeBack.assertIsDisplayed()
                                rail.assertDoesNotExist()
                            })
                            themeBack.performTouchInput { click() }
                            tag("ai_rear_back").assertIsDisplayed().performTouchInput { click() }
                            rail.assertIsDisplayed()
                        }
                    })
                }
            }
        } finally {
            instrument.removeMonitor(monitor)
            ModPackageStore.uninstall(target, verified.id)
            ModPackageStore.revokeAuthorTrust(target, verified.authorFingerprint)
            check(fixture.delete())
        }
    }

    private fun openModDetail(list: String, id: String) {
        val entry = "mod_detail_entry_$id"
        toTag(list, entry)
        val row = tag(entry)
        if (list == "modules_tab") {
            val railTop = tag("main_navigation").fetchSemanticsNode().boundsInRoot.top
            val gap = 8f * host.resources.displayMetrics.density
            val overlap = row.fetchSemanticsNode().boundsInRoot.bottom - (railTop - gap)
            if (overlap > 0f) {
                tag(list).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                    it(0f, overlap)
                }
            }
            check(row.fetchSemanticsNode().boundsInRoot.bottom <= railTop) {
                "The Mod entry must clear the floating capsule before the physical tap"
            }
        }
        row.performTouchInput { click() }
        await(hasTestTag("mod_detail_scroll"))
        tag("mod_detail_scroll").assertIsDisplayed()
    }

    private fun toLabelIn(list: SemanticsNodeInteraction, text: String) {
        list.performScrollToNode(hasText(text))
        label(text).performScrollTo()
    }

    @Test fun standaloneWallpaperUsesTheSameModChildrenAndActualParents() {
        val prefix = "remrss-validation-20261122-mod-details-r02-standalone"
        val counterBytes = instrument.context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val themeBytes = wallpaperNavigationTestArchive(
            instrument.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() })
        val counter = ModPackageReader.read(counterBytes.inputStream())
        val theme = ModPackageReader.read(themeBytes.inputStream())
        for ((bytes, verified) in listOf(counterBytes to counter, themeBytes to theme)) {
            check(ModPackageStore.installed(target, verified.id) == null)
            ModPackageStore.install(target, bytes,
                ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()))
        }
        ModPackageStore.setEnabled(target, counter.id, true)
        try {
            configuration(prefix, stress = true, dark = true) {
                ActivityScenario.launch<FixtureWallpaperEditorActivity>(
                    Intent(target, FixtureWallpaperEditorActivity::class.java),
                ).use { scenario ->
                    scenario.onActivity { host = it }
                    toTag("ai_rear_editor", "ai_rear_theme_selector")
                    tag("ai_rear_theme_selector").performTouchInput { click() }
                    val install = res(R.string.ai_rear_install_in_modules)
                    label(install).performScrollTo().performTouchInput { click() }
                    val manager = tag("mod_management_scroll").assertIsDisplayed()
                    toTag("mod_management_scroll", "mod_detail_entry_${counter.id}")
                    val modPosition = manager.scrollPosition()
                    tag("mod_detail_entry_${counter.id}").performTouchInput { click() }
                    toTag("mod_detail_scroll", "mod_config_${counter.id}")
                    tag("mod_config_${counter.id}").performTouchInput { click() }
                    val configBack = tag("mod_back_${counter.id}").assertIsDisplayed()
                    compose.waitUntil(8_000) { (host as FixtureWallpaperEditorActivity).hostedModConfig()?.sceneVisible == true }
                    scenario.recreate()
                    scenario.onActivity { host = it }
                    configBack.assertIsDisplayed()
                    compose.waitUntil(8_000) { (host as FixtureWallpaperEditorActivity).hostedModConfig()?.sceneVisible == true }
                    val child = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
                    checkWallpaperEdgeGesture(child, "$prefix-mod-config", onCommit = {
                        await(hasTestTag("mod_detail_scroll"))
                        tag("mod_detail_scroll").assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                    })
                    tag("mod_detail_back").performTouchInput { click() }
                    manager.assertIsDisplayed()
                    check(manager.scrollPosition() == modPosition)
                    openModDetail("mod_management_scroll", counter.id)
                    toTag("mod_detail_scroll", "mod_preview_${counter.id}")
                    tag("mod_preview_${counter.id}").performTouchInput { click() }
                    tag("mod_preview_back").assertIsDisplayed()
                    await(hasText("预览选项"))
                    tag("mod_preview_back").performTouchInput { click() }
                    tag("mod_detail_back").performTouchInput { click() }
                    openModDetail("mod_management_scroll", theme.id)
                    toTag("mod_detail_scroll", "mod_wallpaper_${theme.id}")
                    tag("mod_wallpaper_${theme.id}").performTouchInput { click() }
                    toTag("ai_rear_editor", "ai_rear_edit_shortcuts")
                    tag("ai_rear_edit_shortcuts").performTouchInput { click() }
                    tag("ai_rear_shortcuts_back").assertIsDisplayed().performTouchInput { click() }
                    tag("ai_rear_back").assertIsDisplayed().performTouchInput { click() }
                    tag("mod_detail_back").assertIsDisplayed().performTouchInput { click() }
                    manager.assertIsDisplayed()
                    checkWallpaperEdgeGesture(child, "$prefix-management", onCommit = {
                        tag("ai_rear_theme_back").assertIsDisplayed()
                        tag("main_navigation").assertDoesNotExist()
                    })
                    tag("ai_rear_theme_back").performTouchInput { click() }
                    tag("ai_rear_back").assertIsDisplayed().performTouchInput { click() }
                    compose.waitUntil(5_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                }
            }
        } finally {
            for (mod in listOf(counter, theme)) {
                ModPackageStore.uninstall(target, mod.id)
                ModPackageStore.revokeAuthorTrust(target, mod.authorFingerprint)
            }
        }
    }

    @Test fun captureHostedModChildren() {
        val prefix = "remrss-validation-20261122-mod-details-r02-children"
        val baseCounter = instrument.context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val counterBytes = configDecisionTestArchive(baseCounter)
        val baseTheme = instrument.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val themeBytes = wallpaperNavigationTestArchive(baseTheme)
        val counter = ModPackageReader.read(counterBytes.inputStream())
        val theme = ModPackageReader.read(themeBytes.inputStream())
        for ((bytes, verified) in listOf(counterBytes to counter, themeBytes to theme)) {
            check(ModPackageStore.installed(target, verified.id) == null)
            ModPackageStore.install(target, bytes,
                ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()))
        }
        ModPackageStore.setEnabled(target, counter.id, true)
        try {
            for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
                configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    open(FixtureWallpaperSettingsActivity::class.java, {
                        val main = host as FixtureWallpaperSettingsActivity
                        val rail = tag("main_navigation").assertIsDisplayed()
                        val manager = tag("modules_tab")
                        val first = !rotate && !dark
                        tab("nav_modules")
                        toTag("modules_tab", "mod_detail_entry_${counter.id}")
                        val managementPosition = manager.scrollPosition()
                        tag("mod_detail_entry_${counter.id}").performTouchInput { click() }
                        toTag("mod_detail_scroll", "mod_config_${counter.id}")
                        tag("mod_config_${counter.id}").performTouchInput { click() }
                        val back = tag("mod_back_${counter.id}").assertIsDisplayed()
                        val input = compose.onNode(hasSetTextAction())
                        await(hasSetTextAction())
                        compose.waitUntil(8_000) { compose.runOnIdle { main.hostedModConfig()?.sceneVisible == true } }
                        rail.assertDoesNotExist()
                        capture("$prefix-$mode-config")
                        if (first) {
                            input.performScrollTo().performClick()
                            input.performTextClearance()
                            input.performTextInput("3")
                            compose.runOnIdle {
                                host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                    .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
                            }
                            label("保存设置").performScrollTo().performTouchInput { click() }
                            compose.waitUntil(10_000) {
                                ModPackageStore.model(target, counter.id)?.config?.optInt("step") == 3
                            }

                            checkDecisionCancel(counter.id)
                            // While returning, the renderer still exists for its slide-out, but cannot act.
                            compose.mainClock.autoAdvance = false
                            try {
                                back.performTouchInput { click() }
                                compose.mainClock.advanceTimeBy(48)
                                compose.runOnIdle {
                                    val scene = checkNotNull(main.hostedModConfig())
                                    check(!scene.sceneVisible && !main.isFinishing && main.window.decorView.isShown)
                                }
                            } finally {
                                compose.mainClock.autoAdvance = true
                            }
                        } else {
                            if (rotate) {
                                checkDecisionCancel(counter.id) { capture("$prefix-$mode-decision") }
                            }
                            back.performTouchInput { click() }
                        }
                        tag("mod_detail_scroll").assertIsDisplayed()
                        rail.assertDoesNotExist()
                        tag("mod_detail_back").performTouchInput { click() }
                        manager.assertIsDisplayed()
                        check(manager.scrollPosition() == managementPosition)
                        rail.assertIsDisplayed()
                        openModDetail("modules_tab", counter.id)
                        toTag("mod_detail_scroll", "mod_preview_${counter.id}")
                        tag("mod_preview_${counter.id}").performTouchInput { click() }
                        tag("mod_preview_back").assertIsDisplayed()
                        compose.waitUntil(8_000) { has(hasText("预览选项")) }
                        capture("$prefix-$mode-preview")
                        if (first) {
                            checkWallpaperEdgeGesture(
                                compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true),
                                "$prefix-$mode-preview-return",
                                onCommit = {
                                    tag("mod_detail_scroll").assertIsDisplayed()
                                    rail.assertDoesNotExist()
                                },
                            )
                        } else tag("mod_preview_back").performTouchInput { click() }

                        tag("mod_detail_back").assertIsDisplayed().performTouchInput { click() }
                        toTag("modules_tab", "mod_detail_entry_${theme.id}")
                        val themePosition = manager.scrollPosition()
                        tag("mod_detail_entry_${theme.id}").performTouchInput { click() }
                        toTag("mod_detail_scroll", "mod_wallpaper_${theme.id}")
                        tag("mod_wallpaper_${theme.id}").performTouchInput { click() }
                        tag("ai_rear_back").assertIsDisplayed()
                        val capacity = host.getString(R.string.ai_rear_bound_count, 0, 5)
                        await(hasText(capacity))
                        capture("$prefix-$mode-wallpaper")
                        toTag("ai_rear_editor", "ai_rear_edit_shortcuts")
                        tag("ai_rear_edit_shortcuts").performTouchInput { click() }
                        val shortcutsBack = tag("ai_rear_shortcuts_back").assertIsDisplayed()
                        label(capacity).assertExists()
                        rail.assertDoesNotExist()
                        if (first) {
                            val child = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
                            checkWallpaperEdgeGesture(child, "$prefix-$mode-shortcut", onCommit = {
                                tag("ai_rear_back").assertIsDisplayed()
                                rail.assertDoesNotExist()
                            })
                        } else shortcutsBack.performTouchInput { click() }
                        tag("ai_rear_back").performTouchInput { click() }
                        tag("mod_detail_back").assertIsDisplayed().performTouchInput { click() }
                        manager.assertIsDisplayed()
                        check(manager.scrollPosition() == themePosition)
                        tag("mod_settings_back").assertDoesNotExist()
                        rail.assertIsDisplayed()
                    })
                }
            }
        } finally {
            for (verified in listOf(theme, counter)) {
                ModPackageStore.uninstall(target, verified.id)
                ModPackageStore.revokeAuthorTrust(target, verified.authorFingerprint)
            }
        }
    }

    private fun checkDecisionCancel(modId: String, shown: () -> Unit = {}) {
        val modelRevision = checkNotNull(ModPackageStore.model(target, modId)).revision
        label("确认递增").performScrollTo().performTouchInput { click() }
        await(hasText("要增加计数吗？"))
        label("确认").assertIsDisplayed()
        val cancel = label("取消").assertIsDisplayed()
        shown()
        cancel.performTouchInput { click() }
        label("要增加计数吗？").assertDoesNotExist()
        check(ModPackageStore.model(target, modId)?.revision == modelRevision)
    }

    @Test fun hostedModConfigRestoresAndStandaloneHostsStillWork() {
        val prefix = "remrss-validation-20261122-mod-details-r02-restore"
        val archive = instrument.context.assets.open("platform/mods/config-derived-counter.zip").use { it.readBytes() }
        val counter = ModPackageReader.read(archive.inputStream())
        check(ModPackageStore.installed(target, counter.id) == null)
        ModPackageStore.install(target, archive,
            ModInstallDecision(counter.authorFingerprint, counter.requiredCapabilities.map { it.name }.toSet()))
        ModPackageStore.setEnabled(target, counter.id, true)
        try {
            configuration(prefix, stress = true, dark = true) {
                val intent = Intent(target, FixtureWallpaperSettingsActivity::class.java)
                ActivityScenario.launch<FixtureWallpaperSettingsActivity>(intent).use { scenario ->
                    scenario.onActivity { host = it }
                    tab("nav_modules")
                    openModDetail("modules_tab", counter.id)
                    toTag("mod_detail_scroll", "mod_config_${counter.id}")
                    tag("mod_config_${counter.id}").performTouchInput { click() }
                    fun source(): ModCastActivationSource? {
                        var value: ModCastActivationSource? = null
                        scenario.onActivity { activity ->
                            if (activity.hostedModConfig()?.sceneVisible == true) {
                                value = androidx.lifecycle.ViewModelProvider(activity)[ModConfigCastState::class.java].source
                            }
                        }
                        return value
                    }
                    compose.waitUntil(8_000) { source() != null }
                    val initial = checkNotNull(source())
                    check(ModCastActivationSources.isRegistered(initial))
                    scenario.recreate()
                    compose.waitUntil(8_000) { source() != null }
                    check(source() == initial && ModCastActivationSources.isRegistered(initial))
                    tag("mod_back_${counter.id}").performTouchInput { click() }
                    await(hasTestTag("mod_detail_back"))
                    tag("mod_detail_back").assertIsDisplayed().performTouchInput { click() }
                    tag("modules_tab").assertIsDisplayed()
                    compose.waitUntil(8_000) { !ModCastActivationSources.isRegistered(initial) }
                    tag("main_navigation").assertIsDisplayed()
                }
                open(ModConfigActivity::class.java, {
                    tag("mod_back_${counter.id}").assertIsDisplayed()
                    await(hasSetTextAction())
                    tag("mod_back_${counter.id}").performTouchInput { click() }
                    check(host.isFinishing)
                }) { putExtra("mod_id", counter.id) }
                open(ModPreviewActivity::class.java, {
                    tag("mod_preview_back").assertIsDisplayed()
                    await(hasText("预览选项"))
                    tag("mod_preview_back").performTouchInput { click() }
                    check(host.isFinishing)
                }) { putExtra("mod_id", counter.id) }
            }
        } finally {
            ModPackageStore.uninstall(target, counter.id)
            ModPackageStore.revokeAuthorTrust(target, counter.authorFingerprint)
        }
    }

    @Test fun captureRegisteredActionProviderRoutes() {
        val prefix = "remrss-validation-20261122-action-routes-r02"
        val archive = instrument.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val verified = ModPackageReader.read(archive.inputStream())
        check(ModPackageStore.installed(target, verified.id) == null)
        ModPackageStore.install(target, archive,
            ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()))
        ModPackageStore.setEnabled(target, verified.id, true)
        try {
            val owner = checkNotNull(top.zainzh.rearscreen.platform.RearCapabilityCore.get(target).modDataOwner(verified.id))
            val registry = PlatformRegisteredActionRegistry.get(target)
            val settings = checkNotNull(queryShortcutProviders(target.packageManager)?.providers?.firstOrNull {
                it.packageName == "com.android.settings"
            }) { "The disposable system image must expose its real Settings provider" }
            for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
                configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    ActivityScenario.launch<RegisteredActionsActivity>(
                        RegisteredActionsActivity.createIntent(target, owner, selectAction = true),
                    ).use { scenario ->
                        val requested = if (rotate) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        val expected = if (rotate) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            else android.content.res.Configuration.ORIENTATION_PORTRAIT
                        scenario.onActivity { it.requestedOrientation = requested }
                        compose.waitUntil(8_000) {
                            var ready = false
                            scenario.onActivity { host = it; ready = it.resources.configuration.orientation == expected }
                            ready
                        }
                        val actionBack = tag("registered_actions_back").assertIsDisplayed()
                        val headerTop = actionBack.fetchSemanticsNode().boundsInRoot.top
                        val add = label(res(R.string.registered_actions_add))
                        add.performScrollTo().performTouchInput { click() }
                        val providers = tag("registered_actions_provider_list").assertIsDisplayed()
                        val providerBack = tag("registered_actions_provider_back").assertIsDisplayed()
                        check(providerBack.fetchSemanticsNode().boundsInRoot.top == headerTop)
                        check(providers.fetchSemanticsNode().boundsInRoot.top >= providerBack.fetchSemanticsNode().boundsInRoot.bottom)
                        add.assertDoesNotExist()
                        capture("$prefix-$mode-provider")
                        if (!rotate && !dark) {
                            var snapshot: List<top.zainzh.rearscreen.platform.actions.ShortcutProviderChoice> = emptyList()
                            scenario.onActivity { snapshot = queryShortcutProviders(it.packageManager)?.providers.orEmpty() }
                            val collisions = snapshot.groupBy { it.label.lowercase(java.util.Locale.ROOT) to it.packageName }
                                .values.filter { it.size > 1 }
                            for (collision in collisions.flatten()) {
                                val id = "registered_provider_${collision.component.flattenToString()}"
                                toTag("registered_actions_provider_list", id)
                                val detail = "${collision.packageName} · ${collision.component.flattenToShortString().substringAfter('/')}"
                                tag(id).assert(hasText(detail))
                            }
                            providers.performScrollToIndex(snapshot.lastIndex)
                            val positionBeforeRestore = providers.scrollPosition()
                            check(positionBeforeRestore > 0f)
                            scenario.recreate()
                            scenario.onActivity { host = it }
                            await(hasTestTag("registered_actions_provider_list"))
                            providers.assertIsDisplayed()
                            check(providers.scrollPosition() == positionBeforeRestore)
                            checkWallpaperEdgeGesture(compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true),
                                "$prefix-$mode-return", onCommit = {
                                    actionBack.assertIsDisplayed()
                                    check(!host.isFinishing)
                                })
                            add.performScrollTo().performTouchInput { click() }
                            val settingsRow = "registered_provider_${settings.component.flattenToString()}"
                            toTag("registered_actions_provider_list", settingsRow)
                            tag(settingsRow).performTouchInput { click() }
                            compose.waitUntil(8_000) {
                                instrument.uiAutomation.rootInActiveWindow?.packageName?.toString() == settings.packageName
                            }
                            back()
                            actionBack.assertIsDisplayed()
                            label(res(R.string.registered_action_cancelled)).assertIsDisplayed()
                        } else providerBack.performTouchInput { click() }
                        actionBack.assertIsDisplayed()
                        check(registry.listForOwner(owner).isEmpty())
                    }
                }
            }
        } finally {
            ModPackageStore.uninstall(target, verified.id)
            ModPackageStore.revokeAuthorTrust(target, verified.authorFingerprint)
        }
    }

    @Test fun captureRegisteredActionApprovalLayout() {
        val prefix = "remrss-validation-20261122-action-approval-r01"
        for (dark in listOf(false, true)) configuration(prefix, stress = true, rotate = true, dark = dark) {
            open(ComposeTestHostActivity::class.java, {
                var cancelled = 0
                var confirmed = 0
                instrument.runOnMainSync {
                    host.miuixContent {
                        RegisteredActionInvocationContent("示例控制客户端", "查看工作区面板与当前常用操作".repeat(9),
                            "示例目标应用及其详细页面标识".repeat(9),
                            onCancel = { cancelled++ }, onConfirm = { confirmed++ })
                    }
                }
                val body = tag("registered_action_invocation_body").assertIsDisplayed()
                val cancel = tag("registered_action_invocation_cancel").assertIsDisplayed()
                val confirm = tag("registered_action_invocation_confirm").assertIsDisplayed()
                val before = listOf(cancel, confirm).map { it.fetchSemanticsNode().boundsInRoot }
                body.performTouchInput { swipe(Offset(centerX, height * .88f), Offset(centerX, height * .16f), 260) }
                check(body.scrollPosition() > 0f)
                check(listOf(cancel, confirm).map { it.fetchSemanticsNode().boundsInRoot } == before)
                capture("$prefix-${if (dark) "dark" else "light"}-landscape-large")
                cancel.performTouchInput { click() }
                check(cancelled == 1 && confirmed == 0)
                confirm.performTouchInput { click() }
                check(cancelled == 1 && confirmed == 1)
            })
        }
    }

    @Test fun captureTransparentCastApprovals() {
        val prefix = "remrss-validation-20261122-cast-approval-r03"
        for (rotate in listOf(false, true)) for (dark in listOf(false, true)) {
            configuration(prefix, stress = rotate, rotate = rotate, dark = dark) {
                open(ComposeTestHostActivity::class.java, {
                    val background = 0xff416b70.toInt()
                    val monitor = instrument.addMonitor(CastApprovalTestHostActivity::class.java.name, null, false)
                    val underlying = host
                    val mode = (if (dark) "dark" else "light") + if (rotate) "-landscape-large" else "-portrait"
                    val submitting = androidx.compose.runtime.mutableStateOf(false)
                    val failure = androidx.compose.runtime.mutableStateOf(false)
                    val isMod = androidx.compose.runtime.mutableStateOf(rotate)
                    var returned = 0
                    var confirmed = 0
                    var cancelled = 0
                    instrument.runOnMainSync {
                        underlying.setContentView(android.widget.TextView(underlying).apply {
                            setBackgroundColor(background)
                            text = "保留在确认页后面的原主屏应用"
                            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 22f
                            setPadding(0, 0, 0, 45)
                        })
                        underlying.startActivity(Intent(underlying, CastApprovalTestHostActivity::class.java))
                    }
                    val overlay = try {
                        requireNotNull(instrument.waitForMonitorWithTimeout(monitor, 8_000)) as CastApprovalTestHostActivity
                    } finally {
                        instrument.removeMonitor(monitor)
                    }
                    try {
                        compose.waitUntil(8_000) { overlay.hasWindowFocus() }
                        instrument.runOnMainSync {
                            overlay.miuixContent {
                                if (failure.value) {
                                    CastApprovalFailure("未能启动背屏投放", "本次确认已过期。返回后重新选择投放目标。") { returned++ }
                                } else {
                                    CastApprovalPane(
                                        application = if (rotate) "带有很长的主屏任务名称的应用".repeat(12) else "原主屏应用",
                                        mode = if (isMod.value) "虚拟投放" else "物理直投",
                                        region = if (isMod.value) "全域" else "安全区",
                                        occupancy = if (rotate) "替换：现有投放" else "占用：背屏当前没有受控投放",
                                        rotationDegrees = if (isMod.value) 90 else null,
                                        secondaryLabel = if (isMod.value) "返回选择" else "取消",
                                        submitting = submitting.value, onSecondary = { returned++ }, onConfirm = { confirmed++ },
                                        onCancelFlow = if (isMod.value) ({ cancelled++ }) else null,
                                    )
                                }
                            }
                        }
                        val body = tag("cast_approval_details").assertIsDisplayed()
                        val secondary = tag("cast_approval_secondary").assertIsDisplayed()
                        val confirm = tag("cast_approval_confirm").assertIsDisplayed()
                        val buttons = if (rotate) listOf(secondary, confirm, tag("cast_approval_exit").assertIsDisplayed())
                            else listOf(secondary, confirm)
                        val before = buttons.map {
                            val node = it.fetchSemanticsNode()
                            check(node.boundsInRoot.height + 1f >= node.size.height)
                            node.boundsInRoot
                        }
                        // Same Android task underneath the approval; an opaque host would fail this readback.
                        val screen = requireNotNull(instrument.uiAutomation.takeScreenshot())
                        try {
                            check(screen.getPixel(screen.width / 2, screen.height * 93 / 100) == background ||
                                screen.getPixel(screen.width / 15, screen.height * 92 / 100) == background)
                        } finally { screen.recycle() }
                        if (rotate) {
                            confirm.assertIsNotEnabled()
                            val scroll = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                            check(scroll.maxValue() > 0f)
                            repeat(15) {
                                if (body.scrollPosition() < scroll.maxValue()) {
                                    body.performTouchInput {
                                        swipe(Offset(centerX, height * .88f), Offset(centerX, height * .12f), 240)
                                    }
                                    compose.waitForIdle()
                                }
                            }
                            check(body.scrollPosition() == scroll.maxValue())
                            label("内容方向：90°").assertIsDisplayed()
                            check(buttons.map { it.fetchSemanticsNode().boundsInRoot } == before)
                            tag("cast_approval_exit").performTouchInput { click() }
                            check(cancelled == 1 && confirmed == 0 && returned == 0)
                        } else {
                            label("背屏区域：安全区").assertIsDisplayed()
                            tag("cast_approval_exit").assertDoesNotExist()
                        }
                        capture("$prefix-$mode")
                        secondary.performTouchInput { click() }
                        check(returned == 1 && confirmed == 0)
                        confirm.assertIsEnabled().performTouchInput { click() }
                        check(confirmed == 1)
                        compose.runOnIdle { submitting.value = true }
                        secondary.assertIsNotEnabled()
                        confirm.assertIsNotEnabled()
                        compose.runOnIdle { failure.value = true }
                        tag("cast_approval_confirm").assertDoesNotExist()
                        tag("cast_approval_return").assertIsDisplayed().performTouchInput { click() }
                        check(returned == 2 && confirmed == 1)
                        if (rotate && dark) capture("$prefix-$mode-expired")
                    } finally {
                        instrument.runOnMainSync { overlay.finish() }
                    }
                })
            }
        }
    }

    @Test fun captureCompactRearModDecisions() {
        val prefix = "remrss-validation-20261122-cast-approval-r04"
        val previousOverride = Regex("Override size: ([0-9]+x[0-9]+)")
            .find(shell("wm size"))?.groupValues?.get(1)
        shell("wm size 556x916")
        try {
            for (dark in listOf(false, true)) configuration(prefix + "-rear", stress = true, rotate = true, dark = dark) {
                open(ComposeTestHostActivity::class.java, {
                    val window = host.resources.configuration
                    check(window.screenWidthDp in 300..370 && window.screenHeightDp in 155..225 && window.fontScale == 1.6f)
                    val cast = androidx.compose.runtime.mutableStateOf(false)
                    var cancelled = 0
                    var confirmed = 0
                    instrument.runOnMainSync {
                        host.miuixContent {
                            val choosingCast = cast.value
                            androidx.compose.runtime.key(choosingCast) {
                                ModRearDecisionDialog(true, onCancel = { cancelled++ }) {
                                    if (choosingCast) {
                                        ModRearCastRequestContent("虚拟投放", "沿用应用偏好", "沿用应用偏好",
                                            onCancel = { cancelled++ }, onConfirm = { confirmed++ })
                                    } else {
                                        ModRearSceneRequestContent("全屏", 90, "保持亮屏",
                                            onCancel = { cancelled++ }, onConfirm = { confirmed++ })
                                    }
                                }
                            }
                        }
                    }
                    for (choosingCast in listOf(false, true)) {
                        compose.runOnIdle { cast.value = choosingCast }
                        val shot = "$prefix-${if (dark) "dark" else "light"}-rear-${if (choosingCast) "cast" else "scene"}"
                        capture("$shot-opening")
                        val cancel = tag("rear_mod_request_cancel").assertIsDisplayed()
                        val confirm = tag("rear_mod_request_confirm").assertIsDisplayed()
                        val positions = listOf(cancel, confirm).map {
                            val node = it.fetchSemanticsNode()
                            check(node.boundsInRoot.height + 1f >= node.size.height)
                            node.boundsInRoot
                        }
                        val body = tag("rear_mod_request_body").assertIsDisplayed()
                        check(body.fetchSemanticsNode().boundsInRoot.height > positions[0].height) {
                            "Disclosures need more than a one-line sliver"
                        }
                        val scroll = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                        repeat(8) {
                            if (body.scrollPosition() < scroll.maxValue()) {
                                body.performTouchInput {
                                    swipe(Offset(centerX, height * .87f), Offset(centerX, height * .13f), 220)
                                }
                                compose.waitForIdle()
                            }
                        }
                        check(scroll.maxValue() > 0f && body.scrollPosition() == scroll.maxValue())
                        val last = if (choosingCast) "投放与替换另行确认" else "现有策略不变"
                        label(last).assertIsDisplayed()
                        check(listOf(cancel, confirm).map { it.fetchSemanticsNode().boundsInRoot } == positions)
                        capture("$shot-scrolled")
                        cancel.performTouchInput { click() }
                        confirm.performTouchInput { click() }
                    }
                    check(cancelled == 2 && confirmed == 2)
                })
            }
        } finally {
            shell("wm size ${previousOverride ?: "reset"}")
        }
    }

    @Test fun captureWallpaperReplacementDecision() {
        val prefix = "remrss-validation-20261122-wallpaper-decision-r02"
        for (dark in listOf(false, true)) configuration(prefix, stress = true, rotate = true, dark = dark) {
            open(ComposeTestHostActivity::class.java, {
                val show = androidx.compose.runtime.mutableStateOf(true)
                var confirmed = 0
                instrument.runOnMainSync {
                    host.miuixContent {
                        AiRearWallpaperReplaceDialog(show.value, legacy = true, onCancel = { show.value = false },
                            onConfirm = { confirmed++; show.value = false })
                    }
                }
                val cancel = tag("wallpaper_replace_cancel").assertIsDisplayed()
                val confirm = tag("wallpaper_replace_confirm").assertIsDisplayed()
                for (button in listOf(cancel, confirm)) {
                    val node = button.fetchSemanticsNode()
                    check(node.boundsInRoot.height + 1f >= node.size.height)
                }
                capture("$prefix-${if (dark) "dark" else "light"}-landscape-large")
                cancel.performTouchInput { click() }
                confirm.assertDoesNotExist()
                check(confirmed == 0)
                compose.runOnIdle { show.value = true }
                confirm.assertIsDisplayed().performTouchInput { click() }
                check(confirmed == 1)
            })
        }
    }

    @Test fun wallpaperSecondaryStates() = configuration("audit-wallpaper", false) {
        page("wallpaper-editor") { open(FixtureWallpaperEditorActivity::class.java, {
            toTag("ai_rear_editor", "ai_rear_appearance")
            capture("40-wallpaper-editor")
            tag("ai_rear_appearance").performClick(); capture("41-wallpaper-appearance"); back()
            toTag("ai_rear_editor", "ai_rear_edit_shortcuts")
            tag("ai_rear_edit_shortcuts").performClick(); await(hasTestTag("ai_rear_shortcuts"))
            capture("42-wallpaper-shortcuts"); back()
            toTag("ai_rear_editor", "ai_rear_theme_selector")
            tag("ai_rear_theme_selector").performClick(); capture("43-wallpaper-themes")
        }) }
        page("wallpaper-works") { open(FixtureWallpaperEditorActivity::class.java, {
            await(hasTestTag("ai_rear_works"))
            label(res(R.string.ai_rear_connect)).assertIsDisplayed()
            label(res(R.string.ai_rear_pick_hint)).assertDoesNotExist()
            capture("44-wallpaper-works-no-backend")
        }) { putExtra("show_work_state", true) } }
    }

    @Test fun castTargetPickerLongNames() = configuration("audit-cast-target", true, rotate = true) {
        page("cast-picker") { open(ComposeTestHostActivity::class.java, {
            var selected: String? = null
            var cancelled = false
            val fakeChoices = (0 until 12).map {
                top.zainzh.rearscreen.platform.HostCastForegroundChoice(
                    "target-$it", "测试应用 $it：模拟长名称与实际投放目标的视觉空间", "example.visual.target$it")
            }
            instrument.runOnMainSync {
                host.miuixContent {
                    top.zainzh.rearscreen.platform.mods.ForegroundCastTargetScreen(fakeChoices, "", false,
                        { selected = it.opaqueToken }, { cancelled = true })
                }
            }
            await(hasTestTag("foreground_choice_list"))
            tag("foreground_choice_cancel").assertIsDisplayed()
            tag("foreground_choice_list").performScrollToIndex(12)
            tag("foreground_choice_cancel").assertIsDisplayed()
            capture("45-select-cast-target")
            label(fakeChoices.last().label).performScrollTo().performClick()
            org.junit.Assert.assertEquals(fakeChoices.last().opaqueToken, selected)
            tag("foreground_choice_cancel").performClick()
            assertTrue(cancelled)
        }) }
    }

    private fun modalPages() {
        page("update") { open(ComposeTestHostActivity::class.java, {
            lateinit var modal: UpdateDialogUi
            instrument.runOnMainSync {
                host.prepareMiuixWindow()
                modal = UpdateDialogUi(host, Runnable {})
                host.setContentView(modal.view)
                modal.update("有可用更新", "当前版本 4.0.0-rc.6", "", "下载后可选择是否安装。", true,
                    -1, false, arrayOf("支持从已授权的背屏壁纸打开应用。", "保留原有设置与模块数据。"),
                    arrayOf("大字模式下更容易阅读设置说明。"), arrayOf("修正录屏状态和关闭时的提示。"),
                    "新增", "改进", "修复", "暂无说明",
                    arrayOf("下载更新", "稍后"), arrayOf(Runnable {}, Runnable {}))
            }
            tag("update_dialog_action_0").assertExists()
            capture("30-update-available")
            instrument.runOnMainSync {
                modal.update("正在下载更新", "当前版本 4.0.0-rc.6", "已下载 8.3 MB / 24 MB", "", false,
                    34, false, emptyArray(), emptyArray(), emptyArray(), "新增", "改进", "修复", "暂无说明",
                    arrayOf("取消下载"), arrayOf(Runnable {}))
            }
            capture("30-update-progress")
        }) }
        page("cast-drawer") { open(ComposeTestHostActivity::class.java, {
            instrument.runOnMainSync {
                val controls = RearCastMenuUi(host, Runnable {}, Runnable {}, Runnable {}, Runnable {}, Runnable {})
                host.setContentView(android.widget.FrameLayout(host).apply {
                    setBackgroundColor(0xFF152331.toInt())
                    addView(controls.view, android.widget.FrameLayout.LayoutParams(
                        (180 * resources.displayMetrics.density).toInt(), (170 * resources.displayMetrics.density).toInt(),
                        android.view.Gravity.CENTER))
                })
                controls.show(true)
            }
            capture("31-rear-cast-menu")
        }) }
        page("authorizations") { authorizationPages() }
    }

    private fun configuration(name: String, stress: Boolean, rotate: Boolean = false, dark: Boolean = stress, body: () -> Unit) {
        landscape = rotate
        folder = if (name.startsWith("remrss-validation-")) "/data/local/tmp/$name"
            else "/data/local/tmp/remrss-visual-audit/$name"
        shell("mkdir -p $folder")
        val initialFont = shell("settings get system font_scale")
        val initialMode = shell("cmd uimode night").substringAfterLast(' ').let { if (it in setOf("yes", "no", "auto")) it else "auto" }
        val initialAccelerometer = shell("settings get system accelerometer_rotation")
        val initialRotation = shell("settings get system user_rotation").toIntOrNull() ?: 0
        val localeManager = target.getSystemService(LocaleManager::class.java)
        val initialLocale = localeManager.applicationLocales
        try {
            localeManager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
            shell("settings put system font_scale " + if (stress) "1.6" else "1.0")
            shell("cmd uimode night " + if (dark) "yes" else "no")
            shell("wm user-rotation -d 0 lock " + if (rotate) "1" else "0")
            Thread.sleep(600)
            body()
        } finally {
            localeManager.applicationLocales = initialLocale
            shell("cmd uimode night $initialMode")
            shell(if (initialFont.toFloatOrNull() != null) "settings put system font_scale $initialFont" else "settings delete system font_scale")
            shell(if (initialAccelerometer == "1") "wm user-rotation -d 0 free" else "wm user-rotation -d 0 lock $initialRotation")
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    private fun capture(name: String) {
        check(name.matches(Regex("[a-z0-9-]+")))
        compose.waitForIdle()
        Thread.sleep(240)
        val full = requireNotNull(instrument.uiAutomation.takeScreenshot())
        val width = minOf(full.width, if (full.width > full.height) 960 else 640)
        val bitmap = Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
        val fds = instrument.uiAutomation.executeShellCommandRw("dd of=$folder/$name.jpg")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() }
        } finally {
            if (bitmap !== full) bitmap.recycle()
            full.recycle()
        }
        android.util.Log.i("ReMRSSVisualAudit", "$folder/$name.jpg")
    }

    private fun page(name: String, body: () -> Unit) {
        try { body() } catch (problem: Throwable) {
            misses += "$name: " + problem.message?.lineSequence()?.take(4)?.joinToString(" ")
            runCatching { capture("blocked-$name") }
        }
    }
    private fun <T : ComponentActivity> open(type: Class<T>, body: () -> Unit, extra: Intent.() -> Unit = {}) {
        openIntent<T>(Intent(target, type).apply(extra), body)
    }
    private fun <T : ComponentActivity> openIntent(intent: Intent, body: () -> Unit) {
        ActivityScenario.launch<T>(intent).use { scenario ->
            val requested = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val expected = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                else android.content.res.Configuration.ORIENTATION_PORTRAIT
            scenario.onActivity { it.requestedOrientation = requested }
            compose.waitUntil(8_000) {
                var ready = false
                scenario.onActivity {
                    host = it
                    ready = it.resources.configuration.orientation == expected
                }
                ready
            }
            body()
        }
    }
    private fun back() {
        shell("input keyevent KEYCODE_BACK")
        compose.waitForIdle()
        Thread.sleep(320)
    }
    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(7_000) { has(matcher) }
    private fun tab(key: String) { compose.onNodeWithContentDescription(text(key)).performClick(); compose.waitForIdle() }
    private fun findIn(list: String, name: String) {
        if (!has(hasText(name))) tag(list).performScrollToNode(hasText(name))
        val row = label(name).performScrollTo()
        val navigation = hasContentDescription(text("screen_title"))
        if (has(navigation)) {
            val bottom = row.fetchSemanticsNode().boundsInRoot.bottom
            val top = compose.onNode(navigation).fetchSemanticsNode().boundsInRoot.top
            if (bottom > top - 20f) tag(list).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                it(0f, bottom - top + 36f)
            }
        }
    }
    private fun inList(list: String, name: String) {
        findIn(list, name)
        if (landscape) label(name).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        else label(name).performClick()
    }
    private fun toTag(list: String, name: String) { tag(list).performScrollToNode(hasTestTag(name)); tag(name).performScrollTo() }

    private fun mainPages() {
        page("home") { open(MainActivity::class.java, {
            capture("01-home")
            for ((entry, backTag, shot) in listOf(
                Triple("wake_settings_entry", "wake_settings_back", "03-wake"),
                Triple("charging_settings_entry", "charging_settings_back", "04-charging"),
                Triple("cast_settings_entry", "cast_settings_back", "02-cast"))) {
                page(shot) {
                    toTag("home_list", entry)
                    if (shot == "03-wake") capture("01-home-scroll")
                    tag(entry).performClick()
                    await(hasTestTag(backTag))
                    tag(backTag).assertIsDisplayed()
                    capture(shot)
                    if (shot == "02-cast") {
                        val virtual = tag("rear_cast_mode_toggle")
                        if (has(hasTestTag("rear_cast_mode_toggle"))) {
                            virtual.performScrollTo().performClick(); capture("02-cast-physical")
                            if (has(hasTestTag("physical_dpi_entry"))) {
                                tag("physical_dpi_entry").performScrollTo().performClick()
                                capture("02-cast-dpi")
                                back()
                            }
                        }
                    }
                    tag(backTag).performClick()
                    await(hasTestTag("home_list"))
                }
            }
        }) }
        page("notification") { open(MainActivity::class.java, {
            tab("notifications_title"); capture("05-alerts"); inList("notifications_list", text("notification_apps"))
            tag("app_search").assertIsDisplayed(); capture("06-filter")
            if (landscape) {
                tag("app_picker_list").performScrollToIndex(2)
                capture("06-filter-scroll")
            } else {
                tag("picker_system_apps_toggle").performClick()
                tag("app_search").performTextReplacement("com.android.settings")
                compose.runOnIdle {
                    host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
                }
                capture("06-filter-result")
            }
            tag("app_picker_back").performClick(); await(hasTestTag("notifications_list"))
            inList("notifications_list", text("privacy_section")); await(hasTestTag("notification_privacy_back")); capture("07-privacy")
            tag("notification_privacy_back").performClick(); await(hasTestTag("notifications_list"))
            inList("notifications_list", text("notification_rules")); await(hasTestTag("notification_rules_back")); capture("08-rules")
            label(text("notification_click_action")).performScrollTo().performClick()
            capture("09-alert-click"); back()
            label(text("notification_duration_title")).performScrollTo().performClick()
            capture("10-duration"); back()
        }) }
        page("tabs-settings") { open(MainActivity::class.java, {
            tab("nav_modules"); capture("11-modules")
            tab("nav_settings"); capture("12-settings")
            inList("settings_list", text("about")); await(hasTestTag("about_details_back")); capture("13-about")
            tag("about_details_back").performClick()
            inList("settings_list", text("uri_title")); capture("14-automation"); back()
            inList("settings_list", text("diagnostics"))
            compose.waitUntil(8000) { has(hasText(text("license"))) }
            Thread.sleep(1100)
            capture("15-diagnostics"); label(text("license")).performScrollTo().performClick()
            capture("16-license"); back()
        }) }
    }

    private fun toolPages() {
        page("permissions") { open(PermissionChecksActivity::class.java, {
            tag("permissions_scroll").assertIsDisplayed(); capture("20-permissions")
            toTag("permissions_scroll", "permission_status_OVERLAY"); capture("20-permissions-scroll")
        }) }
        page("tutorial") { open(TutorialActivity::class.java, {
            tag("tutorial_scroll").assertIsDisplayed(); capture("21-tutorial")
            toTag("tutorial_scroll", "tutorial_header_setup"); tag("tutorial_header_setup").performClick()
            toTag("tutorial_scroll", "tutorial_shizuku"); capture("21-tutorial-setup")
            toTag("tutorial_scroll", "tutorial_header_tiles"); tag("tutorial_header_tiles").performClick()
            toTag("tutorial_scroll", "tutorial_add_tile_record"); capture("21-tutorial-tiles")
        }) }
        page("density") { open(DensityCompatibilityActivity::class.java, {
            tag("density_app_search").assertIsDisplayed()
            compose.waitUntil(10_000) { has(hasTestTag("density_enabled_group")) || has(hasTestTag("density_disabled_group")) }
            capture("22-density")
            tag("density_app_list").performScrollToNode(hasTestTag("density_app_com.android.settings"))
            tag("density_app_com.android.settings").performScrollTo(); capture("22-density-scroll")
            val setting = hasTestTag("density_app_com.android.settings")
            compose.onNode(isToggleable() and hasAnyAncestor(setting)).performClick()
            toTag("density_app_list", "density_dpi_com.android.settings")
            tag("density_dpi_com.android.settings").performClick()
            val field = tag("density_dpi_input").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("The editable value must not be clipped by the decision footer",
                field.boundsInRoot.height + 1f >= field.size.height)
            tag("density_dpi_save").assertIsDisplayed()
            capture("22-density-dpi")
        }) }
        page("screenshot") { open(FixtureRearScreenshotActivity::class.java, {
            if (!RearScreenshotPreferences.read(host).shell) label(res(R.string.rear_screenshot_shell)).performClick()
            tag("screenshot_style").assertExists(); capture("23-screenshot-tool")
            toTag("screenshot_scroll", "screenshot_style"); tag("screenshot_style").performClick()
            capture("23-screenshot-style"); back()
            toTag("screenshot_scroll", "screenshot_color"); tag("screenshot_color").performClick()
            capture("23-screenshot-color"); back()
            val preview = host.getString(R.string.rear_screenshot_preview)
            findIn("screenshot_scroll", preview)
            capture("23-screenshot-preview")
        }) }
        if (AiRearWallpaperSupport.supported()) page("wallpaper") { open(AiRearWallpaperEditorActivity::class.java, {
            tag("ai_rear_editor").assertIsDisplayed(); capture("25-wallpaper-draft")
            toTag("ai_rear_editor", "ai_rear_appearance"); tag("ai_rear_appearance").performClick()
            capture("25-wallpaper-appearance"); back()
            toTag("ai_rear_editor", "ai_rear_edit_shortcuts"); tag("ai_rear_edit_shortcuts").performClick()
            tag("ai_rear_shortcuts").assertIsDisplayed(); capture("25-wallpaper-shortcuts"); back()
            if (has(hasTestTag("ai_rear_theme_selector"))) {
                tag("ai_rear_theme_selector").performClick(); capture("25-wallpaper-themes")
            }
        }) }
        page("mods") { modPages() }
    }

    private fun authorizationPages() {
        val connected = java.util.concurrent.CountDownLatch(1)
        lateinit var service: top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformService
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                service = top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        val callback = object : top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformCallback.Stub() {
            override fun onEvent(eventJson: String) = Unit
        }
        check(target.bindService(Intent(target, top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService::class.java),
            connection, android.content.Context.BIND_AUTO_CREATE))
        try {
            check(connected.await(5L, java.util.concurrent.TimeUnit.SECONDS))
            service.requestDataAuthorization(target.packageName,
                org.json.JSONArray().put("BATTERY_LEVEL_PERCENT").put("BATTERY_CHARGING").toString(), callback).send()
            compose.waitUntil(8_000) { has(hasText(target.getString(R.string.data_consent_title))) }
            capture("32-data-consent")
            back()
            service.requestNotificationAuthorization(target.packageName, callback).send()
            compose.waitUntil(8_000) { has(hasTestTag("notification_consent_save")) }
            capture("33-notification-consent")
            back()
            service.releaseClient(target.packageName, callback)
        } finally { target.unbindService(connection) }
    }

    private fun modPages() {
        val archive = instrument.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val verified = ModPackageReader.read(archive.inputStream())
        check(ModPackageStore.installed(target, verified.id) == null)
        val mod = ModPackageStore.install(target, archive,
            ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()))
        ModPackageStore.setEnabled(target, mod.id, true)
        open(ModSettingsActivity::class.java, {
            label(mod.name).assertExists(); capture("26-zip-mods")
            openModDetail("mod_management_scroll", mod.id)
            val details = tag("mod_detail_scroll")
            toLabelIn(details, "卸载…")
            label("卸载…").performClick(); capture("26-mod-uninstall"); back()
            toLabelIn(details, "撤销作者信任")
            label("撤销作者信任").performClick(); capture("26-mod-revoke")
        })
        open(ModConfigActivity::class.java, { capture("27-mod-configuration") }) { putExtra("mod_id", mod.id) }
        open(ModPreviewActivity::class.java, {
            capture("28-mod-preview")
            label("预览选项").performScrollTo().performClick()
            capture("28-mod-preview-options")
        }) { putExtra("mod_id", mod.id) }
        page("actions") {
            val owner = requireNotNull(top.zainzh.rearscreen.platform.RearCapabilityCore.get(target).modDataOwner(mod.id))
            openIntent<RegisteredActionsActivity>(RegisteredActionsActivity.createIntent(target, owner)) {
                tag("registered_actions_back").assertIsDisplayed()
                capture("24-registered-actions")
            }
        }
    }
}
