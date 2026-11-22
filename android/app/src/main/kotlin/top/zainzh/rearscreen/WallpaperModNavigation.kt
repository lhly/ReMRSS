package top.zainzh.rearscreen

import androidx.compose.ui.unit.Dp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.zainzh.rearscreen.platform.mods.ModConfigPage
import top.zainzh.rearscreen.platform.mods.ModPreviewPage
import top.zainzh.rearscreen.platform.mods.ModSettingsPage

/** Owns launcher and Mod pages for both the settings tabs and the independent wallpaper entry. */
internal class WallpaperModNavigation(
    private val activity: ComponentActivity,
    savedState: Bundle?,
    private val currentRoute: () -> Int,
    private val navigate: (Int) -> Unit,
    private val canConfigureWallpaper: () -> Boolean,
    private val standalone: Boolean = false,
    private val entryModId: String? = null,
    wallpaperState: Bundle? = savedState?.getBundle("native_wallpaper"),
) {
    private var wallpaper: AiRearWallpaperEditor? = null
    private var modManagement: ModSettingsPage? = null
    private var modConfig: ModConfigPage? = null
    private var modPreview: ModPreviewPage? = null
    private var modWallpaper: AiRearWallpaperEditor? = null
    private var modWallpaperId = savedState?.getString("native_mod_wallpaper_id")
    private var modManagementParent = if (standalone) WALLPAPER_THEMES_ROUTE
        else savedState?.getInt("native_mod_management_parent")
            ?.takeIf { it == 2 || it == WALLPAPER_THEMES_ROUTE } ?: 2
    private val page get() = currentRoute()

    fun parentRoute(route: Int): Int? = when (route) {
        MOD_MANAGEMENT_ROUTE -> modManagementParent
        MOD_DETAIL_ROUTE -> managementListRoute()
        else -> NavigationMotion.parentRoute(route)
    }

    private fun managementListRoute() =
        if (!standalone && modManagementParent == 2) 2 else MOD_MANAGEMENT_ROUTE

    private fun openModDetail() {
        if (!standalone && page == 2) modManagementParent = 2
        else if (page != MOD_MANAGEMENT_ROUTE) return
        navigate(MOD_DETAIL_ROUTE)
    }

    private fun containsRoute(route: Int, ancestor: Int) = NavigationMotion.containsRoute(route, ancestor, ::parentRoute)

    private fun needsWallpaper(route: Int) = containsRoute(route, WALLPAPER_STUDIO_ROUTE)

    private fun openWallpaper(state: Bundle? = null) {
        if (wallpaper == null) {
            wallpaper = AiRearWallpaperEditor(activity, state, entryModId = entryModId, navigate = navigate,
                openModManagement = ::openModManagement)
        }
    }

    fun openModManagement(focusMod: String?) {
        if (page != WALLPAPER_THEMES_ROUTE) return
        modManagementParent = page
        val current = modManagement
        if (current == null) modManagement = makeModManagement(null, focusMod)
        else if (focusMod != null) current.focus(focusMod)
        navigate(MOD_MANAGEMENT_ROUTE)
    }

    private fun makeModManagement(state: Bundle?, focusMod: String? = null) =
        ModSettingsPage(activity, state, focusMod,
            navigateBack = { navigate(modManagementParent) },
            openDetail = ::openModDetail,
            canConfigureWallpaper = canConfigureWallpaper,
            openConfig = ::openModConfig,
            openPreview = ::openModPreview,
            openWallpaper = ::openModWallpaper)

    private fun openModConfig(modId: String) {
        if (page != MOD_DETAIL_ROUTE) return
        if (modConfig?.modId != modId) {
            modConfig?.close()
            modConfig = createModConfig(modId, null)
        }
        navigate(MOD_CONFIG_ROUTE)
    }

    private fun createModConfig(modId: String, state: Bundle?) =
        ModConfigPage(activity, modId, state) {
            if (page == MOD_CONFIG_ROUTE && modConfig?.modId == modId) navigate(MOD_DETAIL_ROUTE)
        }

    private fun openModPreview(modId: String) {
        if (page != MOD_DETAIL_ROUTE) return
        if (modPreview?.modId != modId) {
            modPreview?.close()
            modPreview = createModPreview(modId, null)
        }
        navigate(MOD_PREVIEW_ROUTE)
    }

    private fun createModPreview(modId: String, state: Bundle?) =
        ModPreviewPage(activity, modId, state) { navigate(MOD_DETAIL_ROUTE) }

    private fun openModWallpaper(modId: String) {
        if (page != MOD_DETAIL_ROUTE) return
        if (modWallpaper == null || modWallpaperId != modId) {
            modWallpaper?.close()
            modWallpaperId = modId
            modWallpaper = createModWallpaper(modId, null)
        }
        navigate(MOD_WALLPAPER_STUDIO_ROUTE)
    }

    private fun createModWallpaper(modId: String, state: Bundle?) =
        AiRearWallpaperEditor(activity, state, entryModId = modId, navigate = { target ->
            val route = when (target) {
                0 -> MOD_DETAIL_ROUTE
                WALLPAPER_STUDIO_ROUTE -> MOD_WALLPAPER_STUDIO_ROUTE
                WALLPAPER_SHORTCUTS_ROUTE -> MOD_WALLPAPER_SHORTCUTS_ROUTE
                WALLPAPER_WORKS_ROUTE -> MOD_WALLPAPER_WORKS_ROUTE
                else -> error("Unexpected route for a Mod wallpaper: $target")
            }
            navigate(route)
        }, imageResultKey = "rear_wallpaper_mod_image", openModManagement = ::openModManagement)

    fun releaseUnusedPages() {
        if (!needsWallpaper(page)) {
            wallpaper?.close()
            wallpaper = null
        }
        if (page != MOD_CONFIG_ROUTE) {
            modConfig?.close()
            modConfig = null
        }
        if (page != MOD_PREVIEW_ROUTE) {
            modPreview?.close()
            modPreview = null
        }
        if (!isModWallpaperRoute(page)) {
            modWallpaper?.close()
            modWallpaper = null
            modWallpaperId = null
        }
        if (standalone && !containsRoute(page, MOD_MANAGEMENT_ROUTE)) {
            modManagement?.close()
            modManagement = null
        }
    }

    fun canPreviewBack(): Boolean = when {
        isWallpaperRoute(page) -> wallpaper?.canNavigateBack != false
        isModWallpaperRoute(page) -> modWallpaper?.canNavigateBack != false
        page == MOD_MANAGEMENT_ROUTE || page == MOD_DETAIL_ROUTE || !standalone && page == 2 -> modManagement?.canPreviewBack != false
        else -> true
    }

    fun consumeOwnedBack(): Boolean = when {
        isWallpaperRoute(page) -> wallpaper?.consumeBack() == true
        isModWallpaperRoute(page) -> modWallpaper?.consumeBack() == true
        page == MOD_MANAGEMENT_ROUTE || page == MOD_DETAIL_ROUTE || !standalone && page == 2 -> modManagement?.consumeBack() == true
        else -> false
    }

    init {
        if (needsWallpaper(page)) openWallpaper(wallpaperState)
        if (!standalone || containsRoute(page, MOD_MANAGEMENT_ROUTE)) {
            modManagement = makeModManagement(savedState?.getBundle("native_mod_management"))
        }
        // Older bundles saved the child ID directly, before there was a detail route.
        val restoredChildId = when {
            page == MOD_CONFIG_ROUTE -> savedState?.getString("native_mod_config_id")
            page == MOD_PREVIEW_ROUTE -> savedState?.getString("native_mod_preview_id")
            isModWallpaperRoute(page) -> modWallpaperId
            else -> null
        }
        restoredChildId?.let { modManagement?.selectDetail(it) }
        if (page == MOD_DETAIL_ROUTE || restoredChildId != null) {
            // The inactive detail is drawn behind a restored child during predictive back.
            modManagement?.reload()
        }
        if (page == MOD_CONFIG_ROUTE) {
            modConfig = createModConfig(savedState?.getString("native_mod_config_id").orEmpty(),
                savedState?.getBundle("native_mod_config")).also { it.setRouteActive(true) }
        }
        if (page == MOD_PREVIEW_ROUTE) {
            modPreview = createModPreview(savedState?.getString("native_mod_preview_id").orEmpty(),
                savedState?.getBundle("native_mod_preview")).also { it.setRouteActive(true) }
        }
        if (isModWallpaperRoute(page)) {
            modWallpaperId?.let { modWallpaper = createModWallpaper(it, savedState?.getBundle("native_mod_wallpaper")) }
        }
    }

    fun prepare(target: Int) {
        if (needsWallpaper(target)) openWallpaper()
    }

    fun onRouteChanged(previous: Int, target: Int) {
        if (previous == MOD_MANAGEMENT_ROUTE && target == WALLPAPER_THEMES_ROUTE) wallpaper?.refreshThemes()
        modConfig?.setRouteActive(target == MOD_CONFIG_ROUTE)
        modPreview?.setRouteActive(target == MOD_PREVIEW_ROUTE)
    }

    fun save(bundle: Bundle) {
        if (needsWallpaper(page)) wallpaper?.let { editor ->
            bundle.putBundle("native_wallpaper", Bundle().also(editor::save))
        }
        modManagement?.let { manager ->
            bundle.putInt("native_mod_management_parent", modManagementParent)
            bundle.putBundle("native_mod_management", Bundle().also(manager::save))
        }
        if (page == MOD_CONFIG_ROUTE) modConfig?.let {
            bundle.putString("native_mod_config_id", it.modId)
            bundle.putBundle("native_mod_config", Bundle().also(it::save))
        }
        if (page == MOD_PREVIEW_ROUTE) modPreview?.let {
            bundle.putString("native_mod_preview_id", it.modId)
            bundle.putBundle("native_mod_preview", Bundle().also(it::save))
        }
        if (isModWallpaperRoute(page)) modWallpaper?.let {
            bundle.putString("native_mod_wallpaper_id", modWallpaperId)
            bundle.putBundle("native_mod_wallpaper", Bundle().also(it::save))
        }
    }

    fun close() {
        wallpaper?.close()
        wallpaper = null
        modManagement?.close()
        modManagement = null
        modConfig?.close()
        modConfig = null
        modPreview?.close()
        modPreview = null
        modWallpaper?.close()
        modWallpaper = null
    }

    @Composable
    fun ModTab(
        modifier: Modifier, state: LazyListState, tailPadding: Dp, active: Boolean, heading: @Composable () -> Unit,
    ) {
        checkNotNull(modManagement).TabContent(modifier, state, tailPadding, active, heading)
    }

    @Composable
    fun Content(route: Int, active: Boolean) {
        when (route) {
        in WALLPAPER_STUDIO_ROUTE..WALLPAPER_WORKS_ROUTE -> wallpaper?.Content(route, active)
        MOD_MANAGEMENT_ROUTE -> modManagement?.Content(active)
        MOD_DETAIL_ROUTE -> modManagement?.Detail(active) { navigate(managementListRoute()) }
        MOD_CONFIG_ROUTE -> modConfig?.Content()
        MOD_PREVIEW_ROUTE -> modPreview?.Content()
        MOD_WALLPAPER_STUDIO_ROUTE -> modWallpaper?.Content(WALLPAPER_STUDIO_ROUTE, active)
        MOD_WALLPAPER_SHORTCUTS_ROUTE -> modWallpaper?.Content(WALLPAPER_SHORTCUTS_ROUTE, active)
        MOD_WALLPAPER_WORKS_ROUTE -> modWallpaper?.Content(WALLPAPER_WORKS_ROUTE, active)
        }
    }
}
