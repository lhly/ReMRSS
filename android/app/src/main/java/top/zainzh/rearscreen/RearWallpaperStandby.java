package top.zainzh.rearscreen;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.util.function.BooleanSupplier;

import org.json.JSONObject;


/** The same physical task parks, presents, and parks again without owning input in standby. */
final class RearWallpaperStandby {
    static final String PREPARE = "prepare_ai_wallpaper_host";
    static final String REAR = "ai_wallpaper_rear";
    private static final String TAG = "RearParkProbe";
    private static final String HOST_PACKAGE = BuildConfig.APPLICATION_ID;
    private enum Phase { SETTING_UP, PARKING, PARKED, ACTIVATING, PICKING, PRESENTING, RETURNING, CLOSED }
    private static RearWallpaperStandby preparedHost; // Main thread only.

    private final VirtualRearHostActivity host;
    private final int rear;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Phase phase = Phase.SETTING_UP;
    private volatile boolean windowFocused;
    private boolean stopped;
    private boolean earlyHandoff;
    private boolean handoffPaused;
    private PendingActivation pendingActivation;
    private static final class PendingActivation {
        final int rear;
        final String unique;
        final ComponentName target;
        final ITaskService remote;
        final BooleanSupplier bindingCurrent;
        final long acceptedAt = SystemClock.uptimeMillis();
        PendingActivation(int rear, String unique, ComponentName target, ITaskService remote,
                          BooleanSupplier bindingCurrent) {
            this.rear = rear;
            this.unique = unique;
            this.target = target;
            this.remote = remote;
            this.bindingCurrent = bindingCurrent;
        }
    }
    private int round;
    private ComponentName target;
    private ITaskService authority;
    private String rearUnique;
    private boolean fromAppList;
    private BooleanSupplier bindingCurrent;
    private final Runnable parkDeadline = () -> {
        if (phase == Phase.SETTING_UP || phase == Phase.PARKING) retire("park_not_acknowledged");
    };
    private final Runnable focusDeadline = () -> {
        if (phase == Phase.ACTIVATING) retire("no_rear_focus");
    };
    private final ServiceConnection standbyConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { }
        @Override public void onServiceDisconnected(ComponentName name) { retire("service_disconnected"); }
    };

    private RearWallpaperStandby(VirtualRearHostActivity host, int rear) {
        this.host = host;
        this.rear = rear;
    }

    static RearWallpaperStandby prepare(VirtualRearHostActivity host, int rear) {
        if (!enabled(host) || preparedHost != null || VirtualRearHostActivity.instance != null
                || rear <= 0 || host.getDisplay() == null || host.getDisplay().getDisplayId() != rear
                || !RearDisplay.isRear(rear) || locked(host)) return null;
        RearWallpaperStandby probe = new RearWallpaperStandby(host, rear);
        preparedHost = probe;
        host.showWallpaperStandbyWindow();
        TaskServiceManager.acquire(host, probe.standbyConnection);
        probe.main.postDelayed(probe.parkDeadline, 8_000);
        Log.i(TAG, "setup task=" + host.getTaskId() + " display=" + rear + " locked=false");
        return probe;
    }

    static String requestApp(int rear, String unique, ComponentName target, ITaskService remote,
                             BooleanSupplier bindingCurrent) {
        if (target == null) return reject("parked_target_unavailable");
        return request(rear, unique, target, remote, bindingCurrent);
    }

    static String requestAllApps(int rear, String unique, ITaskService remote, BooleanSupplier bindingCurrent) {
        return request(rear, unique, null, remote, bindingCurrent);
    }

    private static String request(int rear, String unique, ComponentName target, ITaskService remote,
                                  BooleanSupplier bindingCurrent) {
        RearWallpaperStandby probe = preparedHost;
        boolean returningToGrid = probe != null && probe.phase == Phase.RETURNING && probe.earlyHandoff;
        if (probe == null || (!returningToGrid && probe.phase != Phase.PARKED)
                || probe.host.isFinishing() || probe.rear != rear || !probe.onRear()
                || !locked(probe.host)) return reject("parked_host_unavailable");
        if (!RearCastMode.useVirtual(probe.host) || !enabled(probe.host)
                || !TaskServiceManager.isConnectedServiceCurrent(remote) || !bindingCurrent.getAsBoolean())
            return reject("parked_cast_unavailable");
        try {
            RearDisplayCastOccupancy.Result entry =
                    RearDisplayCastOccupancy.query(remote, HOST_PACKAGE, rear);
            if (entry.disposition != RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER)
                return reject("parked_cast_rear_busy");
            if (returningToGrid) {
                if (probe.pendingActivation != null) return reject("parked_cast_resume_already_queued");
                if (probe.host.getDisplay().getState() != Display.STATE_ON)
                    return reject("parked_cast_state_changed");
                probe.pendingActivation = new PendingActivation(rear, unique, target, remote, bindingCurrent);
                Log.i(TAG, "resume_queued_after_handoff task=" + probe.host.getTaskId());
                return "parked_cast_resume_queued";
            }
            for (ActivityManager.AppTask task : probe.host.getSystemService(ActivityManager.class).getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || info.taskId != probe.host.getTaskId()
                        || !probe.host.getComponentName().equals(info.baseActivity)) continue;
                if (!locked(probe.host) || probe.host.getDisplay().getState() != Display.STATE_ON)
                    return reject("parked_cast_state_changed");
                probe.target = target;
                probe.authority = remote;
                probe.rearUnique = unique;
                probe.bindingCurrent = bindingCurrent;
                probe.fromAppList = target == null;
                probe.phase = Phase.ACTIVATING;
                Log.i(TAG, "move_to_front_requested task=" + info.taskId + " source=" + probe.source() + " locked=true");
                task.moveToFront();
                probe.main.postDelayed(probe.focusDeadline, 4_500);
                return "parked_cast_resume_requested";
            }
        } catch (Exception failed) {
            Log.w(TAG, "move_to_front_error type=" + failed.getClass().getSimpleName());
            probe.retire("move_to_front_error");
            return "parked_cast_resume_denied";
        }
        probe.retire("own_task_missing");
        return "parked_host_unavailable";
    }

    void focusChanged(boolean focused) {
        windowFocused = focused;
        if (!focused || preparedHost != this || host.isFinishing() || !onRear()) return;
        if (phase == Phase.SETTING_UP) {
            if (locked(host)) { retire("locked_before_park"); return; }
            park();
        } else if (phase == Phase.ACTIVATING) {
            main.removeCallbacks(focusDeadline);
            if (!requestCurrent() || VirtualRearHostActivity.instance != null) {
                retire("parked_request_stale_before_focus");
                return;
            }
            Log.i(TAG, "same_task_focused locked=true display=" + rear + " task=" + host.getTaskId()
                    + " source=" + source());
            if (fromAppList) openPicker(false);
            else startTarget(target);
        } else if (phase == Phase.PARKED) {
            retire("unsolicited_focus");
        }
    }

    private boolean requestCurrent() {
        BooleanSupplier binding = bindingCurrent;
        return locked(host) && enabled(host) && binding != null && binding.getAsBoolean()
                && TaskServiceManager.isConnectedServiceCurrent(authority);
    }

    private void startTarget(ComponentName component) {
        target = component;
        phase = Phase.PRESENTING;
        round++;
        host.initializePreparedWallpaperCast(component);
    }

    private void openPicker(boolean failed) {
        phase = Phase.PICKING;
        host.showWallpaperAppPicker(authority, fromAppList, failed, this::dismissPicker, this::chooseApp);
        Log.i(TAG, "picker_visible task=" + host.getTaskId() + " display=" + rear + " source=" + source()
                + " failed=" + failed + " locked=" + locked(host));
    }

    private boolean chooseApp(ComponentName app) {
        if (phase != Phase.PICKING || !fromAppList || !requestCurrent() || !onRear()
                || !host.hasWindowFocus() || !AiRearAppCatalog.isCastTarget(host, app, HOST_PACKAGE)) return false;
        Log.i(TAG, "picker_selected task=" + host.getTaskId() + " package=" + app.getPackageName() + " locked=true");
        startTarget(app);
        return true;
    }

    private void dismissPicker() {
        if (phase != Phase.PICKING) return;
        Log.i(TAG, "picker_returned task=" + host.getTaskId() + " locked=" + locked(host));
        host.showWallpaperStandbyWindow();
        clearAndPark();
    }

    void afterStartFailure() {
        if (phase != Phase.PRESENTING || !requestCurrent()) {
            retire("parked_start_recovery_stale");
            return;
        }
        openPicker(true);
    }

    void afterCancelledStart() {
        if (phase == Phase.PRESENTING) clearAndPark();
    }

    private String source() { return fromAppList ? "list" : "shortcut"; }

    void resumed() { stopped = false; }

    void paused() {
        if (phase == Phase.RETURNING && earlyHandoff) handoffPaused = true;
    }

    void stopped() {
        stopped = true;
        windowFocused = false;
        if (earlyHandoff) Log.i(TAG, "rear_handoff_stopped task=" + host.getTaskId()
                + " round=" + round + " locked=" + locked(host));
        if (phase == Phase.PICKING) dismissPicker();
        else acknowledgeParked();
    }

    private void acknowledgeParked() {
        if (phase == Phase.PARKING && stopped) markParked("parked_stopped");
    }

    private void markParked(String event) {
        phase = Phase.PARKED;
        main.removeCallbacks(parkDeadline);
        Log.i(TAG, event + " task=" + host.getTaskId() + " display=" + rear
                + " round=" + round + " locked=" + locked(host));
        earlyHandoff = false;
        handoffPaused = false;
        PendingActivation next = pendingActivation;
        pendingActivation = null;
        if (next != null && SystemClock.uptimeMillis() - next.acceptedAt < 4_500)
            request(next.rear, next.unique, next.target, next.remote, next.bindingCurrent);
    }

    private void park() {
        phase = Phase.PARKING;
        if (!onRear() || !host.moveTaskToBack(false)) {
            retire("park_rejected");
            return;
        }
        Log.i(TAG, "park_requested accepted=true task=" + host.getTaskId() + " round=" + round);
        // Bound setup/return acknowledgement, never the duration of confirmed standby.
        main.removeCallbacks(parkDeadline);
        main.postDelayed(parkDeadline, 8_000);
        acknowledgeParked();
    }

    boolean beginReturn() {
        if (phase != Phase.PRESENTING) return false;
        phase = Phase.RETURNING;
        handoffPaused = false;
        Log.i(TAG, "return_requested task=" + host.getTaskId() + " round=" + round + " locked=" + locked(host));
        return true;
    }

    void outputReleased() {
        if (phase != Phase.RETURNING || earlyHandoff || !onRear() || !locked(host)) return;
        // The reader and virtual output are gone. Return visually while the backend verifies its tail.
        if (host.moveTaskToBack(false)) {
            earlyHandoff = true;
            Log.i(TAG, "rear_handoff_requested task=" + host.getTaskId()
                    + " round=" + round + " locked=" + locked(host));
        }
    }

    // Queried on the worker only after the old virtual cast and Keeper are fully released.
    boolean officialRearOwnsDisplay(ITaskService remote) {
        try {
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancy.Snapshot.parse(
                    remote.getDisplayCastOccupancy(rear), rear);
            if (!"activity_task".equals(snapshot.source) || snapshot.displayState != Display.STATE_ON)
                return false;
            String own = HOST_PACKAGE + ":" + host.getTaskId();
            boolean hidden = snapshot.tasks.stream().anyMatch(task -> own.equals(task.basePackage + ":" + task.taskId)
                    && HOST_PACKAGE.equals(task.topPackage) && task.clearlyHidden(snapshot.displayState));
            return hidden && RearDisplayCastOccupancy.evaluate(snapshot, HOST_PACKAGE, null).disposition
                    == RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER;
        } catch (Exception unavailable) { return false; }
    }

    void afterReturn(JSONObject released, boolean officialFront) {
        if (phase != Phase.RETURNING || !enabled(host)
                || !TaskServiceManager.isConnectedServiceCurrent(authority)) {
            retire("parked_return_authority_changed");
            return;
        }
        Log.i(TAG, "virtual_released host=" + host.getTaskId() + " display=" + released.optInt("display")
                + " targetTask=" + released.optInt("task") + " round=" + round + " locked=" + locked(host));
        clearAndPark(officialFront);
    }

    private void clearAndPark() { clearAndPark(false); }

    private void clearAndPark(boolean officialFront) {
        target = null;
        authority = null;
        rearUnique = null;
        bindingCurrent = null;
        fromAppList = false;
        if (earlyHandoff && handoffPaused && officialFront) {
            // HyperOS can defer onStop after the old window is already hidden. The receipt and
            // physical snapshot establish readiness without waiting for that unrelated callback.
            markParked("parked_ready");
        } else if (earlyHandoff) {
            phase = Phase.PARKING;
            main.postDelayed(parkDeadline, 8_000);
            acknowledgeParked();
        } else park();
    }

    boolean isCurrentStart(ITaskService remote) {
        ITaskService active = authority;
        return phase == Phase.PRESENTING && active != null
                && active.asBinder().equals(remote.asBinder()) && requestCurrent() && onRear() && !host.isFinishing()
                && host.getDisplay().getState() == Display.STATE_ON;
    }

    // Worker: run immediately before this round's dedicated Shizuku start transaction.
    void requireColdStart(ITaskService remote) throws Exception {
        if (!windowFocused || !isCurrentStart(remote))
            throw new IllegalStateException("parked_cast_authority_or_lock_changed");
        JSONObject panel = new JSONObject(remote.getRearDisplayState());
        if (panel.optInt("selectedId", -1) != rear || !rearUnique.equals(panel.optString("selectedUnique")))
            throw new IllegalStateException("parked_cast_display_changed");
        RearDisplayCastOccupancy.Result entry = RearDisplayCastOccupancy.query(remote, HOST_PACKAGE, rear);
        if (entry.disposition != RearDisplayCastOccupancy.OwnerDisposition.HOST
                || !(HOST_PACKAGE + ":" + host.getTaskId()).equals(entry.foreground))
            throw new IllegalStateException("parked_cast_host_not_foreground");
        if (VirtualCastUriFlow.isPhysicalCastActive() || !locked(host) || !TaskServiceManager.isConnectedServiceCurrent(remote))
            throw new IllegalStateException("parked_cast_request_stale");
        Log.i(TAG, "virtual_start_requested host=" + host.getTaskId() + " round=" + round + " locked=true");
    }

    void recordStarted(JSONObject state) {
        Log.i(TAG, "virtual_state=" + state.optString("state") + " host=" + state.optInt("host")
                + " display=" + state.optInt("display") + " targetTask=" + state.optInt("task")
                + " round=" + round + " source=" + source() + " package=" + state.optString("package")
                + " locked=" + locked(host));
    }

    static boolean isParked() {
        RearWallpaperStandby active = preparedHost;
        return active != null && active.phase == Phase.PARKED && active.onRear()
                && !active.host.isFinishing() && enabled(active.host);
    }

    static boolean ownsHost() { return preparedHost != null; }

    static void disarm() {
        if (preparedHost != null) preparedHost.retire("wallpaper_disarmed");
    }

    void close() {
        phase = Phase.CLOSED;
        pendingActivation = null;
        if (preparedHost == this) preparedHost = null;
        main.removeCallbacksAndMessages(null);
        TaskServiceManager.release(standbyConnection);
        Log.i(TAG, "closed task=" + host.getTaskId());
    }

    private boolean onRear() {
        Display display = host.getDisplay();
        return display != null && display.getDisplayId() == rear;
    }

    void retire(String reason) {
        if (phase == Phase.CLOSED || host.isFinishing()) return;
        phase = Phase.CLOSED;
        pendingActivation = null;
        main.removeCallbacksAndMessages(null);
        Log.i(TAG, "retire task=" + host.getTaskId() + " reason=" + reason);
        host.finishAndRemoveTask();
    }

    private static String reject(String reason) {
        Log.i(TAG, "resume_declined " + reason);
        return reason;
    }

    private static boolean locked(VirtualRearHostActivity host) {
        return host.getSystemService(KeyguardManager.class).isDeviceLocked();
    }

    private static boolean enabled(VirtualRearHostActivity host) {
        return AiRearWallpaperSupport.shellReady(host);
    }
}
