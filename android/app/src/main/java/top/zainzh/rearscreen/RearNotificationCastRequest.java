package top.zainzh.rearscreen;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.View;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** In-process, single-use handoff. The DUMP-protected Activity receives only the ticket ID. */
final class RearNotificationCastRequest {
    static final String EXTRA = "rear_notification_open_ticket";
    static final String PREF = "notification_open_on_rear";
    static final String HOME_APPS_PREF = "notification_locked_home_apps";
    private static final AtomicReference<RearNotificationCastRequest> pending = new AtomicReference<>();

    final long session;
    final int rearDisplay;
    final String packageName;
    final boolean locked;
    final boolean homeOnly;
    private final String notificationKey;
    private final long postTime;
    private final String ticket = UUID.randomUUID().toString();
    private final PendingIntent action;
    private final long expires;
    private final AtomicBoolean sent = new AtomicBoolean();
    private final AtomicBoolean sourceStarted = new AtomicBoolean();
    private int dispatchedDisplay = -1; // Set and checked only on the visible host's main thread.

    RearNotificationCastRequest(Context context, long session, int rearDisplay, long clickedAt,
            boolean locked, StatusBarNotification notice, PendingIntent action) {
        this.expires = clickedAt + (locked ? 12_000L : 8_000L);
        this.session = session;
        this.rearDisplay = rearDisplay;
        this.locked = locked;
        this.action = action;
        this.packageName = notice.getPackageName();
        this.homeOnly = locked && lockedHomeAuthorized(context, packageName);
        this.notificationKey = notice.getKey();
        this.postTime = notice.getPostTime();
    }

    static boolean unlocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    static int primaryDisplayState(Context context) {
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        Display primary = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        return primary == null ? Display.STATE_UNKNOWN : primary.getState();
    }

    static boolean lockedWithPrimaryInactive(Context context) {
        return lockedWithPrimaryInactive(context, primaryDisplayState(context));
    }

