package top.zainzh.rearscreen;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Only from an explicit tile tap. Opening guidance never authorizes or replays the feature. */
final class TilePermissionPrompt {
    private TilePermissionPrompt() {}

    static boolean needsConnection(TileService tile) {
        if (TaskServiceManager.canConnect(tile)) return false;
        if (TaskServiceManager.state(tile) == PrivilegeBackend.State.SWITCHING) {
            Toast.makeText(tile, R.string.privilege_switching, Toast.LENGTH_SHORT).show();
            return true;
        }
        open(tile, 1, new Intent(tile, MainActivity.class)
                .putExtra(MainActivity.EXTRA_NAVIGATION_TARGET, 0), R.string.privilege_service_unavailable);
        return true;
    }

    static void openOverlaySettings(TileService tile) {
        open(tile, 2, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", tile.getPackageName(), null)), R.string.ui_permission_settings_unavailable);
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private static void open(TileService tile, int request, Intent intent, int failure) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                tile.startActivityAndCollapse(PendingIntent.getActivity(tile, request, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                tile.startActivityAndCollapse(intent);
            }
        } catch (RuntimeException unavailable) {
            Toast.makeText(tile, failure, Toast.LENGTH_LONG).show();
        }
    }
}
