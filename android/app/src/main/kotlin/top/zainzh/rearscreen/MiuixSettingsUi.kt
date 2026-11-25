package top.zainzh.rearscreen

import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import kotlinx.coroutines.flow.filterNotNull

/** All settings destinations share the same Miuix theme and reusable components. */
class MiuixSettingsUi(
    private val activity: MainActivity,
    savedState: Bundle?,
    private val actions: Actions,
) {
    interface Actions {
        fun navigate(target: Int)
        fun settled(target: Int)
        fun privilege()
        fun connect()
        fun details()
        fun dpi()
        fun rotation(target: Int)
        fun toggle(key: String, method: String, enabled: Boolean)
        fun tutorial()
        fun apps()
        fun lockedHomeApps()
        fun lockedHomeExitMode()
        fun duration()
        fun clickAction()
        fun saveDuration(seconds: Int, neverAutoClose: Boolean)
        fun action(method: String)
        fun updates()
        fun showInRecents(shown: Boolean)
        fun proxy(enabled: Boolean)
        fun permissions()
        fun automation()
    }
    private data class HomeState(
        val status: String = "check_shizuku", val summary: String = "connection_help",
        val action: String = "open_shizuku", val dpi: Int = 0, val rotation: Int = -1,
        val busy: Boolean = false, val values: Map<String, Boolean> = emptyMap(),
    )
    private data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0, val ime: Int = 0)
    private var state by mutableStateOf(HomeState())
    private data class SettingsState(val selected: Int = 0, val listener: Boolean = false, val posting: Boolean = true,
        val duration: Int = 8, val updates: String = "", val checking: Boolean = false, val proxy: Boolean = false,
        val neverAutoClose: Boolean = false, val openOnRear: Boolean = false,
        val showInRecents: Boolean = false, val lockedHomeExitLabel: String = "",
        val notificationClickLabel: String = "", val notificationClickStatus: String = "")
    private var settings by mutableStateOf(SettingsState())
    private var durationSheetVisible by mutableStateOf(false)
    private var durationValue by mutableIntStateOf(savedState?.getInt("notification_duration_draft_value", 10) ?: 10)
    private var durationUnitMinutes by mutableStateOf(savedState?.getBoolean("notification_duration_draft_minutes", false) ?: false)
    private var durationNeverAutoClose by mutableStateOf(savedState?.getBoolean("notification_duration_draft_never", false) ?: false)
    private var durationChanged by mutableStateOf(savedState?.getBoolean("notification_duration_draft_changed", false) ?: false)
    private var picker by mutableStateOf<AppPickerModel?>(null)
    private val notificationsList = savedState.listState("notifications_list")
    private val modulesList = savedState.listState("modules_list")
    private val settingsList = savedState.listState("settings_list")
    private val aboutList = savedState.listState("about_list")
    private val rearSettingsLists = (10..12).associateWith { savedState.listState("rear_settings_$it") }
    private val tutorialGuide = TutorialGuide(activity, savedState)
    private val permissionChecks = PermissionChecksPage(activity, savedState)
    private val tutorialPermissions = PermissionChecksPage(activity, savedState, "tutorial_permissions_scroll")
    private val screenshotSettings = RearScreenshotPage(activity, savedState)
    private val densitySettings = DensityCompatibilityPage(activity, savedState)
    private val notificationDetailsLists = (20..21).associateWith { savedState.listState("notification_details_$it") }
    private var uriConflict by mutableStateOf(false)
    private var navigationHeight by mutableIntStateOf(0)
    private var page by mutableIntStateOf(savedState?.getInt("native_page", 0)?.let {
        if (savedState.getInt("native_route_version", 0) >= 2) it
        else when (it) { 2 -> 3; 3 -> 100; else -> it }
    } ?: 0)
    private var subpage by mutableIntStateOf(savedState?.getInt("native_subpage", 0) ?: 0)
    private var navigationRequest by mutableIntStateOf(0)
    private var insets by mutableStateOf(SafeInsets())
    private val wallpaperMods = WallpaperModNavigation(activity, savedState, { page }, actions::navigate,
        activity::hasWallpaperEditor)

    fun parentRoute(route: Int): Int? = wallpaperMods.parentRoute(route)
    fun canPreviewBack(): Boolean = wallpaperMods.canPreviewBack()
    fun consumeOwnedBack(): Boolean = wallpaperMods.consumeOwnedBack()

    private val routeMotion = AppRouteMotion(activity, page, ::parentRoute)
    private val transitionPair get() = routeMotion.transitionPair
    private var pickerExitSnapshot by mutableStateOf<AppPickerExitSnapshot?>(null)
    private val listState = LazyListState(savedState?.getInt("compose_home_index", 0) ?: 0, savedState?.getInt("compose_home_offset", 0) ?: 0)
    private val mainRailBottomMargin = 12.dp
    private val mainRailContentGap = 8.dp

    init {
        durationSheetVisible = savedState?.getBoolean("notification_duration_sheet", false) ?: false
    }

    private val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val dark = isSystemInDarkTheme()
            val routeMotionProgress = routeMotion.Progress(page) {
                pickerExitSnapshot = null
                wallpaperMods.releaseUnusedPages()
            }
            val motionDurationScale = routeMotion.durationScale
            val pager = rememberPagerState(initialPage = NavigationMotion.routeForTab(page, ::parentRoute), pageCount = { 4 })
            LaunchedEffect(navigationRequest, motionDurationScale) {
                val target = page
                val tab = NavigationMotion.routeForTab(target, ::parentRoute)
                if (pager.currentPage != tab || kotlin.math.abs(pager.currentPageOffsetFraction) > .001f) {
                    if (motionDurationScale == 0f) pager.scrollToPage(tab)
                    else pager.animateScrollToPage(tab, animationSpec = tween(250, easing = pageEasing))
                }
            }
            LaunchedEffect(pager) {
                snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
                    .filterNotNull().collect { if (page != 100 && page < 10) actions.settled(it) }
            }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(page) { if (page != 100) focusManager.clearFocus(force = true) }
            AppTheme {
                val pair = transitionPair ?: parentRoute(page)?.let { NavigationRoutePair(it, page) }
                val progress = if (transitionPair == null && pair != null) 1f else routeMotionProgress.coerceIn(0f, 1f)
                AppNavigationFrame(
                    Modifier.fillMaxSize(),
                    content = { TabSurface(it, dark, pager, pair?.parentRoute, progress) },
                    navigation = { Navigation(pager, pair, progress) },
                    foreground = { SecondarySurfaces(pair, progress) },
                )
            }
        }
    }
    fun getView(): View = view
    fun closeOwnedPages() {
        screenshotSettings.close()
        wallpaperMods.close()
    }
    fun showPicker(model: AppPickerModel?) { picker = model; if (model != null) pickerExitSnapshot = null }
    @JvmName("showPickerExitSnapshot")
    internal fun showPickerExitSnapshot(snapshot: AppPickerExitSnapshot) { pickerExitSnapshot = snapshot }
    @JvmName("syncSettledPage")
    internal fun syncSettledPage(target: Int) {
        if (page in 0..3 && target in 0..3) page = target
    }
    fun updateSettings(selected: Int, listener: Boolean, posting: Boolean, duration: Int, updates: String, checking: Boolean, proxy: Boolean,
                       neverAutoClose: Boolean = false, openOnRear: Boolean = false,
                       showInRecents: Boolean = false, lockedHomeExitLabel: String = "",
                       notificationClickLabel: String = "", notificationClickStatus: String = "") {
        settings = SettingsState(selected, listener, posting, duration, updates, checking, proxy, neverAutoClose,
            openOnRear, showInRecents, lockedHomeExitLabel, notificationClickLabel, notificationClickStatus)
    }
    fun updateUriConflict(conflict: Boolean) { uriConflict = conflict }
    fun showPage(target: Int) {
        val previous = page
        wallpaperMods.prepare(target)
        page = target
        routeMotion.showPage(previous, target)
        wallpaperMods.onRouteChanged(previous, target)
        if (routeMotion.transitionPair == null) wallpaperMods.releaseUnusedPages()
        navigationRequest++
    }
    fun startBackPreview(target: Int, initialProgress: Float) = routeMotion.startBackPreview(page, target, initialProgress)
    fun updateBackPreview(progress: Float, frameTimeMillis: Long) = routeMotion.updateBackPreview(progress, frameTimeMillis)
    fun cancelBackPreview() = routeMotion.cancelBackPreview(page)
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, ime: Int) { insets = SafeInsets(left, top, right, bottom, ime) }
    fun updateHome(status: String, summary: String, action: String, dpi: Int, rotation: Int, busy: Boolean, values: Map<String, Boolean>) {
        state = HomeState(status, summary, action, dpi, rotation, busy, values.toMap())
    }
    fun saveState(bundle: Bundle) {
        wallpaperMods.save(bundle)
        bundle.putBoolean("notification_duration_sheet", durationSheetVisible)
        bundle.putInt("notification_duration_draft_value", durationValue)
        bundle.putBoolean("notification_duration_draft_minutes", durationUnitMinutes)
        bundle.putBoolean("notification_duration_draft_never", durationNeverAutoClose)
        bundle.putBoolean("notification_duration_draft_changed", durationChanged)
        bundle.saveList("notifications_list", notificationsList)
        bundle.saveList("modules_list", modulesList)
        bundle.saveList("settings_list", settingsList)
        bundle.saveList("about_list", aboutList)
        rearSettingsLists.forEach { (route, state) -> bundle.saveList("rear_settings_$route", state) }
        tutorialGuide.save(bundle)
        permissionChecks.save(bundle)
        tutorialPermissions.save(bundle)
        screenshotSettings.save(bundle)
        densitySettings.save(bundle)
        notificationDetailsLists.forEach { (route, state) -> bundle.saveList("notification_details_$route", state) }
        bundle.putInt("compose_home_index", listState.firstVisibleItemIndex)
        bundle.putInt("compose_home_offset", listState.firstVisibleItemScrollOffset)
        bundle.putInt("native_subpage", subpage)
        bundle.putInt("native_route_version", 2)
    }
    fun showDurationSheet(seconds: Int, neverAutoClose: Boolean) {
        if (!durationSheetVisible) {
            durationValue = if (seconds < 60) seconds.coerceIn(1, 60) else ((seconds.toLong() + 59L) / 60L).coerceIn(1L, 60L).toInt()
            durationUnitMinutes = seconds >= 60
            durationNeverAutoClose = neverAutoClose
            durationChanged = false
        }
        durationSheetVisible = true
    }
    private fun s(key: String) = NativeStrings.get(activity, key)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun TabSurface(modifier: Modifier, dark: Boolean, pager: PagerState, parentRoute: Int?, progress: Float) {
        val d = LocalDensity.current
        val direction = if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f
        val nested = parentRoute != null && parentRoute !in 0..3
        val participatingPages by remember(pager) {
            derivedStateOf {
                pager.layoutInfo.visiblePagesInfo.mapTo(mutableSetOf()) { it.index } + pager.currentPage + pager.targetPage
            }
        }
        val imeVisible = insets.ime > insets.bottom
        val tailPadding = if (imeVisible) {
            16.dp
        } else with(d) {
            val railHeight = if (navigationHeight > 0) navigationHeight.toDp() else 72.dp
            insets.bottom.toDp() + mainRailBottomMargin + railHeight + mainRailContentGap
        }
        val background = AppPalette.background()
        Box(modifier.fillMaxSize().background(background)
            .padding(pageInsets(bottom = if (imeVisible) insets.ime else 0)).clipToBounds(),
            contentAlignment = Alignment.TopCenter) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, userScrollEnabled = page in 0..3,
                modifier = Modifier.fillMaxSize().clipToBounds()
                    // Keep the pager composed for restoration, but paint only the actual parent behind a nested route.
                    .drawWithContent { if (!nested) drawContent() }
                    .testTag("main_pager").semantics { if (page >= 10 || page == 100) hideFromAccessibility() }) { index ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val pageActive = page == index
                    if (index in participatingPages) {
                        val motionParent = parentRoute == index || (transitionPair == null && page == index)
                        val pageFrameModifier = Modifier.widthIn(max = 680.dp).fillMaxSize()
                            .then(if (motionParent) Modifier.retreatingRoute(progress, direction) else Modifier)
                            .then(inactiveRouteModifier(!pageActive))
                            .testTag(if (motionParent) "navigation_motion_parent" else "pager_page_$index")
                        // Keep the motion probe on its own Box node; appending a second
                        // testTag to the LazyColumn would replace its established list tag.
                        Box(pageFrameModifier, contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.fillMaxSize()
                                .then(if (pageActive) Modifier else Modifier.clearAndSetSemantics {})) {
                                MainTabFocus(tailPadding) {
                                    val body = Modifier.fillMaxSize()
                                    when (index) {
                                        0 -> Home(dark, body, tailPadding)
                                        1 -> Notifications(body, tailPadding)
                                        2 -> Modules(body, tailPadding, active = pageActive)
                                        3 -> Settings(body, tailPadding)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun pageInsets(bottom: Int = maxOf(insets.bottom, insets.ime)): PaddingValues = with(LocalDensity.current) {
        PaddingValues(
            start = insets.left.toDp(),
            top = insets.top.toDp(),
            end = insets.right.toDp(),
            bottom = bottom.toDp(),
        )
    }

    @Composable private fun SecondarySurfaces(pair: NavigationRoutePair?, progress: Float) {
        if (pair == null) return
        val direction = if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (pair.parentRoute !in 0..3) {
                SecondaryPane(pair.parentRoute, page == pair.parentRoute,
                    Modifier.retreatingRoute(progress, direction).testTag("navigation_motion_parent"))
            }
            SecondaryPane(pair.childRoute, page == pair.childRoute,
                Modifier.enteringRoute(progress, direction).testTag("navigation_motion_child"))
        }
    }

    @Composable private fun SecondaryPane(route: Int, active: Boolean, motion: Modifier) {
        AppRoutePane(active, motion, contentInsets = pageInsets()) {
            when (route) {
                in WALLPAPER_STUDIO_ROUTE..WALLPAPER_WORKS_ROUTE,
                in MOD_MANAGEMENT_ROUTE..MOD_DETAIL_ROUTE -> wallpaperMods.Content(route, active)
                100 -> AppPickerSlot(
                    model = if (active) picker else null,
                    snapshot = if (active) null else pickerExitSnapshot,
                )
                in 10..12 -> BackSubpage(route)
                SCREENSHOT_SETTINGS_ROUTE -> screenshotSettings.Content({ actions.navigate(0) }, active)
                DENSITY_SETTINGS_ROUTE -> densitySettings.Content({ actions.navigate(10) }, active)
                in 20..21 -> NotificationsSubpage(route)
                40 -> AboutDetails()
                TUTORIAL_ROUTE -> tutorialGuide.Content({ actions.navigate(3) }) {
                    actions.navigate(TUTORIAL_PERMISSION_ROUTE)
                }
                PERMISSION_SETTINGS_ROUTE -> permissionChecks.Content({ actions.navigate(3) }, active)
                TUTORIAL_PERMISSION_ROUTE -> tutorialPermissions.Content({ actions.navigate(TUTORIAL_ROUTE) }, active)
            }
        }
    }

    @Composable private fun BoxScope.Navigation(pager: PagerState, pair: NavigationRoutePair?, progress: Float) {
        val navigationProgress = if (pair == null) 1f else 1f - progress
        val shouldRender = page in 0..3 || (transitionPair?.parentRoute ?: -1) in 0..3
        if (!shouldRender || insets.ime > insets.bottom) return
        val bottom = with(LocalDensity.current) { insets.bottom.toDp() }
        Box(Modifier.align(Alignment.BottomCenter)
            .padding(start = 20.dp, end = 20.dp, bottom = bottom + mainRailBottomMargin)
            .widthIn(max = 360.dp).fillMaxWidth()
            .graphicsLayer {
                alpha = navigationProgress
                translationY = size.height * .42f * (1f - navigationProgress)
                // Partial alpha composites the rail offscreen. Keep its soft elevation
                // inside that layer rather than clipping it to the capsule's layout box.
                outsets = LayerOutsets(24.dp)
            }
            // Only the revealed portion can intercept a committed pop; a predictive preview
            // never makes the parent tabs interactive.
            .then(inactiveRouteModifier(page !in 0..3))) {
            AppBottomNavigation(
                progress = { pager.currentPage + pager.currentPageOffsetFraction },
                pagerIsScrollInProgress = { pager.isScrollInProgress },
                pagerTargetPage = { pager.targetPage },
                selectedIndex = pager.currentPage,
                tabsActive = page in 0..3,
                labels = listOf(s("nav_screen"), s("nav_notifications"), s("nav_modules"), s("nav_settings")),
                descriptions = listOf(s("screen_title"), s("notifications_title"), s("nav_modules"), s("nav_settings")),
                onSelected = actions::navigate,
                modifier = Modifier.fillMaxWidth().onSizeChanged { navigationHeight = it.height },
            ) { index, color -> Icon(listOf("screen", "bell", "apps", "more")[index], color, Modifier.size(23.dp)) }
        }
    }

    private val pageEasing = CubicBezierEasing(.77f, 0f, .175f, 1f)

    @Composable private fun Home(dark: Boolean, modifier: Modifier, tailPadding: Dp) {
        val virtualRearCast = rememberVirtualRearCastMode()
        LazyColumn(modifier.testTag("home_list"), state = listState,
            contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, tailPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("screen_title"), s("screen_subtitle")) }
            if (uriConflict) item("uri_conflict") {
                AppCard { AppRow(s("uri_conflict_title"), s("uri_conflict_help").replace("{0}", BuildConfig.URI_SCHEME + "://"), action = actions::automation) }
            }
            item("connection") {
                ConnectionStatusCard(
                    dark = dark,
                    status = state.status,
                    summary = state.summary,
                    action = if (state.status == "connection_ready") "refresh" else state.action,
                )
            }
            item("cast") { EntryCard("nav_cast", s(if (virtualRearCast) "nav_cast_virtual" else "nav_cast_direct"), "cast_settings_entry") { actions.navigate(10) } }
            item("wake") {
                val screenOn = s(if (state.values["keep_screen_on_enabled"] == true) "status_on" else "status_off")
                val coveredWake = s(if (state.values["always_wakeup_enabled"] == true) "status_on" else "status_off")
                val proximity = s(if (state.values["proximity_sensor_enabled"] == true) "status_on" else "status_off")
                EntryCard("nav_wake", s("nav_wake_state").replace("{0}", screenOn).replace("{1}", coveredWake).replace("{2}", proximity), "wake_settings_entry") { actions.navigate(11) }
            }
            item("charging") {
                val animation = s(if (state.values["charging_animation_enabled"] == true) "status_on" else "status_off")
                val always = when {
                    state.values["charging_always_on_enabled"] != true -> s("status_off")
                    state.values["charging_animation_enabled"] == true -> s("status_on")
                    else -> activity.getString(R.string.ui_charging_standby)
                }
                EntryCard("nav_charging", s("nav_charging_state").replace("{0}", animation).replace("{1}", always), "charging_settings_entry") { actions.navigate(12) }
            }
            if (activity.hasWallpaperEditor()) item("ai_rear_wallpaper") {
                AppCard {
                    AppRow(activity.getString(R.string.ai_rear_title), activity.getString(R.string.ai_rear_entry),
                        modifier = Modifier.testTag("ai_rear_wallpaper_entry"),
                        action = { actions.navigate(WALLPAPER_STUDIO_ROUTE) })
                }
            }
            item("screenshots") { EntryCard("nav_screenshots", activity.getString(R.string.rear_screenshot_entry_help), "screenshot_settings_entry") {
                actions.navigate(SCREENSHOT_SETTINGS_ROUTE)
            } }
        }
    }

    @Composable private fun EntryCard(title: String, help: String, tag: String, action: () -> Unit) {
        AppCard { AppRow(s(title), s(help), Modifier.testTag(tag), action = action) }
    }

    @Composable private fun ConnectionTitle(dark: Boolean, status: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(AppPalette.accent().copy(alpha = if (dark) .18f else .09f)).clearAndSetSemantics { },
                contentAlignment = Alignment.Center) {
                Icon("screen", AppPalette.accent(), Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.values["privilege_root"] == true) "ROOT" else "SHIZUKU",
                    fontSize = 10.sp, color = AppPalette.secondary(), fontWeight = FontWeight.SemiBold)
                Text(s(status), fontSize = 19.sp, color = AppPalette.text(), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            }
        }
    }
    @Composable private fun PrivilegeSourceButton() {
        TextButton(activity.getString(R.string.privilege_source_value, if (state.values["privilege_root"] == true) "Root" else "Shizuku"),
            actions::privilege, minHeight = 48.dp,
            enabled = !state.busy && state.status != "privilege_switching",
            modifier = Modifier.fillMaxWidth().testTag("privilege_source"))
    }
    @Composable private fun ConnectionStatusCard(dark: Boolean, status: String, summary: String, action: String) {
        AppCard(Modifier.testTag("connection_status_card")) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
                val contentWidth = maxWidth
                if (contentWidth >= 520.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConnectionTitle(dark, status)
                            Text(s(summary), fontSize = 13.sp, color = AppPalette.secondary())
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrivilegeSourceButton()
                            ConnectButton(Modifier.fillMaxWidth(), action)
                            DetailsButton(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConnectionTitle(dark, status)
                        Text(s(summary), fontSize = 13.sp, color = AppPalette.secondary())
                        PrivilegeSourceButton()
                        if (contentWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ConnectButton(Modifier.fillMaxWidth(), action)
                                DetailsButton(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ConnectButton(Modifier.weight(1f), action)
                                DetailsButton(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun WakeModeChoices() {
        val keep = state.values["keep_screen_on_enabled"] == true
        val always = state.values["always_wakeup_enabled"] == true
        val current = if (always) 2 else if (keep) 1 else 0
        Column(Modifier.selectableGroup()) {
            WakeModeOption(0, s("status_off"), null, current) {
                if (always) actions.toggle("always_wakeup_enabled", "setAlwaysWakeUpEnabled", false)
                else if (keep) actions.toggle("keep_screen_on_enabled", "setKeepScreenOnEnabled", false)
            }
            AppDivider()
            WakeModeOption(1, s("keep_screen_on"), s("keep_help"), current) {
                actions.toggle("keep_screen_on_enabled", "setKeepScreenOnEnabled", true)
            }
            AppDivider()
            WakeModeOption(2, s("always_wake_up"), s("always_help"), current) {
                actions.toggle("always_wakeup_enabled", "setAlwaysWakeUpEnabled", true)
            }
        }
    }

    @Composable private fun WakeModeOption(index: Int, title: String, detail: String?, current: Int, select: () -> Unit) {
        val isSelected = current == index
        AppRow(title, detail, Modifier.testTag("wake_mode_$index").selectable(
            selected = isSelected, role = Role.RadioButton, onClick = { if (!isSelected) select() }
        ), trailing = {
            RadioButton(isSelected, null, Modifier.clearAndSetSemantics {})
        })
    }

    @Composable private fun BackSubpage(route: Int) {
        val (title, backTag) = when (route) {
            10 -> s("nav_cast") to "cast_settings_back"
            11 -> s("nav_wake") to "wake_settings_back"
            else -> s("nav_charging") to "charging_settings_back"
        }
        val list = rearSettingsLists.getValue(route)
        AppPageContent(title, { actions.navigate(0) }, backTag, scrollState = list) { modifier, header ->
            LazyColumn(modifier.testTag("rear_settings_${route}_list"), state = list,
                contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (route) {
                    10 -> item("cast_options") {
                        val virtual = rememberVirtualRearCastMode()
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppCard {
                                RearCastModeToggle(virtual)
                                if (BuildConfig.VIRTUAL_CAST_ENABLED && android.os.Build.VERSION.SDK_INT >= 33) {
                                    AppDivider()
                                    AppRow(activity.getString(R.string.rear_density_title), activity.getString(R.string.rear_density_summary),
                                        modifier = Modifier.testTag("density_compatibility_entry"), action = {
                                            actions.navigate(DENSITY_SETTINGS_ROUTE)
                                        })
                                }
                                AppDivider()
                                RearCameraGapBatteryToggle()
                                AppDivider()
                                RearCameraStatusToggle()
                            }
                            Box(Modifier.fillMaxWidth().testTag("physical_settings_reveal")) {
                                AppSettingsReveal(!virtual) {
                                    AppCard {
                                        AppRow(s("screen_size"), s("dpi_help"), Modifier.testTag("physical_dpi_entry"), action = actions::dpi,
                                            trailing = { Text(if (state.dpi > 0) state.dpi.toString() else "—", color = AppPalette.accent()) })
                                        AppDivider()
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (state.rotation !in 0..3) Text(activity.getString(R.string.ui_rotation_unavailable),
                                                color = AppPalette.secondary(), fontSize = 13.sp)
                                            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                repeat(4) { index ->
                                                    val selected = state.rotation == index
                                                    TextButton("${index * 90}°", onClick = { if (!selected) actions.rotation(index) },
                                                        enabled = !state.busy, modifier = Modifier
                                                            .testTag("physical_rotation_$index").semantics { this.selected = selected },
                                                        colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                                                        minHeight = 48.dp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    11 -> item("wake_options") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppCard { SettingsToggle("proximity_sensor", "proximity_help", "proximity_sensor_enabled", "setProximitySensorEnabled") }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppSection(activity.getString(R.string.ui_wake_behavior))
                                AppCard { WakeModeChoices() }
                                Text(s("warning_burn_in"), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = AppPalette.secondary())
                            }
                        }
                    }
                    12 -> item("charging_options") {
                        AppCard {
                            SettingsToggle("charging_animation", "charging_help", "charging_animation_enabled", "toggleChargingService")
                            AppDivider()
                            val animationOn = state.values["charging_animation_enabled"] == true
                            val alwaysOn = state.values["charging_always_on_enabled"] == true
                            val detail = when {
                                animationOn -> s("charging_always_help")
                                alwaysOn -> activity.getString(R.string.ui_charging_always_paused)
                                else -> activity.getString(R.string.ui_charging_requires_animation)
                            }
                            AppToggle(s("charging_always_on"), detail, alwaysOn,
                                modifier = Modifier.testTag("charging_always_on_enabled"),
                                onChange = { actions.toggle("charging_always_on_enabled", "setChargingAlwaysOnEnabled", it) })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Modules(modifier: Modifier, tailPadding: Dp, active: Boolean) {
        wallpaperMods.ModTab(modifier, modulesList, tailPadding, active, heading = { PageTitle(s("nav_modules")) })
    }

    @Composable private fun Notifications(modifier: Modifier, tailPadding: Dp) {
        LazyColumn(modifier.testTag("notifications_list"), state = notificationsList,
            contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, tailPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("notifications_title"), s("notifications_subtitle")) }
            item("delivery") {
                AppCard {
                    SettingsToggle("notification_service", "notification_help", "notification_service_enabled", "toggleNotificationService")
                    AppDivider()
                    AppRow(s("notification_apps"), s("selected_count").replace("{0}", settings.selected.toString()),
                        modifier = Modifier.testTag("notification_apps_entry"), action = actions::apps)
                    if (AiRearWallpaperSupport.supported()) {
                        val lockedHomeAvailable = settings.openOnRear && settings.notificationClickStatus.isEmpty()
                        val lockedHomePaused = settings.notificationClickStatus.ifEmpty {
                            activity.getString(R.string.ui_notification_locked_home_paused_action)
                        }
                        AppDivider()
                        AppRow(s("notification_locked_home"),
                            if (lockedHomeAvailable) s("notification_locked_home_summary") else lockedHomePaused,
                            modifier = Modifier.testTag("notification_locked_home_entry"),
                            action = actions::lockedHomeApps)
                        AppDivider()
                        AppRow(activity.getString(R.string.ui_locked_home_exit_title),
                            if (lockedHomeAvailable) settings.lockedHomeExitLabel
                            else "${settings.lockedHomeExitLabel}\n$lockedHomePaused",
                            modifier = Modifier.testTag("notification_locked_home_exit_mode"),
                            action = actions::lockedHomeExitMode)
                    }
                    AppDivider()
                    AppRow(s("notification_access"), s("notification_access_help"), action = { actions.action("openNotificationListenerSettings") },
                        value = s(if (settings.listener) "access_granted" else "access_pending"))
                    if (!settings.posting) {
                        AppDivider()
                        AppRow(s("notification_permission"), s("notification_permission_help"), action = { actions.action("requestNotificationPermission") })
                    }
                }
            }
            item("privacy") { AppCard { AppRow(s("privacy_section"), null, action = { actions.navigate(20) }) } }
            item("rules") { AppCard { AppRow(s("notification_rules"), s("duration_help"),
                Modifier.testTag("notification_rules_entry"), action = { actions.navigate(21) }) } }
        }
    }

    @Composable private fun NotificationsSubpage(route: Int) {
        val privacy = route == 20
        val title = s(if (privacy) "privacy_section" else "notification_rules")
        val list = notificationDetailsLists.getValue(route)
        AppPageContent(title, { actions.navigate(1) }, if (privacy) "notification_privacy_back" else "notification_rules_back", scrollState = list) { modifier, header ->
            LazyColumn(modifier.testTag(if (privacy) "notification_privacy_list" else "notification_rules_list"), state = list,
                contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    AppCard {
                        if (privacy) {
                            SettingsToggle("hide_notification_title", "hide_title_help", "notification_privacy_hide_title", "setNotificationPrivacyHideTitle")
                            AppDivider()
                            SettingsToggle("hide_notification_content", "hide_content_help", "notification_privacy_hide_content", "setNotificationPrivacyHideContent")
                            AppDivider()
                            SettingsToggle("notification_dark_mode", "dark_notification_help", "notification_dark_mode", "setNotificationDarkMode")
                        } else {
                            SettingsToggle("follow_system_dnd", "dnd_help", "notification_follow_dnd_mode", "setFollowDndMode")
                            AppDivider()
                            SettingsToggle("only_when_locked", "upside_help", "notification_only_when_locked", "setOnlyWhenLocked")
                            AppDivider()
                            AppRow(s("notification_duration_title"), activity.getString(R.string.ui_notification_duration_scope), action = actions::duration,
                                value = durationSummary(settings.duration))
                            AppDivider()
                            AppRow(s("notification_click_action"), settings.notificationClickStatus.ifEmpty { null }, action = actions::clickAction,
                                value = settings.notificationClickLabel)
                        }
                    }
                }
            }
        }
        DurationBottomSheet()
    }

    private fun durationSummary(seconds: Int): String = when {
        settings.neverAutoClose -> s("notification_never_close")
        seconds < 60 -> s("seconds_value").replace("{0}", seconds.toString())
        seconds % 60 == 0 -> s("minutes_value").replace("{0}", (seconds / 60).toString())
        else -> s("seconds_value").replace("{0}", seconds.toString())
    }

    @Composable private fun DurationBottomSheet() {
        val configuration = LocalConfiguration.current
        val shortWindow = configuration.screenHeightDp < 500
        val sideBySide = shortWindow && configuration.screenWidthDp >= 600
        // Reserve room for the sheet title and system bars; the body can scroll but not the actions.
        val maxContentHeight = (configuration.screenHeightDp.dp - 92.dp).coerceAtLeast(130.dp)
        WindowBottomSheet(show = durationSheetVisible, title = s("notification_duration_title"),
            onDismissRequest = { durationSheetVisible = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxContentHeight).navigationBarsPadding().imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sideBySide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            DurationWheelRow(true, Modifier.weight(1.15f))
                            Box(Modifier.weight(1f)) { DurationPersistentToggle(showHelp = false) }
                        }
                        Text(s("notification_never_close_help"),
                            modifier = Modifier.padding(horizontal = 12.dp).testTag("notification_duration_persist_help"),
                            fontSize = 13.sp, color = AppPalette.secondary())
                    } else {
                        DurationWheelRow(shortWindow, Modifier.fillMaxWidth())
                        DurationPersistentToggle()
                    }
                    if (!durationChanged &&
                        settings.duration != NotificationDisplayOptions.durationSeconds(durationValue, durationUnitMinutes)) {
                        Text(activity.getString(R.string.ui_notification_duration_current,
                            s("seconds_value").replace("{0}", settings.duration.toString())),
                            fontSize = 13.sp, color = AppPalette.secondary())
                    }
                }
                AppActionPair(
                    s("cancel"), { durationSheetVisible = false },
                    s("confirm"), {
                        val seconds = NotificationDisplayOptions.durationAfterConfirmation(
                            settings.duration, durationChanged, durationValue, durationUnitMinutes)
                        durationSheetVisible = false
                        if (durationChanged || durationNeverAutoClose != activity.getSharedPreferences("mrss_settings", android.content.Context.MODE_PRIVATE)
                                .getBoolean("notification_never_auto_close", false))
                            actions.saveDuration(if (durationChanged) seconds else -1, durationNeverAutoClose)
                    },
                    secondaryModifier = Modifier.testTag("notification_duration_cancel"),
                    primaryModifier = Modifier.testTag("notification_duration_confirm"),
                )
            }
        }
    }

    @Composable private fun DurationWheelRow(shortWindow: Boolean, modifier: Modifier) {
        val rows = if (shortWindow) 3 else 5
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            top.yukonga.miuix.kmp.basic.NumberPicker(
                value = durationValue, onValueChange = { durationValue = it; durationChanged = true },
                modifier = Modifier.weight(1f).testTag("notification_duration_value"),
                range = 1..60, visibleItemCount = rows)
            top.yukonga.miuix.kmp.basic.NumberPicker(
                value = if (durationUnitMinutes) 1 else 0,
                onValueChange = { durationUnitMinutes = it == 1; durationChanged = true },
                modifier = Modifier.weight(1.25f).testTag("notification_duration_unit"),
                range = 0..1, visibleItemCount = rows,
                label = { if (it == 0) s("unit_seconds") else s("unit_minutes") })
        }
    }

    @Composable private fun DurationPersistentToggle(showHelp: Boolean = true) {
        AppToggle(s("notification_never_close"), if (showHelp) s("notification_never_close_help") else null,
            durationNeverAutoClose,
            modifier = Modifier.testTag("notification_duration_persist"), onChange = { durationNeverAutoClose = it })
    }

    @Composable private fun Settings(modifier: Modifier, tailPadding: Dp) {
        LazyColumn(modifier.testTag("settings_list"), state = settingsList,
            contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, tailPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("nav_settings")) }
            item("app_preferences") {
                AppSection(activity.getString(R.string.app_preferences_title))
                AppCard {
                    AppToggle(activity.getString(R.string.recents_hide_title),
                        activity.getString(R.string.recents_visibility_summary), !settings.showInRecents,
                        modifier = Modifier.testTag("hide_in_recents")) { hidden ->
                        actions.showInRecents(!hidden)
                    }
                }
            }
            item("runtime") {
                AppSection(s("settings_runtime"))
                AppCard {
                    AppRow(activity.getString(R.string.ui_permissions_title), activity.getString(R.string.ui_permissions_menu_help),
                        modifier = Modifier.testTag("permission_checks_menu"), action = actions::permissions)
                    AppDivider()
                    AppRow(s("diagnostics"), null, action = actions::details)
                }
            }
            item("usage") {
                AppSection(s("settings_usage"))
                AppCard {
                    AppRow(s("tutorial"), activity.getString(R.string.tutorial_entry_help), action = actions::tutorial)
                    AppDivider()
                    AppRow(s("uri_title"), s("uri_help").replace("mrss://", BuildConfig.URI_SCHEME + "://"), action = actions::automation)
                }
            }
            item("support") {
                AppSection(s("settings_support"))
                AppCard {
                    AppRow(s("check_updates"), settings.updates, enabled = !settings.checking, action = actions::updates,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    AppDivider()
                    AppToggle(s("update_proxy"), s("update_proxy_help"), settings.proxy, !settings.checking,
                        onChange = actions::proxy)
                    AppDivider()
                    AppRow(s("about"), s("nav_about_help"), Modifier.testTag("about_entry"), action = { actions.navigate(40) })
                }
            }
        }
    }

    @Composable private fun AboutDetails() {
        AppPageContent(s("about"), { actions.navigate(3) }, "about_details_back", scrollState = aboutList) { modifier, header ->
            LazyColumn(modifier.testTag("about_list"), state = aboutList,
                contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("brand") {
                    Row(Modifier.padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_app_logo),
                            contentDescription = null, modifier = Modifier.size(64.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ReMRSS", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppPalette.text())
                            Text(s("version_label").replace("{0}", BuildConfig.VERSION_NAME), fontSize = 14.sp, color = AppPalette.secondary())
                        }
                    }
                }
                item("project") {
                    AppCard {
                        AppRow(s("project_repository"), "GitHub · ${ProjectInfo.REPOSITORY_NAME}\n" +
                            s("project_maintainer").replace("{0}", ProjectInfo.MAINTAINER), action = { actions.action("openProject") })
                        AppDivider()
                        AppRow(s("issue_feedback"), "GitHub Issues", action = { actions.action("openFeedback") })
                        AppDivider()
                        AppRow(s("author_anti"), null, action = { actions.action("openOriginalProject") })
                    }
                }
            }
        }
    }

    @Composable private fun PageTitle(title: String, subtitle: String? = null) {
        Column(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppPalette.text(), modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = AppPalette.secondary())
        }
    }
    @Composable private fun SettingsToggle(title: String, help: String, key: String, method: String) {
        AppToggle(s(title), s(help), state.values[key] ?: false) { actions.toggle(key, method, it) }
    }

    @Composable private fun Group(content: @Composable ColumnScope.() -> Unit) {
        AppCard(content = content)
    }
    @Composable private fun ConnectButton(modifier: Modifier, action: String = state.action) {
        TextButton(s(action), actions::connect, modifier, enabled = !state.busy && state.status !in setOf("privilege_root_authorizing", "connection_binding", "privilege_switching"), minHeight = 48.dp, cornerRadius = 16.dp, insideMargin = PaddingValues(10.dp), colors = if (action == "refresh") ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColorsPrimary())
    }
    @Composable private fun DetailsButton(modifier: Modifier) {
        TextButton(s("connection_details"), actions::details, modifier, minHeight = 48.dp, cornerRadius = 16.dp, insideMargin = PaddingValues(10.dp))
    }
    @Composable private fun Section(key: String, dark: Boolean) {
        Text(s(key), fontSize = 13.sp, color = AppPalette.secondary(), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp).semantics { heading() })
    }
    @Composable private fun Label(key: String, dark: Boolean, modifier: Modifier = Modifier) {
        Text(s(key), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppPalette.text(), modifier = modifier)
    }
    @Composable private fun Description(key: String, dark: Boolean) { Text(s(key), fontSize = 12.sp, color = AppPalette.secondary()) }
    @Composable private fun ToggleRow(icon: String, title: String, description: String, key: String, method: String, dark: Boolean) {
        val checked = state.values[key] ?: false
        Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = { actions.toggle(key, method, it) })
            .padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, dark)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Label(title, dark); Description(description, dark) }
            // The entire row is one accessible switch; Miuix owns the visual thumb/spring.
            Switch(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
        }
    }
    @Composable private fun Divider(dark: Boolean) {
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(0.5.dp).background(if (dark) Color.White.copy(alpha = .07f) else Color.Black.copy(alpha = .045f)))
    }
    @Composable private fun IconTile(name: String, dark: Boolean) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (dark) Color(0xFF303039) else Color(0xFFF2F4F9)), contentAlignment = Alignment.Center) { Icon(name, AppPalette.secondary(), Modifier.size(20.dp)) }
    }
}

