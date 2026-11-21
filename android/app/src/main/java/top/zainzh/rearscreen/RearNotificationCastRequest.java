package top.zainzh.rearscreen;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** In-process, single-use handoff. The DUMP-protected Activity receives only the ticket ID. */
final class RearNotificationCastRequest {
    static final String EXTRA = "rear_notification_open_ticket";
    static final String PREF = "notification_open_on_rear";
    private static final long HANDOFF_TIMEOUT_MS = 8_000L;
    private static final AtomicReference<RearNotificationCastRequest> pending = new AtomicReference<>();

    final long session;
    final int rearDisplay;
    final String packageName;
    private final String ticket = UUID.randomUUID().toString();
    private final PendingIntent action;
    private final long expires;
    private final AtomicBoolean sent = new AtomicBoolean();

    RearNotificationCastRequest(long session, int rearDisplay, long clickedAt, PendingIntent action) {
        this.expires = clickedAt + HANDOFF_TIMEOUT_MS;
        this.session = session;
        this.rearDisplay = rearDisplay;
        this.action = action;
        this.packageName = action.getCreatorPackage();
    }

    static boolean unlocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    boolean usable(Context context) {
        var settings = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        return SystemClock.uptimeMillis() < expires && unlocked(context)
                && RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, session)
                && settings.getBoolean(PREF, false) && !settings.getBoolean("notification_dismiss_only", false);
    }

    boolean dispatch(ITaskService remote, String hostPackage) throws Exception {
        RearNotificationCastRequest previous = pending.get();
        if (previous != null && SystemClock.uptimeMillis() < previous.expires) return false;
        if (!pending.compareAndSet(previous, this)) return false;
        new Handler(Looper.getMainLooper()).postAtTime(() -> pending.compareAndSet(this, null), expires);
        boolean dispatched = false;
        try {
            dispatched = remote.executeShellCommand("am start -f 0x10000000 --display " + rearDisplay
                    + " --windowingMode 1 -n " + hostPackage + "/" + VirtualRearHostActivity.class.getName()
                    + " --es " + EXTRA + " " + ticket + " --ez managed true");
            return dispatched;
        } finally {
            if (!dispatched) pending.compareAndSet(this, null);
        }
    }

    static RearNotificationCastRequest accept(Context host, String ticket, int displayId) {
        RearNotificationCastRequest request = pending.get();
        if (request == null || !request.ticket.equals(ticket) || request.rearDisplay != displayId
                || !request.usable(host)) return null;
        return pending.compareAndSet(request, null) ? request : null;
    }

    /** Executed in the visible host's process, never with the backend's shell/root identity. */
    boolean launch(Activity host, View root, int virtualDisplayId) {
        Display display = host.getDisplay();
        if (!usable(host) || host.isFinishing() || host.isDestroyed() || display == null
                || display.getDisplayId() != rearDisplay || display.getState() != Display.STATE_ON
                || root == null || !root.isShown() || !root.isAttachedToWindow()
                || root.getWindowVisibility() != View.VISIBLE
                || virtualDisplayId <= 0 || virtualDisplayId == rearDisplay) return false;
        PendingIntent live = NotificationService.originalActivityAction(session);
        if (!action.equals(live) || !sent.compareAndSet(false, true)) return false;
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(virtualDisplayId)
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
            action.send(options.toBundle());
            return true; // The backend verifies the actual task, not this transport acknowledgement.
        } catch (PendingIntent.CanceledException | SecurityException rejected) {
            return false;
        }
    }
}
