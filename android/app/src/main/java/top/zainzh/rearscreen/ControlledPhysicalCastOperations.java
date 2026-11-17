package top.zainzh.rearscreen;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import top.zainzh.rearscreen.platform.LegacyDisplayOccupancy;
import top.zainzh.rearscreen.platform.PhysicalReplacementLifecycle;

/** Exact idle-display direct-cast start/return. It never replaces an occupied physical owner. */
public final class ControlledPhysicalCastOperations {
    private static final String TAG = "ControlledPhysicalCast";
    private static final long PHYSICAL_REPLACEMENT_READINESS_WINDOW_MS = 400L;

    public static final class StartResult {
        public final String sessionId, failure;
        public final long ownerGeneration;
        public final boolean committed, indeterminate, keeperStarted;
        private StartResult(String sessionId, long ownerGeneration, boolean committed,
                            boolean indeterminate, boolean keeperStarted, String failure) {
            this.sessionId = sessionId; this.ownerGeneration = ownerGeneration; this.committed = committed;
            this.indeterminate = indeterminate; this.keeperStarted = keeperStarted; this.failure = failure;
        }
    }

    public static final class ReturnResult {
        public final String result;
        public final boolean stopped;
        ReturnResult(String result, boolean stopped) { this.result = result; this.stopped = stopped; }
    }

    /** One privileged snapshot binds the selected ID and its stable unique identity together. */
    static final class SelectedRearIdentity {
        final int selectedId;
        final String selectedUnique;
        final String identity;
        private SelectedRearIdentity(int selectedId, String selectedUnique) {
            this.selectedId = selectedId;
            this.selectedUnique = selectedUnique;
            this.identity = selectedId + ":" + selectedUnique;
        }
        boolean matches(int expectedId, String expectedIdentity) {
            return expectedId > 0 && selectedId == expectedId && identity.equals(expectedIdentity);
        }
    }

    enum ExactTaskPlacement { REAR, MAIN, ABSENT, UNKNOWN }

    static String readinessFailure(boolean keeperClaimed, boolean ownerCommitFailed,
            boolean launcherCleanupFailed, ExactTaskPlacement placement) {
        if (!keeperClaimed) return "physical_keeper_not_claimed";
        return ownerCommitFailed || launcherCleanupFailed || placement != ExactTaskPlacement.REAR
                ? "physical_ready_unverified" : null;
    }

    private ControlledPhysicalCastOperations() { }

    /** Debug fixture switch; arms only for the exact known sample A Keeper and selected display. */
    public static boolean armDebugPhysicalReplacementLateFailure(Context context, ITaskService remote) {
        return armDebugPhysicalReplacementLateFailure(context, remote, 0, null);
    }

