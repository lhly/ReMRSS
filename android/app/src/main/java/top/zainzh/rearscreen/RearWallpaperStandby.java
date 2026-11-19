package top.zainzh.rearscreen;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.StringRes;

import java.util.function.BooleanSupplier;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import org.json.JSONObject;


/** The same physical task parks, presents, and parks again without owning input in standby. */
final class RearWallpaperStandby {
    static final String PREPARE = "prepare_ai_wallpaper_host";
    static final String REAR = "ai_wallpaper_rear";
    private static final String TAG = "RearParkProbe";
    private static final String HOST_PACKAGE = BuildConfig.APPLICATION_ID;
    private enum Phase { SETTING_UP, PARKING, PARKED, ACTIVATING, PICKING, PRESENTING, RETURNING, CLOSED }
    enum Readiness { UNPREPARED, PREPARING, PREPARED }
    private static final MutableStateFlow<Readiness> readiness = StateFlowKt.MutableStateFlow(Readiness.UNPREPARED);
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
    private final Runnable focusDeadline = this::activationTimedOut;
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
        readiness.setValue(Readiness.PREPARING);
        host.showWallpaperStandbyWindow();
        TaskServiceManager.acquire(host, probe.standbyConnection);
        probe.main.postDelayed(probe.parkDeadline, 8_000);
        Log.i(TAG, "setup task=" + host.getTaskId() + " display=" + rear + " locked=false");
        return probe;
    }

    // Zero means accepted or already being handled. Otherwise return a localized status for the tap.
    @StringRes
    static int requestApp(int rear, String unique, ComponentName target, ITaskService remote,
                          BooleanSupplier bindingCurrent) {
        if (target == null) return reject("parked_target_unavailable", R.string.ai_rear_shortcuts_app_failed);
        return request(rear, unique, target, remote, bindingCurrent);
    }

    @StringRes
    static int requestAllApps(int rear, String unique, ITaskService remote, BooleanSupplier bindingCurrent) {
        return request(rear, unique, null, remote, bindingCurrent);
    }

    @StringRes
    private static int request(int rear, String unique, ComponentName target, ITaskService remote,
                               BooleanSupplier bindingCurrent) {
        RearWallpaperStandby probe = preparedHost;
        boolean returningToGrid = probe != null && probe.earlyHandoff
                && (probe.phase == Phase.RETURNING || probe.phase == Phase.PARKING);
        if (probe == null || probe.phase == Phase.CLOSED || probe.host.isFinishing()
                || probe.rear != rear || !probe.onRear())
            return reject("parked_host_unavailable", R.string.ai_rear_shortcuts_not_prepared);
        if (!returningToGrid && (probe.phase == Phase.SETTING_UP || probe.phase == Phase.PARKING))
            return reject("parked_host_not_ready", R.string.ai_rear_shortcuts_preparing);
        if (!returningToGrid && probe.phase != Phase.PARKED) return 0; // One tap already owns the transition.
        if (!AiRearWallpaperSupport.allowsShortcut(probe.host))
            return reject("parked_cast_unlocked_blocked", R.string.ai_rear_unlocked_blocked);
        if (!RearCastMode.useVirtual(probe.host))
            return reject("parked_virtual_disabled", R.string.ai_rear_shortcuts_virtual);
        if (!enabled(probe.host)
                || !TaskServiceManager.isConnectedServiceCurrent(remote) || !bindingCurrent.getAsBoolean())
            return reject("parked_cast_unavailable", R.string.ai_rear_shortcuts_not_prepared);
        try {
            RearDisplayCastOccupancy.Result entry =
                    RearDisplayCastOccupancy.query(remote, HOST_PACKAGE, rear);
            if (entry.disposition != RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER)
                return reject("parked_cast_rear_busy", R.string.ai_rear_shortcuts_rear_busy);
            if (returningToGrid) {
                if (probe.pendingActivation != null) return 0;
                if (probe.host.getDisplay().getState() != Display.STATE_ON)
                    return reject("parked_cast_state_changed", R.string.ai_rear_shortcuts_app_failed);
                probe.pendingActivation = new PendingActivation(rear, unique, target, remote, bindingCurrent);
                Log.i(TAG, "resume_queued_after_handoff task=" + probe.host.getTaskId());
                return 0;
            }
            for (ActivityManager.AppTask task : probe.host.getSystemService(ActivityManager.class).getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || info.taskId != probe.host.getTaskId()
                        || !probe.host.getComponentName().equals(info.baseActivity)) continue;
                if (!AiRearWallpaperSupport.allowsShortcut(probe.host))
                    return reject("parked_cast_unlocked_blocked", R.string.ai_rear_unlocked_blocked);
                if (probe.host.getDisplay().getState() != Display.STATE_ON)
                    return reject("parked_cast_state_changed", R.string.ai_rear_shortcuts_app_failed);
                probe.target = target;
                probe.authority = remote;
                probe.rearUnique = unique;
                probe.bindingCurrent = bindingCurrent;
                probe.fromAppList = target == null;
                probe.phase = Phase.ACTIVATING;
                Log.i(TAG, "move_to_front_requested task=" + info.taskId + " source=" + probe.source()
                        + " locked=" + locked(probe.host));
                task.moveToFront();
                probe.main.postDelayed(probe.focusDeadline, 4_500);
                return 0;
            }
        } catch (Exception failed) {
            Log.w(TAG, "move_to_front_error type=" + failed.getClass().getSimpleName());
            probe.retire("move_to_front_error");
            return R.string.ai_rear_shortcuts_app_failed;
        }
        probe.retire("own_task_missing");
        return R.string.ai_rear_shortcuts_not_prepared;
    }

    void focusChanged(boolean focused) {
        windowFocused = focused;
        if (!focused || preparedHost != this || host.isFinishing() || !onRear()) return;
        if (phase == Phase.SETTING_UP) {
            if (locked(host)) { retire("locked_before_park"); return; }
            park();
        } else if (phase == Phase.ACTIVATING) {
            main.removeCallbacks(focusDeadline);
            if (!requestBound() || VirtualRearHostActivity.instance != null) {
                retire("parked_request_stale_before_focus");
                return;
            }
            if (!AiRearWallpaperSupport.allowsShortcut(host)) { denyAndPark(); return; }
            Log.i(TAG, "same_task_focused locked=" + locked(host) + " display=" + rear + " task=" + host.getTaskId()
                    + " source=" + source());
            if (fromAppList) openPicker(false);
            else startTarget(target);
        } else if (phase == Phase.PARKED) {
            retire("unsolicited_focus");
        }
    }

    private boolean requestBound() {
        BooleanSupplier binding = bindingCurrent;
        return enabled(host) && binding != null && binding.getAsBoolean()
                && TaskServiceManager.isConnectedServiceCurrent(authority);
    }

    private boolean requestCurrent() {
        return AiRearWallpaperSupport.allowsShortcut(host) && requestBound();
    }

    private void activationTimedOut() {
        if (phase != Phase.ACTIVATING) return;
        if (requestBound()) showFeedback(host, rear, AiRearWallpaperSupport.allowsShortcut(host)
                ? R.string.ai_rear_shortcuts_app_failed : R.string.ai_rear_unlocked_blocked);
        retire("no_rear_focus");
    }

    private void denyAndPark() {
        showFeedback(host, rear, R.string.ai_rear_unlocked_blocked);
        host.showWallpaperStandbyWindow();
        clearAndPark();
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
        if (phase != Phase.PICKING || !fromAppList || !requestBound() || !onRear()
                || !host.hasWindowFocus() || !AiRearAppCatalog.isCastTarget(host, app, HOST_PACKAGE)) return false;
        if (!AiRearWallpaperSupport.allowsShortcut(host)) {
            denyAndPark();
            return true; // The rear hint handled the tap; don't also render the generic picker error.
        }
        Log.i(TAG, "picker_selected task=" + host.getTaskId() + " package=" + app.getPackageName()
                + " locked=" + locked(host));
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
        if (phase != Phase.PRESENTING || !requestBound()) {
            retire("parked_start_recovery_stale");
            return;
        }
        if (!AiRearWallpaperSupport.allowsShortcut(host)) { denyAndPark(); return; }
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
        readiness.setValue(Readiness.PREPARED);
        main.removeCallbacks(parkDeadline);
        Log.i(TAG, event + " task=" + host.getTaskId() + " display=" + rear
                + " round=" + round + " locked=" + locked(host));
        earlyHandoff = false;
        handoffPaused = false;
        PendingActivation next = pendingActivation;
        pendingActivation = null;
        if (next != null && SystemClock.uptimeMillis() - next.acceptedAt < 4_500) {
            int feedback = request(next.rear, next.unique, next.target, next.remote, next.bindingCurrent);
            if (feedback != 0) showFeedback(host, rear, feedback);
        }
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
        if (phase != Phase.RETURNING || earlyHandoff || !onRear()) return;
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
        if (VirtualCastUriFlow.isPhysicalCastActive() || !isCurrentStart(remote))
            throw new IllegalStateException("parked_cast_request_stale");
        Log.i(TAG, "virtual_start_requested host=" + host.getTaskId() + " round=" + round
                + " locked=" + locked(host));
    }

    void recordStarted(JSONObject state) {
        Log.i(TAG, "virtual_state=" + state.optString("state") + " host=" + state.optInt("host")
                + " display=" + state.optInt("display") + " targetTask=" + state.optInt("task")
                + " round=" + round + " source=" + source() + " package=" + state.optString("package")
                + " locked=" + locked(host));
    }

    static StateFlow<Readiness> readiness() { return readiness; }

    static void disarm() {
        if (preparedHost != null) preparedHost.retire("wallpaper_disarmed");
    }

    void close() {
        phase = Phase.CLOSED;
        pendingActivation = null;
        if (preparedHost == this) {
            preparedHost = null;
            readiness.setValue(Readiness.UNPREPARED);
        }
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

    static void showFeedback(Context source, int rear, @StringRes int message) {
        if (rear <= Display.DEFAULT_DISPLAY) return;
        RearWallpaperStandby probe = preparedHost;
        Display display = probe != null && probe.rear == rear && probe.onRear()
                ? probe.host.getDisplay() : source.getSystemService(DisplayManager.class).getDisplay(rear);
        if (display == null || !display.isValid() || display.getState() != Display.STATE_ON) return;
        try {
            Toast.makeText(source.createDisplayContext(display), message, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException | IllegalStateException gone) {
            Log.w(TAG, "rear_feedback_display_gone");
        }
    }

    @StringRes
    private static int reject(String reason, @StringRes int message) {
        Log.i(TAG, "resume_declined " + reason);
        return message;
    }

    private static boolean locked(VirtualRearHostActivity host) {
        return host.getSystemService(KeyguardManager.class).isDeviceLocked();
    }

    private static boolean enabled(VirtualRearHostActivity host) {
        return AiRearWallpaperSupport.shellReady(host);
    }
}
