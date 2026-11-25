package top.zainzh.rearscreen;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.view.Display;

/** Keep the existing source-app entry for "open on main", regardless of notification presentation. */
final class NotificationMainLaunch {
    private NotificationMainLaunch() {}

    static boolean open(Activity host, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        Intent launch = host.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (Build.VERSION.SDK_INT >= 26) try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            host.startActivity(launch, options.toBundle());
            return true;
        } catch (RuntimeException unavailable) {
            // Older vendor implementations can reject ActivityOptions; the backend still pins display 0.
        }

        ComponentName component = launch.getComponent();
        if (component == null) {
            ResolveInfo resolved = host.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0);
            if (resolved == null || resolved.activityInfo == null) return false;
            component = new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
        }
        if (!packageName.equals(component.getPackageName())) return false;
        String name = component.flattenToShortString();
        // This is a resolved launcher component, not input taken from the notification body.
        if (!name.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) return false;
        ITaskService backend = NotificationService.getTaskService();
        try {
            return backend != null && backend.executeShellCommand("am start --display 0 -n '" + name + "'");
        } catch (Exception unavailable) {
            return false;
        }
    }
}