    /** Exact Debug-only cooperative fixture arm; binds the expected B task to the one-shot gate. */
    public static boolean armDebugPhysicalReplacementLateFailure(Context context, ITaskService remote,
            int expectedBTask, String expectedIdentity) {
        if (!BuildConfig.DEBUG) return false;
        boolean cooperative = expectedBTask > 0 || expectedIdentity != null;
        if (cooperative && (expectedBTask <= 0 || expectedIdentity == null || expectedIdentity.trim().isEmpty()))
            return false;
        Context app = context.getApplicationContext();
        int display = RearDisplay.requireId();
        LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity a =
                LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, display);
        if (a == null || !"dev.remrss.sample.rc5physicala".equals(a.getPackageName())
                || !("dev.remrss.sample.rc5physicala:" + a.getTaskId()).equals(RearScreenKeeperService.physicalCastTask())
                || RearScreenKeeperService.physicalCastOwnerGeneration() != a.getGeneration()) return false;
        SelectedRearIdentity selected;
        try { selected = readSelectedRearIdentity(remote); }
        catch (Exception unavailable) { return false; }
        boolean armed = selected != null && selected.matches(display, a.getIdentity())
                && (!cooperative || (expectedBTask != a.getTaskId() && expectedIdentity.equals(a.getIdentity())))
                && DebugPhysicalReplacementLateFailure.arm(a.getTaskId(), a.getGeneration(), display, a.getIdentity(),
                    a.getPackageName(), "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity",
                    cooperative ? expectedBTask : 0);
        if (armed) Log.i(TAG, "test_fixture_armed stage=after_b_visible_before_a_return");
        return armed;
    }

    /** Explicit Debug-only arm for the exact RC5 A/B MAIN-focus comparison fixture. */
    public static boolean armDebugPhysicalReplacementMainFocusGate(Context context, ITaskService remote,
            int expectedBTask, DebugPhysicalReplacementMainFocusGate.Mode mode) {
        if (!BuildConfig.DEBUG || context == null || remote == null || expectedBTask <= 0 || mode == null) return false;
        Context app = context.getApplicationContext();
        int display = RearDisplay.requireId();
        if (LegacyDisplayOccupancy.physicalReplacementJournal(app) != null) return false;
        LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity a =
                LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, display);
        if (a == null || !"dev.remrss.sample.rc5physicala".equals(a.getPackageName())
                || !("dev.remrss.sample.rc5physicala:" + a.getTaskId())
                    .equals(RearScreenKeeperService.physicalCastTask())
                || RearScreenKeeperService.physicalCastOwnerGeneration() != a.getGeneration()
                || !LegacyDisplayOccupancy.physicalKeeperStartClaimed(app,
                    "dev.remrss.sample.rc5physicala:" + a.getTaskId(), display,
                    a.getIdentity(), a.getGeneration())) return false;
        SelectedRearIdentity selected;
        try { selected = readSelectedRearIdentity(remote); }
        catch (Exception unavailable) { return false; }
        boolean armed = selected != null && selected.matches(display, a.getIdentity())
                && DebugPhysicalReplacementMainFocusProbe.arm(a.getTaskId(), a.getGeneration(), expectedBTask, display,
                    a.getIdentity(), a.getPackageName(), a.getBaseComponent(),
                    mode);
        if (armed) Log.i(TAG, "physical_main_focus_gate v=1 lane=b mode="
                + mode.name().toLowerCase(java.util.Locale.ROOT) + " event=armed");
        return armed;
    }

    /** Arms only the one exact Debug physical A/B fixture for the bottom-then-focus experiment. */
    public static boolean armDebugPhysicalBFocusTransfer(Context context, ITaskService remote, int expectedBTask) {
        if (!BuildConfig.DEBUG || !BuildConfig.APPLICATION_ID.endsWith(".debug")
                || context == null || remote == null || expectedBTask <= 0) return false;
        Context app = context.getApplicationContext();
        int display = RearDisplay.requireId();
        if (LegacyDisplayOccupancy.physicalReplacementJournal(app) != null) return false;
        LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity a =
                LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, display);
        if (a == null || !"dev.remrss.sample.rc5physicala".equals(a.getPackageName())
                || !"dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity".equals(a.getBaseComponent())
                || !("dev.remrss.sample.rc5physicala:" + a.getTaskId()).equals(RearScreenKeeperService.physicalCastTask())
                || RearScreenKeeperService.physicalCastOwnerGeneration() != a.getGeneration()
                || !LegacyDisplayOccupancy.physicalKeeperStartClaimed(app,
                    "dev.remrss.sample.rc5physicala:" + a.getTaskId(), display, a.getIdentity(), a.getGeneration()))
            return false;
        try {
            if (a.getTaskId() == expectedBTask || !selectedRearIdentityMatches(remote, display, a.getIdentity())
                    || exactPlacement(remote, expectedBTask, "dev.remrss.sample.rc5physicalb",
                        "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity", display, a.getIdentity())
                        != ExactTaskPlacement.MAIN) return false;
            JSONObject aPresentation = new JSONObject(remote.queryExactPhysicalPresentation(a.getTaskId(),
                    a.getPackageName(), a.getBaseComponent(), display, a.getIdentity()));
            String bPackage = "dev.remrss.sample.rc5physicalb";
            String bBase = bPackage + "/dev.remrss.physical.MainActivity";
            JSONObject bPresentation = new JSONObject(remote.queryExactPhysicalPresentation(expectedBTask,
                    bPackage, bBase, Display.DEFAULT_DISPLAY, a.getIdentity() + "|rc5-main-focus-mode=observe"));
            if (!exactPhysicalPresentationReady(aPresentation, a.getTaskId(), a.getPackageName(),
                        a.getBaseComponent(), display, a.getIdentity())
                    || !exactPhysicalPresentationReady(bPresentation, expectedBTask, bPackage, bBase,
                        Display.DEFAULT_DISPLAY, a.getIdentity())) return false;
            int aPid = aPresentation.optInt("dispatcherOwnerPid", -1);
            int bPid = bPresentation.optInt("dispatcherOwnerPid", -1);
            return aPid > 0 && bPid > 0 && aPid != bPid
                    && DebugPhysicalBFocusTransferProbe.arm(a.getTaskId(), a.getGeneration(), expectedBTask,
                        display, a.getIdentity(), a.getPackageName(), a.getBaseComponent(), aPid, bPid);
        } catch (Exception unavailable) { return false; }
    }

    public static StartResult start(Context context, ITaskService remote, int taskId, String packageName,
                                    String baseComponent, int rearDisplayId, String rearIdentity) {
        return start(context, remote, taskId, packageName, baseComponent, rearDisplayId, rearIdentity,
                () -> true, () -> true);
    }

    public static StartResult start(Context context, ITaskService remote, int taskId, String packageName,
                                    String baseComponent, int rearDisplayId, String rearIdentity,
                                    BooleanSupplier authorized, BooleanSupplier claimMove) {
        final Context app = context.getApplicationContext();
        final DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return failed("display_action_busy", false);
        final long[] generation = {0L};
        final boolean[] keeperStarted = {false};
        final String[] failure = {null};
        final boolean[] ownerCommitFailed = {false};
        final boolean[] launcherCleanupFailed = {false};
        try {
            if (LegacyDisplayOccupancy.hasPhysicalProjection(app, remote, rearDisplayId)
                    || LegacyDisplayOccupancy.physicalProjectionTask(app, remote, rearDisplayId) != null)
                return failed("physical_owner_exists", false);
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            if (selected == null || !selected.matches(rearDisplayId, rearIdentity))
                return failed("rear_identity_changed", true);
            PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.prepareAndHandoff(
                    new PhysicalCastRequestFlow.Target(packageName, taskId), new PhysicalCastRequestFlow.Port() {
                @Override public void prepare() throws Exception {
                    if (!authorized.getAsBoolean()) throw new IllegalStateException("controlled_operation_cancelled");
                    if (!RearAnimationManager.isCurrentDisplayAction(reservation)) throw new IllegalStateException("display_action_stale");
                    if (remote.isTaskOnDisplay(taskId, rearDisplayId) || !remote.isTaskOnDisplay(taskId, 0)
                            || !packageName.equals(remote.getPackageNameFromTaskId(taskId))
                            || !baseComponent.equals(remote.getTaskBaseComponent(taskId, 0)))
                        throw new IllegalStateException("target_identity_changed");
                }
                @Override public String launchAndObserve(int attempt) { return null; }
                @Override public PhysicalCastRequestFlow.MoveAttempt moveToRear(int id) throws Exception {
                    if (id != taskId || !authorized.getAsBoolean() || !RearAnimationManager.isCurrentDisplayAction(reservation))
                        return PhysicalCastRequestFlow.MoveAttempt.REJECTED_BEFORE_SEND;
                    RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(
                            remote, app.getPackageName(), rearDisplayId);
                    if (occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.EMPTY
                            && occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER)
                        return PhysicalCastRequestFlow.MoveAttempt.REJECTED_BEFORE_SEND;
                    if (!remote.isTaskOnDisplay(taskId, 0)
                            || !baseComponent.equals(remote.getTaskBaseComponent(taskId, 0)))
                        return PhysicalCastRequestFlow.MoveAttempt.REJECTED_BEFORE_SEND;
                    if (!authorized.getAsBoolean() || !claimMove.getAsBoolean()
                            || !RearAnimationManager.isCurrentDisplayAction(reservation))
                        return PhysicalCastRequestFlow.MoveAttempt.REJECTED_BEFORE_SEND;
                    String move = controlledMove(remote, taskId, packageName, baseComponent, 0,
                            rearDisplayId, rearIdentity);
                    String status = new JSONObject(move).optString("status", "unknown");
                    if ("rejected".equals(status)) return PhysicalCastRequestFlow.MoveAttempt.REJECTED_BEFORE_SEND;
                    // The privileged transaction has its own post-move proof; retain flow reconciliation too.
                    return PhysicalCastRequestFlow.MoveAttempt.SENT_UNVERIFIED;
                }
                @Override public PhysicalCastRequestFlow.MoveDisposition reconcileMove(PhysicalCastRequestFlow.Target target) throws Exception {
                    JSONObject placement = new JSONObject(remote.queryExactTaskPlacement(target.taskId));
                    if (!"present".equals(placement.optString("status"))
                            || !packageName.equals(placement.optString("package"))
                            || !baseComponent.equals(placement.optString("base"))
                            || !placement.optBoolean("rootSole", false)
                            || !placement.optBoolean("rootSnapshotKnown", false))
                        return PhysicalCastRequestFlow.MoveDisposition.UNKNOWN;
                    if (placement.optInt("display", -1) == rearDisplayId)
                        return PhysicalCastRequestFlow.MoveDisposition.VERIFIED_REAR;
                    if (placement.optInt("display", -1) == 0)
                        return PhysicalCastRequestFlow.MoveDisposition.VERIFIED_NOT_REAR;
                    return PhysicalCastRequestFlow.MoveDisposition.UNKNOWN;
                }
                @Override public void projectionCommitted(int id, String pkg) throws Exception {
                    try {
                        if (!authorized.getAsBoolean()) throw new IllegalStateException("controlled_operation_cancelled_after_move");
                        if (!RearAnimationManager.isCurrentDisplayAction(reservation)) throw new IllegalStateException("display_action_stale_after_move");
                        SelectedRearIdentity current = readSelectedRearIdentity(remote);
                        if (current == null || !current.matches(rearDisplayId, rearIdentity))
                            throw new IllegalStateException("rear_identity_changed_after_move");
                        Long claimed = LegacyDisplayOccupancy.recordPhysicalProjectionIfEmpty(app, id, pkg, baseComponent,
                                rearDisplayId, rearIdentity, current.selectedId, current.selectedUnique);
                        if (claimed == null || claimed <= 0L) throw new IllegalStateException("physical_owner_claim_failed");
                        generation[0] = claimed;
                        RearAnimationManager.controlledProjectionStarted(app, id, pkg);
                    } catch (Exception failure) {
                        ownerCommitFailed[0] = true;
                        throw failure;
                    }
                }
                @Override public void disableLauncher(PhysicalCastRequestFlow.Target target) throws Exception {
                    if (generation[0] <= 0L || LegacyDisplayOccupancy.physicalProjectionGeneration(app, taskId, rearDisplayId) != generation[0]) {
                        launcherCleanupFailed[0] = true;
                        throw new IllegalStateException("physical_owner_changed_before_launcher_disable");
                    }
                    // Persist a restorable intent before disabling the OEM launcher. If the Binder
                    // succeeds but the following process dies, close still knows cleanup is due.
                    if (!LegacyDisplayOccupancy.markPhysicalLauncherDisabled(app, taskId, rearDisplayId, generation[0])) {
                        launcherCleanupFailed[0] = true;
                        throw new IllegalStateException("physical_launcher_cleanup_record_failed");
                    }
                    launcherCleanupFailed[0] = true;
                    if (!remote.disableSubScreenLauncher()) throw new IllegalStateException("launcher_disable_failed");
                    launcherCleanupFailed[0] = false;
                }
                @Override public boolean startKeeper(String packageTask) throws Exception {
                    if (generation[0] <= 0L) return false;
                    // onStartCommand runs on main; don't block it waiting for its own claim.
                    if (Looper.myLooper() == Looper.getMainLooper()) return false;
                    if (!LegacyDisplayOccupancy.markPhysicalKeeperStartPending(app, packageTask, rearDisplayId,
                            rearIdentity, generation[0]))
                        throw new IllegalStateException("physical_keeper_start_record_failed");
                    Intent intent = new Intent(app, RearScreenKeeperService.class).putExtra("lastMovedTask", packageTask)
                            .putExtra("physicalCastOwnerGeneration", generation[0]).putExtra("keepScreenOnEnabled",
                                    app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                                            .getBoolean("flutter.keep_screen_on_enabled", true))
                            .putExtra("controlledPhysicalOwner", true)
                            .putExtra("controlledPhysicalDisplayId", rearDisplayId)
                            .putExtra("controlledPhysicalIdentity", rearIdentity);
                    ComponentName started;
                    try {
                        started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? app.startForegroundService(intent) : app.startService(intent);
                    } catch (Exception startFailure) {
                        boolean cancelled = LegacyDisplayOccupancy.clearPhysicalKeeperStartPending(
                                app, packageTask, rearDisplayId, rearIdentity, generation[0]);
                        if (!cancelled && LegacyDisplayOccupancy.physicalKeeperStartClaimed(
                                app, packageTask, rearDisplayId, rearIdentity, generation[0])) {
                            keeperStarted[0] = true;
                            return true;
                        }
                        throw startFailure;
                    }
                    if (started != null) keeperStarted[0] = LegacyDisplayOccupancy.awaitPhysicalKeeperStartClaim(
                            app, packageTask, rearDisplayId, rearIdentity, generation[0], 1500L);
                    if (!keeperStarted[0]) {
                        boolean cancelled = LegacyDisplayOccupancy.clearPhysicalKeeperStartPending(
                                app, packageTask, rearDisplayId, rearIdentity, generation[0]);
                        if (!cancelled) keeperStarted[0] = LegacyDisplayOccupancy.physicalKeeperStartClaimed(
                                app, packageTask, rearDisplayId, rearIdentity, generation[0]);
                    }
                    return keeperStarted[0];
                }
                @Override public void rollbackPreparation() { }
                @Override public void postCommitFailure(Exception error) { failure[0] = error.getClass().getSimpleName(); Log.w(TAG, "post_commit_failure", error); }
            });
            if (!result.committed) {
                if (generation[0] > 0L) return recoverable(taskId, generation[0], keeperStarted[0],
                        failure[0] == null ? "physical_post_move_incomplete" : failure[0]);
                if (result.indeterminate) return failed("move_owner_unverified", true);
                return failed(result.failure == null ? "move_rejected" : result.failure, false);
            }
            if (generation[0] <= 0L) {
                try {
                    ExactTaskPlacement before = exactPlacement(remote, taskId, packageName,
                            baseComponent, rearDisplayId, rearIdentity);
                    if (before == ExactTaskPlacement.REAR) {
                        controlledMove(remote, taskId, packageName, baseComponent, rearDisplayId, 0, rearIdentity);
                        ExactTaskPlacement after = exactPlacement(remote, taskId, packageName,
                                baseComponent, rearDisplayId, rearIdentity);
                        if (!compensationConfirmedHome(before, after))
                            return failed("owner_claim_failed_return_unverified", true);
                    } else if (before != ExactTaskPlacement.MAIN) {
                        return failed("owner_claim_failed_return_unverified", true);
                    }
                } catch (Exception recovery) { return failed("owner_claim_failed_return_unverified", true); }
                return failed("physical_owner_claim_failed", false);
            }
            String session = "physical:" + taskId + ":" + generation[0];
            String readyFailure = readinessFailure(keeperStarted[0], ownerCommitFailed[0], launcherCleanupFailed[0],
                    exactPlacement(remote, taskId, packageName, baseComponent, rearDisplayId, rearIdentity));
            if (readyFailure != null) return recoverable(session, generation[0], keeperStarted[0],
                    "physical_keeper_not_claimed".equals(readyFailure) && failure[0] != null
                            ? failure[0] : readyFailure);
            return new StartResult(session, generation[0], true, false, keeperStarted[0], failure[0]);
        } catch (Exception error) {
            if (generation[0] > 0L) return recoverable(taskId, generation[0], keeperStarted[0], error.getClass().getSimpleName());
            return failed(error.getClass().getSimpleName(), false);
        } finally { RearAnimationManager.completeDisplayAction(reservation); }
    }

    /** Exact A→B physical replacement. A remains the recorded owner and Keeper until B is proven live. */
    public static StartResult replace(Context context, ITaskService remote,
            int oldTask, String oldPackage, String oldBase, long oldGeneration,
            int newTask, String newPackage, String newBase, int rearDisplayId, String rearIdentity,
            long newCoordinatorGeneration, String newOwnerIdentity, String operationId,
            BooleanSupplier authorized, BooleanSupplier claimMove, BooleanSupplier targetCurrentBeforeBMove) {
        StartResult result = replaceInternal(context, remote, oldTask, oldPackage, oldBase, oldGeneration,
                newTask, newPackage, newBase, rearDisplayId, rearIdentity, newCoordinatorGeneration,
                newOwnerIdentity, operationId, authorized, claimMove, targetCurrentBeforeBMove);
        DebugPhysicalReplacementMainFocusProbe.recordTransactionTerminal(oldTask, oldGeneration,
                newTask, rearDisplayId, rearIdentity, operationId, result);
        DebugPhysicalBFocusTransferProbe.recordTransactionTerminal(oldTask, oldGeneration,
                newTask, rearDisplayId, rearIdentity, operationId, result);
        return result;
    }

    private static StartResult replaceInternal(Context context, ITaskService remote,
            int oldTask, String oldPackage, String oldBase, long oldGeneration,
            int newTask, String newPackage, String newBase, int rearDisplayId, String rearIdentity,
            long newCoordinatorGeneration, String newOwnerIdentity, String operationId,
            BooleanSupplier authorized, BooleanSupplier claimMove, BooleanSupplier targetCurrentBeforeBMove) {
        Context app = context.getApplicationContext();
        final boolean firstRejectDiagnostic = PhysicalReplacementFirstReject.enabled(
                BuildConfig.DEBUG, Build.DEVICE, Build.MODEL);
        boolean firstRejectSummaryLogged = false;
        String oldSession = "physical:" + oldTask + ":" + oldGeneration;
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) {
            logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_reservation", false);
            return failed("display_action_busy", false);
        }
        String token = operationId;
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal = null;
        boolean movedB = false, movedAHome = false, stoppedAKeeper = false, ownerB = false;
        boolean startedBKeeper = false;
        boolean bottomFocusTrial = false;
        long readinessStartedElapsedMs = 0L;
        try {
            if (!authorized.getAsBoolean()) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_authority", false);
                firstRejectSummaryLogged = true;
                return failed("replacement_cancelled", false);
            }
            if (!claimMove.getAsBoolean()) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_claim", false);
                firstRejectSummaryLogged = true;
                return failed("replacement_cancelled", false);
            }
            if (!RearAnimationManager.isCurrentDisplayAction(reservation)) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_reservation", false);
                firstRejectSummaryLogged = true;
                return failed("replacement_cancelled", false);
            }
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            if (selected == null || !selected.matches(rearDisplayId, rearIdentity)) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "rear_identity", false);
                firstRejectSummaryLogged = true;
                return failed("rear_identity_changed", true);
            }
            if (LegacyDisplayOccupancy.physicalProjectionGeneration(app, oldTask, rearDisplayId) != oldGeneration
                    || !oldSession.equals("physical:" + oldTask + ":" + oldGeneration)
                    || !oldPackage.equals(RearScreenKeeperService.physicalCastTask() == null ? null
                        : RearScreenKeeperService.physicalCastTask().substring(
                            0, RearScreenKeeperService.physicalCastTask().lastIndexOf(':')))
                    || RearScreenKeeperService.physicalCastOwnerGeneration() != oldGeneration) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_owner", false);
                firstRejectSummaryLogged = true;
                return failed("physical_owner_changed", true);
            }
            ExactTaskPlacement a0 = exactPlacement(remote, oldTask, oldPackage, oldBase, rearDisplayId, rearIdentity);
            ExactTaskPlacement b0 = exactPlacement(remote, newTask, newPackage, newBase, rearDisplayId, rearIdentity);
            if (a0 != ExactTaskPlacement.REAR || b0 != ExactTaskPlacement.MAIN) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_placement", false);
                firstRejectSummaryLogged = true;
                return failed("replacement_initial_placement_unverified", true);
            }
            boolean launcherDisabled = LegacyDisplayOccupancy.physicalProjectionLauncherDisabled(app, oldTask, rearDisplayId);
            journal = LegacyDisplayOccupancy.beginPhysicalReplacement(app, token, oldTask, oldPackage,
                    oldBase, rearDisplayId, rearIdentity, oldGeneration, launcherDisabled,
                    newTask, newPackage, newBase, false, newCoordinatorGeneration, newOwnerIdentity, "");
            if (journal == null) {
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", "host_journal", false);
                firstRejectSummaryLogged = true;
                return failed("replacement_journal_unavailable", true);
            }

            boolean preRpcAuthorized = authorized.getAsBoolean();
            boolean preRpcClaimed = preRpcAuthorized && claimMove.getAsBoolean();
            boolean preRpcTargetCurrent = preRpcClaimed && targetCurrentBeforeBMove.getAsBoolean();
            boolean preRpcReservationCurrent = preRpcTargetCurrent
                    && RearAnimationManager.isCurrentDisplayAction(reservation);
            if (!preRpcReservationCurrent) {
                String preRpcFailure = PhysicalReplacementFirstReject.hostGateReason(
                        preRpcAuthorized, preRpcClaimed, preRpcTargetCurrent, preRpcReservationCurrent);
                logBottomDispatchSummary(firstRejectDiagnostic, "not_called", preRpcFailure, false);
                firstRejectSummaryLogged = true;
                return compensateReplacement(app, remote, reservation, journal, false, false, false,
                        "replacement_cancelled_before_b_move");
            }

            bottomFocusTrial = DebugPhysicalBFocusTransferProbe.claim(oldTask, oldGeneration,
                    newTask, rearDisplayId, rearIdentity, oldPackage, oldBase, newPackage, newBase, operationId);
            final boolean presentationDiagnostic = BuildConfig.DEBUG
                    && isPhysicalReplacementDiagnosticFixture(newPackage);
            long bMoveReturnedElapsedMs = 0L;
            DebugPhysicalReplacementMainFocusGate.Mode focusMode = bottomFocusTrial ? null
                    : DebugPhysicalReplacementMainFocusProbe.claim(
                    oldTask, oldGeneration, newTask, rearDisplayId, rearIdentity, oldPackage, oldBase,
                    newPackage, newBase, operationId);
            if (focusMode != null) {
                final LegacyDisplayOccupancy.PhysicalReplacementJournal focusJournal = journal;
                final long gateStarted = android.os.SystemClock.elapsedRealtime();
                final int[] readNo = {0};
                DebugPhysicalReplacementMainFocusGate.Decision decision =
                        DebugPhysicalReplacementMainFocusGate.evaluate(focusMode, () -> {
                            int read = ++readNo[0];
                            DebugPhysicalReplacementMainFocusGate.Evidence evidence = readMainFocusEvidence(
                                    app, remote, reservation, focusJournal, newTask, newPackage, newBase,
                                    focusMode, authorized, targetCurrentBeforeBMove, gateStarted);
                            Log.i(TAG, "physical_main_focus_gate v=1 lane=b mode="
                                    + focusMode.name().toLowerCase(java.util.Locale.ROOT)
                                    + " event=" + (read == 1 ? "baseline" : "sample")
                                    + " read=" + read + " class=" + evidence.classification.wire
                                    + " reason=" + evidence.reason.wire);
                            return evidence;
                        }, new DebugPhysicalReplacementMainFocusGate.Clock() {
                            @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                        });
                String mode = focusMode.name().toLowerCase(java.util.Locale.ROOT);
                if (focusMode == DebugPhysicalReplacementMainFocusGate.Mode.OBSERVE) {
                    // Observation adds one read but still requires the original dispatch gates after it.
                    String dispatchFailure = mainFocusGateDispatchPrecondition(app, reservation,
                            journal, newTask, newPackage, newBase, authorized, claimMove,
                            targetCurrentBeforeBMove, gateStarted, false, operationId);
                    if (dispatchFailure != null) {
                        DebugPhysicalReplacementMainFocusGate.Reason reason = gateReason(dispatchFailure);
                        DebugPhysicalReplacementMainFocusProbe.recordDecision(
                                new DebugPhysicalReplacementMainFocusGate.Decision(focusMode, false,
                                        decision.reads, decision.waitMs, unknownGateEvidence(reason), reason));
                        Log.i(TAG, "physical_main_focus_gate v=1 lane=b mode=" + mode
                                + " event=gate_closed read=" + decision.reads + " wait_ms=" + decision.waitMs
                                + " reason=" + dispatchFailure);
                        return closeMainFocusGateWithoutMovingB(app, remote, reservation, journal,
                                authorized, newTask, newPackage, newBase);
                    }
                    DebugPhysicalReplacementMainFocusProbe.recordDecision(decision);
                } else if (!decision.allowMove) {
                    DebugPhysicalReplacementMainFocusProbe.recordDecision(decision);
                    Log.i(TAG, "physical_main_focus_gate v=1 lane=b mode=" + mode
                            + " event=gate_closed read=" + decision.reads + " wait_ms=" + decision.waitMs
                            + " reason=" + decision.reason.wire);
                    return closeMainFocusGateWithoutMovingB(app, remote, reservation, journal,
                            authorized, newTask, newPackage, newBase);
                }
                if (focusMode == DebugPhysicalReplacementMainFocusGate.Mode.GATED) {
                    String dispatchFailure = mainFocusGateDispatchPrecondition(app, reservation,
                            journal, newTask, newPackage, newBase, authorized, claimMove, targetCurrentBeforeBMove,
                            gateStarted, true, operationId);
                    if (dispatchFailure != null) {
                        DebugPhysicalReplacementMainFocusGate.Reason reason = gateReason(dispatchFailure);
                        DebugPhysicalReplacementMainFocusProbe.recordDecision(
                                new DebugPhysicalReplacementMainFocusGate.Decision(focusMode, false,
                                        decision.reads, decision.waitMs, unknownGateEvidence(reason), reason));
                        Log.i(TAG, "physical_main_focus_gate v=1 lane=b mode=" + mode
                                + " event=gate_closed read=" + decision.reads + " wait_ms=" + decision.waitMs
                                + " reason=" + dispatchFailure);
                        return closeMainFocusGateWithoutMovingB(app, remote, reservation, journal,
                                authorized, newTask, newPackage, newBase);
                    }
                    DebugPhysicalReplacementMainFocusProbe.recordDecision(decision);
                    Log.i(TAG, "physical_main_focus_gate v=1 lane=b mode=" + mode
                            + " event=gate_open read=" + decision.reads + " wait_ms=" + decision.waitMs
                            + " reason=" + decision.reason.wire);
                }
            }

            boolean verifiedDeviceBottomFocus = !bottomFocusTrial
                    && supportsPhysicalReplacementBottomFocusForHost();

            // In the explicit Debug fixture only, first attach B beneath A, then request focus once.
            // Every uncertain result goes through the ordinary exact compensation path; neither action retries.
            if (bottomFocusTrial) {
                if (!DebugPhysicalBFocusTransferProbe.beginDispatch(oldTask, oldGeneration, newTask,
                        rearDisplayId, rearIdentity, operationId, DebugPhysicalBFocusTransferProbe.Stage.BOTTOM))
                    return compensateReplacement(app, remote, reservation, journal, false, false, false,
                            "debug_b_focus_probe_cancelled_before_bottom");
                ITaskFocusActionGate bottomGate = exactBFocusActionGate(app, remote, reservation, journal,
                        oldTask, oldPackage, oldBase, oldGeneration, newTask, newPackage, newBase,
                        rearDisplayId, rearIdentity, operationId, authorized, claimMove,
                        DebugPhysicalBFocusTransferProbe.Stage.BOTTOM);
                int[] processPids = DebugPhysicalBFocusTransferProbe.dispatchProcessPids(oldTask, oldGeneration,
                        newTask, rearDisplayId, rearIdentity, operationId,
                        DebugPhysicalBFocusTransferProbe.Stage.BOTTOM);
                if (processPids == null)
                    return compensateReplacement(app, remote, reservation, journal, false, false, false,
                            "debug_b_focus_process_identity_unavailable");
                final JSONObject bottomResult;
                try {
                    bottomResult = new JSONObject(remote.moveRc5PhysicalFixtureBToRearBottom(
                            oldTask, oldGeneration, newTask, processPids[0], processPids[1],
                            rearDisplayId, rearIdentity, operationId, bottomGate));
                } catch (Exception unclassifiedReply) {
                    Log.i(TAG, "physical_b_focus_transfer v=1 lane=b event=bottom_dispatch result=unknown reason=unknown");
                    throw unclassifiedReply;
                }
                bMoveReturnedElapsedMs = android.os.SystemClock.elapsedRealtime();
                String bottomStatus = bottomResult.optString("status", "unknown");
                Log.i(TAG, "physical_b_focus_transfer v=1 lane=b event=bottom_dispatch result="
                        + safeProbeDispatchResult(bottomStatus) + " reason="
                        + DebugPhysicalBFocusTransferProbe.bottomReasonFromResult(bottomResult));
                movedB = !"rejected".equals(bottomStatus);
                if (!"moved".equals(bottomStatus)
                        || !DebugPhysicalBFocusTransferProbe.recordBottomReadback(oldTask, oldGeneration,
                            newTask, rearDisplayId, rearIdentity, operationId))
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "debug_b_focus_bottom_unverified");
                if (!exactBFocusTransferInterstageReady(app, remote, reservation, journal,
                        oldTask, oldPackage, oldBase, oldGeneration, newTask, newPackage, newBase,
                        rearDisplayId, rearIdentity, operationId, authorized, claimMove))
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "debug_b_focus_interstage_gate_rejected");
                if (!DebugPhysicalBFocusTransferProbe.beginDispatch(oldTask, oldGeneration, newTask,
                        rearDisplayId, rearIdentity, operationId, DebugPhysicalBFocusTransferProbe.Stage.FOCUS))
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "debug_b_focus_probe_cancelled_before_focus");
                ITaskFocusActionGate focusGate = exactBFocusActionGate(app, remote, reservation, journal,
                        oldTask, oldPackage, oldBase, oldGeneration, newTask, newPackage, newBase,
                        rearDisplayId, rearIdentity, operationId, authorized, claimMove,
                        DebugPhysicalBFocusTransferProbe.Stage.FOCUS);
                processPids = DebugPhysicalBFocusTransferProbe.dispatchProcessPids(oldTask, oldGeneration,
                        newTask, rearDisplayId, rearIdentity, operationId,
                        DebugPhysicalBFocusTransferProbe.Stage.FOCUS);
                if (processPids == null)
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "debug_b_focus_process_identity_unavailable");
                String focusReply = remote.focusRc5PhysicalFixtureBOnRear(
                        oldTask, oldGeneration, newTask, processPids[0], processPids[1],
                        rearDisplayId, rearIdentity, operationId, focusGate);
                long focusReturnedElapsedMs = android.os.SystemClock.elapsedRealtime();
                JSONObject focusResult = new JSONObject(focusReply);
                String focusStatus = focusResult.optString("status", "unknown");
                Log.i(TAG, "physical_b_focus_transfer v=1 lane=b event=focus_dispatch result="
                        + safeProbeDispatchResult(focusStatus));
                boolean focusAcknowledged = "requested".equals(focusStatus)
                        && DebugPhysicalBFocusTransferProbe.recordFocusRequest(oldTask, oldGeneration,
                            newTask, rearDisplayId, rearIdentity, operationId);
                if (!focusAcknowledged)
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "debug_b_focus_request_unverified");
                readinessStartedElapsedMs = PhysicalReplacementReadiness.readinessStartedAt(
                        bMoveReturnedElapsedMs, true, true, focusReturnedElapsedMs);
                if (readinessStartedElapsedMs == PhysicalReplacementReadiness.NOT_STARTED)
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "debug_b_focus_readiness_anchor_unavailable");
                bMoveReturnedElapsedMs = readinessStartedElapsedMs;
                if (presentationDiagnostic) Log.i(TAG,
                        "physical_replace phase=b_move_returned elapsed_ms=0");
            } else if (verifiedDeviceBottomFocus) {
                IPhysicalReplacementMoveGate bottomGate = exactPhysicalReplacementBActionGate(app,
                        reservation, journal, oldTask, oldPackage, oldBase, oldGeneration,
                        newTask, newPackage, newBase, journal.getNewGeneration(), newCoordinatorGeneration,
                        newOwnerIdentity, rearDisplayId, rearIdentity, operationId, authorized,
                        claimMove, targetCurrentBeforeBMove, true);
                IPhysicalReplacementMoveGate focusGate = exactPhysicalReplacementBActionGate(app,
                        reservation, journal, oldTask, oldPackage, oldBase, oldGeneration,
                        newTask, newPackage, newBase, journal.getNewGeneration(), newCoordinatorGeneration,
                        newOwnerIdentity, rearDisplayId, rearIdentity, operationId, authorized,
                        claimMove, targetCurrentBeforeBMove, false);
                final JSONObject stagedResult;
                try {
                    stagedResult = new JSONObject(remote.moveControlledPhysicalReplacementBWithBottomFocus(
                            oldTask, oldPackage, oldBase, oldGeneration, newTask, newPackage, newBase,
                            journal.getNewGeneration(), newCoordinatorGeneration, newOwnerIdentity, rearDisplayId,
                            rearIdentity, operationId, bottomGate, focusGate));
                } catch (Exception uncertainReply) {
                    // The single privileged RPC may have completed one or both system actions.
                    // Never issue the legacy move after an ambiguous reply; enter exact compensation.
                    logBottomDispatchSummary(firstRejectDiagnostic, "unknown", "reply_unknown", true);
                    firstRejectSummaryLogged = true;
                    return compensateReplacement(app, remote, reservation, journal, true, false, false,
                            "replacement_bottom_focus_reply_unknown");
                }
                String stagedStatus = stagedResult.optString("status", "unknown");
                movedB = stagedResult.optBoolean("bMoved", !"unsupported".equals(stagedStatus)
                        && !"rejected".equals(stagedStatus));
                logBottomDispatchSummary(firstRejectDiagnostic, stagedStatus,
                        stagedResult.optString("reason", "unknown"), movedB);
                firstRejectSummaryLogged = true;
                if ("unsupported".equals(stagedStatus) && !movedB) {
                    // Capability/device refusal is guaranteed pre-send; only this response may use
                    // the original single-step path, preserving behavior on unsupported devices.
                    verifiedDeviceBottomFocus = false;
                } else if (!"requested".equals(stagedStatus) || !movedB) {
                    return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                            "replacement_bottom_focus_unverified");
                } else {
                    Log.i(TAG, "physical_replace phase=bottom_focus result=requested");
                    bMoveReturnedElapsedMs = android.os.SystemClock.elapsedRealtime();
                    readinessStartedElapsedMs = PhysicalReplacementReadiness.readinessStartedAt(
                            bMoveReturnedElapsedMs, true, true, bMoveReturnedElapsedMs);
                }
            }
            if (!bottomFocusTrial && !verifiedDeviceBottomFocus) {
                // Default path is unchanged: one normal move-to-top and its original strict readback.
                String moved = controlledMove(remote, newTask, newPackage, newBase, 0, rearDisplayId, rearIdentity);
                JSONObject moveResult = new JSONObject(moved);
                if (!"moved".equals(moveResult.optString("status"))) {
                    Log.i(TAG, "physical_replace phase=b_move result=" + safeMoveResult(moveResult));
                    return compensateReplacement(app, remote, reservation, journal, false, false, false,
                            "replacement_b_move_unverified");
                }
                movedB = true;
                bMoveReturnedElapsedMs = android.os.SystemClock.elapsedRealtime();
                readinessStartedElapsedMs = PhysicalReplacementReadiness.readinessStartedAt(
                        bMoveReturnedElapsedMs, false, false, 0L);
                if (presentationDiagnostic) Log.i(TAG,
                        "physical_replace phase=b_move_returned elapsed_ms=0");
            }
            if (readinessStartedElapsedMs == PhysicalReplacementReadiness.NOT_STARTED)
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_readiness_anchor_unavailable");
            String readyFailure = awaitReplacementCandidateReady(app, remote, reservation, journal,
                    newTask, newPackage, newBase, authorized, readinessStartedElapsedMs);
            if (readyFailure != null) {
                return compensateReplacement(app, remote, reservation, journal,
                        movedB, false, false, readyFailure);
            }
            if (bottomFocusTrial && !DebugPhysicalBFocusTransferProbe.recordReady(oldTask, oldGeneration,
                    newTask, rearDisplayId, rearIdentity, operationId))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "debug_b_focus_probe_revoked_after_readiness");
            if (bottomFocusTrial) Log.i(TAG, "physical_b_focus_transfer v=1 lane=b event=ready result=ready");
            if (!authorized.getAsBoolean() || !RearAnimationManager.isCurrentDisplayAction(reservation)
                    || !replacementJournalAtFrontier(journal,
                        LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                    || !LegacyDisplayOccupancy.physicalProjectionMatches(app, oldTask, oldPackage,
                        oldBase, rearDisplayId, rearIdentity, oldGeneration)
                    || !exactOldPhysicalOwner(oldPackage, oldTask, oldGeneration))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_readiness_changed_before_b_ready");
            if (!LegacyDisplayOccupancy.advancePhysicalReplacement(app, token, "PREPARING", "B_READY"))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_journal_stage_failed");
            Log.i(TAG, "physical_replace phase=B_VISIBLE result=verified");

            if (DebugPhysicalReplacementLateFailure.consume(oldTask, oldGeneration, newTask,
                    rearDisplayId, rearIdentity, newPackage, newBase)) {
                Log.w(TAG, "test_fixture_injected stage=after_b_visible_before_a_return");
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "test_late_failure_after_b_visible");
            }

            if (!authorized.getAsBoolean() || !claimMove.getAsBoolean()
                    || !RearAnimationManager.isCurrentDisplayAction(reservation))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_cancelled_after_b_ready");
            final LegacyDisplayOccupancy.PhysicalReplacementJournal expectedReadyJournal = journal;
            if (!runReplacementFinalSampleGate(new ReplacementFinalChecks() {
                    @Override public boolean presentationReady() {
                        try {
                            JSONObject presentation = new JSONObject(remote.queryExactPhysicalPresentation(
                                    newTask, newPackage, newBase, rearDisplayId, rearIdentity));
                            logPhysicalPresentation("pre_a_return", presentation);
                            return exactPhysicalPresentationReady(presentation, newTask, newPackage,
                                    newBase, rearDisplayId, rearIdentity);
                        } catch (Exception unknown) { return false; }
                    }
                    @Override public boolean finalInventoryAndSelectedReady() {
                        try {
                            JSONObject inventory = new JSONObject(remote.getDisplayCastOccupancy(rearDisplayId));
                            String reason = replacementCandidateReadinessReason(inventory, oldTask,
                                    oldPackage, oldBase, newTask, newPackage, newBase, rearDisplayId);
                            if (!"ready".equals(reason)) return false;
                            return selectedRearIdentityMatches(remote, rearDisplayId, rearIdentity);
                        } catch (Exception unknown) { return false; }
                    }
                    @Override public boolean finalLocalGateReady() {
                        return authorized.getAsBoolean()
                                && RearAnimationManager.isCurrentDisplayAction(reservation)
                                && replacementJournalAtFrontier(expectedReadyJournal,
                                    LegacyDisplayOccupancy.physicalReplacementJournal(app), "B_READY")
                                && LegacyDisplayOccupancy.physicalProjectionMatches(app, oldTask,
                                    oldPackage, oldBase, rearDisplayId, rearIdentity, oldGeneration)
                                && exactOldPhysicalOwner(oldPackage, oldTask, oldGeneration);
                    }
                }))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_readiness_changed_before_a_return");
            if (!LegacyDisplayOccupancy.advancePhysicalReplacement(app, token, "B_READY", "A_RETURNING"))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_journal_stage_failed_before_a_return");
            if (!authorized.getAsBoolean() || !RearAnimationManager.isCurrentDisplayAction(reservation)
                    || !replacementJournalAtFrontier(journal,
                        LegacyDisplayOccupancy.physicalReplacementJournal(app), "A_RETURNING")
                    || !LegacyDisplayOccupancy.physicalProjectionMatches(app, oldTask, oldPackage,
                        oldBase, rearDisplayId, rearIdentity, oldGeneration)
                    || !exactOldPhysicalOwner(oldPackage, oldTask, oldGeneration))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_cancelled_before_a_move");
            LegacyDisplayOccupancy.PhysicalReplacementJournal returning =
                    LegacyDisplayOccupancy.physicalReplacementJournal(app);
            if (!replacementJournalAtFrontier(journal, returning, "A_RETURNING")
                    || !authorized.getAsBoolean() || !RearAnimationManager.isCurrentDisplayAction(reservation)
                    || !LegacyDisplayOccupancy.physicalProjectionMatches(app, oldTask, oldPackage, oldBase,
                        rearDisplayId, rearIdentity, oldGeneration)
                    || !exactOldPhysicalOwner(oldPackage, oldTask, oldGeneration))
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        "replacement_a_move_authority_changed");
            journal = returning;
            final LegacyDisplayOccupancy.PhysicalReplacementJournal aReturning = returning;
            IPhysicalReplacementMoveGate moveAGate = new IPhysicalReplacementMoveGate.Stub() {
                private boolean exactRequest(String requestToken, int requestOldTask, String requestOldPackage,
                        String requestOldBase, long requestOldGeneration, int requestNewTask,
                        String requestNewPackage, String requestNewBase, long requestNewGeneration,
                        long requestCoordinatorGeneration, String requestOwnerIdentity,
                        int requestDisplay, String requestRearIdentity) {
                    return aReturning.getToken().equals(requestToken)
                            && aReturning.getOldTask() == requestOldTask
                            && aReturning.getOldPackage().equals(requestOldPackage)
                            && aReturning.getOldBase().equals(requestOldBase)
                            && aReturning.getOldGeneration() == requestOldGeneration
                            && aReturning.getNewTask() == requestNewTask
                            && aReturning.getNewPackage().equals(requestNewPackage)
                            && aReturning.getNewBase().equals(requestNewBase)
                            && aReturning.getNewGeneration() == requestNewGeneration
                            && aReturning.getNewCoordinatorGeneration() == requestCoordinatorGeneration
                            && aReturning.getNewOwnerIdentity().equals(requestOwnerIdentity)
                            && aReturning.getDisplayId() == requestDisplay
                            && aReturning.getIdentity().equals(requestRearIdentity);
                }
                private boolean appGateCurrent() {
                    LegacyDisplayOccupancy.PhysicalReplacementJournal live =
                            LegacyDisplayOccupancy.physicalReplacementJournal(app);
                    return replacementJournalAtFrontier(aReturning, live, "A_RETURNING")
                            && !LegacyDisplayOccupancy.physicalAReturnMoveAuthorized(app, aReturning)
                            && authorized.getAsBoolean() && RearAnimationManager.isCurrentDisplayAction(reservation)
                            && LegacyDisplayOccupancy.physicalProjectionMatches(app, oldTask, oldPackage,
                                oldBase, rearDisplayId, rearIdentity, oldGeneration)
                            && exactOldPhysicalOwner(oldPackage, oldTask, oldGeneration);
                }
                @Override public boolean isCurrent(String requestToken, int requestOldTask, String requestOldPackage,
                        String requestOldBase, long requestOldGeneration, int requestNewTask,
                        String requestNewPackage, String requestNewBase, long requestNewGeneration,
                        long requestCoordinatorGeneration, String requestOwnerIdentity,
                        int requestDisplay, String requestRearIdentity) {
                    return exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                            requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                            requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                            requestDisplay, requestRearIdentity) && appGateCurrent();
                }
                @Override public boolean authorizeDispatch(String requestToken, int requestOldTask,
                        String requestOldPackage, String requestOldBase, long requestOldGeneration,
                        int requestNewTask, String requestNewPackage, String requestNewBase,
                        long requestNewGeneration, long requestCoordinatorGeneration,
                        String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                    if (!exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                            requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                            requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                            requestDisplay, requestRearIdentity) || !appGateCurrent()) return false;
                    LegacyDisplayOccupancy.PhysicalReplacementJournal authorizedJournal =
                            LegacyDisplayOccupancy.authorizePhysicalAReturnMove(app, aReturning);
                    if (authorizedJournal == null) return false;
                    boolean stillCurrent = LegacyDisplayOccupancy.physicalAReturnMovePending(app, authorizedJournal)
                            && replacementJournalAtFrontier(aReturning,
                                LegacyDisplayOccupancy.physicalReplacementJournal(app), "A_RETURNING")
                            && authorized.getAsBoolean() && RearAnimationManager.isCurrentDisplayAction(reservation)
                            && LegacyDisplayOccupancy.physicalProjectionMatches(app, oldTask, oldPackage,
                                oldBase, rearDisplayId, rearIdentity, oldGeneration)
                            && exactOldPhysicalOwner(oldPackage, oldTask, oldGeneration);
                    if (!stillCurrent) LegacyDisplayOccupancy.revokePhysicalAReturnMoveAuthorization(app, aReturning);
                    return stillCurrent;
                }
                @Override public boolean onMoveMayHaveBeenSent(String requestToken, int requestOldTask,
                        String requestOldPackage, String requestOldBase, long requestOldGeneration,
                        int requestNewTask, String requestNewPackage, String requestNewBase,
                        long requestNewGeneration, long requestCoordinatorGeneration,
                        String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                    return exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                            requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                            requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                            requestDisplay, requestRearIdentity)
                            && LegacyDisplayOccupancy.recordPhysicalAReturnMoveMayHaveBeenSent(app, aReturning);
                }
                @Override public boolean onRejectedBeforeSend(String requestToken, int requestOldTask,
                        String requestOldPackage, String requestOldBase, long requestOldGeneration,
                        int requestNewTask, String requestNewPackage, String requestNewBase,
                        long requestNewGeneration, long requestCoordinatorGeneration,
                        String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                    if (exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                            requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                            requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                            requestDisplay, requestRearIdentity))
                        return LegacyDisplayOccupancy.revokePhysicalAReturnMoveAuthorization(app, aReturning);
                    return false;
                }
            };
            String aMoveResult = remote.returnPhysicalAForPhysicalReplacement(oldTask, oldPackage, oldBase,
                    rearDisplayId, rearIdentity, token, oldGeneration, newTask, newPackage, newBase,
                    journal.getNewGeneration(), newCoordinatorGeneration, newOwnerIdentity, moveAGate);
            JSONObject aMoveReceipt = new JSONObject(aMoveResult);
            String aMoveStatus = aMoveReceipt.optString("status", "unknown");
            if (!"moved".equals(aMoveStatus)) {
                boolean explicitlyNotSent = "rejected".equals(aMoveStatus);
                if (explicitlyNotSent)
                    LegacyDisplayOccupancy.revokePhysicalAReturnMoveAuthorization(app, aReturning);
                boolean mayRestoreAFromMain = physicalReturnReceiptAllowsRestore(aMoveStatus,
                        LegacyDisplayOccupancy.physicalAReturnMoveAuthorized(app, aReturning));
                return compensateReplacement(app, remote, reservation, journal, movedB, false, false,
                        explicitlyNotSent ? "replacement_a_return_rejected_before_send"
                                : "replacement_a_return_unverified", mayRestoreAFromMain);
            }
            movedAHome = true;
            if (exactPlacement(remote, oldTask, oldPackage, oldBase, rearDisplayId, rearIdentity) != ExactTaskPlacement.MAIN
                    || exactPlacement(remote, newTask, newPackage, newBase, rearDisplayId, rearIdentity) != ExactTaskPlacement.REAR)
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, false,
                        "replacement_post_a_move_unverified");
            if (!LegacyDisplayOccupancy.advancePhysicalReplacement(app, token, "A_RETURNING", "A_RETURNED"))
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, false,
                        "replacement_journal_stage_failed_after_a_return");
            Log.i(TAG, "physical_replace phase=A_RETURNED result=verified");

            stoppedAKeeper = RearScreenKeeperService.stopPhysicalSession(oldPackage + ":" + oldTask, oldGeneration);
            if (!stoppedAKeeper)
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, false,
                        "replacement_old_keeper_stop_unverified");
            if (!LegacyDisplayOccupancy.commitPhysicalReplacementOwner(app, token))
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, stoppedAKeeper,
                        "replacement_owner_commit_failed");
            ownerB = true;
            String newSession = "physical:" + newTask + ":" + journal.getNewGeneration();
            if (!LegacyDisplayOccupancy.recordPhysicalReplacementOwnerSession(app, token, newSession,
                    journal.getNewGeneration()))
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, stoppedAKeeper,
                        "replacement_session_record_failed");
            LegacyDisplayOccupancy.PhysicalReplacementJournal boundOwnerB =
                    LegacyDisplayOccupancy.physicalReplacementJournal(app);
            if (!replacementOwnerBJournalMatches(journal, boundOwnerB, newSession, journal.getNewGeneration()))
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, stoppedAKeeper,
                        "replacement_session_readback_failed");
            // Keep compensation's expected snapshot at the actual one-way OWNER_B session bind.
            journal = boundOwnerB;
            Log.i(TAG, "physical_replace phase=OWNER_B result=committed");

            startedBKeeper = startExactPhysicalKeeper(app, newPackage, newTask, rearDisplayId,
                    rearIdentity, journal.getNewGeneration());
            if (!startedBKeeper)
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome, stoppedAKeeper,
                        "replacement_new_keeper_unclaimed");
            final LegacyDisplayOccupancy.PhysicalReplacementJournal ownerBJournal =
                    LegacyDisplayOccupancy.physicalReplacementJournal(app);
            final LegacyDisplayOccupancy.PhysicalReplacementJournal originalReplacementJournal = journal;
            final long committedGeneration = journal.getNewGeneration();
            final String expectedNewSession = "physical:" + newTask + ":" + journal.getNewGeneration();
            if (!replacementOwnerBJournalMatches(originalReplacementJournal, ownerBJournal,
                    expectedNewSession, committedGeneration)
                    || !runReplacementFinalSampleGate(new ReplacementFinalChecks() {
                        @Override public boolean presentationReady() {
                            try {
                                JSONObject presentation = new JSONObject(remote.queryExactPhysicalPresentation(
                                        newTask, newPackage, newBase, rearDisplayId, rearIdentity));
                                logPhysicalPresentation("replace_ready", presentation);
                                return exactPhysicalPresentationReady(presentation, newTask, newPackage,
                                        newBase, rearDisplayId, rearIdentity);
                            } catch (Exception unknown) { return false; }
                        }
                        @Override public boolean finalInventoryAndSelectedReady() {
                            try {
                                ExactTaskPlacement a = exactPlacement(remote, oldTask, oldPackage, oldBase,
                                        rearDisplayId, rearIdentity);
                                ExactTaskPlacement b = exactPlacement(remote, newTask, newPackage, newBase,
                                        rearDisplayId, rearIdentity);
                                JSONObject inventory = new JSONObject(remote.getDisplayCastOccupancy(rearDisplayId));
                                return a == ExactTaskPlacement.MAIN && b == ExactTaskPlacement.REAR
                                        && exactVisiblePhysicalTaskSnapshot(inventory, newTask, newPackage,
                                                newBase, rearDisplayId)
                                        && selectedRearIdentityMatches(remote, rearDisplayId, rearIdentity);
                            } catch (Exception unknown) { return false; }
                        }
                        @Override public boolean finalLocalGateReady() {
                            return authorized.getAsBoolean()
                                    && RearAnimationManager.isCurrentDisplayAction(reservation)
                                    && sameReplacementJournalIdentity(ownerBJournal,
                                        LegacyDisplayOccupancy.physicalReplacementJournal(app))
                                    && replacementOwnerBJournalMatches(originalReplacementJournal,
                                        LegacyDisplayOccupancy.physicalReplacementJournal(app),
                                        expectedNewSession, committedGeneration)
                                    && LegacyDisplayOccupancy.physicalProjectionMatches(app, newTask,
                                        newPackage, newBase, rearDisplayId, rearIdentity,
                                        committedGeneration)
                                    && (newPackage + ":" + newTask).equals(
                                        RearScreenKeeperService.physicalCastTask())
                                    && RearScreenKeeperService.physicalCastOwnerGeneration()
                                        == committedGeneration
                                    && LegacyDisplayOccupancy.physicalKeeperStartClaimed(app,
                                        newPackage + ":" + newTask, rearDisplayId, rearIdentity,
                                        committedGeneration);
                        }
                    }))
                return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome,
                        stoppedAKeeper, "replacement_final_state_unverified");
            Log.i(TAG, "physical_replace phase=ReplaceReady result=verified");
            return new StartResult("physical:" + newTask + ":" + journal.getNewGeneration(),
                    journal.getNewGeneration(), true, false, true, null);
        } catch (Exception error) {
            if (!firstRejectSummaryLogged)
                logBottomDispatchSummary(firstRejectDiagnostic, movedB ? "unknown" : "not_called",
                        "response_unclassified", movedB);
            if (journal == null) return failed("replacement_" + error.getClass().getSimpleName(), true);
            return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome,
                    stoppedAKeeper, "replacement_" + error.getClass().getSimpleName());
        } finally {
            if (bottomFocusTrial && remote != null) {
                try {
                    remote.clearRc5PhysicalFixtureBottomBinding(oldTask, oldGeneration, newTask,
                            rearDisplayId, rearIdentity, operationId);
                } catch (Exception ignored) { /* The service's bounded slot expiry is the process-death fallback. */ }
            }
            RearAnimationManager.completeDisplayAction(reservation);
        }
    }


    private static boolean startExactPhysicalKeeper(Context app, String pkg, int task, int display,
            String identity, long generation) throws Exception {
        String packageTask = pkg + ":" + task;
        if (!LegacyDisplayOccupancy.markPhysicalKeeperStartPending(app, packageTask, display, identity, generation)) return false;
        Intent intent = new Intent(app, RearScreenKeeperService.class).putExtra("lastMovedTask", packageTask)
                .putExtra("physicalCastOwnerGeneration", generation).putExtra("keepScreenOnEnabled",
                    app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                        .getBoolean("flutter.keep_screen_on_enabled", true))
                .putExtra("controlledPhysicalOwner", true).putExtra("controlledPhysicalDisplayId", display)
                .putExtra("controlledPhysicalIdentity", identity);
        ComponentName started;
        try { started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? app.startForegroundService(intent) : app.startService(intent); }
        catch (Exception failure) {
            boolean cancelled = LegacyDisplayOccupancy.clearPhysicalKeeperStartPending(app, packageTask, display, identity, generation);
            return !cancelled && LegacyDisplayOccupancy.physicalKeeperStartClaimed(app, packageTask, display, identity, generation);
        }
        if (started == null) return false;
        boolean claimed = LegacyDisplayOccupancy.awaitPhysicalKeeperStartClaim(
                app, packageTask, display, identity, generation, 1500L);
        if (claimed) return true;
        boolean cancelled = LegacyDisplayOccupancy.clearPhysicalKeeperStartPending(
                app, packageTask, display, identity, generation);
        return !cancelled && LegacyDisplayOccupancy.physicalKeeperStartClaimed(
                app, packageTask, display, identity, generation);
    }

    private static ITaskFocusActionGate exactBFocusActionGate(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            int aTask, String aPackage, String aBase, long aGeneration,
            int bTask, String bPackage, String bBase, int display, String identity,
            String operationId, BooleanSupplier authorized, BooleanSupplier claimMove,
            DebugPhysicalBFocusTransferProbe.Stage stage) {
        return new ITaskFocusActionGate.Stub() {
            @Override public boolean isCurrent(int taskId, String expectedBaseComponent, String expectedTopComponent) {
                return taskId == bTask && bBase.equals(expectedBaseComponent) && bBase.equals(expectedTopComponent)
                        && exactBFocusLocalGuard(app, reservation, journal, aTask, aPackage, aBase, aGeneration,
                            bTask, display, identity, operationId, authorized, claimMove, stage);
            }
        };
    }

    private static IPhysicalReplacementMoveGate exactPhysicalReplacementBActionGate(Context app,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            int aTask, String aPackage, String aBase, long aGeneration,
            int bTask, String bPackage, String bBase, long bGeneration,
            long coordinatorGeneration, String ownerIdentity, int display, String identity,
            String operationId, BooleanSupplier authorized, BooleanSupplier claimMove,
            BooleanSupplier targetCurrentBeforeBMove, boolean requireTargetOnMain) {
        return new IPhysicalReplacementMoveGate.Stub() {
            private boolean exactRequest(String token, int requestATask, String requestAPackage,
                    String requestABase, long requestAGeneration, int requestBTask, String requestBPackage,
                    String requestBBase, long requestBGeneration, long requestCoordinatorGeneration,
                    String requestOwnerIdentity, int requestDisplay, String requestIdentity) {
                return operationId.equals(token) && aTask == requestATask && aPackage.equals(requestAPackage)
                        && aBase.equals(requestABase) && aGeneration == requestAGeneration
                        && bTask == requestBTask && bPackage.equals(requestBPackage) && bBase.equals(requestBBase)
                        && bGeneration == requestBGeneration && coordinatorGeneration == requestCoordinatorGeneration
                        && ownerIdentity.equals(requestOwnerIdentity) && display == requestDisplay
                        && identity.equals(requestIdentity);
            }
            private boolean localCurrent() {
                try {
                    String ownerTask = aPackage + ":" + aTask;
                    return authorized.getAsBoolean() && claimMove.getAsBoolean()
                            && RearAnimationManager.isCurrentDisplayAction(reservation)
                            && replacementJournalAtFrontier(journal,
                                LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                            && LegacyDisplayOccupancy.physicalProjectionMatches(app, aTask, aPackage,
                                aBase, display, identity, aGeneration)
                            && exactOldPhysicalOwner(aPackage, aTask, aGeneration)
                            && ownerTask.equals(RearScreenKeeperService.physicalCastTask())
                            && RearScreenKeeperService.physicalCastOwnerGeneration() == aGeneration
                            && LegacyDisplayOccupancy.physicalKeeperStartClaimed(app, ownerTask,
                                display, identity, aGeneration)
                            && (!requireTargetOnMain || targetCurrentBeforeBMove.getAsBoolean());
                } catch (RuntimeException stale) { return false; }
            }
            @Override public boolean isCurrent(String token, int requestATask, String requestAPackage,
                    String requestABase, long requestAGeneration, int requestBTask, String requestBPackage,
                    String requestBBase, long requestBGeneration, long requestCoordinatorGeneration,
                    String requestOwnerIdentity, int requestDisplay, String requestIdentity) {
                return exactRequest(token, requestATask, requestAPackage, requestABase, requestAGeneration,
                        requestBTask, requestBPackage, requestBBase, requestBGeneration,
                        requestCoordinatorGeneration, requestOwnerIdentity, requestDisplay, requestIdentity)
                        && localCurrent();
            }
            // This RPC is exclusively the B bottom/focus action. The A-return durable intent callbacks
            // are deliberately not used or reused for these B operations.
            @Override public boolean authorizeDispatch(String token, int requestATask, String requestAPackage,
                    String requestABase, long requestAGeneration, int requestBTask, String requestBPackage,
                    String requestBBase, long requestBGeneration, long requestCoordinatorGeneration,
                    String requestOwnerIdentity, int requestDisplay, String requestIdentity) { return false; }
            @Override public boolean onMoveMayHaveBeenSent(String token, int requestATask, String requestAPackage,
                    String requestABase, long requestAGeneration, int requestBTask, String requestBPackage,
                    String requestBBase, long requestBGeneration, long requestCoordinatorGeneration,
                    String requestOwnerIdentity, int requestDisplay, String requestIdentity) { return false; }
            @Override public boolean onRejectedBeforeSend(String token, int requestATask, String requestAPackage,
                    String requestABase, long requestAGeneration, int requestBTask, String requestBPackage,
                    String requestBBase, long requestBGeneration, long requestCoordinatorGeneration,
                    String requestOwnerIdentity, int requestDisplay, String requestIdentity) { return false; }
        };
    }

    private static boolean supportsPhysicalReplacementBottomFocusForHost() {
        return PhysicalReplacementBottomFocus.supportedDevice(Build.DEVICE, Build.MODEL);
    }

    private static void logBottomDispatchSummary(boolean enabled, String status, String reason,
            boolean bMoved) {
        if (enabled) Log.i(TAG, PhysicalReplacementFirstReject.summary(status, reason, bMoved));
    }

    private static boolean exactBFocusTransferInterstageReady(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            int aTask, String aPackage, String aBase, long aGeneration,
            int bTask, String bPackage, String bBase, int display, String identity, String operationId,
            BooleanSupplier authorized, BooleanSupplier claimMove) {
        try {
            JSONObject inventory = new JSONObject(remote.getDisplayCastOccupancy(display));
            if (!DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(inventory, aTask, aPackage, aBase,
                    bTask, bPackage, bBase, display)) return false;
            JSONObject aPresentation = new JSONObject(remote.queryExactPhysicalPresentation(
                    aTask, aPackage, aBase, display, identity));
            if (!exactPhysicalPresentationReady(aPresentation, aTask, aPackage, aBase, display, identity))
                return false;
            return selectedRearIdentityMatches(remote, display, identity)
                    && exactBFocusInterstageLocalGuard(app, reservation, journal, aTask, aPackage, aBase,
                        aGeneration, bTask, display, identity, operationId, authorized, claimMove);
        } catch (Exception | LinkageError unknown) { return false; }
    }

    private static boolean exactBFocusInterstageLocalGuard(Context app,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            int aTask, String aPackage, String aBase, long aGeneration,
            int bTask, int display, String identity, String operationId,
            BooleanSupplier authorized, BooleanSupplier claimMove) {
        try {
            return authorized.getAsBoolean() && claimMove.getAsBoolean()
                    && RearAnimationManager.isCurrentDisplayAction(reservation)
                    && replacementJournalAtFrontier(journal,
                        LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                    && LegacyDisplayOccupancy.physicalProjectionMatches(app, aTask, aPackage, aBase,
                        display, identity, aGeneration)
                    && (aPackage + ":" + aTask).equals(RearScreenKeeperService.physicalCastTask())
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == aGeneration
                    && DebugPhysicalBFocusTransferProbe.bottomVerifiedCurrent(aTask, aGeneration, bTask,
                        display, identity, operationId);
        } catch (RuntimeException stale) { return false; }
    }

    /** Last non-blocking app-side transaction check; the service repeats this callback at dispatch. */
    private static boolean exactBFocusLocalGuard(Context app, DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            int aTask, String aPackage, String aBase, long aGeneration,
            int bTask, int display, String identity, String operationId,
            BooleanSupplier authorized, BooleanSupplier claimMove,
            DebugPhysicalBFocusTransferProbe.Stage stage) {
        try {
            return authorized.getAsBoolean() && claimMove.getAsBoolean()
                    && RearAnimationManager.isCurrentDisplayAction(reservation)
                    && replacementJournalAtFrontier(journal,
                        LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                    && LegacyDisplayOccupancy.physicalProjectionMatches(app, aTask, aPackage, aBase,
                        display, identity, aGeneration)
                    && (aPackage + ":" + aTask).equals(RearScreenKeeperService.physicalCastTask())
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == aGeneration
                    && DebugPhysicalBFocusTransferProbe.dispatchCurrent(aTask, aGeneration, bTask,
                        display, identity, operationId, stage);
        } catch (RuntimeException stale) { return false; }
    }

    private static String safeProbeDispatchResult(String status) {
        return "moved".equals(status) || "requested".equals(status) || "rejected".equals(status)
                ? status : "unknown";
    }

    private static String awaitReplacementCandidateReady(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected, int newTask,
            String newPackage, String newBase, BooleanSupplier authorized, long readinessStartedElapsedMs) {
        final int[] presentationReads = {0};
        final int[] exactMainDisplayStaleReplies = {0};
        final boolean[] extendedThirdSampleUsed = {false};
        final boolean[] thirdReadDelayScheduled = {false};
        final boolean diagnostic = BuildConfig.DEBUG && isPhysicalReplacementDiagnosticFixture(newPackage);
        final int[] samplesStarted = diagnostic ? new int[1] : null;
        final String[] endingReason = diagnostic ? new String[]{"unknown"} : null;
        final boolean[] sleepInterrupted = diagnostic ? new boolean[1] : null;
        PhysicalReplacementReadiness.Outcome outcome = PhysicalReplacementReadiness.awaitFrom(() -> {
            final int sampleIndex = diagnostic ? ++samplesStarted[0] : 0;
            if (diagnostic) {
                endingReason[0] = "sample_in_progress";
                Log.i(TAG, "physical_replace phase=readiness_sample sample=" + sampleIndex
                        + " since_b_move_ms=" + elapsedSince(readinessStartedElapsedMs));
            }
            try {
            if (!authorized.getAsBoolean() || !RearAnimationManager.isCurrentDisplayAction(reservation)) {
                if (diagnostic) endingReason[0] = "authorization_or_reservation_stale";
                return PhysicalReplacementReadiness.Sample.REJECTED;
            }
            LegacyDisplayOccupancy.PhysicalReplacementJournal current = LegacyDisplayOccupancy
                    .physicalReplacementJournal(app);
            if (!replacementJournalAtFrontier(expected, current, "PREPARING")) {
                if (diagnostic) endingReason[0] = "journal_changed";
                return PhysicalReplacementReadiness.Sample.REJECTED;
            }
            String exactOldOwner = expected.getOldPackage() + ":" + expected.getOldTask();
            if (!LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                        expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(),
                        expected.getIdentity(), expected.getOldGeneration())
                    || !exactOldOwner.equals(RearScreenKeeperService.physicalCastTask())
                    || RearScreenKeeperService.physicalCastOwnerGeneration() != expected.getOldGeneration()) {
                if (diagnostic) endingReason[0] = "old_owner_changed";
                return PhysicalReplacementReadiness.Sample.REJECTED;
            }
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            if (selected == null || !selected.matches(expected.getDisplayId(), expected.getIdentity())) {
                if (diagnostic) endingReason[0] = "rear_identity_changed";
                return PhysicalReplacementReadiness.Sample.REJECTED;
            }
            JSONObject coexist = new JSONObject(remote.getDisplayCastOccupancy(expected.getDisplayId()));
            String reason = replacementCandidateReadinessReason(coexist, expected.getOldTask(),
                    expected.getOldPackage(), expected.getOldBase(), newTask, newPackage,
                    newBase, expected.getDisplayId());
            Log.i(TAG, "physical_replace phase=readiness inventory=" + reason);
            if ("old_task_still_requested".equals(reason)
                    || "new_task_not_visible_requested".equals(reason)) {
                if (diagnostic) endingReason[0] = "inventory_pending";
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            if (!"ready".equals(reason)) {
                if (diagnostic) endingReason[0] = "inventory_rejected";
                return PhysicalReplacementReadiness.Sample.REJECTED;
            }
            if (presentationReads[0] >= 3) {
                if (diagnostic) endingReason[0] = "presentation_sample_limit";
                return PhysicalReplacementReadiness.Sample.REJECTED;
            }
            final long[] presentationStartedElapsedMs = {0L};
            final JSONObject presentation;
            try {
                // Only the added third read gets this final admission check. The first two
                // reads preserve their prior contract; an admitted exact read may finish late.
                long readAdmissionElapsedMs = android.os.SystemClock.elapsedRealtime();
                if (presentationReads[0] == 2
                        && !PhysicalReplacementReadiness.mayStartThirdPresentationRead(
                                readinessStartedElapsedMs, readAdmissionElapsedMs,
                                exactMainDisplayStaleReplies[0] >= 2
                                    ? PHYSICAL_REPLACEMENT_READINESS_WINDOW_MS + 500L
                                    : PHYSICAL_REPLACEMENT_READINESS_WINDOW_MS)) {
                    if (diagnostic) {
                        endingReason[0] = "third_presentation_read_window_expired";
                        Log.i(TAG, "physical_replace phase=presentation_read sample=" + sampleIndex
                                + " read=3 since_b_move_ms=" + elapsedSince(readinessStartedElapsedMs)
                                + " event=not_started reason=third_presentation_read_window_expired");
                    }
                    return PhysicalReplacementReadiness.Sample.PENDING;
                }
                if (presentationReads[0] == 2 && exactMainDisplayStaleReplies[0] >= 2
                        && readAdmissionElapsedMs >= readinessStartedElapsedMs
                            + PHYSICAL_REPLACEMENT_READINESS_WINDOW_MS) {
                    extendedThirdSampleUsed[0] = true;
                }
                presentationReads[0]++;
                presentationStartedElapsedMs[0] = diagnostic ? readAdmissionElapsedMs : 0L;
                presentation = new JSONObject(remote.queryExactPhysicalPresentation(newTask,
                        newPackage, newBase, expected.getDisplayId(), expected.getIdentity()));
            } catch (Exception failure) {
                if (diagnostic) Log.i(TAG, "physical_replace phase=presentation_read sample=" + sampleIndex
                        + " read=" + presentationReads[0] + " since_b_move_ms="
                        + elapsedSince(readinessStartedElapsedMs) + " duration_ms="
                        + (android.os.SystemClock.elapsedRealtime() - presentationStartedElapsedMs[0])
                        + " status=unknown reason=probe_unavailable");
                if (diagnostic) endingReason[0] = "presentation_read_failed";
                throw failure;
            }
            String status = presentation.optString("status", "unknown");
            String presentationReason = safePresentationReason(presentation.optString("reason", "unavailable"));
            String routeProbe = null;
            if (diagnostic && BuildConfig.DEBUG
                    && "dev.remrss.sample.rc5physicalb".equals(newPackage)
                    && presentationReads[0] >= 1 && presentationReads[0] <= 3) {
                Object rawRouteProbe = presentation.opt("routeProbeV1");
                if (rawRouteProbe instanceof String) {
                    routeProbe = PhysicalTaskPresentationEvidence.validateRouteProbeV1(
                            (String) rawRouteProbe, "b");
                    if (routeProbe != null) Log.i(TAG, "physical_presentation_route_probe v=1 read="
                            + presentationReads[0] + " " + routeProbe);
                    Object rawSampleClock = presentation.opt("presentationSampleV1");
                    if (routeProbe != null && rawSampleClock instanceof String) {
                        try {
                            String sampleClock = PhysicalTaskPresentationEvidence.validatePresentationSampleV1(
                                    (String) rawSampleClock, "b");
                            if (sampleClock != null) Log.i(TAG, "physical_presentation_sample_clock v=1 read="
                                    + presentationReads[0] + " " + sampleClock);
                        } catch (RuntimeException | LinkageError diagnosticUnavailable) { }
                    }
                    Object rawLayerBinding = presentation.opt("presentationLayerBindingV2");
                    if (routeProbe != null && rawLayerBinding instanceof String) {
                        try {
                            String layerBinding = PhysicalTaskPresentationEvidence
                                    .validatePresentationLayerBindingV2((String) rawLayerBinding, "b", newTask);
                            if (layerBinding != null) Log.i(TAG,
                                    "physical_presentation_layer_binding v=2 read="
                                            + presentationReads[0] + " " + layerBinding);
                        } catch (RuntimeException | LinkageError diagnosticUnavailable) { }
                    }
                }
            }
            if (presentationReads[0] <= 2) {
                if (isExactMainDisplayStaleReply(presentation, newTask, newPackage, newBase,
                        expected.getDisplayId(), expected.getIdentity())) {
                    exactMainDisplayStaleReplies[0]++;
                } else {
                    exactMainDisplayStaleReplies[0] = 0;
                }
            }
            if (diagnostic) Log.i(TAG, "physical_replace phase=presentation_read sample=" + sampleIndex
                    + " read=" + presentationReads[0] + " since_b_move_ms="
                    + elapsedSince(readinessStartedElapsedMs) + " duration_ms="
                    + (android.os.SystemClock.elapsedRealtime() - presentationStartedElapsedMs[0])
                    + " status=" + safePresentationStatus(status) + " reason=" + presentationReason);
            Log.i(TAG, "physical_replace phase=presentation result=" + status + " reason=" + presentationReason);
            if (exactPhysicalPresentationReady(presentation, newTask, newPackage, newBase,
                    expected.getDisplayId(), expected.getIdentity())) {
                boolean finalReady = runReplacementFinalSampleGate(new ReplacementFinalChecks() {
                    @Override public boolean presentationReady() { return true; }
                    @Override public boolean finalInventoryAndSelectedReady() {
                        return exactReplacementInventoryAndSelection(remote, expected, newTask,
                                newPackage, newBase);
                    }
                    @Override public boolean finalLocalGateReady() {
                        return authorized.getAsBoolean()
                                && RearAnimationManager.isCurrentDisplayAction(reservation)
                                && replacementJournalAtFrontier(expected,
                                    LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                                && LegacyDisplayOccupancy.physicalProjectionMatches(app,
                                    expected.getOldTask(), expected.getOldPackage(), expected.getOldBase(),
                                    expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration())
                                && exactOldPhysicalOwner(expected.getOldPackage(), expected.getOldTask(),
                                    expected.getOldGeneration());
                    }
                });
                if (diagnostic) endingReason[0] = finalReady ? "ready" : "final_gate_rejected";
                return finalReady ? PhysicalReplacementReadiness.Sample.READY
                        : PhysicalReplacementReadiness.Sample.REJECTED;
            }
            if (isTransientPresentationNotReady(presentationReason)) {
                if (diagnostic) endingReason[0] = "presentation_transient";
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            if (diagnostic) endingReason[0] = "presentation_rejected";
            return PhysicalReplacementReadiness.Sample.REJECTED;
            } catch (Exception unavailable) {
                if (diagnostic && "sample_in_progress".equals(endingReason[0]))
                    endingReason[0] = "read_interrupted_or_unavailable";
                throw unavailable;
            }
        }, new PhysicalReplacementReadiness.Clock() {
            @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
            @Override public void sleep(long millis) throws InterruptedException {
                try { Thread.sleep(millis); }
                catch (InterruptedException interrupted) {
                    if (diagnostic) sleepInterrupted[0] = true;
                    throw interrupted;
                }
            }
        }, readinessStartedElapsedMs, () -> PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                        PHYSICAL_REPLACEMENT_READINESS_WINDOW_MS, exactMainDisplayStaleReplies[0],
                        presentationReads[0], extendedThirdSampleUsed[0]), () -> {
                    long target = PhysicalReplacementReadiness.thirdPresentationReadNotBeforeElapsedMs(
                            readinessStartedElapsedMs, exactMainDisplayStaleReplies[0],
                            presentationReads[0], thirdReadDelayScheduled[0]);
                    if (target != Long.MIN_VALUE) thirdReadDelayScheduled[0] = true;
                    return target;
                }, 4, 75L);
        if (diagnostic && outcome == PhysicalReplacementReadiness.Outcome.UNKNOWN) {
            if ("presentation_transient".equals(endingReason[0])
                    || "inventory_pending".equals(endingReason[0])) {
                long elapsedMs = android.os.SystemClock.elapsedRealtime() - readinessStartedElapsedMs;
                long effectiveWindowMs = PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                        PHYSICAL_REPLACEMENT_READINESS_WINDOW_MS, exactMainDisplayStaleReplies[0],
                        presentationReads[0], extendedThirdSampleUsed[0]);
                endingReason[0] = sleepInterrupted[0] ? "read_interrupted_or_unavailable"
                        : samplesStarted[0] >= 4 ? "sample_limit"
                        : elapsedMs >= effectiveWindowMs
                            ? "window_expired" : "read_interrupted_or_unavailable";
            }
        }
        if (diagnostic) Log.i(TAG, "physical_replace phase=readiness_terminal outcome="
                + outcome.name().toLowerCase(java.util.Locale.ROOT) + " reason=" + endingReason[0]
                + " samples=" + samplesStarted[0] + " presentation_reads=" + presentationReads[0]
                + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - readinessStartedElapsedMs)
                + " since_b_move_ms=" + elapsedSince(readinessStartedElapsedMs));
        if (outcome == PhysicalReplacementReadiness.Outcome.READY) return null;
        return outcome == PhysicalReplacementReadiness.Outcome.REJECTED
                ? "replacement_readiness_changed" : "replacement_readiness_timeout_or_unknown";
    }

    private static long elapsedSince(long startElapsedMs) {
        return Math.max(0L, android.os.SystemClock.elapsedRealtime() - startElapsedMs);
    }

    private static boolean isPhysicalReplacementDiagnosticFixture(String packageName) {
        return "dev.remrss.sample.rc5physicala".equals(packageName)
                || "dev.remrss.sample.rc5physicalb".equals(packageName);
    }

    /** Capability handshake used by the exact AndroidTest fixture before arming an A/B run. */
    public static boolean debugMainFocusProbeAvailable(ITaskService remote, int bTask, String rearIdentity) {
        if (!BuildConfig.DEBUG || remote == null || bTask <= 0 || rearIdentity == null) return false;
        try {
            JSONObject row = new JSONObject(remote.queryExactPhysicalPresentation(bTask,
                    "dev.remrss.sample.rc5physicalb",
                    "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity",
                    Display.DEFAULT_DISPLAY, rearIdentity));
            String route = PhysicalTaskPresentationEvidence.validateRouteProbeV1(
                    row.optString("routeProbeV1", null), "b");
            return row.optInt("task", -1) == bTask && row.optInt("display", -1) == Display.DEFAULT_DISPLAY
                    && "ready".equals(row.optString("status"))
                    && "dev.remrss.sample.rc5physicalb".equals(row.optString("package"))
                    && "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(row.optString("base"))
                    && row.optBoolean("topExact", false) && row.optBoolean("rootSnapshotKnown", false)
                    && row.optBoolean("rootSole", false) && row.optInt("selectedId", -1) > 0
                    && (row.optInt("selectedId", -1) + ":" + row.optString("selectedUnique", ""))
                        .equals(rearIdentity)
                    && route != null;
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private static DebugPhysicalReplacementMainFocusGate.Evidence readMainFocusEvidence(Context app,
            ITaskService remote, DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected, int newTask,
            String newPackage, String newBase, DebugPhysicalReplacementMainFocusGate.Mode mode,
            BooleanSupplier authorized,
            BooleanSupplier targetCurrentBeforeBMove, long gateStartedElapsedMs) {
        DebugPhysicalReplacementMainFocusGate.Reason guard = mainFocusGateLocalGuard(app, reservation,
                expected, newTask, newPackage, newBase, authorized, targetCurrentBeforeBMove);
        if (guard != null) return unknownGateEvidence(guard);
        try {
            String requestIdentity = DebugPhysicalReplacementMainFocusGate.encodeRequestIdentity(
                    expected.getIdentity(), mode);
            JSONObject presentation = new JSONObject(remote.queryExactPhysicalPresentation(newTask,
                    newPackage, newBase, Display.DEFAULT_DISPLAY, requestIdentity));
            if (!exactMainFocusProbeIdentity(presentation, newTask, newPackage, newBase,
                    expected.getIdentity())) return unknownGateEvidence(DebugPhysicalReplacementMainFocusGate.Reason.TASK_NOT_MAIN);
            String route = presentation.optString("routeProbeV1", null);
            DebugPhysicalReplacementMainFocusGate.Evidence routeEvidence =
                    DebugPhysicalReplacementMainFocusGate.classify(route, true,
                            presentation.optBoolean("topExact", false),
                            "ready".equals(presentation.optString("status")));
            DebugPhysicalReplacementMainFocusGate.Reason postReadGuard = mainFocusGateLocalGuard(
                    app, reservation, expected, newTask, newPackage, newBase, authorized, targetCurrentBeforeBMove);
            return postReadGuard == null ? routeEvidence : unknownGateEvidence(postReadGuard);
        } catch (Exception | LinkageError unavailable) {
            return unknownGateEvidence(mode == DebugPhysicalReplacementMainFocusGate.Mode.GATED
                    && android.os.SystemClock.elapsedRealtime() - gateStartedElapsedMs
                        >= DebugPhysicalReplacementMainFocusGate.TIMEOUT_MS
                    ? DebugPhysicalReplacementMainFocusGate.Reason.DEADLINE_EXPIRED
                    : DebugPhysicalReplacementMainFocusGate.Reason.ROUTE_UNAVAILABLE);
        }
    }

    private static boolean exactMainFocusProbeIdentity(JSONObject row, int task, String pkg,
            String base, String identity) {
        return row != null && row.optInt("task", -1) == task && row.optInt("display", -1) == Display.DEFAULT_DISPLAY
                && pkg.equals(row.optString("package")) && base.equals(row.optString("base"))
                && row.optBoolean("rootSnapshotKnown", false) && row.optBoolean("rootSole", false)
                && (row.optInt("selectedId", -1) + ":" + row.optString("selectedUnique", "")).equals(identity);
    }

    private static DebugPhysicalReplacementMainFocusGate.Reason mainFocusGateLocalGuard(Context app,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected, int newTask,
            String newPackage, String newBase, BooleanSupplier authorized,
            BooleanSupplier targetCurrentBeforeBMove) {
        if (!authorized.getAsBoolean()) return DebugPhysicalReplacementMainFocusGate.Reason.AUTHORIZATION_REVOKED;
        if (!RearAnimationManager.isCurrentDisplayAction(reservation))
            return DebugPhysicalReplacementMainFocusGate.Reason.RESERVATION_LOST;
        if (!replacementJournalAtFrontier(expected, LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING"))
            return DebugPhysicalReplacementMainFocusGate.Reason.JOURNAL_CHANGED;
        if (!LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(), expected.getOldPackage(),
                    expected.getOldBase(), expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration())
                || !exactOldPhysicalOwner(expected.getOldPackage(), expected.getOldTask(), expected.getOldGeneration())
                || !LegacyDisplayOccupancy.physicalKeeperStartClaimed(app,
                    expected.getOldPackage() + ":" + expected.getOldTask(), expected.getDisplayId(),
                    expected.getIdentity(), expected.getOldGeneration()))
            return DebugPhysicalReplacementMainFocusGate.Reason.OWNER_CHANGED;
        if (!targetCurrentBeforeBMove.getAsBoolean()) return DebugPhysicalReplacementMainFocusGate.Reason.TARGET_CHANGED;
        return null;
    }

    private static String mainFocusGateDispatchPrecondition(Context app,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected, int newTask,
            String newPackage, String newBase, BooleanSupplier authorized,
            BooleanSupplier claimMove, BooleanSupplier targetCurrentBeforeBMove,
            long gateStartedElapsedMs, boolean gated, String operationId) {
        // All synchronous remote route/root/screen evidence was gathered by the same final sample.
        try {
            String reason = gateReasonWire(mainFocusGateLocalGuard(app, reservation, expected, newTask,
                    newPackage, newBase, authorized, targetCurrentBeforeBMove));
            if (reason != null) return reason;
            if (gated && gateDeadlineExpired(gateStartedElapsedMs)) return "deadline_expired";
            if (!claimMove.getAsBoolean()) return "authorization_revoked";
            reason = gateReasonWire(mainFocusGateLocalGuard(app, reservation, expected, newTask,
                    newPackage, newBase, authorized, targetCurrentBeforeBMove));
            if (reason != null) return reason;
            if (gated && gateDeadlineExpired(gateStartedElapsedMs)) return "deadline_expired";
            if (!authorized.getAsBoolean()) return "authorization_revoked";
            if (!RearAnimationManager.isCurrentDisplayAction(reservation)) return "reservation_lost";
            if (gated && !DebugPhysicalReplacementMainFocusProbe.consumeDispatchLicense(operationId))
                return "authorization_revoked";
            return null;
        } catch (RuntimeException unavailable) { return "precondition_unknown"; }
    }

    private static boolean gateDeadlineExpired(long startedElapsedMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        return now < startedElapsedMs || now - startedElapsedMs >= DebugPhysicalReplacementMainFocusGate.TIMEOUT_MS;
    }

    private static StartResult closeMainFocusGateWithoutMovingB(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected, BooleanSupplier authorized,
            int newTask, String newPackage, String newBase) {
        boolean exactPlacementSafe;
        try {
            exactPlacementSafe = exactPlacement(remote, expected.getOldTask(), expected.getOldPackage(),
                    expected.getOldBase(), expected.getDisplayId(), expected.getIdentity()) == ExactTaskPlacement.REAR
                    && exactPlacement(remote, newTask, newPackage, newBase,
                        expected.getDisplayId(), expected.getIdentity()) == ExactTaskPlacement.MAIN
                    && selectedRearIdentityMatches(remote, expected.getDisplayId(), expected.getIdentity());
        } catch (Exception unavailable) { exactPlacementSafe = false; }
        if (!exactPlacementSafe) return failed("replacement_debug_main_focus_gate_unverified", true);
        try {
            String ownerTask = expected.getOldPackage() + ":" + expected.getOldTask();
            boolean safe = authorized.getAsBoolean() && RearAnimationManager.isCurrentDisplayAction(reservation)
                    && replacementJournalAtFrontier(expected,
                        LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                    && LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                        expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(),
                        expected.getIdentity(), expected.getOldGeneration())
                    && ownerTask.equals(RearScreenKeeperService.physicalCastTask())
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == expected.getOldGeneration()
                    && LegacyDisplayOccupancy.physicalKeeperStartClaimed(app, ownerTask,
                        expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration());
            if (safe && LegacyDisplayOccupancy.rollbackPhysicalReplacementOwner(app, expected.getToken(), true)
                    && LegacyDisplayOccupancy.finishPhysicalReplacement(app, expected.getToken(), "OWNER_A"))
                return failed("replacement_debug_main_focus_gate_closed", false);
        } catch (Exception persistenceFailure) { /* leave the exact journal for normal recovery */ }
        return failed("replacement_debug_main_focus_gate_unverified", true);
    }

    private static DebugPhysicalReplacementMainFocusGate.Evidence unknownGateEvidence(
            DebugPhysicalReplacementMainFocusGate.Reason reason) {
        return new DebugPhysicalReplacementMainFocusGate.Evidence(
                DebugPhysicalReplacementMainFocusGate.Classification.UNKNOWN, reason);
    }

    private static String gateReasonWire(String reason) {
        return reason == null ? null : reason;
    }

    private static String gateReasonWire(DebugPhysicalReplacementMainFocusGate.Reason reason) {
        return reason == null ? null : reason.wire;
    }

    private static DebugPhysicalReplacementMainFocusGate.Reason gateReason(String wire) {
        if (wire == null) return DebugPhysicalReplacementMainFocusGate.Reason.UNKNOWN;
        for (DebugPhysicalReplacementMainFocusGate.Reason reason
                : DebugPhysicalReplacementMainFocusGate.Reason.values()) {
            if (wire.equals(reason.wire)) return reason;
        }
        return DebugPhysicalReplacementMainFocusGate.Reason.UNKNOWN;
    }

    private static String safePresentationStatus(String status) {
        return "ready".equals(status) ? "ready" : "unknown";
    }

    interface ReplacementFinalChecks {
        boolean presentationReady();
        boolean finalInventoryAndSelectedReady();
        boolean finalLocalGateReady();
    }

    /** Enforced order: B presentation, final exact inventory/selected display, then non-blocking local gates. */
    static boolean runReplacementFinalSampleGate(ReplacementFinalChecks checks) {
        return checks != null && checks.presentationReady()
                && checks.finalInventoryAndSelectedReady() && checks.finalLocalGateReady();
    }

    private static boolean exactReplacementInventoryAndSelection(ITaskService remote,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected,
            int newTask, String newPackage, String newBase) {
        try {
            JSONObject inventory = new JSONObject(remote.getDisplayCastOccupancy(expected.getDisplayId()));
            if (!"ready".equals(replacementCandidateReadinessReason(inventory, expected.getOldTask(),
                    expected.getOldPackage(), expected.getOldBase(), newTask, newPackage, newBase,
                    expected.getDisplayId()))) return false;
            return selectedRearIdentityMatches(remote, expected.getDisplayId(), expected.getIdentity());
        } catch (Exception unknown) { return false; }
    }

    private static boolean exactOldPhysicalOwner(String oldPackage, int oldTask, long oldGeneration) {
        return (oldPackage + ":" + oldTask).equals(RearScreenKeeperService.physicalCastTask())
                && RearScreenKeeperService.physicalCastOwnerGeneration() == oldGeneration;
    }

    static boolean replacementJournalAtFrontier(
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected,
            LegacyDisplayOccupancy.PhysicalReplacementJournal current, String phase) {
        return expected != null && current != null && phase != null
                && phase.equals(current.getPhase()) && "NONE".equals(current.getDecision())
                && "NONE".equals(expected.getDecision())
                && sameReplacementJournalOperation(expected, current);
    }

    static boolean physicalReturnReceiptAllowsRestore(String status, boolean liveDispatchMarker) {
        return liveDispatchMarker && !"rejected".equals(status);
    }

    static boolean replacementOwnerBJournalMatches(
            LegacyDisplayOccupancy.PhysicalReplacementJournal original,
            LegacyDisplayOccupancy.PhysicalReplacementJournal current,
            String expectedSession, long expectedGeneration) {
        return original != null && current != null && expectedSession != null
                && original.getToken().equals(current.getToken())
                && current.getOldTask() == original.getOldTask()
                && current.getOldPackage().equals(original.getOldPackage())
                && current.getOldBase().equals(original.getOldBase())
                && current.getDisplayId() == original.getDisplayId()
                && current.getIdentity().equals(original.getIdentity())
                && current.getOldGeneration() == original.getOldGeneration()
                && current.getNewTask() == original.getNewTask()
                && current.getNewPackage().equals(original.getNewPackage())
                && current.getNewBase().equals(original.getNewBase())
                && current.getNewGeneration() == expectedGeneration
                && expectedGeneration == original.getNewGeneration()
                && expectedSession.equals(current.getNewSessionId())
                && "OWNER_B".equals(current.getPhase()) && "NONE".equals(current.getDecision());
    }

    private static boolean sameReplacementJournalOperation(
            LegacyDisplayOccupancy.PhysicalReplacementJournal a,
            LegacyDisplayOccupancy.PhysicalReplacementJournal b) {
        return a != null && b != null && a.getToken().equals(b.getToken())
                && a.getNewVirtual() == b.getNewVirtual() && a.getOldTask() == b.getOldTask()
                && a.getOldPackage().equals(b.getOldPackage()) && a.getOldBase().equals(b.getOldBase())
                && a.getDisplayId() == b.getDisplayId() && a.getIdentity().equals(b.getIdentity())
                && a.getOldGeneration() == b.getOldGeneration()
                && a.getOldLauncherDisabled() == b.getOldLauncherDisabled()
                && a.getNewTask() == b.getNewTask() && a.getNewPackage().equals(b.getNewPackage())
                && a.getNewBase().equals(b.getNewBase()) && a.getNewGeneration() == b.getNewGeneration()
                && a.getNewSessionId().equals(b.getNewSessionId())
                && a.getNewCoordinatorGeneration() == b.getNewCoordinatorGeneration()
                && a.getNewOwnerIdentity().equals(b.getNewOwnerIdentity())
                && a.getNewBackendGeneration() == b.getNewBackendGeneration()
                && a.getVirtualBaseline().equals(b.getVirtualBaseline())
                && a.getReaderState().equals(b.getReaderState()) && a.getReaderId().equals(b.getReaderId())
                && a.getReaderGeneration() == b.getReaderGeneration();
    }

    /** Only permits compensation across the known one-way physical A_RETURNING→OWNER_B session bind. */
    static boolean sameKnownPhysicalReplacementOwnerSession(
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected,
            LegacyDisplayOccupancy.PhysicalReplacementJournal live) {
        if (expected == null || live == null || expected.getNewVirtual() || live.getNewVirtual()
                || !("A_RETURNING".equals(expected.getPhase()) || "A_RETURNED".equals(expected.getPhase()))
                || !"OWNER_B".equals(live.getPhase()) || !"NONE".equals(expected.getDecision())
                || !"NONE".equals(live.getDecision()) || !expected.getNewSessionId().isEmpty()
                || expected.getNewBackendGeneration() != 0L) return false;
        String session = "physical:" + expected.getNewTask() + ":" + expected.getNewGeneration();
        return expected.getToken().equals(live.getToken())
                && expected.getOldTask() == live.getOldTask()
                && expected.getOldPackage().equals(live.getOldPackage())
                && expected.getOldBase().equals(live.getOldBase())
                && expected.getDisplayId() == live.getDisplayId()
                && expected.getIdentity().equals(live.getIdentity())
                && expected.getOldGeneration() == live.getOldGeneration()
                && expected.getOldLauncherDisabled() == live.getOldLauncherDisabled()
                && expected.getNewTask() == live.getNewTask()
                && expected.getNewPackage().equals(live.getNewPackage())
                && expected.getNewBase().equals(live.getNewBase())
                && expected.getNewGeneration() == live.getNewGeneration()
                && expected.getNewOwnerIdentity().equals(live.getNewOwnerIdentity())
                && expected.getNewCoordinatorGeneration() == live.getNewCoordinatorGeneration()
                && session.equals(live.getNewSessionId())
                && live.getNewBackendGeneration() == expected.getNewGeneration()
                && expected.getVirtualBaseline().equals(live.getVirtualBaseline())
                && expected.getReaderState().equals(live.getReaderState())
                && expected.getReaderId().equals(live.getReaderId())
                && expected.getReaderGeneration() == live.getReaderGeneration();
    }

    private static boolean isTransientPresentationNotReady(String reason) {
        return "window_task_missing".equals(reason) || "window_not_drawn_and_shown".equals(reason)
                || "input_window_missing".equals(reason) || "input_main_display_stale".equals(reason);
    }

    private static StartResult compensateReplacement(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation, LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            boolean movedB, boolean movedAHome, boolean stoppedAKeeper, String failure) {
        return compensateReplacement(app, remote, reservation, journal, movedB, movedAHome,
                stoppedAKeeper, failure, true);
    }

    private static StartResult compensateReplacement(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation, LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            boolean movedB, boolean movedAHome, boolean stoppedAKeeper, String failure,
            boolean mayRestoreAFromMain) {
        try {
            Log.i(TAG, "physical_replace phase=rollback_begin");
            boolean committedToB = ownerIsNew(app, journal);
            if (committedToB) {
                String exactB = journal.getNewPackage() + ":" + journal.getNewTask();
                String liveTask = RearScreenKeeperService.physicalCastTask();
                long liveGeneration = RearScreenKeeperService.physicalCastOwnerGeneration();
                if (exactB.equals(liveTask) && liveGeneration == journal.getNewGeneration()
                        && !RearScreenKeeperService.stopPhysicalSession(exactB, journal.getNewGeneration()))
                    return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                            journal.getNewGeneration(), true, failure);
                if (liveTask != null && (!exactB.equals(liveTask) || liveGeneration != journal.getNewGeneration()))
                    return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                            journal.getNewGeneration(), false, failure);
            }
            if (journal.getNewGeneration() > 0L && journal.getNewTask() > 0) {
                ExactTaskPlacement b = exactPlacement(remote, journal.getNewTask(), journal.getNewPackage(),
                        journal.getNewBase(), journal.getDisplayId(), journal.getIdentity());
                Log.i(TAG, "physical_replace phase=rollback_b_readback placement=" + b.name());
                if (b == ExactTaskPlacement.REAR || b == ExactTaskPlacement.UNKNOWN) {
                    if (!RearAnimationManager.isCurrentDisplayAction(reservation)) return recoverable(
                            "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(), journal.getNewGeneration(), false, failure);
                    if (b == ExactTaskPlacement.UNKNOWN
                            && !canAttemptExactUnknownBCompensation(app, remote, reservation, journal))
                        return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                                journal.getNewGeneration(), false, failure + "_b_source_not_authorized");
                    String moved = controlledMove(remote, journal.getNewTask(), journal.getNewPackage(), journal.getNewBase(),
                            journal.getDisplayId(), 0, journal.getIdentity());
                    Log.i(TAG, "physical_replace phase=rollback_b_move result="
                            + safeMoveResult(new JSONObject(moved)));
                    if (!"moved".equals(new JSONObject(moved).optString("status"))
                            && exactPlacement(remote, journal.getNewTask(), journal.getNewPackage(),
                                    journal.getNewBase(), journal.getDisplayId(), journal.getIdentity())
                                != ExactTaskPlacement.MAIN) return recoverable(
                            "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                            journal.getNewGeneration(), false, failure);
                } else if (b != ExactTaskPlacement.MAIN) return recoverable(
                        "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(), journal.getNewGeneration(), false, failure);
            }
            ExactTaskPlacement a = exactPlacement(remote, journal.getOldTask(), journal.getOldPackage(),
                    journal.getOldBase(), journal.getDisplayId(), journal.getIdentity());
            Log.i(TAG, "physical_replace phase=rollback_a_readback placement=" + a.name());
            if (a == ExactTaskPlacement.MAIN) {
                if (!mayRestoreAFromMain) return recoverable(
                        "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                        journal.getNewGeneration(), false, failure + "_explicit_close_a_already_main");
                if (!canRestoreOldTaskToRear(app, remote, reservation, journal)) return recoverable(
                        "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                        journal.getNewGeneration(), false, failure + "_a_restore_gate_unknown");
                final LegacyDisplayOccupancy.PhysicalReplacementJournal restoreExpected = journal;
                IPhysicalReplacementMoveGate restoreGate = new IPhysicalReplacementMoveGate.Stub() {
                    private boolean exactRequest(String requestToken, int requestOldTask, String requestOldPackage,
                            String requestOldBase, long requestOldGeneration, int requestNewTask,
                            String requestNewPackage, String requestNewBase, long requestNewGeneration,
                            long requestCoordinatorGeneration, String requestOwnerIdentity,
                            int requestDisplay, String requestRearIdentity) {
                        return restoreExpected.getToken().equals(requestToken)
                                && restoreExpected.getOldTask() == requestOldTask
                                && restoreExpected.getOldPackage().equals(requestOldPackage)
                                && restoreExpected.getOldBase().equals(requestOldBase)
                                && restoreExpected.getOldGeneration() == requestOldGeneration
                                && restoreExpected.getNewTask() == requestNewTask
                                && restoreExpected.getNewPackage().equals(requestNewPackage)
                                && restoreExpected.getNewBase().equals(requestNewBase)
                                && restoreExpected.getNewGeneration() == requestNewGeneration
                                && restoreExpected.getNewCoordinatorGeneration() == requestCoordinatorGeneration
                                && restoreExpected.getNewOwnerIdentity().equals(requestOwnerIdentity)
                                && restoreExpected.getDisplayId() == requestDisplay
                                && restoreExpected.getIdentity().equals(requestRearIdentity);
                    }
                    @Override public boolean isCurrent(String requestToken, int requestOldTask,
                            String requestOldPackage, String requestOldBase, long requestOldGeneration,
                            int requestNewTask, String requestNewPackage, String requestNewBase,
                            long requestNewGeneration, long requestCoordinatorGeneration,
                            String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                        return exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                                requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                                requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                                requestDisplay, requestRearIdentity)
                                && exactPhysicalReplacementRestoreActionCurrent(app, reservation, restoreExpected);
                    }
                    @Override public boolean authorizeDispatch(String requestToken, int requestOldTask,
                            String requestOldPackage, String requestOldBase, long requestOldGeneration,
                            int requestNewTask, String requestNewPackage, String requestNewBase,
                            long requestNewGeneration, long requestCoordinatorGeneration,
                            String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                        return isCurrent(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                                requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                                requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                                requestDisplay, requestRearIdentity);
                    }
                    @Override public boolean onMoveMayHaveBeenSent(String requestToken, int requestOldTask,
                            String requestOldPackage, String requestOldBase, long requestOldGeneration,
                            int requestNewTask, String requestNewPackage, String requestNewBase,
                            long requestNewGeneration, long requestCoordinatorGeneration,
                            String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                        return exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                                requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                                requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                                requestDisplay, requestRearIdentity);
                    }
                    @Override public boolean onRejectedBeforeSend(String requestToken, int requestOldTask,
                            String requestOldPackage, String requestOldBase, long requestOldGeneration,
                            int requestNewTask, String requestNewPackage, String requestNewBase,
                            long requestNewGeneration, long requestCoordinatorGeneration,
                            String requestOwnerIdentity, int requestDisplay, String requestRearIdentity) {
                        // The original A-to-MAIN receipt remains valid; this rejected restoration may be retried.
                        return exactRequest(requestToken, requestOldTask, requestOldPackage, requestOldBase,
                                requestOldGeneration, requestNewTask, requestNewPackage, requestNewBase,
                                requestNewGeneration, requestCoordinatorGeneration, requestOwnerIdentity,
                                requestDisplay, requestRearIdentity);
                    }
                };
                String moved = remote.restorePhysicalAForPhysicalReplacement(journal.getOldTask(),
                        journal.getOldPackage(), journal.getOldBase(), journal.getDisplayId(), journal.getIdentity(),
                        journal.getToken(), journal.getOldGeneration(), journal.getNewTask(),
                        journal.getNewPackage(), journal.getNewBase(), journal.getNewGeneration(),
                        journal.getNewCoordinatorGeneration(), journal.getNewOwnerIdentity(), restoreGate);
                if (!"moved".equals(new JSONObject(moved).optString("status"))) return recoverable(
                        "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(), journal.getNewGeneration(), false, failure);
            } else if (a != ExactTaskPlacement.REAR) return recoverable(
                    "physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(), journal.getNewGeneration(), false, failure);
            if (exactPlacement(remote, journal.getOldTask(), journal.getOldPackage(), journal.getOldBase(),
                    journal.getDisplayId(), journal.getIdentity()) != ExactTaskPlacement.REAR
                    || exactPlacement(remote, journal.getNewTask(), journal.getNewPackage(), journal.getNewBase(),
                    journal.getDisplayId(), journal.getIdentity()) != ExactTaskPlacement.MAIN)
                return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                        journal.getNewGeneration(), false, failure);
            JSONObject restoredSnapshot = new JSONObject(remote.getDisplayCastOccupancy(journal.getDisplayId()));
            JSONObject restoredPresentation = new JSONObject(remote.queryExactPhysicalPresentation(journal.getOldTask(),
                    journal.getOldPackage(), journal.getOldBase(), journal.getDisplayId(), journal.getIdentity()));
            logPhysicalPresentation("rollback_old_frame", restoredPresentation);
            final boolean[] oldFrameRetryAttempted = {false};
            restoredPresentation = retryTransientRollbackOldFrameOnce(failure, restoredPresentation,
                    new RollbackOldFrameRetryPort() {
                @Override public boolean revalidateExactRollbackState() {
                    boolean exact = exactRollbackOldFrameRetryInventory(app, remote, reservation, journal) != null;
                    if (!exact) Log.i(TAG, "physical_replace phase=rollback_old_frame_retry"
                            + " event=skipped reason=exact_state_or_inventory_unverified");
                    return exact;
                }
                @Override public boolean revalidateLocalRetryAuthority() {
                    return exactRollbackOldFrameRetryLocalGate(app, reservation, journal);
                }
                @Override public JSONObject queryExactOldPresentation() throws Exception {
                    oldFrameRetryAttempted[0] = true;
                    Log.i(TAG, "physical_replace phase=rollback_old_frame_retry"
                            + " event=started reason=window_not_drawn_and_shown");
                    return new JSONObject(remote.queryExactPhysicalPresentation(journal.getOldTask(),
                            journal.getOldPackage(), journal.getOldBase(), journal.getDisplayId(), journal.getIdentity()));
                }
            });
            if (oldFrameRetryAttempted[0]) logPhysicalPresentation("rollback_old_frame_retry", restoredPresentation);
            if (oldFrameRetryAttempted[0] && exactPhysicalPresentationReady(restoredPresentation,
                    journal.getOldTask(), journal.getOldPackage(), journal.getOldBase(),
                    journal.getDisplayId(), journal.getIdentity())) {
                restoredSnapshot = exactRollbackOldFrameRetryInventory(app, remote, reservation, journal);
            }
            if (!exactRestoredPhysicalFrameProof(restoredSnapshot, restoredPresentation,
                    journal.getOldTask(), journal.getOldPackage(), journal.getOldBase(),
                    journal.getDisplayId(), journal.getIdentity())) return recoverable(
                    "physical:" + journal.getOldTask() + ":" + journal.getOldGeneration(),
                    journal.getOldGeneration(), false, failure + "_old_frame_unverified");
            String exactA = journal.getOldPackage() + ":" + journal.getOldTask();
            String liveTask = RearScreenKeeperService.physicalCastTask();
            long liveGeneration = RearScreenKeeperService.physicalCastOwnerGeneration();
            boolean keeperAConfirmed = exactA.equals(liveTask) && liveGeneration == journal.getOldGeneration();
            long ownerAGeneration = LegacyDisplayOccupancy.physicalProjectionGeneration(app, journal.getOldTask(), journal.getDisplayId());
            long ownerBGeneration = LegacyDisplayOccupancy.physicalProjectionGeneration(app, journal.getNewTask(), journal.getDisplayId());
            boolean retryOwnerState = oldFrameRetryAttempted[0]
                    ? retryRollbackOwnerStateAllowsCommit(ownerAGeneration,
                            ownerBGeneration, journal.getOldGeneration())
                    : ownerAGeneration == journal.getOldGeneration()
                        || ownerBGeneration == journal.getNewGeneration()
                        || (ownerAGeneration == 0L && ownerBGeneration == 0L
                            && LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(
                                    app, journal.getDisplayId()) == null);
            if (!retryOwnerState || (oldFrameRetryAttempted[0]
                    && !exactRollbackOldFrameRetryLocalGate(app, reservation, journal)))
                return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                        journal.getNewGeneration(), false, failure);
            if (!LegacyDisplayOccupancy.rollbackPhysicalReplacementOwner(app, journal.getToken(), keeperAConfirmed))
                return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                        journal.getNewGeneration(), false, failure);
            if (!keeperAConfirmed) {
                if (liveTask != null || !startExactPhysicalKeeper(app, journal.getOldPackage(), journal.getOldTask(),
                        journal.getDisplayId(), journal.getIdentity(), journal.getOldGeneration()))
                    return recoverable("physical:" + journal.getOldTask() + ":" + journal.getOldGeneration(),
                            journal.getOldGeneration(), false, failure);
                keeperAConfirmed = exactA.equals(RearScreenKeeperService.physicalCastTask())
                        && RearScreenKeeperService.physicalCastOwnerGeneration() == journal.getOldGeneration();
            }
            if (!keeperAConfirmed) return recoverable("physical:" + journal.getOldTask() + ":" + journal.getOldGeneration(),
                    journal.getOldGeneration(), false, failure);
            if (!LegacyDisplayOccupancy.finishPhysicalReplacement(app, journal.getToken(), "OWNER_A"))
                return recoverable("physical:" + journal.getOldTask() + ":" + journal.getOldGeneration(),
                        journal.getOldGeneration(), stoppedAKeeper, failure);
            if ("test_late_failure_after_b_visible".equals(failure)) {
                DebugPhysicalReplacementLateFailure.recordRollbackVerified(journal.getOldTask(),
                        journal.getOldGeneration(), journal.getNewTask(), journal.getDisplayId(),
                        journal.getIdentity());
                Log.i(TAG, "test_fixture_cleanup_verified=true stage=after_b_visible_before_a_return");
            }
            return failed(failure, false);
        } catch (Exception unknown) {
            return recoverable("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration(),
                    journal.getNewGeneration(), false, failure + "_compensation_unknown");
        }
    }

    interface RollbackOldFrameRetryPort {
        boolean revalidateExactRollbackState() throws Exception;
        boolean revalidateLocalRetryAuthority() throws Exception;
        JSONObject queryExactOldPresentation() throws Exception;
    }

    /** One observation for the draw-settle race only; the caller still applies the full exact-ready proof. */
    static JSONObject retryTransientRollbackOldFrameOnce(String failure, JSONObject first,
            RollbackOldFrameRetryPort port) throws Exception {
        if (!"replacement_readiness_timeout_or_unknown".equals(failure) || first == null || port == null
                || !"unknown".equals(first.optString("status"))
                || !"window_not_drawn_and_shown".equals(first.optString("reason"))) return first;
        if (!port.revalidateExactRollbackState()) return first;
        JSONObject retry = port.queryExactOldPresentation();
        return port.revalidateLocalRetryAuthority() ? retry : first;
    }

    private static boolean exactRollbackOldFrameRetryLocalGate(Context app,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        return app != null && expected != null && RearAnimationManager.isCurrentDisplayAction(reservation)
                && replacementJournalAtFrontier(expected,
                        LegacyDisplayOccupancy.physicalReplacementJournal(app), "PREPARING")
                && !expected.getNewVirtual()
                && !ownerIsNew(app, expected)
                && LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                        expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(),
                        expected.getIdentity(), expected.getOldGeneration())
                && exactOldPhysicalOwner(expected.getOldPackage(), expected.getOldTask(),
                        expected.getOldGeneration());
    }

    static boolean retryRollbackOwnerStateAllowsCommit(long ownerAGeneration,
            long ownerBGeneration, long expectedAGeneration) {
        return expectedAGeneration > 0L && ownerAGeneration == expectedAGeneration && ownerBGeneration == 0L;
    }

    private static JSONObject exactRollbackOldFrameRetryInventory(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        try {
            if (!exactRollbackOldFrameRetryLocalGate(app, reservation, expected)
                    || !selectedRearIdentityMatches(remote, expected.getDisplayId(), expected.getIdentity())
                    || exactPlacement(remote, expected.getOldTask(), expected.getOldPackage(),
                        expected.getOldBase(), expected.getDisplayId(), expected.getIdentity())
                        != ExactTaskPlacement.REAR
                    || exactPlacement(remote, expected.getNewTask(), expected.getNewPackage(),
                        expected.getNewBase(), expected.getDisplayId(), expected.getIdentity())
                        != ExactTaskPlacement.MAIN) return null;
            JSONObject inventory = new JSONObject(remote.getDisplayCastOccupancy(expected.getDisplayId()));
            return exactPhysicalTaskObservationSnapshotWithHiddenLauncher(inventory, expected.getOldTask(),
                    expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId())
                    && exactRollbackOldFrameRetryLocalGate(app, reservation, expected) ? inventory : null;
        } catch (Exception unknown) { return null; }
    }

    private static boolean ownerIsNew(Context context, LegacyDisplayOccupancy.PhysicalReplacementJournal journal) {
        return LegacyDisplayOccupancy.physicalProjectionGeneration(context, journal.getNewTask(), journal.getDisplayId())
                == journal.getNewGeneration();
    }

    private static boolean canRestoreOldTaskToRear(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation, LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        try {
            LegacyDisplayOccupancy.PhysicalReplacementJournal live = LegacyDisplayOccupancy.physicalReplacementJournal(app);
            if (live == null || (!sameReplacementJournalOperation(expected, live)
                    && !sameKnownPhysicalReplacementOwnerSession(expected, live))
                    || !"NONE".equals(expected.getDecision())
                    || !"NONE".equals(live.getDecision())
                    || !replacementRestorePhaseAllowed(live.getPhase(), !live.getNewVirtual(),
                        LegacyDisplayOccupancy.physicalAReturnMoveAuthorized(app, live))
                    || !RearAnimationManager.isCurrentDisplayAction(reservation)
                    || !selectedRearIdentityMatches(remote, expected.getDisplayId(), expected.getIdentity())) return false;
            boolean ownerA = LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                    expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(), expected.getIdentity(),
                    expected.getOldGeneration());
            boolean ownerB = LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getNewTask(),
                    expected.getNewPackage(), expected.getNewBase(), expected.getDisplayId(), expected.getIdentity(),
                    expected.getNewGeneration());
            ExactTaskPlacement b = exactPlacement(remote, expected.getNewTask(), expected.getNewPackage(),
                    expected.getNewBase(), expected.getDisplayId(), expected.getIdentity());
            String occupancy = remote.getDisplayCastOccupancy(expected.getDisplayId());
            boolean restoringAnAuthorizedReturn = "A_RETURNING".equals(live.getPhase());
            boolean exactRestoringOwner = restoringAnAuthorizedReturn
                    ? ownerA && exactOldPhysicalOwner(expected.getOldPackage(), expected.getOldTask(),
                        expected.getOldGeneration())
                    : ownerA || ownerB;
            return replacementRestoreGate(expected.getToken(), live.getToken(), live.getPhase(),
                    !live.getNewVirtual(), LegacyDisplayOccupancy.physicalAReturnMoveAuthorized(app, live), exactRestoringOwner,
                    b == ExactTaskPlacement.MAIN,
                    RearDisplayCastOccupancy.emptyOrOnlySystemLauncher(occupancy, expected.getDisplayId()),
                    RearAnimationManager.isCurrentDisplayAction(reservation));
        } catch (Exception unknown) { return false; }
    }

    /** Cheap callback for txn85 after the service's final typed occupancy/root checks. */
    private static boolean exactPhysicalReplacementRestoreActionCurrent(Context app,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        try {
            LegacyDisplayOccupancy.PhysicalReplacementJournal live = LegacyDisplayOccupancy.physicalReplacementJournal(app);
            if (live == null || (!sameReplacementJournalOperation(expected, live)
                    && !sameKnownPhysicalReplacementOwnerSession(expected, live))
                    || !"NONE".equals(live.getDecision())
                    || !replacementRestorePhaseAllowed(live.getPhase(), !live.getNewVirtual(),
                        LegacyDisplayOccupancy.physicalAReturnMoveAuthorized(app, live))
                    || !LegacyDisplayOccupancy.physicalAReturnMoveAuthorized(app, live)
                    || !RearAnimationManager.isCurrentDisplayAction(reservation)) return false;
            boolean ownerA = LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                    expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(), expected.getIdentity(),
                    expected.getOldGeneration());
            boolean ownerB = LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getNewTask(),
                    expected.getNewPackage(), expected.getNewBase(), expected.getDisplayId(), expected.getIdentity(),
                    expected.getNewGeneration());
            if ("A_RETURNING".equals(live.getPhase()))
                return ownerA && exactOldPhysicalOwner(expected.getOldPackage(), expected.getOldTask(),
                        expected.getOldGeneration());
            return ownerA || ownerB;
        } catch (Exception unknown) { return false; }
    }

    static boolean replacementRestoreGate(String expectedToken, String liveToken, String phase,
            boolean physicalReplacement, boolean aReturnMoveAuthorized, boolean exactOwner,
            boolean candidateKnownMain, boolean rearEmptyOrLauncher, boolean actionCurrent) {
        return expectedToken != null && expectedToken.equals(liveToken)
                && replacementRestorePhaseAllowed(phase, physicalReplacement, aReturnMoveAuthorized)
                && exactOwner && candidateKnownMain && rearEmptyOrLauncher && actionCurrent;
    }

    private static boolean replacementRestorePhaseAllowed(String phase, boolean physicalReplacement,
            boolean aReturnMoveAuthorized) {
        return physicalReplacement && aReturnMoveAuthorized
                && ("A_RETURNING".equals(phase) || "A_RETURNED".equals(phase) || "OWNER_B".equals(phase));
    }

    static boolean replacementFinalReadbackReady(ExactTaskPlacement oldPlacement,
            ExactTaskPlacement newPlacement, boolean exactPresentationReady, String journalPhase) {
        return oldPlacement == ExactTaskPlacement.MAIN && newPlacement == ExactTaskPlacement.REAR
                && exactPresentationReady && "OWNER_B".equals(journalPhase);
    }

    /** Coordinator acceptance closes A's logical handle; only then may the durable B transaction journal retire. */
    public static boolean confirmReplacement(Context context, String newSessionId) {
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal = LegacyDisplayOccupancy.physicalReplacementJournal(context);
        if (journal == null) return true;
        if ("ACCEPTED_B".equals(journal.getPhase()) && !journal.getNewVirtual()) {
            if (!newSessionId.equals(journal.getNewSessionId())) return false;
            return LegacyDisplayOccupancy.finishPhysicalReplacement(context, journal.getToken(), "ACCEPTED_B");
        }
        return "ACCEPTED_VIRTUAL".equals(journal.getPhase()) && newSessionId.equals(journal.getNewSessionId());
    }

    /** If the Coordinator rejects a completed-but-unconsumed ReplaceReady, restore A from the same journal. */
    public static boolean rollbackReplacement(Context context, ITaskService remote, String newSessionId) {
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal = LegacyDisplayOccupancy.physicalReplacementJournal(context);
        if (journal == null) return false;
        if ("ACCEPTED_B".equals(journal.getPhase()) || "ACCEPTED_VIRTUAL".equals(journal.getPhase())) return false;
        if (!newSessionId.equals(journal.getNewSessionId())) return false;
        if (!("physical:" + journal.getNewTask() + ":" + journal.getNewGeneration()).equals(newSessionId))
            return rollbackPhysicalToVirtual(context, remote, journal, newSessionId);
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return false;
        try {
            StartResult result = compensateReplacement(context.getApplicationContext(), remote, reservation,
                    journal, true, true, true, "replacement_coordinator_rejected");
            return !result.indeterminate && !result.committed
                    && LegacyDisplayOccupancy.physicalReplacementJournal(context) == null;
        } finally { RearAnimationManager.completeDisplayAction(reservation); }
    }

    /** Explicit recovery entry for the persisted journal, including process death before ReplaceReady. */
    public static boolean recoverPhysicalReplacement(Context context, ITaskService remote) {
        return recoverPhysicalReplacement(context, remote, true);
    }

    /** A user's exact old-owner close may recover B, but must never resurrect A from MAIN. */
    private static boolean recoverPhysicalReplacement(Context context, ITaskService remote,
            boolean mayRestoreAFromMain) {
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal = LegacyDisplayOccupancy.physicalReplacementJournal(context);
        if (journal == null) return true;
        String recoveryRoute = LegacyDisplayOccupancy.replacementRecoveryRoute(journal.getPhase(),
                journal.getNewVirtual(), !journal.getNewSessionId().isBlank());
        if ("KEEP_ACCEPTED_B".equals(recoveryRoute)) return false; // B stays the owner; never resurrect A.
        if ("CLOSE_ACCEPTED_VIRTUAL".equals(recoveryRoute)) {
            if (!journal.getNewVirtual() || journal.getNewSessionId().isBlank()) return false;
            try {
                JSONObject status = new JSONObject(remote.getRearVirtualCastStatus());
                if (!matchesVirtualSession(status, journal)) return false;
                return finalizeAcceptedVirtualReplacement(context, remote, journal, status);
            } catch (Exception unknown) { return false; }
        }
        if (!"ROLLBACK_TO_A".equals(recoveryRoute)) return false;
        if (journal.getNewVirtual()) {
            if (journal.getNewSessionId().isBlank()) {
                if (!java.util.Arrays.asList("PREPARING", "OWNER_A").contains(journal.getPhase())) return false;
                return discardUnmovedPhysicalToVirtual(context, remote, journal.getToken(), journal.getToken());
            }
            return rollbackPhysicalToVirtual(context, remote, journal, journal.getNewSessionId());
        }
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return false;
        try {
            StartResult result = compensateReplacement(context.getApplicationContext(), remote,
                    reservation, journal, true, true, true, "replacement_user_recovery", mayRestoreAFromMain);
            return !result.indeterminate && !result.committed
                    && LegacyDisplayOccupancy.physicalReplacementJournal(context) == null;
        } finally { RearAnimationManager.completeDisplayAction(reservation); }
    }

    /** P→V keeps A intact until the ordinary virtual path has a real output frame and live input route. */
    public static boolean completePhysicalToVirtualReplacement(Context context, ITaskService remote,
            String token, String virtualSessionId, int newTask, String newPackage, String newBase,
            long newCoordinatorGeneration, String newOwnerIdentity, long backendGeneration,
            BooleanSupplier authorized) {
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal = LegacyDisplayOccupancy.physicalReplacementJournal(context);
        if (journal == null || !token.equals(journal.getToken()) || !"PREPARING".equals(journal.getPhase())
                || !"NONE".equals(journal.getDecision()) || !journal.getNewVirtual()
                || journal.getNewTask() != newTask || !journal.getNewPackage().equals(newPackage)
                || !journal.getNewBase().equals(newBase)
                || !virtualSessionId.equals(journal.getNewSessionId())
                || backendGeneration != journal.getNewBackendGeneration()
                || !"READY".equals(journal.getReaderState()) || journal.getReaderId().isBlank()
                || journal.getReaderGeneration() <= 0L
                || newCoordinatorGeneration != journal.getNewCoordinatorGeneration()
                || !newOwnerIdentity.equals(journal.getNewOwnerIdentity()) || backendGeneration <= 0L
                || !safeAuthorized(authorized)) return false;
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return false;
        try {
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            String oldExact = journal.getOldPackage() + ":" + journal.getOldTask();
            if (selected == null || !selected.matches(journal.getDisplayId(), journal.getIdentity())
                    || exactPlacement(remote, journal.getOldTask(), journal.getOldPackage(), journal.getOldBase(),
                        journal.getDisplayId(), journal.getIdentity()) != ExactTaskPlacement.REAR
                    || !oldExact.equals(RearScreenKeeperService.physicalCastTask())
                    || RearScreenKeeperService.physicalCastOwnerGeneration() != journal.getOldGeneration()
                    || !authorized.getAsBoolean() || !RearAnimationManager.isCurrentDisplayAction(reservation)
                    || !exactVirtualReplacementSessionReady(remote, journal, true)) return false;
            // The single full B receipt (including PixelCopy) and exact A proof precede the durable B_READY CAS.
            LegacyDisplayOccupancy.PhysicalReplacementJournal bReady =
                    LegacyDisplayOccupancy.advancePhysicalReplacementToBReady(context, journal);
            if (bReady == null) return false;
            // Advance with the exact current B_READY snapshot. The returned journal is read back under the CAS lock.
            LegacyDisplayOccupancy.PhysicalReplacementJournal aReturning =
                    LegacyDisplayOccupancy.advancePhysicalReplacementToAReturning(context, bReady);
            if (aReturning == null) return false;
            ISceneTaskActionGate moveA = new ISceneTaskActionGate.Stub() {
                @Override public boolean isCurrent() {
                    LegacyDisplayOccupancy.PhysicalReplacementJournal live =
                            LegacyDisplayOccupancy.physicalReplacementJournal(context);
                    return live != null && live.equals(aReturning) && "A_RETURNING".equals(live.getPhase())
                            && "NONE".equals(live.getDecision())
                            && oldExact.equals(RearScreenKeeperService.physicalCastTask())
                            && RearScreenKeeperService.physicalCastOwnerGeneration() == live.getOldGeneration()
                            && LegacyDisplayOccupancy.isCurrentClaimedPhysicalReplacementOwner(context, live)
                            && safeAuthorized(authorized) && RearAnimationManager.isCurrentDisplayAction(reservation);
                }
                @Override public boolean isCurrentBeforeTargetMove() { return isCurrent(); }
            };
            String moved = remote.returnPhysicalAForVirtualReplacement(journal.getOldTask(),
                    journal.getOldPackage(), journal.getOldBase(), journal.getDisplayId(), journal.getIdentity(),
                    token, newCoordinatorGeneration, newOwnerIdentity, virtualSessionId, backendGeneration,
                    newTask, newBase, journal.getReaderId(), journal.getReaderGeneration(), moveA);
            if (!"moved".equals(new JSONObject(moved).optString("status"))
                    || exactPlacement(remote, journal.getOldTask(), journal.getOldPackage(), journal.getOldBase(),
                        journal.getDisplayId(), journal.getIdentity()) != ExactTaskPlacement.MAIN
                    || !exactVirtualReplacementSessionReady(remote, journal, false)
                    || !authorized.getAsBoolean()) return false;
            if (!RearScreenKeeperService.stopPhysicalSession(oldExact, journal.getOldGeneration())) return false;
            // P→V deliberately leaves OEM launcher restoration to B's exact exit path.
            // Starting it here would cover the still-running virtual candidate before handoff.
            if (!authorized.getAsBoolean()
                    || !LegacyDisplayOccupancy.advancePhysicalReplacement(context, token, "A_RETURNING", "A_RETURNED")) return false;
            android.util.Log.i(TAG, "physical_replace mode=P_V phase=A_RETURNED result=verified");
            Intent keeper = new Intent(context, RearScreenKeeperService.class)
                    .putExtra(VirtualCastUriFlow.EXTRA_SESSION, virtualSessionId)
                    .putExtra("lastMovedTask", newPackage + ":" + newTask)
                    .putExtra("keepScreenOnEnabled", context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                            .getBoolean("flutter.keep_screen_on_enabled", true));
            ComponentName started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? context.startForegroundService(keeper) : context.startService(keeper);
            if (started == null || !RearScreenKeeperService.awaitVirtualSessionClaim(virtualSessionId, 1500L)
                    || !authorized.getAsBoolean()) return false;
            boolean ownerTransferred = LegacyDisplayOccupancy.clearPhysicalOwnerForVirtualReplacement(context, token);
            if (ownerTransferred) android.util.Log.i(TAG, "physical_replace mode=P_V phase=OWNER_VIRTUAL result=committed");
            return ownerTransferred;
        } catch (Exception failure) { return false; }
        finally { RearAnimationManager.completeDisplayAction(reservation); }
    }

    private static boolean safeAuthorized(BooleanSupplier authorized) {
        try { return authorized != null && authorized.getAsBoolean(); }
        catch (RuntimeException stale) { return false; }
    }

    /** Clears a P→V journal only after an exact candidate operation is observed stopped and A stayed live. */
    public static boolean discardUnmovedPhysicalToVirtual(Context context, ITaskService remote,
            String token, String operationId) {
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal = LegacyDisplayOccupancy.physicalReplacementJournal(context);
        if (journal == null || !token.equals(journal.getToken())
                || !java.util.Arrays.asList("PREPARING", "OWNER_A").contains(journal.getPhase())) return false;
        if (!operationId.equals(journal.getToken())) return false;
        return rollbackPhysicalToVirtual(context, remote, journal, journal.getNewSessionId());
    }

    /** Reverses an unaccepted P→V handoff from its durable A journal; every unknown keeps the journal. */
    public static boolean rollbackPhysicalToVirtual(Context context, ITaskService remote,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, String virtualSessionId) {
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return false;
        try {
            LegacyDisplayOccupancy.PhysicalReplacementJournal live =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            if (live == null || !sameReplacementAuthority(journal, live)) return false;
            journal = live;
            String id = virtualSessionId == null ? "" : virtualSessionId;
            JSONObject status = new JSONObject(remote.getRearVirtualCastStatus());
            if (id.isEmpty() && journal.getNewSessionId().isBlank()
                    && "PREPARING".equals(journal.getPhase())
                    && journal.getToken().equals(status.optString("coordinatorOperationId"))
                    && journal.getNewCoordinatorGeneration() == status.optLong("ownerGeneration", -1L)
                    && journal.getNewOwnerIdentity().equals(status.optString("ownerIdentity"))
                    && journal.getNewTask() == status.optInt("task", -1)
                    && journal.getNewBase().equals(status.optString("intendedBaseComponent"))) {
                id = status.optString("id");
                long backendGeneration = status.optLong("generation", -1L);
                if (id.isBlank() || backendGeneration <= 0L || !LegacyDisplayOccupancy.setPhysicalReplacementSession(
                        context, journal.getToken(), "PREPARING", id, backendGeneration)) return false;
                journal = LegacyDisplayOccupancy.physicalReplacementJournal(context);
                if (journal == null) return false;
            }
            SelectedRearIdentity rollbackDisplay = readSelectedRearIdentity(remote);
            if (rollbackDisplay == null || !rollbackDisplay.matches(journal.getDisplayId(), journal.getIdentity()))
                return false;
            ExactTaskPlacement beforeStopA = exactPlacement(remote, journal.getOldTask(), journal.getOldPackage(),
                    journal.getOldBase(), journal.getDisplayId(), journal.getIdentity());
            String beforeStopOwnerFact = LegacyDisplayOccupancy.physicalReplacementOwnerFact(context, journal);
            String beforeStopOccupancy = remote.getDisplayCastOccupancy(journal.getDisplayId());
            boolean unallocatedRollbackProof = false;
            if (id.isEmpty()) {
                boolean exactStoppedBaseline = matchesUnallocatedVirtualBaseline(status, journal.getVirtualBaseline());
                boolean singletonA = RearDisplayCastOccupancy.containsOnlyExactTask(beforeStopOccupancy,
                        journal.getDisplayId(), journal.getOldTask(), journal.getOldPackage(), journal.getOldBase());
                if (!PhysicalReplacementLifecycle.unallocatedRollbackCandidate(journal.getPhase(),
                        journal.getNewSessionId(), journal.getReaderState(), exactStoppedBaseline,
                        "SAME_A".equals(beforeStopOwnerFact), beforeStopA.name(), singletonA))
                    return false;
                unallocatedRollbackProof = true;
            } else if (!matchesVirtualSession(status, journal)
                    || !p2vPreStopInventoryAllowed(beforeStopA, beforeStopOwnerFact, beforeStopOccupancy,
                        journal, status, new ComponentName(context, VirtualRearHostActivity.class).flattenToString(),
                        RearSystemBridge::isValidatedRearLauncher)
                    || !exactP2vHostOccupancy(context, remote, status, journal, beforeStopOccupancy, true)) {
                return false;
            }
            final String rollbackId = id;
            final LegacyDisplayOccupancy.PhysicalReplacementJournal initialJournal = journal;
            final LegacyDisplayOccupancy.PhysicalReplacementJournal[] stateJournal = {journal};
            final JSONObject[] stateStatus = {status};
            final boolean[] exactUnallocatedReceipt = {unallocatedRollbackProof};
            return PhysicalReplacementRollbackFlow.execute(
                    () -> LegacyDisplayOccupancy.claimPhysicalReplacementRollback(context, initialJournal.getToken(),
                            initialJournal.getNewSessionId(), initialJournal.getNewBackendGeneration(),
                            initialJournal.getNewCoordinatorGeneration(), initialJournal.getNewOwnerIdentity(),
                            initialJournal.getNewTask(), initialJournal.getNewBase(), initialJournal.getDisplayId(), initialJournal.getIdentity()),
                    () -> {
                        LegacyDisplayOccupancy.PhysicalReplacementJournal claimed =
                                LegacyDisplayOccupancy.physicalReplacementJournal(context);
                        JSONObject fresh = new JSONObject(remote.getRearVirtualCastStatus());
                        SelectedRearIdentity selectedNow = readSelectedRearIdentity(remote);
                        ExactTaskPlacement currentA = exactPlacement(remote, initialJournal.getOldTask(), initialJournal.getOldPackage(),
                                initialJournal.getOldBase(), initialJournal.getDisplayId(), initialJournal.getIdentity());
                        String currentOwnerFact = claimed == null ? "UNKNOWN"
                                : LegacyDisplayOccupancy.physicalReplacementOwnerFact(context, claimed);
                        String currentOccupancy = remote.getDisplayCastOccupancy(initialJournal.getDisplayId());
                        if (claimed == null || !sameReplacementAuthority(initialJournal, claimed)
                                || !"ROLLBACK".equals(claimed.getDecision()) || selectedNow == null
                                || !selectedNow.matches(initialJournal.getDisplayId(), initialJournal.getIdentity())) return false;
                        if (rollbackId.isEmpty()) {
                            boolean baseline = matchesUnallocatedVirtualBaseline(fresh, initialJournal.getVirtualBaseline());
                            boolean onlyA = RearDisplayCastOccupancy.containsOnlyExactTask(currentOccupancy,
                                    initialJournal.getDisplayId(), initialJournal.getOldTask(), initialJournal.getOldPackage(), initialJournal.getOldBase());
                            if (!PhysicalReplacementLifecycle.unallocatedRollbackCandidate(initialJournal.getPhase(),
                                    initialJournal.getNewSessionId(), initialJournal.getReaderState(), baseline,
                                    "SAME_A".equals(currentOwnerFact), currentA.name(), onlyA)) return false;
                            exactUnallocatedReceipt[0] = true;
                        } else if (!matchesVirtualSession(fresh, claimed)
                                || !p2vPreStopInventoryAllowed(currentA, currentOwnerFact, currentOccupancy,
                                        initialJournal, fresh,
                                        new ComponentName(context, VirtualRearHostActivity.class).flattenToString(),
                                        RearSystemBridge::isValidatedRearLauncher)
                                || !exactP2vHostOccupancy(context, remote, fresh, initialJournal, currentOccupancy, true)) return false;
                        stateJournal[0] = claimed;
                        stateStatus[0] = fresh;
                        return true;
                    },
                    () -> {
                        LegacyDisplayOccupancy.PhysicalReplacementJournal current = stateJournal[0];
                        JSONObject currentStatus = stateStatus[0];
                        if (rollbackId.isEmpty()) return exactUnallocatedReceipt[0]
                                && current.getNewSessionId().isBlank()
                                && matchesUnallocatedVirtualBaseline(currentStatus, current.getVirtualBaseline());
                        if (!matchesVirtualSession(currentStatus, current)) return false;
                        VirtualRearHostActivity.cancelControlledCast(context, current.getToken());
                        if (!"stopped".equals(currentStatus.optString("state"))) {
                            if (RearScreenKeeperService.virtualSessionMatches(rollbackId)
                                    && !RearScreenKeeperService.stopVirtualSessionAndAwait(rollbackId, 1500L)) return false;
                            remote.stopRearVirtualCast(rollbackId);
                            currentStatus = new JSONObject(remote.getRearVirtualCastStatus());
                        }
                        LegacyDisplayOccupancy.PhysicalReplacementJournal afterStop =
                                LegacyDisplayOccupancy.physicalReplacementJournal(context);
                        if (afterStop == null || !sameReplacementAuthority(current, afterStop)
                                || !"ROLLBACK".equals(afterStop.getDecision())
                                || !java.util.Arrays.asList("PREPARING", "B_READY", "A_RETURNING", "A_RETURNED", "OWNER_VIRTUAL", "OWNER_A")
                                        .contains(afterStop.getPhase())
                                || !matchesVirtualTerminal(currentStatus, afterStop)) return false;
                        stateJournal[0] = afterStop;
                        stateStatus[0] = currentStatus;
                        return true;
                    },
                    () -> {
                        LegacyDisplayOccupancy.PhysicalReplacementJournal current = stateJournal[0];
                        JSONObject currentStatus = stateStatus[0];
                        SelectedRearIdentity selectedNow = readSelectedRearIdentity(remote);
                        if (selectedNow == null || !selectedNow.matches(current.getDisplayId(), current.getIdentity())) return false;
                        ExactTaskPlacement a = exactPlacement(remote, current.getOldTask(), current.getOldPackage(),
                                current.getOldBase(), current.getDisplayId(), current.getIdentity());
                        String rearSnapshot = remote.getDisplayCastOccupancy(current.getDisplayId());
                        LegacyDisplayOccupancy.PhysicalReplacementJournal latest =
                                LegacyDisplayOccupancy.physicalReplacementJournal(context);
                        if (latest == null || !sameReplacementAuthority(current, latest)
                                || !"ROLLBACK".equals(latest.getDecision()) || latest.getPhase().startsWith("ACCEPTED")) return false;
                        current = latest;
                        String ownerFact = LegacyDisplayOccupancy.physicalReplacementOwnerFact(context, current);
                        boolean ownerA = "SAME_A".equals(ownerFact);
                        boolean terminal = !rollbackId.isEmpty() ? matchesVirtualTerminal(currentStatus, current)
                                : exactUnallocatedReceipt[0] && matchesUnallocatedVirtualBaseline(currentStatus, current.getVirtualBaseline());
                        if (!PhysicalReplacementLifecycle.mayRollbackToA(current.getPhase(), current.getNewVirtual(),
                                current.getPhase().startsWith("ACCEPTED"), current.getReaderState(), terminal,
                                ownerA, a.name())) return false;
                        if (!PhysicalReplacementLifecycle.p2vOwnerFactAllowed(current.getPhase(), a.name(), ownerFact)) return false;
                        if (a == ExactTaskPlacement.MAIN && !("A_RETURNING".equals(current.getPhase())
                                || "A_RETURNED".equals(current.getPhase()) || "OWNER_VIRTUAL".equals(current.getPhase()))) return false;
                        if (!p2vRecoveryAPlacementAllowed(a, ownerA, rearSnapshot, current.getDisplayId(),
                                current.getOldTask(), current.getOldPackage(), current.getOldBase())) return false;
                        if (!rollbackId.isEmpty() && !exactP2vHostOccupancy(context, remote, currentStatus, current, rearSnapshot, false)) return false;
                        if (a == ExactTaskPlacement.MAIN) {
                            if (current.getOldLauncherDisabled() && !remote.disableSubScreenLauncher()) return false;
                            String moved = controlledMove(remote, current.getOldTask(), current.getOldPackage(), current.getOldBase(),
                                    0, current.getDisplayId(), current.getIdentity());
                            if (!"moved".equals(new JSONObject(moved).optString("status"))) return false;
                        } else if (a != ExactTaskPlacement.REAR) return false;
                        JSONObject restoredSnapshot = new JSONObject(remote.getDisplayCastOccupancy(current.getDisplayId()));
                        JSONObject restoredPresentation = new JSONObject(remote.queryExactPhysicalPresentation(
                                current.getOldTask(), current.getOldPackage(), current.getOldBase(),
                                current.getDisplayId(), current.getIdentity()));
                        logPhysicalPresentation("rollback_a_frame", restoredPresentation);
                        String exactA = current.getOldPackage() + ":" + current.getOldTask();
                        boolean keeperAConfirmed = exactA.equals(RearScreenKeeperService.physicalCastTask())
                                && RearScreenKeeperService.physicalCastOwnerGeneration() == current.getOldGeneration();
                        if (exactPlacement(remote, current.getOldTask(), current.getOldPackage(), current.getOldBase(),
                                current.getDisplayId(), current.getIdentity()) != ExactTaskPlacement.REAR
                                || !exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(restoredSnapshot, current.getOldTask(),
                                        current.getOldPackage(), current.getOldBase(), current.getDisplayId())
                                || !exactPhysicalPresentationReady(restoredPresentation, current.getOldTask(),
                                        current.getOldPackage(), current.getOldBase(), current.getDisplayId(), current.getIdentity())
                                || !LegacyDisplayOccupancy.rollbackPhysicalReplacementOwner(
                                        context, current.getToken(), keeperAConfirmed)) return false;
                        if (!keeperAConfirmed) {
                            if (RearScreenKeeperService.physicalCastTask() != null
                                    || !startExactPhysicalKeeper(context.getApplicationContext(), current.getOldPackage(),
                                            current.getOldTask(), current.getDisplayId(), current.getIdentity(), current.getOldGeneration())) return false;
                        }
                        if (!exactA.equals(RearScreenKeeperService.physicalCastTask())
                                || RearScreenKeeperService.physicalCastOwnerGeneration() != current.getOldGeneration()
                                || !LegacyDisplayOccupancy.physicalKeeperStartClaimed(context, exactA,
                                        current.getDisplayId(), current.getIdentity(), current.getOldGeneration())) return false;
                        stateJournal[0] = current;
                        return true;
                    },
                    () -> LegacyDisplayOccupancy.finishPhysicalReplacement(context, stateJournal[0].getToken(), "OWNER_A"));
        } catch (Exception unknown) { return false; }
        finally { RearAnimationManager.completeDisplayAction(reservation); }
    }

    /** B receipt bound to the durable operation, coordinator, backend, target, rear and reader identities. */
    private static boolean exactVirtualReplacementSessionReady(ITaskService remote,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, boolean requireFrame) {
        try {
            JSONObject state = new JSONObject(remote.getRearVirtualCastStatus());
            boolean exactRunning = journal != null && matchesVirtualSession(state, journal)
                    && "READY".equals(journal.getReaderState())
                    && VirtualCastTerminalStatus.matchesRunningPhysicalReplacement(state,
                        journal.getDisplayId(), journal.getIdentity(), journal.getToken(),
                        journal.getNewCoordinatorGeneration(), journal.getNewOwnerIdentity(),
                        journal.getNewSessionId(), journal.getNewBackendGeneration(),
                        journal.getNewTask(), journal.getNewBase(), journal.getReaderId(),
                        journal.getReaderGeneration());
            return exactRunning && (!requireFrame
                    || VirtualRearHostActivity.controlledOutputHasFrame(journal.getNewSessionId(), 1500L));
        } catch (Exception unknown) { return false; }
    }

    /** Exact terminal readback for accepted P→V; same ID is expected to remain with state=stopped. */
    public static boolean matchesVirtualTerminal(JSONObject status,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal) {
        JSONObject input = status == null ? null : status.optJSONObject("input");
        if (!matchesVirtualSession(status, journal) || !"stopped".equals(status.optString("state"))
                || status.optBoolean("outputAvailable", true) || input == null
                || input.optInt("task", -1) != journal.getNewTask()
                || input.optInt("display", -1) != status.optInt("display", -2)
                || input.optBoolean("outputAvailable", true)
                || !"stopped".equals(input.optString("state"))) return false;
        String lifecycle = status.optString("readerLifecycle", "");
        if ("NO_READER".equals(journal.getReaderState()))
            return "NO_READER".equals(lifecycle) && journal.getReaderId().isEmpty()
                    && journal.getReaderGeneration() == 0L && status.optString("readerId", "").isEmpty()
                    && status.optLong("readerGeneration", 0L) == 0L
                    && "NO_READER".equals(input.optString("readerLifecycle"));
        return "RELEASED".equals(journal.getReaderState()) && "RELEASED".equals(lifecycle)
                && !journal.getReaderId().isEmpty() && journal.getReaderGeneration() > 0L
                && journal.getReaderId().equals(status.optString("readerId", ""))
                && journal.getReaderGeneration() == status.optLong("readerGeneration", 0L)
                && journal.getReaderId().equals(input.optString("id", ""))
                && journal.getReaderGeneration() == input.optLong("generation", 0L);
    }

    static boolean matchesUnallocatedVirtualBaseline(JSONObject status, String encodedBaseline) {
        try {
            if (status == null || encodedBaseline == null || encodedBaseline.isBlank()
                    || !"stopped".equals(status.optString("state"))) return false;
            JSONObject baseline = new JSONObject(encodedBaseline);
            if (!"stopped".equals(baseline.optString("state"))) return false;
            for (String key : new String[]{"id", "generation", "coordinatorOperationId", "ownerGeneration",
                    "ownerIdentity", "task", "package", "baseComponent", "rearDisplayId", "rearIdentity"}) {
                if (baseline.has(key) != status.has(key)) return false;
                if (!baseline.has(key)) continue;
                Object before = baseline.get(key), now = status.get(key);
                if (before instanceof Number && now instanceof Number) {
                    if (((Number) before).longValue() != ((Number) now).longValue()) return false;
                } else if (!before.equals(now)) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }

    static boolean p2vRecoveryAPlacementAllowed(ExactTaskPlacement placement, boolean exactJournalOwner,
            String occupancy, int displayId, int oldTask, String oldPackage, String oldBase) {
        if (placement == ExactTaskPlacement.REAR)
            return exactJournalOwner && RearDisplayCastOccupancy.containsOnlyExactTask(
                    occupancy, displayId, oldTask, oldPackage, oldBase);
        if (placement == ExactTaskPlacement.MAIN)
            return RearDisplayCastOccupancy.emptyOrOnlySystemLauncher(occupancy, displayId);
        return false;
    }

    /** Inventory gate for the instant before B is stopped; unlike post-stop, B is an allowed row. */
    static boolean p2vPreStopInventoryAllowed(ExactTaskPlacement aPlacement, String ownerFact,
            String encoded, LegacyDisplayOccupancy.PhysicalReplacementJournal journal, JSONObject status,
            String expectedHostComponent, java.util.function.BiPredicate<String, Integer> verifiedLauncher) {
        if ((aPlacement != ExactTaskPlacement.REAR && aPlacement != ExactTaskPlacement.MAIN)
                || !PhysicalReplacementLifecycle.p2vOwnerFactAllowed(journal.getPhase(), aPlacement.name(), ownerFact))
            return false;
        try {
            JSONObject snapshot = new JSONObject(encoded);
            if (!"ok".equals(snapshot.optString("status"))
                    || snapshot.optInt("display", -1) != journal.getDisplayId()) return false;
            JSONArray rows = snapshot.optJSONArray("tasks");
            if (rows == null) return false;
            int hostId = status.optInt("host", -1);
            if (expectedHostComponent == null || expectedHostComponent.isBlank()) return false;
            boolean foundHost = false, foundA = false;
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) return false;
                int task = row.optInt("task", -1);
                if (task < 0 || !ids.add(task)) return false;
                if (task == hostId && hostId > 0) {
                    if (foundHost || !expectedHostComponent.equals(row.optString("baseComponent", ""))) return false;
                    foundHost = true;
                    continue;
                }
                if (task == journal.getOldTask()) {
                    if (foundA || !journal.getOldPackage().equals(row.optString("base", ""))
                            || !journal.getOldPackage().equals(row.optString("top", ""))
                            || !journal.getOldBase().equals(row.optString("baseComponent", ""))) return false;
                    foundA = true;
                    continue;
                }
                int userId = row.optInt("userId", -1);
                if (userId >= 0 && verifiedLauncher != null && verifiedLauncher.test(
                        row.optString("baseComponent", ""), userId)) continue;
                return false;
            }
            if (!foundHost && !"stopped".equals(status.optString("state"))) return false;
            return aPlacement == ExactTaskPlacement.REAR ? foundA : !foundA;
        } catch (Exception unknown) { return false; }
    }

    /** Exact pre-stop host authority. A component match without this transaction's live host task is insufficient. */
    private static boolean exactP2vHostOccupancy(Context context, ITaskService remote, JSONObject status,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, String encoded, boolean beforeStop) {
        try {
            JSONObject snapshot = new JSONObject(encoded);
            if (!"ok".equals(snapshot.optString("status"))
                    || snapshot.optInt("display", -1) != journal.getDisplayId()) return false;
            JSONArray rows = snapshot.optJSONArray("tasks");
            if (rows == null) return false;
            int hostTask = status.optInt("host", -1);
            String expectedHost = new ComponentName(context, VirtualRearHostActivity.class).flattenToString();
            boolean hostFound = false;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) return false;
                int task = row.optInt("task", -1);
                if (task < 0 || !seen.add(task)) return false;
                String component = row.optString("baseComponent", "");
                if (task == hostTask && hostTask > 0) {
                    if (!beforeStop || hostFound || !expectedHost.equals(component)
                            || !remote.isTaskOnDisplay(hostTask, journal.getDisplayId())
                            || !expectedHost.equals(remote.getTaskBaseComponent(hostTask, journal.getDisplayId())))
                        return false;
                    hostFound = true;
                    continue;
                }
                if (task == journal.getOldTask()
                        && journal.getOldPackage().equals(row.optString("base", ""))
                        && journal.getOldPackage().equals(row.optString("top", ""))
                        && journal.getOldBase().equals(component)) continue;
                int userId = row.optInt("userId", -1);
                if (userId >= 0 && RearSystemBridge.isValidatedRearLauncher(component, userId)) continue;
                return false;
            }
            if (beforeStop && !"stopped".equals(status.optString("state"))) return hostFound;
            return !hostFound;
        } catch (Exception unknown) { return false; }
    }

    private static boolean sameReplacementAuthority(
            LegacyDisplayOccupancy.PhysicalReplacementJournal a,
            LegacyDisplayOccupancy.PhysicalReplacementJournal b) {
        return a != null && b != null && a.getToken().equals(b.getToken())
                && a.getOldTask() == b.getOldTask() && a.getOldGeneration() == b.getOldGeneration()
                && a.getNewTask() == b.getNewTask()
                && a.getNewCoordinatorGeneration() == b.getNewCoordinatorGeneration()
                && a.getNewOwnerIdentity().equals(b.getNewOwnerIdentity())
                && a.getNewSessionId().equals(b.getNewSessionId())
                && a.getNewBackendGeneration() == b.getNewBackendGeneration()
                && a.getNewBase().equals(b.getNewBase()) && a.getDisplayId() == b.getDisplayId()
                && a.getIdentity().equals(b.getIdentity());
    }

    public static boolean finalizeAcceptedVirtualReplacement(Context context, ITaskService remote,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, JSONObject terminalStatus) {
        try {
            LegacyDisplayOccupancy.PhysicalReplacementJournal live =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            if (journal == null || live == null || !sameReplacementAuthority(journal, live)
                    || !matchesVirtualSession(terminalStatus, live)
                    || "REJECT".equals(PhysicalReplacementLifecycle.acceptedCloseAction(
                        live.getPhase(), live.getDecision(), true,
                        "stopped".equals(terminalStatus.optString("state")),
                        matchesVirtualTerminal(terminalStatus, live)))) return false;
            terminalStatus = AcceptedVirtualCloseFlow.stopAndReadTerminal(live.getNewSessionId(),
                    terminalStatus, () -> new JSONObject(remote.getRearVirtualCastStatus()),
                    remote::stopRearVirtualCast, status -> matchesVirtualSession(status, live),
                    status -> {
                        LegacyDisplayOccupancy.PhysicalReplacementJournal readback =
                                LegacyDisplayOccupancy.physicalReplacementJournal(context);
                        return readback != null && sameReplacementAuthority(live, readback)
                                && "ACCEPTED_VIRTUAL".equals(readback.getPhase())
                                && "ACCEPT".equals(readback.getDecision())
                                && matchesVirtualTerminal(status, readback);
                    });
            if (terminalStatus == null) return false;
            LegacyDisplayOccupancy.PhysicalReplacementJournal afterStop =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            if (afterStop == null || !sameReplacementAuthority(live, afterStop)
                    || !"FINALIZE".equals(PhysicalReplacementLifecycle.acceptedCloseAction(
                        afterStop.getPhase(), afterStop.getDecision(),
                        matchesVirtualSession(terminalStatus, afterStop),
                        "stopped".equals(terminalStatus.optString("state")),
                        matchesVirtualTerminal(terminalStatus, afterStop)))
                    || !selectedRearIdentityMatches(remote, afterStop.getDisplayId(), afterStop.getIdentity())) return false;
            journal = afterStop;
            RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(remote,
                    context.getPackageName(), journal.getDisplayId());
            if (occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.EMPTY
                    && occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER) return false;
            if (journal.getOldLauncherDisabled()
                    && occupancy.disposition == RearDisplayCastOccupancy.OwnerDisposition.EMPTY) {
                if (!remote.restoreSubScreenLauncherForControlledReplacement(journal.getDisplayId(), journal.getIdentity())) return false;
                occupancy = RearDisplayCastOccupancy.query(remote, context.getPackageName(), journal.getDisplayId());
            }
            if (journal.getOldLauncherDisabled()
                    && occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER) return false;
            return LegacyDisplayOccupancy.finishPhysicalReplacement(context, journal.getToken(), "ACCEPTED_VIRTUAL");
        } catch (Exception unknown) { return false; }
    }

    private static boolean matchesVirtualSession(JSONObject status,
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal) {
        return status != null && journal != null && journal.getNewVirtual()
                && journal.getNewSessionId().equals(status.optString("id"))
                && journal.getToken().equals(status.optString("coordinatorOperationId"))
                && journal.getNewCoordinatorGeneration() == status.optLong("ownerGeneration", -1L)
                && journal.getNewOwnerIdentity().equals(status.optString("ownerIdentity"))
                && journal.getNewBackendGeneration() == status.optLong("generation", -1L)
                && journal.getNewTask() == status.optInt("task", -1)
                && journal.getNewPackage().equals(status.optString("package"))
                && journal.getNewBase().equals(status.optString("intendedBaseComponent"))
                && (status.optString("baseComponent").isEmpty()
                    || journal.getNewBase().equals(status.optString("baseComponent")))
                && journal.getDisplayId() == status.optInt("rearDisplayId", -1)
                && journal.getIdentity().equals(status.optString("rearIdentity"));
    }

    /** Requires a fresh typed occupancy row; task-on-display alone does not prove visible presentation. */
    public static boolean isExactVisibleTask(ITaskService remote, int taskId, String packageName,
                                             String baseComponent, int rearDisplayId, String rearIdentity) {
        try {
            if (exactPlacement(remote, taskId, packageName, baseComponent, rearDisplayId, rearIdentity)
                    != ExactTaskPlacement.REAR) return false;
            JSONObject snapshot = new JSONObject(remote.getDisplayCastOccupancy(rearDisplayId));
            return isVisibleTaskSnapshot(snapshot, taskId, packageName, rearDisplayId);
        } catch (Exception error) { return false; }
    }

    /**
     * Debug-only cleanup for an exact retained owner which is already home. Unlike stop(), this
     * path has no task-move or launcher-start operation: the OEM launcher must already be present.
     */
    static ReturnResult stopRetainedPhysicalOwnerAtHomeForDebug(Context context, ITaskService remote,
            String sessionId, int taskId, String packageName, String baseComponent,
            int rearDisplayId, String rearIdentity, long generation) {
        if (!BuildConfig.DEBUG || !BuildConfig.APPLICATION_ID.endsWith(".debug"))
            return new ReturnResult("debug_fixture_only", false);
        if (context == null || remote == null || sessionId == null || taskId <= 0 || generation <= 0L
                || !sessionId.equals("physical:" + taskId + ":" + generation)
                || packageName == null || packageName.isBlank() || baseComponent == null || baseComponent.isBlank()
                || rearDisplayId <= 0 || rearIdentity == null || rearIdentity.isBlank())
            return new ReturnResult("invalid_exact_owner", false);
        Context app = context.getApplicationContext();
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return new ReturnResult("display_action_busy", false);
        try {
            String packageTask = packageName + ":" + taskId;
            if (!retainedPhysicalOwnerAtHomePreconditions(BuildConfig.DEBUG,
                    BuildConfig.APPLICATION_ID.endsWith(".debug"),
                    LegacyDisplayOccupancy.physicalReplacementJournal(app) == null,
                    LegacyDisplayOccupancy.physicalProjectionMatches(app, taskId, packageName,
                            baseComponent, rearDisplayId, rearIdentity, generation),
                    !RearScreenKeeperService.hasAnyKeeperInstance()
                            && RearScreenKeeperService.physicalCastTask() == null
                            && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L,
                    !LegacyDisplayOccupancy.physicalKeeperStartPending(app, packageTask,
                            rearDisplayId, rearIdentity, generation),
                    selectedRearIdentityMatches(remote, rearDisplayId, rearIdentity),
                    exactRetainedOwnerMainPlacement(remote, taskId, packageName, baseComponent,
                            rearDisplayId, rearIdentity),
                    exactOemLauncherAlreadyAtHome(remote, rearDisplayId)))
                return new ReturnResult("retained_owner_not_safely_at_home", false);

            // No remote action follows this local atomic close. It shares the display reservation
            // with normal display operations and the owner lock with Keeper start claims.
            if (LegacyDisplayOccupancy.physicalReplacementJournal(app) != null
                    || RearScreenKeeperService.hasAnyKeeperInstance()
                    || RearScreenKeeperService.physicalCastTask() != null
                    || RearScreenKeeperService.physicalCastOwnerGeneration() != 0L
                    || LegacyDisplayOccupancy.physicalKeeperStartPending(app, packageTask,
                            rearDisplayId, rearIdentity, generation)
                    || !LegacyDisplayOccupancy.physicalProjectionMatches(app, taskId, packageName,
                            baseComponent, rearDisplayId, rearIdentity, generation))
                return new ReturnResult("retained_owner_changed_before_close", false);
            if (!RearAnimationManager.isCurrentDisplayAction(reservation))
                return new ReturnResult("display_action_expired_before_close", false);
            if (!LegacyDisplayOccupancy.clearRetainedPhysicalOwnerAtHome(app, taskId, packageName,
                    baseComponent, rearDisplayId, rearIdentity, generation,
                    () -> !RearScreenKeeperService.hasAnyKeeperInstance()
                            && RearScreenKeeperService.physicalCastTask() == null
                            && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L))
                return new ReturnResult("retained_owner_close_cas_failed", false);
            RearScreenKeeperService.clearControlledPhysicalRecoveryNotification(app, generation);
            return new ReturnResult("STOPPED", true);
        } catch (Exception unavailable) {
            return new ReturnResult("INDETERMINATE", false);
        } finally {
            RearAnimationManager.completeDisplayAction(reservation);
        }
    }

    static boolean retainedPhysicalOwnerAtHomePreconditions(boolean debugBuild, boolean debugApplication,
            boolean noReplacementJournal, boolean exactOwner, boolean noLiveKeeper, boolean noPendingKeeper,
            boolean selectedRearMatches, boolean exactMain, boolean exactLauncherAlreadyHome) {
        return debugBuild && debugApplication && noReplacementJournal && exactOwner && noLiveKeeper
                && noPendingKeeper && selectedRearMatches && exactMain
                && exactLauncherAlreadyHome;
    }

    private static boolean exactRetainedOwnerMainPlacement(ITaskService remote, int taskId, String packageName,
            String baseComponent, int rearDisplayId, String rearIdentity) throws Exception {
        return exactRetainedOwnerMainPlacement(new JSONObject(remote.queryExactTaskPlacement(taskId)), taskId,
                packageName, baseComponent, rearDisplayId, rearIdentity);
    }

    static boolean exactRetainedOwnerMainPlacement(JSONObject row, int taskId, String packageName,
            String baseComponent, int rearDisplayId, String rearIdentity) {
        if (row == null) return false;
        return "present".equals(row.optString("status")) && row.optInt("task", -1) == taskId
                && row.optInt("display", -1) == Display.DEFAULT_DISPLAY
                && packageName.equals(row.optString("package")) && baseComponent.equals(row.optString("base"))
                && baseComponent.equals(row.optString("top"))
                && row.optInt("selectedId", -1) == rearDisplayId
                && (rearDisplayId + ":" + row.optString("selectedUnique")).equals(rearIdentity)
                && row.optBoolean("rootSnapshotKnown", false) && row.optBoolean("rootSole", false);
    }

    static boolean exactOemLauncherAlreadyAtHome(ITaskService remote, int rearDisplayId)
            throws Exception {
        if (remote == null || rearDisplayId <= 0) return false;
        return exactOemLauncherOnlySnapshot(remote.getDisplayCastOccupancy(rearDisplayId), rearDisplayId,
                RearDisplayCastOccupancy::isExactSystemLauncher);
    }

    static boolean exactOemLauncherOnlySnapshot(String encoded, int rearDisplayId,
            RearDisplayCastOccupancy.ExactLauncherMatcher launcherMatcher) {
        if (encoded == null || rearDisplayId <= 0 || launcherMatcher == null) return false;
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancy.Snapshot.parse(encoded, rearDisplayId);
        return snapshot.failure == null && snapshot.displayState != Display.STATE_UNKNOWN
                && snapshot.powerBefore == snapshot.powerAfter && snapshot.tasks.size() == 1
                && launcherMatcher.matches(snapshot.tasks.get(0));
    }

    /** Pure half of the exact visible-task proof, separately testable without a privileged device. */
    static boolean isVisibleTaskSnapshot(JSONObject snapshot, int taskId, String packageName, int rearDisplayId) {
        if (snapshot == null || taskId <= 0 || packageName == null || packageName.isEmpty()
                || !"ok".equals(snapshot.optString("status")) || snapshot.optInt("display", -1) != rearDisplayId
                || snapshot.optInt("power", -1) != Display.STATE_ON
                || snapshot.optInt("powerBefore", -1) != Display.STATE_ON
                || snapshot.optInt("powerAfter", -1) != Display.STATE_ON) return false;
        String source = snapshot.optString("source");
        if (!"activity_task".equals(source) && !"activity_task_focused_stack".equals(source)
                && !"activity_focused_stack".equals(source)) return false;
        JSONArray rows = snapshot.optJSONArray("tasks");
        if (rows == null) return false;
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) return false;
            if (row.optInt("task", -1) == taskId) {
                if (found != null) return false;
                found = row;
            }
        }
        return found != null && packageName.equals(found.optString("base"))
                && packageName.equals(found.optString("top")) && found.has("visible")
                && found.optBoolean("visible") && found.has("requested") && !found.isNull("requested")
                && found.optBoolean("requested") && found.has("sleeping") && !found.isNull("sleeping")
                && !found.optBoolean("sleeping");
    }

    static boolean exactPhysicalPresentationReady(JSONObject evidence, int task, String packageName,
            String base, int displayId, String rearIdentity) {
        return exactPhysicalPresentationIdentity(evidence, task, packageName, base, displayId, rearIdentity)
                && "ready".equals(evidence.optString("status"))
                && evidence.optBoolean("drawn") && evidence.optBoolean("inputReady")
                && !evidence.optString("windowId").isBlank();
    }

    private static boolean exactPhysicalPresentationIdentity(JSONObject evidence, int task, String packageName,
            String base, int displayId, String rearIdentity) {
        return evidence != null && evidence.optInt("task", -1) == task
                && packageName.equals(evidence.optString("package"))
                && base.equals(evidence.optString("base")) && evidence.optInt("display", -1) == displayId
                && (evidence.optInt("selectedId", -1) + ":" + evidence.optString("selectedUnique")).equals(rearIdentity);
    }

    static boolean isExactMainDisplayStaleReply(JSONObject evidence, int task, String packageName,
            String base, int displayId, String rearIdentity) {
        return exactPhysicalPresentationIdentity(evidence, task, packageName, base, displayId, rearIdentity)
                && "unknown".equals(evidence.optString("status"))
                && "input_main_display_stale".equals(evidence.optString("reason"));
    }

    /** Both exact task rows must coexist; B visibility is separately joined to its WMS/dispatcher proof. */
    static boolean replacementCandidateReadySnapshot(JSONObject snapshot, int oldTask, String oldPackage,
            String oldBase, int newTask, String newPackage, String newBase, int rearDisplayId) {
        return "ready".equals(replacementCandidateReadinessReason(snapshot, oldTask, oldPackage,
                oldBase, newTask, newPackage, newBase, rearDisplayId));
    }

    static String replacementCandidateReadinessReason(JSONObject snapshot, int oldTask, String oldPackage,
            String oldBase, int newTask, String newPackage, String newBase, int rearDisplayId) {
        if (snapshot == null || oldTask <= 0 || newTask <= 0 || oldTask == newTask
                || oldPackage == null || oldBase == null || newPackage == null || newBase == null
                || !"ok".equals(snapshot.optString("status")) || snapshot.optInt("display", -1) != rearDisplayId
                || snapshot.optInt("power", -1) != Display.STATE_ON
                || snapshot.optInt("powerBefore", -1) != Display.STATE_ON
                || snapshot.optInt("powerAfter", -1) != Display.STATE_ON) return "snapshot_or_power_unknown";
        String source = snapshot.optString("source");
        if (!"activity_task".equals(source) && !"activity_task_focused_stack".equals(source)
                && !"activity_focused_stack".equals(source)) return "snapshot_source_unknown";
        JSONArray rows = snapshot.optJSONArray("tasks");
        // The proof is specifically a two-root coexistence snapshot, not a partial list that
        // happens to include A and B while another task or malformed row is omitted.
        if (rows == null || rows.length() != 2) return "task_inventory_not_two_rows";
        JSONObject a = null, b = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) return "task_inventory_malformed";
            int task = row.optInt("task", -1);
            if (task == oldTask) { if (a != null) return "old_task_duplicated"; a = row; }
            if (task == newTask) { if (b != null) return "new_task_duplicated"; b = row; }
        }
        if (a == null) return "old_task_missing";
        if (b == null) return "new_task_missing";
        if (!exactIdentityRow(a, oldPackage, oldBase)) return "old_task_identity_mismatch";
        if (!exactIdentityRow(b, newPackage, newBase)) return "new_task_identity_mismatch";
        if (!knownVisibility(a) || !knownVisibility(b)) return "task_visibility_unknown";
        if (Boolean.TRUE.equals(a.optBoolean("sleeping")) || Boolean.TRUE.equals(b.optBoolean("sleeping")))
            return "task_sleeping";
        if (Boolean.TRUE.equals(a.optBoolean("visible")) || Boolean.TRUE.equals(a.optBoolean("requested")))
            return "old_task_still_requested";
        if (!Boolean.TRUE.equals(b.optBoolean("visible")) || !Boolean.TRUE.equals(b.optBoolean("requested")))
            return "new_task_not_visible_requested";
        return "ready";
    }

    private static boolean knownVisibility(JSONObject row) {
        return row != null && row.has("visible") && !row.isNull("visible")
                && row.has("requested") && !row.isNull("requested")
                && row.has("sleeping") && !row.isNull("sleeping");
    }

    private static String safeMoveResult(JSONObject result) {
        if (result == null) return "unknown/unavailable";
        String status = result.optString("status", "unknown");
        String safeStatus = ("moved".equals(status) || "rejected".equals(status)) ? status : "unknown";
        String reason = result.optString("reason", "unavailable");
        switch (reason) {
            case "root_precheck_rejected": case "exact_target_readback":
            case "exact_target_readback_unverified": case "privileged_query_failed":
                return safeStatus + "/" + reason;
            default: return safeStatus + "/other";
        }
    }

    private static String safePresentationReason(String reason) {
        switch (reason) {
            case "ready": case "window_task_missing": case "window_task_ambiguous":
            case "window_display_or_owner_mismatch": case "window_not_drawn_and_shown":
            case "not_base_app_window": case "input_window_missing": case "input_window_ambiguous":
            case "input_display_missing": case "input_display_mismatch": case "input_main_display_stale":
            case "input_uid_missing": case "input_uid_mismatch":
            case "input_id_invalid": case "input_pid_invalid": case "input_not_touchable":
            case "input_flags_missing": case "input_flags_rejected":
            case "input_region_missing": case "input_region_empty": case "input_region_invalid":
            case "presentation_parser_unavailable": case "rear_identity_changed": case "invalid_input":
            case "exact_task_identity_unverified": case "package_uid_unavailable":
            case "package_uid_ambiguous": case "window_probe_unavailable": case "probe_unavailable":
                return reason;
            default: return "other";
        }
    }

    private static void logPhysicalPresentation(String phase, JSONObject evidence) {
        String status = evidence == null ? "unknown" : evidence.optString("status", "unknown");
        String reason = evidence == null ? "unavailable"
                : safePresentationReason(evidence.optString("reason", "unavailable"));
        Log.i(TAG, "physical_replace phase=" + phase + " presentation=" + status + " reason=" + reason);
    }

    /** Rear snapshots intentionally have no global focusedTask; exact typed inventory and row flags govern here. */
    static boolean exactVisiblePhysicalTaskSnapshot(JSONObject snapshot, int taskId, String packageName,
            String baseComponent, int rearDisplayId) {
        if (snapshot == null || taskId <= 0 || packageName == null || baseComponent == null
                || !"ok".equals(snapshot.optString("status")) || snapshot.optInt("display", -1) != rearDisplayId
                || snapshot.optInt("power", -1) != Display.STATE_ON
                || snapshot.optInt("powerBefore", -1) != Display.STATE_ON
                || snapshot.optInt("powerAfter", -1) != Display.STATE_ON) return false;
        String source = snapshot.optString("source");
        if (!"activity_task".equals(source) && !"activity_task_focused_stack".equals(source)
                && !"activity_focused_stack".equals(source)) return false;
        JSONArray rows = snapshot.optJSONArray("tasks");
        if (rows == null || rows.length() != 1) return false;
        JSONObject row = rows.optJSONObject(0);
        return exactVisiblePhysicalRow(row, taskId, packageName, baseComponent);
    }

    static boolean exactRestoredPhysicalFrameProof(JSONObject snapshot, JSONObject presentation,
            int taskId, String packageName, String baseComponent, int rearDisplayId, String rearIdentity) {
        return exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(snapshot, taskId, packageName,
                baseComponent, rearDisplayId)
                && exactPhysicalPresentationReady(presentation, taskId, packageName, baseComponent,
                        rearDisplayId, rearIdentity);
    }

    /**
     * Read-only rollback observation gate: exact task identity and a coherent typed inventory are
     * enough to ask WMS whether A has drawn yet. Visibility is deliberately not a prerequisite here;
     * the later owner-restoration gate still requires the full visible-row and exact-ready proof.
     */
    static boolean exactPhysicalTaskObservationSnapshotWithHiddenLauncher(JSONObject snapshot, int taskId,
            String packageName, String baseComponent, int rearDisplayId) {
        return exactPhysicalTaskObservationSnapshotWithHiddenLauncher(snapshot, taskId, packageName,
                baseComponent, rearDisplayId, RearSystemBridge::isValidatedRearLauncher);
    }

    static boolean exactPhysicalTaskObservationSnapshotWithHiddenLauncher(JSONObject snapshot, int taskId,
            String packageName, String baseComponent, int rearDisplayId,
            java.util.function.BiPredicate<String, Integer> verifiedLauncher) {
        if (snapshot == null || taskId <= 0 || packageName == null || baseComponent == null
                || verifiedLauncher == null || !"ok".equals(snapshot.optString("status"))
                || snapshot.optInt("display", -1) != rearDisplayId
                || snapshot.optInt("power", -1) != Display.STATE_ON
                || snapshot.optInt("powerBefore", -1) != Display.STATE_ON
                || snapshot.optInt("powerAfter", -1) != Display.STATE_ON) return false;
        String source = snapshot.optString("source");
        if (!"activity_task".equals(source) && !"activity_task_focused_stack".equals(source)
                && !"activity_focused_stack".equals(source)) return false;
        JSONArray rows = snapshot.optJSONArray("tasks");
        if (rows == null || (rows.length() != 1 && rows.length() != 2)) return false;
        JSONObject exactTask = null, hiddenLauncher = null;
        java.util.Set<Integer> taskIds = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !row.has("task") || row.isNull("task")) return false;
            int rowTask = row.optInt("task", -1);
            if (rowTask <= 0 || !taskIds.add(rowTask)) return false;
            if (rowTask == taskId) {
                if (exactTask != null || !exactIdentityRow(row, packageName, baseComponent)) return false;
                exactTask = row;
            } else {
                if (hiddenLauncher != null || row.optInt("userId", -1) < 0
                        || !row.has("userId") || row.isNull("userId")
                        || !Boolean.FALSE.equals(requiredBoolean(row, "visible"))
                        || !Boolean.FALSE.equals(requiredBoolean(row, "requested"))
                        || !Boolean.FALSE.equals(requiredBoolean(row, "sleeping"))) return false;
                String launcherPackage = row.optString("base", "");
                String launcherTop = row.optString("top", "");
                String launcherComponent = row.optString("baseComponent", "");
                String launcherTopComponent = row.optString("topComponent", "");
                int userId = row.optInt("userId", -1);
                if (launcherPackage.isBlank() || !launcherPackage.equals(launcherTop)
                        || launcherComponent.isBlank() || !launcherComponent.equals(launcherTopComponent)
                        || !launcherComponent.startsWith(launcherPackage + "/")
                        || !verifiedLauncher.test(launcherComponent, userId)) return false;
                hiddenLauncher = row;
            }
        }
        return exactTask != null && observationFlagsKnown(exactTask);
    }

    private static Boolean requiredBoolean(JSONObject row, String key) {
        if (row == null || !row.has(key) || row.isNull(key)) return null;
        Object value = row.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static boolean observationFlagsKnown(JSONObject row) {
        return requiredBoolean(row, "visible") != null && requiredBoolean(row, "requested") != null
                && requiredBoolean(row, "sleeping") != null && row.has("userId")
                && !row.isNull("userId") && row.optInt("userId", -1) >= 0;
    }

    /** Recovery may observe the one exact, validated OEM launcher alongside restored A, but never a visible launcher. */
    static boolean exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(JSONObject snapshot, int taskId,
            String packageName, String baseComponent, int rearDisplayId) {
        return exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(snapshot, taskId, packageName, baseComponent,
                rearDisplayId, RearSystemBridge::isValidatedRearLauncher);
    }

    static boolean exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(JSONObject snapshot, int taskId,
            String packageName, String baseComponent, int rearDisplayId,
            java.util.function.BiPredicate<String, Integer> verifiedLauncher) {
        if (snapshot == null || taskId <= 0 || packageName == null || baseComponent == null || verifiedLauncher == null
                || !"ok".equals(snapshot.optString("status")) || snapshot.optInt("display", -1) != rearDisplayId
                || snapshot.optInt("power", -1) != Display.STATE_ON
                || snapshot.optInt("powerBefore", -1) != Display.STATE_ON
                || snapshot.optInt("powerAfter", -1) != Display.STATE_ON) return false;
        String source = snapshot.optString("source");
        if (!"activity_task".equals(source) && !"activity_task_focused_stack".equals(source)
                && !"activity_focused_stack".equals(source)) return false;
        JSONArray rows = snapshot.optJSONArray("tasks");
        if (rows == null || (rows.length() != 1 && rows.length() != 2)) return false;
        JSONObject restored = null, launcher = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) return false;
            if (row.optInt("task", -1) == taskId) {
                if (restored != null) return false;
                restored = row;
            } else {
                if (launcher != null) return false;
                launcher = row;
            }
        }
        if (!exactVisiblePhysicalRow(restored, taskId, packageName, baseComponent)) return false;
        if (rows.length() == 1) return launcher == null;
        if (launcher == null || launcher.optInt("task", -1) <= 0 || !launcher.has("userId")
                || launcher.isNull("userId") || !launcher.has("visible") || launcher.isNull("visible")
                || launcher.optBoolean("visible") || !launcher.has("requested") || launcher.isNull("requested")
                || launcher.optBoolean("requested") || !launcher.has("sleeping") || launcher.isNull("sleeping")
                || launcher.optBoolean("sleeping")) return false;
        String launcherPackage = launcher.optString("base", "");
        String launcherTop = launcher.optString("top", "");
        String launcherComponent = launcher.optString("baseComponent", "");
        String launcherTopComponent = launcher.optString("topComponent", "");
        int userId = launcher.optInt("userId", -1);
        return userId >= 0 && !launcherPackage.isBlank() && launcherPackage.equals(launcherTop)
                && !launcherComponent.isBlank() && launcherComponent.equals(launcherTopComponent)
                && launcherComponent.startsWith(launcherPackage + "/")
                && verifiedLauncher.test(launcherComponent, userId);
    }

    private static boolean exactIdentityRow(JSONObject row, String packageName, String base) {
        if (row == null || !packageName.equals(row.optString("base"))
                || !base.equals(row.optString("baseComponent"))
                || !(row.optString("topComponent").startsWith(packageName + "/")
                    || row.optString("topComponent").equals(base))) return false;
        return true;
    }

    private static boolean exactVisiblePhysicalRow(JSONObject row, int taskId, String packageName, String base) {
        if (row == null || row.optInt("task", -1) != taskId || !exactIdentityRow(row, packageName, base)) return false;
        return row.has("visible") && !row.isNull("visible") && row.optBoolean("visible")
                && row.has("requested") && !row.isNull("requested") && row.optBoolean("requested")
                && row.has("sleeping") && !row.isNull("sleeping") && !row.optBoolean("sleeping");
    }

    private static boolean exactHiddenPhysicalRow(JSONObject row, int taskId, String packageName, String base) {
        if (row == null || row.optInt("task", -1) != taskId || !exactIdentityRow(row, packageName, base)) return false;
        return row.has("visible") && !row.isNull("visible") && !row.optBoolean("visible")
                && row.has("requested") && !row.isNull("requested") && !row.optBoolean("requested")
                && row.has("sleeping") && !row.isNull("sleeping") && !row.optBoolean("sleeping");
    }

    public static ReturnResult stop(Context context, ITaskService remote, String sessionId,
                                    int taskId, String packageName, String baseComponent,
                                    int rearDisplayId, String rearIdentity, long generation) {
        if (sessionId == null) return new ReturnResult("stale_owner", false);
        LegacyDisplayOccupancy.PhysicalReplacementJournal replacement = LegacyDisplayOccupancy.physicalReplacementJournal(context);
        DisplayActionGate.Reservation explicitCloseReservation = null;
        LegacyDisplayOccupancy.PhysicalReplacementJournal explicitVirtualRollbackClose = null;
        if (replacement != null) {
            String oldSession = "physical:" + replacement.getOldTask() + ":" + replacement.getOldGeneration();
            if (oldSession.equals(sessionId) && isPreparingVirtualRollback(replacement)) {
                if (!exactOldCloseArguments(replacement, sessionId, taskId, packageName, baseComponent,
                        rearDisplayId, rearIdentity, generation)) return new ReturnResult("stale_replacement_owner", false);
                explicitVirtualRollbackClose = replacement;
                replacement = null; // Explicitly return A; do not resurrect it or require a touchable rear window.
            }
        }
        if (replacement != null) {
            String oldSession = "physical:" + replacement.getOldTask() + ":" + replacement.getOldGeneration();
            if ("PREPARING".equals(replacement.getPhase()) && "NONE".equals(replacement.getDecision())
                    && !replacement.getNewVirtual() && oldSession.equals(sessionId)) {
                if (!exactUnacceptedOldCloseArguments(replacement, sessionId, taskId, packageName,
                        baseComponent, rearDisplayId, rearIdentity, generation))
                    return new ReturnResult("stale_replacement_owner", false);
                ExactTaskPlacement currentA;
                try { currentA = exactPlacement(remote, replacement.getOldTask(), replacement.getOldPackage(),
                        replacement.getOldBase(), replacement.getDisplayId(), replacement.getIdentity()); }
                catch (Exception unknown) { return new ReturnResult("INDETERMINATE", false); }
                String closeRoute = unacceptedPhysicalCloseRoute(currentA);
                if ("NO_MOVE_CLOSE".equals(closeRoute)) {
                    boolean closed = closeUnacceptedPhysicalReplacement(context, remote, replacement);
                    return new ReturnResult(closed ? "STOPPED" : "INDETERMINATE", closed);
                }
                if ("RECOVER_THEN_CLOSE".equals(closeRoute)) {
                    explicitCloseReservation = recoverUnacceptedPhysicalReplacementForClose(context, remote, replacement);
                    if (explicitCloseReservation == null)
                        return new ReturnResult("INDETERMINATE", false);
                    replacement = LegacyDisplayOccupancy.physicalReplacementJournal(context);
                    if (replacement != null) {
                        RearAnimationManager.completeDisplayAction(explicitCloseReservation);
                        return new ReturnResult("INDETERMINATE", false);
                    }
                } else return new ReturnResult("INDETERMINATE", false);
            }
            if (replacement != null) {
            String replacementRoute = LegacyDisplayOccupancy.replacementCloseRoute(replacement.getPhase(),
                    sessionId, oldSession, replacement.getNewSessionId());
            if ("CLOSE_NEW_OWNER".equals(replacementRoute)) {
                if (!replacement.getNewSessionId().equals(sessionId)
                        || replacement.getNewTask() != taskId || replacement.getNewGeneration() != generation
                        || !replacement.getNewPackage().equals(packageName)
                        || !replacement.getNewBase().equals(baseComponent)
                        || replacement.getDisplayId() != rearDisplayId
                        || !replacement.getIdentity().equals(rearIdentity))
                    return new ReturnResult("stale_replacement_owner", false);
                if (!LegacyDisplayOccupancy.finishPhysicalReplacement(context, replacement.getToken(), "ACCEPTED_B"))
                    return new ReturnResult("replacement_journal_unavailable", false);
            } else if ("RECOVER_PENDING".equals(replacementRoute)) {
                boolean recovered = recoverPhysicalReplacement(context, remote);
                if (!recovered) return new ReturnResult("replacement_recovery_indeterminate", false);
                if (!oldSession.equals(sessionId)) return new ReturnResult("replacement_candidate_returned", true);
            } else return new ReturnResult("stale_replacement_owner", false);
            }
        }
        if (sessionId == null || !sessionId.equals("physical:" + taskId + ":" + generation)
                || generation <= 0L || LegacyDisplayOccupancy.physicalProjectionGeneration(
                context, taskId, rearDisplayId) != generation) {
            if (explicitCloseReservation != null) RearAnimationManager.completeDisplayAction(explicitCloseReservation);
            return new ReturnResult("stale_owner", false);
        }
        String exact = packageName + ":" + taskId;
        boolean keeperOwns = (exact.equals(RearScreenKeeperService.physicalCastTask())
                && RearScreenKeeperService.physicalCastOwnerGeneration() == generation)
                || LegacyDisplayOccupancy.physicalKeeperStartPending(context, exact, rearDisplayId,
                    rearIdentity, generation);
        boolean launcherDisabled = LegacyDisplayOccupancy.physicalProjectionLauncherDisabled(context, taskId, rearDisplayId);
        DisplayActionGate.Reservation reservation = explicitCloseReservation != null ? explicitCloseReservation
                : RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return new ReturnResult("display_action_busy", false);
        LegacyDisplayOccupancy.PhysicalReplacementJournal rollbackClose = explicitVirtualRollbackClose;
        boolean closed = false;
        boolean closeClaimed = false;
        try {
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            if (selected == null || !selected.matches(rearDisplayId, rearIdentity))
                return new ReturnResult("rear_identity_changed", false);
            if (rollbackClose != null && !canExplicitlyClosePreparingVirtualRollback(
                    context, remote, reservation, rollbackClose, false))
                return new ReturnResult("replacement_recovery_indeterminate", false);
            // Close and Keeper start share the owner lock. Pending starts are cancelled here;
            // a start that already claimed must be stopped exactly before ownership can clear.
            if (LegacyDisplayOccupancy.beginControlledPhysicalProjectionClose(context, exact, rearDisplayId,
                    rearIdentity, generation) < 0) return new ReturnResult("owner_close_cas_failed", false);
            closeClaimed = true;
            PhysicalCastReturnFlow.Result result = PhysicalCastReturnFlow.stop(exact, keeperOwns,
                    launcherDisabled, generation, new PhysicalCastReturnFlow.Port() {
                @Override public boolean isExactTaskOnRear(int id, String pkg) throws Exception {
                    return id == taskId && pkg.equals(packageName) && remote.isTaskOnDisplay(id, rearDisplayId)
                            && pkg.equals(remote.getPackageNameFromTaskId(id))
                            && baseComponent.equals(remote.getTaskBaseComponent(id, rearDisplayId));
                }
                @Override public boolean isStrictControlledReturn() { return true; }
                @Override public PhysicalCastReturnFlow.TaskState inspectExactTask(int id, String pkg) throws Exception {
                    if (id != taskId || !packageName.equals(pkg)) return PhysicalCastReturnFlow.TaskState.UNKNOWN;
                    JSONObject row = new JSONObject(remote.queryExactTaskPlacement(id));
                    String status = row.optString("status", "unknown");
                    if (row.optInt("selectedId", -1) != rearDisplayId
                            || !rearIdentity.equals(row.optInt("selectedId", -1) + ":" + row.optString("selectedUnique")))
                        return PhysicalCastReturnFlow.TaskState.UNKNOWN;
                    if (!"present".equals(status)) return PhysicalCastReturnFlow.TaskState.UNKNOWN;
                    if (!packageName.equals(row.optString("package"))
                            || !baseComponent.equals(row.optString("base"))) return PhysicalCastReturnFlow.TaskState.UNKNOWN;
                    int display = row.optInt("display", -1);
                    if (display == rearDisplayId) return PhysicalCastReturnFlow.TaskState.REAR;
                    if (display == 0) return PhysicalCastReturnFlow.TaskState.MAIN;
                    return PhysicalCastReturnFlow.TaskState.UNKNOWN;
                }
                @Override public boolean moveExactTaskToMain(int id) throws Exception {
                    if (id != taskId || !RearAnimationManager.isCurrentDisplayAction(reservation)) return false;
                    String moved;
                    if (rollbackClose == null) {
                        moved = controlledMove(remote, id, packageName, baseComponent, rearDisplayId, 0, rearIdentity);
                    } else {
                        ISceneTaskActionGate gate = new ISceneTaskActionGate.Stub() {
                            @Override public boolean isCurrent() {
                                return LegacyDisplayOccupancy.isCurrentExplicitVirtualRollbackClose(context, rollbackClose)
                                        && RearAnimationManager.isCurrentDisplayAction(reservation);
                            }
                            @Override public boolean isCurrentBeforeTargetMove() { return isCurrent(); }
                        };
                        moved = remote.returnPhysicalAAfterVirtualRollback(id, packageName, baseComponent,
                                rearDisplayId, rearIdentity, rollbackClose.getNewTask(), rollbackClose.getNewPackage(),
                                rollbackClose.getNewBase(), gate);
                    }
                    return "moved".equals(new JSONObject(moved).optString("status"));
                }
                @Override public boolean isExactTaskOnMain(int id, String pkg) throws Exception {
                    return id == taskId && pkg.equals(packageName) && remote.isTaskOnDisplay(id, 0)
                            && pkg.equals(remote.getPackageNameFromTaskId(id))
                            && baseComponent.equals(remote.getTaskBaseComponent(id, 0));
                }
                @Override public boolean mayRestoreLauncher() throws Exception {
                    if (rollbackClose != null) return canExplicitlyClosePreparingVirtualRollback(
                            context, remote, reservation, rollbackClose, true);
                    if (LegacyDisplayOccupancy.physicalProjectionGeneration(context, taskId, rearDisplayId) != generation)
                        return false;
                    RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(
                            remote, context.getPackageName(), rearDisplayId);
                    return occupancy.disposition == RearDisplayCastOccupancy.OwnerDisposition.EMPTY
                            || occupancy.disposition == RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER;
                }
                @Override public boolean restoreLauncher() throws Exception {
                    return rollbackClose == null
                            ? remote.restoreSubScreenLauncherAfterNormalControlledClose(rearDisplayId, rearIdentity)
                            : remote.restoreSubScreenLauncherForControlledReplacement(rearDisplayId, rearIdentity);
                }
                @Override public boolean stopKeeperForExactTask(String ownerTask, long ownerGeneration) {
                    if (ownerTask.equals(RearScreenKeeperService.physicalCastTask())
                            && RearScreenKeeperService.physicalCastOwnerGeneration() == ownerGeneration) {
                        boolean acknowledged = RearScreenKeeperService.stopPhysicalSession(ownerTask, ownerGeneration);
                        if (!acknowledged) {
                            if (rollbackClose != null || !PhysicalKeeperStopPolicy.mayReconcileNaturallyStoppedKeeper(
                                    false, RearScreenKeeperService.hasAnyKeeperInstance())) return false;
                            return reconcileNaturallyStoppedKeeper(context, ownerTask, baseComponent, rearDisplayId,
                                    rearIdentity, ownerGeneration);
                        }
                        if (rollbackClose == null) return true;
                    }
                    if (RearScreenKeeperService.hasAnyKeeperInstance()) return false;
                    // A cancelled pending start is safe; an exact claimed owner requires close reconciliation.
                    if (!LegacyDisplayOccupancy.physicalKeeperStartClaimed(context, ownerTask, rearDisplayId,
                            rearIdentity, ownerGeneration)) return true;
                    if (rollbackClose != null)
                        return LegacyDisplayOccupancy.clearClaimedKeeperForClosedOwner(context, ownerTask,
                                rearDisplayId, rearIdentity, ownerGeneration);
                    return reconcileNaturallyStoppedKeeper(context, ownerTask, baseComponent, rearDisplayId,
                            rearIdentity, ownerGeneration);
                }
                @Override public void clearExactOwnership(int id, long ownerGeneration) { }
                @Override public boolean clearExactOwnershipVerified(int id, long ownerGeneration) {
                    if (rollbackClose != null) {
                        try {
                            if (!canExplicitlyClosePreparingVirtualRollback(context, remote, reservation, rollbackClose, true)
                                    || !exactStableNoOwnerRearInventory(new JSONObject(remote.getDisplayCastOccupancy(rearDisplayId)),
                                            rearDisplayId, rollbackClose.getOldLauncherDisabled())
                                    || RearScreenKeeperService.hasAnyKeeperInstance()
                                    || !RearAnimationManager.isCurrentDisplayAction(reservation)) return false;
                            return LegacyDisplayOccupancy.finishUnacceptedPhysicalReplacementClose(context, rollbackClose);
                        } catch (Exception unknown) { return false; }
                    }
                    return LegacyDisplayOccupancy.clearPhysicalProjection(context, id, packageName, rearDisplayId,
                            rearIdentity, ownerGeneration);
                }
            });
            boolean stopped = result == PhysicalCastReturnFlow.Result.STOPPED;
            closed = stopped;
            if (stopped) RearScreenKeeperService.clearControlledPhysicalRecoveryNotification(context, generation);
            return new ReturnResult(result.name(), stopped);
        } catch (Exception error) { return new ReturnResult("INDETERMINATE", false); }
        finally {
            try {
                if (rollbackClose != null && closeClaimed && !closed)
                    LegacyDisplayOccupancy.cancelUnacceptedPhysicalReplacementClose(context, rollbackClose);
            } finally { RearAnimationManager.completeDisplayAction(reservation); }
        }
    }

    private static boolean reconcileNaturallyStoppedKeeper(Context context, String ownerTask,
            String expectedBaseComponent, int displayId, String rearIdentity, long ownerGeneration) {
        if (RearScreenKeeperService.hasAnyKeeperInstance()) return false;
        boolean reconciled = LegacyDisplayOccupancy.reconcileNaturallyStoppedKeeperForClosedOwner(context,
                ownerTask, expectedBaseComponent, displayId, rearIdentity, ownerGeneration,
                () -> !RearScreenKeeperService.hasAnyKeeperInstance());
        return reconciled && !RearScreenKeeperService.hasAnyKeeperInstance();
    }

    private static boolean exactUnacceptedOldCloseArguments(
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, String sessionId, int taskId,
            String packageName, String baseComponent, int displayId, String identity, long generation) {
        return journal != null && packageName != null && baseComponent != null && identity != null
                && "PREPARING".equals(journal.getPhase())
                && "NONE".equals(journal.getDecision()) && !journal.getNewVirtual()
                && journal.getNewSessionId().isBlank()
                && exactOldCloseArguments(journal, sessionId, taskId, packageName, baseComponent, displayId, identity, generation);
    }

    private static boolean exactOldCloseArguments(
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, String sessionId, int taskId,
            String packageName, String baseComponent, int displayId, String identity, long generation) {
        return journal != null && sessionId != null && packageName != null && baseComponent != null && identity != null
                && sessionId.equals("physical:" + journal.getOldTask() + ":" + journal.getOldGeneration())
                && taskId == journal.getOldTask() && packageName.equals(journal.getOldPackage())
                && baseComponent.equals(journal.getOldBase()) && displayId == journal.getDisplayId()
                && identity.equals(journal.getIdentity()) && generation == journal.getOldGeneration();
    }

    private static boolean canExplicitlyClosePreparingVirtualRollback(Context context, ITaskService remote,
            DisplayActionGate.Reservation reservation, LegacyDisplayOccupancy.PhysicalReplacementJournal journal,
            boolean requireBothMain) throws Exception {
        if (!journal.equals(LegacyDisplayOccupancy.physicalReplacementJournal(context))
                || !LegacyDisplayOccupancy.physicalProjectionMatches(context, journal.getOldTask(), journal.getOldPackage(),
                    journal.getOldBase(), journal.getDisplayId(), journal.getIdentity(), journal.getOldGeneration())
                || !selectedRearIdentityMatches(remote, journal.getDisplayId(), journal.getIdentity())
                || exactPlacement(remote, journal.getNewTask(), journal.getNewPackage(), journal.getNewBase(),
                    journal.getDisplayId(), journal.getIdentity()) != ExactTaskPlacement.MAIN
                || !hasTerminalCandidateForExplicitRollbackClose(journal, new JSONObject(remote.getRearVirtualCastStatus())))
            return false;
        ExactTaskPlacement a = exactPlacement(remote, journal.getOldTask(), journal.getOldPackage(), journal.getOldBase(),
                journal.getDisplayId(), journal.getIdentity());
        String rear = remote.getDisplayCastOccupancy(journal.getDisplayId());
        return (!requireBothMain || a == ExactTaskPlacement.MAIN)
                && p2vRecoveryAPlacementAllowed(a, true, rear, journal.getDisplayId(), journal.getOldTask(),
                    journal.getOldPackage(), journal.getOldBase())
                && journal.equals(LegacyDisplayOccupancy.physicalReplacementJournal(context))
                && RearAnimationManager.isCurrentDisplayAction(reservation);
    }

    public static boolean hasTerminalCandidateForExplicitRollbackClose(
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal, JSONObject state) {
        if (!isPreparingVirtualRollback(journal)
                || !exactVirtualResourcesStopped(state)) return false;
        if (!journal.getNewSessionId().isBlank()) return matchesVirtualTerminal(state, journal);
        // A rollback before allocation may outlive the control process's last-session history.
        // An untouched receipt plus the current manager's explicit no-resource proof is sufficient
        // for an explicit exit; it does not authorize automatic restoration of A.
        return journal.getNewBackendGeneration() == 0L && "NOT_REQUESTED".equals(journal.getReaderState())
                && journal.getReaderId().isBlank() && journal.getReaderGeneration() == 0L;
    }

    private static boolean isPreparingVirtualRollback(LegacyDisplayOccupancy.PhysicalReplacementJournal journal) {
        return journal != null && PhysicalReplacementLifecycle.isPreparingVirtualRollback(
                journal.getPhase(), journal.getDecision(), journal.getNewVirtual());
    }

    static String unacceptedPhysicalCloseRoute(ExactTaskPlacement oldTaskPlacement) {
        if (oldTaskPlacement == ExactTaskPlacement.MAIN) return "NO_MOVE_CLOSE";
        if (oldTaskPlacement == ExactTaskPlacement.REAR) return "RECOVER_THEN_CLOSE";
        return "INDETERMINATE";
    }

    /** Recovers only the caller's exact still-pending ticket and retains the gate for A's close. */
    private static DisplayActionGate.Reservation recoverUnacceptedPhysicalReplacementForClose(
            Context supplied, ITaskService remote,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return null;
        boolean keep = false;
        try {
            Context app = supplied.getApplicationContext();
            LegacyDisplayOccupancy.PhysicalReplacementJournal live = LegacyDisplayOccupancy.physicalReplacementJournal(app);
            if (!sameUnacceptedPhysicalCloseJournal(expected, live)
                    || !RearAnimationManager.isCurrentDisplayAction(reservation)) return null;
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            if (selected == null || !selected.matches(live.getDisplayId(), live.getIdentity())
                    || exactPlacement(remote, live.getOldTask(), live.getOldPackage(), live.getOldBase(),
                            live.getDisplayId(), live.getIdentity()) != ExactTaskPlacement.REAR) return null;
            StartResult recovered = compensateReplacement(app, remote, reservation, live,
                    true, true, true, "replacement_explicit_old_owner_close", false);
            if (recovered.indeterminate || recovered.committed
                    || LegacyDisplayOccupancy.physicalReplacementJournal(app) != null
                    || !LegacyDisplayOccupancy.physicalProjectionMatches(app, live.getOldTask(), live.getOldPackage(),
                            live.getOldBase(), live.getDisplayId(), live.getIdentity(), live.getOldGeneration())
                    || exactPlacement(remote, live.getOldTask(), live.getOldPackage(), live.getOldBase(),
                            live.getDisplayId(), live.getIdentity()) != ExactTaskPlacement.REAR
                    || !RearAnimationManager.isCurrentDisplayAction(reservation)) return null;
            SelectedRearIdentity selectedAfter = readSelectedRearIdentity(remote);
            if (selectedAfter == null || !selectedAfter.matches(live.getDisplayId(), live.getIdentity())) return null;
            keep = true;
            return reservation;
        } catch (Exception unknown) { return null; }
        finally { if (!keep) RearAnimationManager.completeDisplayAction(reservation); }
    }

    static boolean sameUnacceptedPhysicalCloseJournal(
            LegacyDisplayOccupancy.PhysicalReplacementJournal a,
            LegacyDisplayOccupancy.PhysicalReplacementJournal b) {
        return a != null && b != null && "PREPARING".equals(a.getPhase()) && "PREPARING".equals(b.getPhase())
                && "NONE".equals(a.getDecision()) && "NONE".equals(b.getDecision())
                && !a.getNewVirtual() && !b.getNewVirtual()
                && a.getToken().equals(b.getToken()) && a.getOldTask() == b.getOldTask()
                && a.getOldPackage().equals(b.getOldPackage()) && a.getOldBase().equals(b.getOldBase())
                && a.getDisplayId() == b.getDisplayId() && a.getIdentity().equals(b.getIdentity())
                && a.getOldGeneration() == b.getOldGeneration()
                && a.getOldLauncherDisabled() == b.getOldLauncherDisabled()
                && a.getNewTask() == b.getNewTask() && a.getNewPackage().equals(b.getNewPackage())
                && a.getNewBase().equals(b.getNewBase()) && a.getNewGeneration() == b.getNewGeneration()
                && a.getNewSessionId().isBlank() && b.getNewSessionId().isBlank()
                && a.getNewCoordinatorGeneration() == b.getNewCoordinatorGeneration()
                && a.getNewOwnerIdentity().equals(b.getNewOwnerIdentity())
                && a.getNewBackendGeneration() == b.getNewBackendGeneration()
                && a.getVirtualBaseline().equals(b.getVirtualBaseline())
                && a.getReaderState().equals(b.getReaderState()) && a.getReaderId().equals(b.getReaderId())
                && a.getReaderGeneration() == b.getReaderGeneration();
    }

    private static boolean sameReplacementJournalIdentity(
            LegacyDisplayOccupancy.PhysicalReplacementJournal a,
            LegacyDisplayOccupancy.PhysicalReplacementJournal b) {
        return a != null && b != null && a.getToken().equals(b.getToken())
                && a.getPhase().equals(b.getPhase()) && a.getDecision().equals(b.getDecision())
                && a.getNewVirtual() == b.getNewVirtual() && a.getOldTask() == b.getOldTask()
                && a.getOldPackage().equals(b.getOldPackage()) && a.getOldBase().equals(b.getOldBase())
                && a.getDisplayId() == b.getDisplayId() && a.getIdentity().equals(b.getIdentity())
                && a.getOldGeneration() == b.getOldGeneration() && a.getNewTask() == b.getNewTask()
                && a.getNewPackage().equals(b.getNewPackage()) && a.getNewBase().equals(b.getNewBase())
                && a.getNewGeneration() == b.getNewGeneration()
                && a.getNewSessionId().equals(b.getNewSessionId())
                && a.getNewCoordinatorGeneration() == b.getNewCoordinatorGeneration()
                && a.getNewOwnerIdentity().equals(b.getNewOwnerIdentity())
                && a.getNewBackendGeneration() == b.getNewBackendGeneration()
                && a.getVirtualBaseline().equals(b.getVirtualBaseline())
                && a.getReaderState().equals(b.getReaderState()) && a.getReaderId().equals(b.getReaderId())
                && a.getReaderGeneration() == b.getReaderGeneration();
    }

    private static boolean canAttemptExactUnknownBCompensation(Context app, ITaskService remote,
            DisplayActionGate.Reservation reservation,
            LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        if (!RearAnimationManager.isCurrentDisplayAction(reservation)
                || !sameReplacementJournalIdentity(expected,
                    LegacyDisplayOccupancy.physicalReplacementJournal(app))) return false;
        SelectedRearIdentity selected;
        try { selected = readSelectedRearIdentity(remote); }
        catch (Exception unknown) { return false; }
        if (selected == null || !selected.matches(expected.getDisplayId(), expected.getIdentity())) return false;
        boolean oldOwner = LegacyDisplayOccupancy.physicalProjectionMatches(app,
                expected.getOldTask(), expected.getOldPackage(), expected.getOldBase(),
                expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration());
        boolean newOwner = LegacyDisplayOccupancy.physicalProjectionMatches(app,
                expected.getNewTask(), expected.getNewPackage(), expected.getNewBase(),
                expected.getDisplayId(), expected.getIdentity(), expected.getNewGeneration());
        return oldOwner != newOwner;
    }

    /** Explicit close of A in PREPARING/NONE retires the journal without moving or restarting either task. */
    private static boolean closeUnacceptedPhysicalReplacement(Context supplied,
            ITaskService remote, LegacyDisplayOccupancy.PhysicalReplacementJournal expected) {
        Context app = supplied.getApplicationContext();
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) return false;
        try {
            if (!RearAnimationManager.isCurrentDisplayAction(reservation)) return false;
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            if (selected == null || !selected.matches(expected.getDisplayId(), expected.getIdentity())) return false;
            if (!LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                    expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(),
                    expected.getIdentity(), expected.getOldGeneration())) return false;
            if (RearScreenKeeperService.hasAnyKeeperInstance()) return false;
            ExactTaskPlacement a = exactPlacement(remote, expected.getOldTask(), expected.getOldPackage(),
                    expected.getOldBase(), expected.getDisplayId(), expected.getIdentity());
            ExactTaskPlacement b = exactPlacement(remote, expected.getNewTask(), expected.getNewPackage(),
                    expected.getNewBase(), expected.getDisplayId(), expected.getIdentity());
            JSONObject rear = new JSONObject(remote.getDisplayCastOccupancy(expected.getDisplayId()));
            JSONObject virtual = new JSONObject(remote.getRearVirtualCastStatus());
            if (a != ExactTaskPlacement.MAIN || b != ExactTaskPlacement.MAIN
                    || !exactStableNoOwnerRearInventory(rear, expected.getDisplayId(),
                            expected.getOldLauncherDisabled())
                    || !exactVirtualResourcesStopped(virtual)
                    || !RearAnimationManager.isCurrentDisplayAction(reservation)) return false;

            String ownerTask = expected.getOldPackage() + ":" + expected.getOldTask();
            int closeOutcome = LegacyDisplayOccupancy.beginControlledPhysicalProjectionClose(app,
                    ownerTask, expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration());
            if (closeOutcome < 0) return false;
            boolean committed = false;
            try {
                if (RearScreenKeeperService.hasAnyKeeperInstance()) return false;
                boolean claimed = LegacyDisplayOccupancy.physicalKeeperStartClaimed(app, ownerTask,
                        expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration());
                if (closeOutcome == 1 || claimed) {
                    // The exact service instance is absent and close now blocks a late onStart claim.
                    if (!LegacyDisplayOccupancy.clearClaimedKeeperForClosedOwner(app, ownerTask,
                            expected.getDisplayId(), expected.getIdentity(), expected.getOldGeneration())) return false;
                }
                if (RearScreenKeeperService.hasAnyKeeperInstance()
                        || !LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                                expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(),
                                expected.getIdentity(), expected.getOldGeneration())) return false;
                SelectedRearIdentity selectedAfter = readSelectedRearIdentity(remote);
                ExactTaskPlacement aAfter = exactPlacement(remote, expected.getOldTask(), expected.getOldPackage(),
                        expected.getOldBase(), expected.getDisplayId(), expected.getIdentity());
                ExactTaskPlacement bAfter = exactPlacement(remote, expected.getNewTask(), expected.getNewPackage(),
                        expected.getNewBase(), expected.getDisplayId(), expected.getIdentity());
                JSONObject rearAfter = new JSONObject(remote.getDisplayCastOccupancy(expected.getDisplayId()));
                JSONObject virtualAfter = new JSONObject(remote.getRearVirtualCastStatus());
                boolean ownerAfter = LegacyDisplayOccupancy.physicalProjectionMatches(app, expected.getOldTask(),
                        expected.getOldPackage(), expected.getOldBase(), expected.getDisplayId(),
                        expected.getIdentity(), expected.getOldGeneration());
                boolean resourcesStopped = exactVirtualResourcesStopped(virtualAfter);
                boolean rearNoOwner = exactStableNoOwnerRearInventory(rearAfter, expected.getDisplayId(),
                        expected.getOldLauncherDisabled());
                boolean keeperAbsent = !RearScreenKeeperService.hasAnyKeeperInstance();
                if (!top.zainzh.rearscreen.platform.PhysicalReplacementLifecycle.unacceptedPhysicalCloseAllowed(
                        expected.getPhase(), expected.getDecision(), expected.getNewVirtual(),
                        expected.getNewSessionId().isBlank(), ownerAfter, closeOutcome >= 0, keeperAbsent,
                        resourcesStopped, aAfter.name(), bAfter.name(), rearNoOwner)) return false;
                if (selectedAfter == null || !selectedAfter.matches(expected.getDisplayId(), expected.getIdentity())
                        || aAfter != ExactTaskPlacement.MAIN || bAfter != ExactTaskPlacement.MAIN
                        || !RearAnimationManager.isCurrentDisplayAction(reservation)) return false;
                committed = LegacyDisplayOccupancy.finishUnacceptedPhysicalReplacementClose(app, expected);
                return committed;
            } finally {
                if (!committed) LegacyDisplayOccupancy.cancelUnacceptedPhysicalReplacementClose(app, expected);
            }
        } catch (Exception unknown) { return false; }
        finally { RearAnimationManager.completeDisplayAction(reservation); }
    }

    static boolean exactStableNoOwnerRearInventory(JSONObject snapshot, int displayId) {
        return exactStableNoOwnerRearInventory(snapshot, displayId, false);
    }

    static boolean exactStableNoOwnerRearInventory(JSONObject snapshot, int displayId,
            boolean requireLauncher) {
        return exactStableNoOwnerRearInventory(snapshot, displayId, requireLauncher,
                RearSystemBridge::isValidatedRearLauncher);
    }

    static boolean exactStableNoOwnerRearInventory(JSONObject snapshot, int displayId,
            boolean requireLauncher, java.util.function.BiPredicate<String, Integer> verifiedLauncher) {
        if (snapshot == null || !"ok".equals(snapshot.optString("status"))
                || snapshot.optInt("display", -1) != displayId
                || !snapshot.has("power") || !snapshot.has("powerBefore") || !snapshot.has("powerAfter")
                || snapshot.optInt("power", Display.STATE_UNKNOWN) != snapshot.optInt("powerBefore", Display.STATE_UNKNOWN)
                || snapshot.optInt("power", Display.STATE_UNKNOWN) != snapshot.optInt("powerAfter", Display.STATE_UNKNOWN)) return false;
        int power = snapshot.optInt("power", Display.STATE_UNKNOWN);
        if (power != Display.STATE_ON && power != Display.STATE_OFF && power != Display.STATE_DOZE
                && power != Display.STATE_DOZE_SUSPEND) return false;
        String source = snapshot.optString("source");
        if (!"activity_task".equals(source) && !"activity_task_focused_stack".equals(source)
                && !"activity_focused_stack".equals(source)) return false;
        if (requireLauncher) {
            JSONArray rows = snapshot.optJSONArray("tasks");
            if (rows == null || rows.length() != 1 || verifiedLauncher == null) return false;
            JSONObject row = rows.optJSONObject(0);
            if (row == null || row.optInt("task", -1) <= 0 || !row.has("userId") || row.isNull("userId")) return false;
            String base = row.optString("base", ""), top = row.optString("top", "");
            String component = row.optString("baseComponent", ""), topComponent = row.optString("topComponent", "");
            int userId = row.optInt("userId", -1);
            return userId >= 0 && !base.isBlank() && base.equals(top) && !component.isBlank()
                    && component.equals(topComponent) && component.startsWith(base + "/")
                    && verifiedLauncher.test(component, userId);
        }
        return RearDisplayCastOccupancy.emptyOrOnlySystemLauncher(snapshot.toString(), displayId);
    }

    static boolean exactVirtualResourcesStopped(JSONObject status) {
        JSONObject input = status == null ? null : status.optJSONObject("managerInput");
        return status != null && "stopped".equals(status.optString("state"))
                && status.has("managerResourcesStopped") && status.optBoolean("managerResourcesStopped")
                && input != null && "stopped".equals(input.optString("state"))
                && input.has("resourcesStopped") && input.optBoolean("resourcesStopped")
                && input.has("outputAvailable") && !input.optBoolean("outputAvailable");
    }

    private static StartResult failed(String failure, boolean indeterminate) {
        return new StartResult(null, 0L, false, indeterminate, false, failure);
    }
    static StartResult recoverable(int taskId, long generation, boolean keeperStarted, String failure) {
        return recoverable("physical:" + taskId + ":" + generation, generation, keeperStarted, failure);
    }
    private static StartResult recoverable(String sessionId, long generation, boolean keeperStarted, String failure) {
        return new StartResult(sessionId, generation, false, true, keeperStarted, failure);
    }

    static ExactTaskPlacement parseExactPlacement(String encoded, int taskId, String packageName,
            String baseComponent, int rearDisplayId, String rearIdentity) {
        try {
            JSONObject row = new JSONObject(encoded);
            if (!"present".equals(row.optString("status")) || row.optInt("task", -1) != taskId
                    || row.optInt("selectedId", -1) != rearDisplayId
                    || !(rearDisplayId + ":" + row.optString("selectedUnique")).equals(rearIdentity)
                    || !packageName.equals(row.optString("package"))
                    || !baseComponent.equals(row.optString("base"))) return ExactTaskPlacement.UNKNOWN;
            int display = row.optInt("display", -1);
            if (display == rearDisplayId)
                return row.optBoolean("rootSnapshotKnown", false) && row.optBoolean("rootSole", false)
                        ? ExactTaskPlacement.REAR : ExactTaskPlacement.UNKNOWN;
            if (display == 0) return ExactTaskPlacement.MAIN;
            return ExactTaskPlacement.UNKNOWN;
        } catch (Exception invalid) { return ExactTaskPlacement.UNKNOWN; }
    }

    private static ExactTaskPlacement exactPlacement(ITaskService remote, int taskId, String packageName,
            String baseComponent, int rearDisplayId, String rearIdentity) throws Exception {
        return parseExactPlacement(remote.queryExactTaskPlacement(taskId), taskId, packageName,
                baseComponent, rearDisplayId, rearIdentity);
    }

    static boolean compensationConfirmedHome(ExactTaskPlacement before, ExactTaskPlacement after) {
        return (before == ExactTaskPlacement.REAR || before == ExactTaskPlacement.MAIN)
                && after == ExactTaskPlacement.MAIN;
    }
    private static String controlledMove(ITaskService remote, int taskId, String packageName,
            String baseComponent, int sourceDisplay, int targetDisplay, String rearIdentity) throws Exception {
        return remote.moveControlledPhysicalTask(taskId, packageName, baseComponent,
                sourceDisplay, targetDisplay, rearIdentity);
    }
    static SelectedRearIdentity parseSelectedRearIdentity(String encodedState) {
        try {
            JSONObject state = new JSONObject(encodedState);
            Object idValue = state.opt("selectedId");
            Object uniqueValue = state.opt("selectedUnique");
            if (!(idValue instanceof Number) || !(uniqueValue instanceof String)) return null;
            Number idNumber = (Number) idValue;
            int selectedId = idNumber.intValue();
            String unique = (String) uniqueValue;
            if (idNumber.doubleValue() != selectedId) return null;
            if (state.has("error") || selectedId <= 0 || unique.trim().isEmpty()) return null;
            return new SelectedRearIdentity(selectedId, unique);
        } catch (Exception invalid) { return null; }
    }

    private static SelectedRearIdentity readSelectedRearIdentity(ITaskService remote) throws Exception {
        return parseSelectedRearIdentity(remote.getRearDisplayState());
    }

    public static boolean selectedRearIdentityMatches(ITaskService remote, int displayId, String identity) {
        try {
            SelectedRearIdentity selected = readSelectedRearIdentity(remote);
            return selected != null && selected.matches(displayId, identity);
        } catch (Exception unknown) { return false; }
    }
}