/** Small consistent stroke icons without bundling an unused icon font/library. */
@Composable private fun Icon(name: String, baseTint: Color, modifier: Modifier, tintProvider: (() -> Color)? = null) {
    Canvas(modifier) {
        val tint = tintProvider?.invoke() ?: baseTint
        val scale = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x * scale, y * scale), Offset(x2 * scale, y2 * scale), 1.7f * scale, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val stroke = Stroke(1.7f * scale)
        when (name) {
            "screen" -> { drawRoundRect(tint, Offset(5f*scale,2f*scale), Size(14f*scale,20f*scale), androidx.compose.ui.geometry.CornerRadius(3f*scale), style=stroke); drawRoundRect(tint,Offset(8f*scale,5f*scale),Size(8f*scale,7f*scale), androidx.compose.ui.geometry.CornerRadius(2f*scale),style=stroke);line(10f,19f,14f,19f) }
            "bell" -> { drawArc(tint,180f,180f,false,Offset(6f*scale,4f*scale),Size(12f*scale,12f*scale),style=stroke);line(6f,10f,5f,17f);line(18f,10f,19f,17f);line(5f,17f,19f,17f);drawArc(tint,0f,180f,false,Offset(10f*scale,18f*scale),Size(4f*scale,3f*scale),style=stroke) }
            "info" -> { drawCircle(tint,9f*scale,style=stroke);line(12f,11f,12f,17f);drawCircle(tint,1f*scale,Offset(12f*scale,7f*scale)) }
            "more" -> {
                drawCircle(tint, 9.5f * scale, style = stroke)
                for (x in 8..16 step 4) {
                    drawCircle(tint, 1.2f * scale, Offset(x * scale, 12f * scale))
                }
            }
            "sun" -> { drawCircle(tint,4f*scale,style=stroke);repeat(8){ val a=it*Math.PI/4;line(12f+8f*kotlin.math.cos(a).toFloat(),12f+8f*kotlin.math.sin(a).toFloat(),12f+10f*kotlin.math.cos(a).toFloat(),12f+10f*kotlin.math.sin(a).toFloat()) } }
            "eye" -> { drawOval(tint,Offset(2f*scale,6f*scale),Size(20f*scale,12f*scale),style=stroke);drawCircle(tint,3f*scale,style=stroke) }
            "charge" -> { line(14f,2f,6f,13f);line(6f,13f,12f,13f);line(12f,13f,10f,22f);line(10f,22f,18f,10f);line(18f,10f,12f,10f);line(12f,10f,14f,2f) }
            "rotate" -> { drawArc(tint,35f,285f,false,Offset(3f*scale,3f*scale),Size(18f*scale,18f*scale),style=stroke);line(20f,4f,20f,10f);line(20f,10f,14f,10f) }
            else -> { for (x in listOf(4f,14f)) for(y in listOf(4f,14f)) drawRoundRect(tint,Offset(x*scale,y*scale),Size(6f*scale,6f*scale),androidx.compose.ui.geometry.CornerRadius(scale),style=stroke) }
        }
    }
}