    static boolean lockedWithPrimaryInactive(Context context, int primaryState) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && mayPresentWhileLocked(primaryState, keyguard.isKeyguardLocked(), keyguard.isDeviceLocked());
    }

    static boolean mayPresentWhileLocked(int primaryState, boolean keyguardLocked, boolean deviceLocked) {
        // AOD belongs to the system. It does not make a securely locked main display interactive.
        return keyguardLocked && deviceLocked && (primaryState == Display.STATE_OFF
                || primaryState == Display.STATE_DOZE || primaryState == Display.STATE_DOZE_SUSPEND);
    }

    boolean mayKeepLockedOutput(Context context, int primaryState, boolean verifiedHomeRunning,
            boolean userPresent) {
        if (!homeOnly || !verifiedHomeRunning)
            return !userPresent && lockedWithPrimaryInactive(context, primaryState);

        var settings = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean(PREF, false) || settings.getBoolean("notification_dismiss_only", false)
                || !lockedHomeAuthorized(context, packageName)) return false;

        RearNotificationExitMode mode = RearNotificationExitMode.read(settings);
        if (mode == RearNotificationExitMode.MANUAL) return true;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && mode.keepsOutput(primaryState, keyguard.isKeyguardLocked(),
                keyguard.isDeviceLocked(), userPresent);
    }

    boolean powerAndLockUnchanged(Context context) {
        return locked ? lockedWithPrimaryInactive(context) : unlocked(context);
    }

    private static boolean lockedHomeAuthorized(Context context, String packageName) {
        if (!AiRearWallpaperSupport.shellReady(context)) return false;
        var settings = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        return settings.getStringSet("notification_selected_apps", java.util.Collections.emptySet()).contains(packageName)
                && settings.getStringSet(HOME_APPS_PREF, java.util.Collections.emptySet()).contains(packageName);
    }

    boolean usable(Context context) {
        var settings = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        return SystemClock.uptimeMillis() < expires && powerAndLockUnchanged(context)
                && RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, session)
                && settings.getBoolean(PREF, false) && !settings.getBoolean("notification_dismiss_only", false)
                && (!homeOnly || lockedHomeAuthorized(context, packageName));
    }

    private StatusBarNotification liveNotification() {
        StatusBarNotification notice = NotificationService.currentRearOpenNotification(session);
        if (notice == null || !notificationKey.equals(notice.getKey()) || postTime != notice.getPostTime()
                || !packageName.equals(notice.getPackageName())
                || !Objects.equals(action, NotificationService.originalActivityAction(notice))
                || (locked && notice.getNotification().visibility == Notification.VISIBILITY_SECRET)) return null;
        return notice;
    }

    boolean dispatch(Context context, ITaskService remote, int noticeTask) throws Exception {
        if (liveNotification() == null) return false;
        RearNotificationCastRequest previous = pending.get();
        if (previous != null && SystemClock.uptimeMillis() < previous.expires) return false;
        if (!pending.compareAndSet(previous, this)) return false;
        new Handler(Looper.getMainLooper()).postAtTime(() -> pending.compareAndSet(this, null), expires);
        boolean dispatched = false;
        try {
            dispatched = locked
                    ? remote.stageLockedRearNotificationHost(rearDisplay, noticeTask, ticket,
                        new ISceneTaskActionGate.Stub() {
                            @Override public boolean isCurrent() {
                                return pending.get() == RearNotificationCastRequest.this
                                        && usable(context) && liveNotification() != null;
                            }
                            @Override public boolean isCurrentBeforeTargetMove() { return isCurrent(); }
                        })
                    : remote.executeShellCommand("am start -f 0x10000000 --display " + rearDisplay
                        + " --windowingMode 1 -n " + context.getPackageName() + "/" + VirtualRearHostActivity.class.getName()
                        + " --es " + EXTRA + " " + ticket + " --ez managed true");
            return dispatched;
        } finally {
            if (!dispatched) pending.compareAndSet(this, null);
        }
    }

    private static RearNotificationCastRequest find(Context host, String ticket) {
        RearNotificationCastRequest request = pending.get();
        return request != null && request.ticket.equals(ticket) && request.usable(host) ? request : null;
    }

    static boolean waitingOnStaging(Context host, String ticket, int displayId) {
        RearNotificationCastRequest request = find(host, ticket);
        return request != null && request.locked && displayId > 0 && displayId != request.rearDisplay;
    }

    static RearNotificationCastRequest accept(Context host, String ticket, int displayId) {
        RearNotificationCastRequest request = find(host, ticket);
        if (request == null || request.rearDisplay != displayId) return null;
        return pending.compareAndSet(request, null) ? request : null;
    }

    private boolean hostStillOwnsRear(Activity host, View root) {
        Display display = host.getDisplay();
        return usable(host) && !host.isFinishing() && !host.isDestroyed() && display != null
                && display.getDisplayId() == rearDisplay && display.getState() == Display.STATE_ON
                && root != null && root.isAttachedToWindow();
    }

    boolean currentOnRear(Activity host, View root) {
        return hostStillOwnsRear(host, root) && root.isShown()
                && root.getWindowVisibility() == View.VISIBLE && liveNotification() != null;
    }

    boolean mayVerifyLaunch(Activity host, View root, int displayId) {
        return locked && dispatchedDisplay > 0 && dispatchedDisplay == displayId && hostStillOwnsRear(host, root);
    }

    private boolean canLaunch(Activity host, View root, int display) {
        return display > 0 && display != rearDisplay && currentOnRear(host, root);
    }

    /** Executed by the visible host, never using the backend's shell identity to send the token. */
    boolean launch(Activity host, View root, int virtualDisplayId) {
        if (homeOnly || !canLaunch(host, root, virtualDisplayId) || action == null
                || !sent.compareAndSet(false, true)) return false;
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(virtualDisplayId)
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
            action.send(options.toBundle());
            dispatchedDisplay = virtualDisplayId;
            return true; // The backend verifies the actual task, not this transport acknowledgement.
        } catch (PendingIntent.CanceledException | SecurityException rejected) {
            return false;
        }
    }

    boolean launchSource(Activity host, View root, int virtualDisplayId) {
        if (!locked || !canLaunch(host, root, virtualDisplayId) || !sourceStarted.compareAndSet(false, true)) return false;
        Intent entry;
        if (homeOnly) {
            android.content.ComponentName launcher = AiRearAppCatalog.independentLauncher(host, packageName);
            if (launcher == null) return false;
            // Start a fresh public entry only; never copy the notification's action, extras or URI.
            entry = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(launcher);
        } else {
            entry = host.getPackageManager().getLaunchIntentForPackage(packageName);
        }
        if (entry == null || entry.getComponent() == null
                || !packageName.equals(entry.getComponent().getPackageName())) return false;
        try {
            ActivityInfo launcher = host.getPackageManager().getActivityInfo(entry.getComponent(), 0);
            if (!launcher.enabled || !launcher.exported || !packageName.equals(launcher.packageName)
                    || !canStartIndependentLauncher(launcher.launchMode, launcher.documentLaunchMode, launcher.flags)
                    || !canLaunch(host, root, virtualDisplayId)) return false;
            entry.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(virtualDisplayId);
            host.startActivity(entry, options.toBundle());
            dispatchedDisplay = virtualDisplayId;
            return true;
        } catch (PackageManager.NameNotFoundException | android.content.ActivityNotFoundException
                | SecurityException rejected) {
            return false;
        }
    }

    static boolean canStartIndependentLauncher(int launchMode, int documentMode, int activityFlags) {
        // MULTIPLE_TASK does not override a launch mode that requires reusing an existing task.
        return (launchMode == ActivityInfo.LAUNCH_MULTIPLE || launchMode == ActivityInfo.LAUNCH_SINGLE_TOP)
                && documentMode == ActivityInfo.DOCUMENT_LAUNCH_NONE
                && (activityFlags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) == 0;
    }
}
