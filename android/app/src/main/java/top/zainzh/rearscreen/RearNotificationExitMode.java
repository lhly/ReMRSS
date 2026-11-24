package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** Main-screen transitions that close an already-running, approved lock-screen home launch. */
enum RearNotificationExitMode {
    MANUAL("manual", R.string.ui_locked_home_exit_manual),
    MAIN_UNLOCK("main_unlock", R.string.ui_locked_home_exit_unlock),
    MAIN_WAKE("main_wake", R.string.ui_locked_home_exit_wake);

    static final String PREF = "notification_locked_home_exit_mode";

    private final String value;
    final int label;

    RearNotificationExitMode(String value, int label) {
        this.value = value;
        this.label = label;
    }

    static RearNotificationExitMode fromValue(String value) {
        for (RearNotificationExitMode mode : values()) if (mode.value.equals(value)) return mode;
        return MANUAL;
    }

    static RearNotificationExitMode read(Context context) {
        return read(context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE));
    }

    static RearNotificationExitMode read(SharedPreferences settings) {
        return fromValue(settings.getString(PREF, null));
    }

    void save(Context context) {
        context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE).edit().putString(PREF, value).apply();
    }

    boolean keepsOutput(int primaryState, boolean keyguardLocked, boolean deviceLocked, boolean userPresent) {
        if (this == MANUAL) return true;
        if (userPresent) return false;
        if (this == MAIN_UNLOCK) return keyguardLocked && deviceLocked;
        return RearNotificationCastRequest.mayPresentWhileLocked(primaryState, keyguardLocked, deviceLocked);
    }
}
