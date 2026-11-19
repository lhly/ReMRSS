package top.zainzh.rearscreen

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import org.json.JSONObject
import top.zainzh.rearscreen.platform.mods.ModPackageStore

/** The wallpaper carries a slot, never a package name or shell command. */
class AiRearWallpaperActivationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (savedInstanceState == null) acceptTap()
        } finally {
            // The stock renderer addresses this no-display callback to the primary display.
            finish()
        }
    }

    private fun acceptTap() {
        if (!AiRearWallpaperSupport.supported() || intent.action != Intent.ACTION_VIEW) return
        val from = launchedFromPackage
        if (from !in setOf("com.xiaomi.subscreencenter", "com.xiaomi.mirror")) return
        val renderer = runCatching { packageManager.getApplicationInfo(checkNotNull(from), 0) }.getOrNull() ?: return
        if (renderer.uid != launchedFromUid ||
            renderer.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return
        val uri = intent.data ?: return
        if (uri.scheme != "remrss-ai-activation" || uri.isOpaque || uri.port != -1 ||
            uri.userInfo != null || uri.fragment != null || uri.encodedQuery != null) return
        val installations = AiRearWallpaperInstallations.forApp(this)
        val tap = runCatching { installations.resolve(uri.encodedAuthority, uri.encodedPath) }.getOrNull() ?: return
        val tile = tap.tile
        if (tile == AiRearGrid.Tile.Empty) return
        fun showFeedback(message: Int) = RearWallpaperStandby.showFeedback(this, RearDisplay.id(), message)
        if (!AiRearWallpaperSupport.allowsShortcut(this)) {
            showFeedback(R.string.ai_rear_unlocked_blocked)
            return
        }
        fun themeAvailable(): Boolean = tap.placement.modId?.let {
            runCatching { ModPackageStore.usableWallpaperTheme(this, it) != null }.getOrDefault(false)
        } ?: true
        if (!themeAvailable()) {
            showFeedback(R.string.ai_rear_theme_paused_short)
            return
        }
        if (!AiRearWallpaperSupport.shellReady(this)) {
            showFeedback(R.string.ai_rear_shortcuts_connect)
            return
        }
        val remote = TaskServiceManager.peekConnectedServiceForWallpaper() ?: run {
            showFeedback(R.string.ai_rear_shortcuts_not_prepared)
            return
        }
        val stillBound = java.util.function.BooleanSupplier {
            runCatching { installations.isCurrent(tap) && themeAvailable() }.getOrDefault(false)
        }
        val panel = runCatching { JSONObject(remote.rearDisplayState) }.getOrNull() ?: return
        val rear = panel.optInt("selectedId", -1)
        val unique = panel.optString("selectedUnique")
        if (rear <= 0 || panel.optString("reason") != "secondary_internal_panel" || !unique.startsWith("local:"))
            return
        val feedback = when (tile) {
            AiRearGrid.Tile.AllApps -> RearWallpaperStandby.requestAllApps(rear, unique, remote, stillBound)
            AiRearGrid.Tile.Empty -> 0
            is AiRearGrid.Tile.App -> {
                val target = ComponentName.unflattenFromString(tile.component)
                if (AiRearAppCatalog.isCastTarget(this, target, packageName))
                    RearWallpaperStandby.requestApp(rear, unique, target, remote, stillBound)
                else R.string.ai_rear_shortcuts_app_failed
            }
        }
        if (feedback != 0) RearWallpaperStandby.showFeedback(this, rear, feedback)
    }
}
