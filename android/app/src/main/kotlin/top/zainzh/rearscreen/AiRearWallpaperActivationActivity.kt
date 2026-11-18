package top.zainzh.rearscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import java.io.File
import org.json.JSONObject

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
        if (!AiRearWallpaperSupport.shellReady(this) || intent.action != Intent.ACTION_VIEW ||
            !getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val from = launchedFromPackage
        if (from !in setOf("com.xiaomi.subscreencenter", "com.xiaomi.mirror")) return
        val renderer = runCatching { packageManager.getApplicationInfo(checkNotNull(from), 0) }.getOrNull() ?: return
        if (renderer.uid != launchedFromUid ||
            renderer.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return
        val uri = intent.data ?: return
        if (uri.scheme != "remrss-ai-activation" || uri.isOpaque || uri.port != -1 ||
            uri.userInfo != null || uri.fragment != null || uri.encodedQuery != null) return
        val prefs = getSharedPreferences(AiRearWallpaperBinding.PREFS, MODE_PRIVATE)
        val root = File(noBackupFilesDir, "ai-rear-wallpaper")
        val encoded = prefs.getString(AiRearWallpaperBinding.ACTIVE, null) ?: return
        val installed = runCatching { AiRearWallpaperJournal.activeId(root) }.getOrNull() ?: return
        val tile = AiRearWallpaperBinding.decode(encoded)?.tile(uri.encodedAuthority, uri.encodedPath, installed)
            ?: return
        val stillBound = java.util.function.BooleanSupplier {
            prefs.getString(AiRearWallpaperBinding.ACTIVE, null) == encoded &&
                runCatching { AiRearWallpaperJournal.activeId(root) == installed }.getOrDefault(false)
        }
        val remote = TaskServiceManager.peekConnectedServiceForWallpaper() ?: return
        val panel = runCatching { JSONObject(remote.rearDisplayState) }.getOrNull() ?: return
        val rear = panel.optInt("selectedId", -1)
        val unique = panel.optString("selectedUnique")
        if (rear <= 0 || panel.optString("reason") != "secondary_internal_panel" || !unique.startsWith("local:"))
            return
        when (tile) {
            AiRearGrid.Tile.AllApps -> RearWallpaperStandby.requestAllApps(rear, unique, remote, stillBound)
            AiRearGrid.Tile.Empty -> Unit
            is AiRearGrid.Tile.App -> {
                val target = ComponentName.unflattenFromString(tile.component)
                if (AiRearAppCatalog.isCastTarget(this, target, packageName))
                    RearWallpaperStandby.requestApp(rear, unique, target, remote, stillBound)
            }
        }
    }
}
