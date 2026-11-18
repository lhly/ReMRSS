package top.zainzh.rearscreen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import rikka.shizuku.Shizuku;

/** The official AI carrier and touch handoff have been verified only on this device family. */
final class AiRearWallpaperSupport {
    private AiRearWallpaperSupport() {}

    static boolean supported() {
        return BuildConfig.VIRTUAL_CAST_ENABLED && "popsicle".equals(Build.DEVICE);
    }

    static boolean shellReady(Context context) {
        if (!supported() || TaskServiceManager.isRoot(context)) return false;
        try {
            return Shizuku.pingBinder() && Shizuku.getUid() == 2000
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException disconnected) {
            return false;
        }
    }
}
