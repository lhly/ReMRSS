package top.zainzh.rearscreen;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import rikka.shizuku.Shizuku;

/** The official AI carrier and touch handoff have been verified only on this device family. */
final class AiRearWallpaperSupport {
    private static final String ALLOW_UNLOCKED = "allow_unlocked_launch";

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

    static boolean unlockedLaunchEnabled(Context context) {
        return context.getSharedPreferences(AiRearWallpaperBinding.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ALLOW_UNLOCKED, false);
    }

    static void setUnlockedLaunchEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(AiRearWallpaperBinding.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ALLOW_UNLOCKED, enabled).apply();
    }

    static boolean allowsShortcut(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && allowsShortcut(keyguard.isDeviceLocked(), unlockedLaunchEnabled(context));
    }

    static boolean allowsShortcut(boolean deviceLocked, boolean allowUnlocked) {
        return deviceLocked || allowUnlocked;
    }
}
