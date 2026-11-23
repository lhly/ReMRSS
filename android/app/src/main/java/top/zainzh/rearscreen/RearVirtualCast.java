package top.zainzh.rearscreen;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.*;
import android.view.Surface;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns a virtual display and presents its Surface on the physical rear display. */
final class RearVirtualCast {
    static final int RECOVERY_WAKE_REAR = 1;
    static final int RECOVERY_PRESENT_HOST = 2;
    // Framework flags hidden from the public SDK. Preserve these values for the shell-owned display.
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;
    private static final int CAST_DISPLAY_FLAGS = RearTrustedVirtualDisplay.TRUSTED_NON_MIRRORING
            | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            | VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;
    private final TaskService service;
    private final RearInputProxy input;
    private final AtomicLong generationSequence = new AtomicLong();
    private volatile Session current;
    private volatile Replacement replacement;
    private volatile String controlledStartInProgress;
    private volatile String last = "{\"state\":\"stopped\"}";
    RearVirtualCast(TaskService service, RearInputProxy input) { this.service = service; this.input = input; }

    private static final class Replacement {
        final Session previous;
        final Session next;
        final String token;
        volatile ISceneTaskActionGate actionGate;
        volatile boolean outputFrameReady, paused, rebound, completing, cancelled;
        final String operationId;
        final long ownerGeneration;
        Replacement(Session previous, Session next, String operationId, long ownerGeneration) {
            this.previous = previous; this.next = next; token = next.id;
            this.operationId = operationId; this.ownerGeneration = ownerGeneration;
        }
    }

    String prepareReplacement(String previousId, long previousGeneration, long previousOwnerGeneration,
                              String previousOwnerIdentity,
                              int previousTask, int previousHost, String previousInputId,
                              String previousBaseComponent, String expectedRearIdentity,
                              String expectedCandidateBaseComponent, int rearDisplayId,
                              String operationId, long ownerGeneration, String ownerIdentity,
                              Surface candidateSurface, String reader, int task, int host, int density,
                              String component, IBinder owner, IRearGestureListener gestures, String viewport,
                              boolean compatibility, int rotation, boolean full,
                              ISceneTaskActionGate targetActionGate) throws Exception {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        Session a = current;
        if (a == null || previousId == null || !a.id.equals(previousId) || a.generation != previousGeneration
                || a.closed || a.task != previousTask
                || a.host != previousHost || !a.inputId.equals(previousInputId)
                || a.baseComponent == null || !canonicalComponent(a.baseComponent).equals(canonicalComponent(previousBaseComponent))
                || a.coordinatorOwnerGeneration != previousOwnerGeneration
                || previousOwnerIdentity == null || !previousOwnerIdentity.equals(a.coordinatorOwnerIdentity)
                || !a.physicalIdentity.equals(expectedRearIdentity) || !expectedRearIdentity.equals(RearDisplay.identity())
                || owner != a.owner && !owner.equals(a.owner) || !a.reader.equals(reader)
                || expectedCandidateBaseComponent == null || operationId == null || operationId.isEmpty()
                || ownerGeneration <= 0 || ownerIdentity == null || ownerIdentity.isEmpty() || host != a.host
                || rearDisplayId != a.physicalRearId || rearDisplayId != RearDisplay.requireId()
                || !a.owner.isBinderAlive() || owner == null || !owner.isBinderAlive())
            throw new IllegalStateException("stale_previous_owner");
        String currentCandidateBase = task > 0 ? service.getTaskBaseComponent(task, 0) : null;
        if (candidateSurface == null || !candidateSurface.isValid() || task <= 0
                || !ControlledCastTaskIdentity.matchesExact(task, expectedCandidateBaseComponent, task,
                    currentCandidateBase, service.isTaskOnDisplay(task, 0))
                || !service.isTaskOnDisplay(host, a.physicalRearId))
            throw new IllegalStateException("replacement_target_unavailable");
        RearCastViewport safe = RearCastGeometry.read();
        RearCastViewport candidateView = RearCastGeometry.decode(viewport);
        RearCastViewport expectedView = full ? safe.full() : safe;
        if (!safe.samePhysicalArea(a.safeViewport) || !expectedView.samePhysicalArea(candidateView))
            throw new IllegalStateException("replacement_geometry_changed");
        synchronized (this) {
            if (current != a || replacement != null || controlledStartInProgress != null)
                throw new IllegalStateException("replacement_busy_or_stale");
        }
        VirtualCastRequest.density(Integer.toString(density));
        if (component != null) VirtualCastRequest.component(component);
        JSONObject plan = new JSONObject(renderPlan(task, component, density, compatibility, full));
        if (!RearCastGeometry.decode(plan.toString()).renderKey().equals(RearCastGeometry.decode(viewport).renderKey()))
            throw new IllegalStateException("replacement_render_plan_changed");
        Session b = new Session(candidateSurface, reader, task, host, density, component, owner, gestures, safe,
                plan.getInt("renderDensity"), compatibility, rotation, full, operationId, ownerGeneration, ownerIdentity, false);
        b.controlledStartActionGate = targetActionGate;
        b.expectedBaseComponent = canonicalComponent(expectedCandidateBaseComponent);
        if (a.freezeGuard == null) {
            b.close("previous_freeze_guard_unavailable", false);
            throw new IllegalStateException("previous_freeze_guard_unavailable");
        }
        b.freezeGuard = a.freezeGuard;
        b.ownsFreezeGuard = false;
        Replacement transaction = new Replacement(a, b, operationId, ownerGeneration);
        boolean reserved;
        synchronized (this) {
            reserved = current == a && replacement == null && controlledStartInProgress == null && !a.closed;
            // Reserve before the slow package-guard IPC, so legacy start cannot stop A in the gap.
            if (reserved) replacement = transaction;
        }
        if (!reserved) {
            b.close("replacement_busy_or_stale", false);
            throw new IllegalStateException("replacement_busy_or_stale");
        }
        try {
            a.freezeGuard.includePackage(b.packageName);
            synchronized (this) {
                if (replacement != transaction || current != a || a.closed || transaction.cancelled)
                    throw new IllegalStateException("replacement_cancelled_during_guard_prepare");
            }
            b.begin(false);
            if (b.baseComponent == null || !canonicalComponent(b.baseComponent).equals(b.expectedBaseComponent))
                throw new IllegalStateException("replacement_candidate_component_changed");
            android.util.Log.i("RearVirtualCast", "replacement_prepared token=" + transaction.token
                    + " previous=" + a.id + " task=" + b.task + " display=" + b.display);
            return b.status();
        } catch (Exception failure) {
            b.close("replacement_prepare_failed: " + root(failure), false);
            synchronized (this) { if (replacement == transaction) replacement = null; }
            if (current == a && !a.closed && a.freezeGuard != null) {
                try { a.freezeGuard.setPackages(new String[]{a.packageName}); }
                catch (Exception rollback) { CompatibilityDiagnostics.record("freeze_guard_package_rollback_failed", rollback.toString()); }
            }
            throw failure;
        }
    }

    boolean replacementOutputFrameReady(String token) {
        Replacement tx = replacement;
        if (!matches(tx, token) || tx.cancelled || tx.next.closed || !tx.next.owner.isBinderAlive()
                || tx.next.surface == null || !tx.next.surface.isValid() || !replacementIdentityCurrent(tx)) return false;
        tx.outputFrameReady = true;
        return true;
    }

    boolean beginReplacementHandoff(String token, ISceneTaskActionGate actionGate) throws Exception {
        Replacement tx = replacement;
        if (!matches(tx, token) || !tx.outputFrameReady || tx.completing || tx.cancelled
                || !tx.previous.owner.isBinderAlive() || !tx.next.owner.isBinderAlive()
                || current != tx.previous || tx.previous.closed || tx.next.closed || !actionCurrent(actionGate)
                || !replacementIdentityCurrent(tx)) return false;
        synchronized (tx) {
            if (tx.actionGate != null) return sameGate(tx.actionGate, actionGate) && tx.paused;
            tx.actionGate = actionGate;
        }
        // Record the attempted transition before touching the reader. Abort must reconcile even
        // when the native PAUSED acknowledgment is delayed or the IPC fails mid-command.
        tx.paused = true;
        if (!input.pauseForTransfer(tx.previous.inputId, token)) return false;
        return true;
    }

    boolean bindReplacementInput(String token, ISceneTaskActionGate actionGate) throws Exception {
        Replacement tx = replacement;
        if (!matches(tx, token) || !tx.paused || tx.cancelled || tx.completing
                || !sameGate(tx.actionGate, actionGate) || !actionCurrent(actionGate)
                || current != tx.previous || tx.previous.closed || tx.next.closed
                || !replacementIdentityCurrent(tx)) return false;
        boolean rebound = input.rebindPaused(tx.previous.inputId, token, tx.next.task, tx.next.display,
                tx.next.owner, tx.next.viewport, tx.next.contentRotation, tx.next.inputFeedback(),
                tx.next.inputMenu(), tx.next.inputInteraction());
        tx.rebound = rebound;
        return rebound;
    }

    String finishReplacementHandoff(String token, boolean commit, ISceneTaskActionGate actionGate) throws Exception {
        Replacement tx = replacement;
        if (!matches(tx, token) || !tx.paused || tx.completing || current != tx.previous
                || !sameGate(tx.actionGate, actionGate) || (commit && !actionCurrent(actionGate))) return "stale_candidate";
        synchronized (tx) {
            if (tx.completing) return "candidate_busy";
            tx.completing = true;
        }
        if (!commit) {
            boolean restored;
            try { restored = tx.previous.closed || !tx.paused || input.abortTransfer(tx.previous.inputId, token); }
            catch (Exception failure) { restored = false; CompatibilityDiagnostics.record("virtual_candidate_abort", failure.toString()); }
            if (restored) tx.previous.touchLease();
            tx.next.close("replacement_aborted", false);
            synchronized (this) { if (replacement == tx) replacement = null; }
            if (!restored) {
                CompatibilityDiagnostics.record("virtual_candidate_abort", "previous_input_restore_unverified_candidate_released");
                return "aborted_previous_input_unverified_candidate_released";
            }
            return "aborted_previous_retained";
        }
        if (!tx.outputFrameReady || !tx.rebound || tx.cancelled || !tx.next.owner.isBinderAlive()
                || !replacementIdentityCurrent(tx)) {
            tx.completing = false;
            return "candidate_not_ready";
        }
        if (!actionCurrent(actionGate) || !replacementIdentityCurrent(tx)
                || !input.resumeTransfer(tx.previous.inputId, token)) {
            tx.completing = false;
            return "candidate_input_resume_unverified";
        }
        boolean gateCurrentAtCommit = actionCurrent(actionGate) && replacementIdentityCurrent(tx);
        synchronized (this) {
            if (replacement != tx || current != tx.previous || tx.previous.closed || tx.next.closed || tx.cancelled
                    || !tx.next.owner.isBinderAlive() || !gateCurrentAtCommit || !actionCurrent(actionGate)) {
                tx.completing = false;
                return "owner_changed_after_input_resume";
            }
            if (!input.commitTransfer(tx.previous.inputId, token)) {
                tx.completing = false;
                return "candidate_input_commit_unverified";
            }
            tx.next.inputId = tx.previous.inputId;
            tx.previous.inputId = "";
            tx.next.freezeGuard = tx.previous.freezeGuard;
            tx.next.ownsFreezeGuard = tx.previous.ownsFreezeGuard;
            tx.previous.freezeGuard = null;
            tx.previous.ownsFreezeGuard = false;
            current = tx.next;
            replacement = null;
            tx.next.activate();
        }
        tx.previous.close("replaced", false);
        if (tx.next.freezeGuard != null) {
            try { tx.next.freezeGuard.setPackages(new String[]{tx.next.packageName}); }
            catch (Exception transferFailure) { CompatibilityDiagnostics.record("freeze_guard_package_finalize_failed", transferFailure.toString()); }
        }
        android.util.Log.i("RearVirtualCast", "replacement_committed previous=" + tx.previous.id
                + " current=" + tx.next.id + " task=" + tx.next.task + " display=" + tx.next.display);
        return tx.next.status();
    }

    boolean abortReplacement(String token) throws Exception {
        Replacement tx = replacement;
        if (!matches(tx, token)) return false;
        synchronized (tx) {
            if (tx.completing) return false;
            tx.completing = true;
        }
        boolean restored;
        try { restored = tx.previous.closed || !tx.paused || input.abortTransfer(tx.previous.inputId, token); }
        catch (Exception failure) { restored = false; CompatibilityDiagnostics.record("virtual_candidate_abort", failure.toString()); }
        if (restored && tx.paused) tx.previous.touchLease();
        tx.next.close("replacement_aborted", false);
        synchronized (this) { if (replacement == tx) replacement = null; }
        if (restored && current == tx.previous && !tx.previous.closed && tx.previous.freezeGuard != null) {
            try { tx.previous.freezeGuard.setPackages(new String[]{tx.previous.packageName}); }
            catch (Exception rollback) { CompatibilityDiagnostics.record("freeze_guard_package_rollback_failed", rollback.toString()); }
        }
        if (!restored) CompatibilityDiagnostics.record("virtual_candidate_abort",
                "previous_input_restore_unverified_candidate_released");
        return restored;
    }

    private boolean matches(Replacement tx, String token) {
        return tx != null && token != null && tx.token.equals(token) && replacement == tx;
    }

    /** Exact final CAS predicate: both task identities, owner generations, host, and physical rear stay pinned. */
    private boolean replacementIdentityCurrent(Replacement tx) {
        try {
            Session a = tx.previous, b = tx.next;
            if (replacement != tx || current != a || a.closed || b.closed || tx.cancelled
                    || !a.owner.isBinderAlive() || !b.owner.isBinderAlive()
                    || a.coordinatorOwnerGeneration <= 0 || b.coordinatorOwnerGeneration != tx.ownerGeneration
                    || !tx.operationId.equals(b.coordinatorOperationId)
                    || !RearDisplay.identity().equals(a.physicalIdentity)
                    || !a.physicalIdentity.equals(b.physicalIdentity)
                    || a.physicalRearId != b.physicalRearId || a.physicalRearId != RearDisplay.requireId()
                    || !service.isTaskOnDisplay(a.host, a.physicalRearId)
                    || !service.isTaskOnDisplay(a.task, a.display)
                    || !service.isTaskOnDisplay(b.host, b.physicalRearId)
                    || !service.isTaskOnDisplay(b.task, b.display)
                    || !canonicalComponent(a.baseComponent).equals(canonicalComponent(
                        service.getTaskBaseComponent(a.task, a.display)))
                    || !canonicalComponent(b.expectedBaseComponent).equals(canonicalComponent(
                        service.getTaskBaseComponent(b.task, b.display)))) return false;
            return true;
        } catch (Exception unavailable) { return false; }
    }

    private static boolean sameGate(ISceneTaskActionGate left, ISceneTaskActionGate right) {
        return left != null && right != null && left.asBinder().equals(right.asBinder());
    }

    private static boolean actionCurrent(ISceneTaskActionGate gate) {
        try { return gate != null && gate.isCurrent(); }
        catch (RemoteException unavailable) { return false; }
    }

    private static String canonicalComponent(String value) {
        android.content.ComponentName component = value == null ? null
                : android.content.ComponentName.unflattenFromString(value);
        return component == null ? "" : component.flattenToString();
    }

    private void expireCandidate(Session session) {
        Replacement tx = replacement;
        if (tx != null && tx.next == session && !tx.completing) {
            try { abortReplacement(tx.token); }
            catch (Exception cleanupFailure) {
                CompatibilityDiagnostics.record("virtual_candidate_cleanup_failed", cleanupFailure.toString());
            }
        }
    }

    /** A closes invalidate B immediately; potentially slow rollback/release runs outside A's monitor. */
    private void previousSessionClosed(Session previous) {
        Replacement tx = replacement;
        if (tx == null || tx.previous != previous) return;
        synchronized (tx) { tx.cancelled = true; }
        Thread cleanup = new Thread(() -> {
            long deadline = SystemClock.uptimeMillis() + 2500;
            while (replacement == tx && tx.completing && SystemClock.uptimeMillis() < deadline) {
                try { Thread.sleep(20); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            if (replacement == tx) {
                try { abortReplacement(tx.token); }
                catch (Exception failure) { CompatibilityDiagnostics.record("virtual_candidate_cleanup_failed", failure.toString()); }
            }
        }, "rear-cast-candidate-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    String renderPlan(int task, String component, int density, boolean compatibility, boolean full) throws Exception {
        if (component != null) VirtualCastRequest.component(component);
        if (task >= 0 ? !service.isTaskOnDisplay(task, 0) : component == null)
            throw new IllegalStateException("Source task unavailable");
        String pkg = component == null ? service.getPackageNameFromTaskId(task) : component.substring(0, component.indexOf('/'));
        return renderPlanForPackage(pkg, density, compatibility, full, task, component);
    }

    String renderPlanForPackage(String pkg, int density, boolean compatibility, boolean full) throws Exception {
        return renderPlanForPackage(pkg, density, compatibility, full, -1, null);
    }

    private String renderPlanForPackage(String pkg, int density, boolean compatibility, boolean full,
            int task, String component) throws Exception {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        VirtualCastRequest.packageName(pkg);
        VirtualCastRequest.density(Integer.toString(density));
        int source = RearCastGeometry.displayDensity(0);
        RearCastViewport safe = RearCastGeometry.read();
        int actual = compatibility
                ? RearCastCompatibility.density(true, density, source)
                : RearCastCompatibility.automaticRenderDensity(density, source, safe);
        RearCastViewport render = RearCastCompatibility.viewport(full ? safe.full() : safe, density, actual);
        return new JSONObject(RearCastGeometry.encode(render)).put("package", pkg).put("renderDensity", actual)
                .put("requestedDensity", density).put("sourceDensity", source)
                .put("renderProfile", actual == density ? "layout_density" : "source_density")
                .put("autoRotation", RearCastOrientation.initialRotation(task, component, render))
                .put("compatibility", compatibility ? "source_density" : "none").toString();
    }
    String start(Surface surface, String reader, int task, int host, int density, String component,
            IBinder owner, IRearGestureListener gestures, String viewport, boolean compatibility, int rotation,
            boolean full) throws Exception {
        return start(surface, reader, task, host, density, component, owner, gestures, viewport, compatibility,
                rotation, full, null, null);
    }

    String startNotification(Surface surface, String reader, int host, int density, String packageName,
            IBinder owner, IRearGestureListener gestures, String viewport, boolean compatibility, int rotation,
            boolean full, IRearNotificationTarget target, boolean locked, boolean homeOnly) throws Exception {
        if (target == null || !target.asBinder().isBinderAlive()
                || BuildConfig.APPLICATION_ID.equals(VirtualCastRequest.packageName(packageName)))
            throw new SecurityException("Invalid rear notification target");
        return start(surface, reader, -1, host, density, null, owner, gestures, viewport, compatibility,
                rotation, full, null, new NotificationTarget(packageName, target, locked, homeOnly));
    }

    private static final class NotificationTarget {
        final String packageName;
        final IRearNotificationTarget action;

        final boolean locked;
        final boolean homeOnly;

        NotificationTarget(String packageName, IRearNotificationTarget action, boolean locked, boolean homeOnly) {
            this.packageName = packageName;
            this.action = action;
            this.locked = locked;
            this.homeOnly = homeOnly;
        }
    }

    String startDisposableWallpaperCast(Surface surface, String reader, int host, int density, String component,
            IBinder owner, IRearGestureListener gestures, String viewport, boolean compatibility, int rotation,
            boolean full, ISceneTaskActionGate launchGate) throws Exception {
        if (launchGate == null) throw new SecurityException("Missing parked wallpaper action gate");
        return start(surface, reader, -1, host, density, component, owner, gestures, viewport, compatibility,
                rotation, full, launchGate, null);
    }

    private synchronized String start(Surface surface, String reader, int task, int host, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, ISceneTaskActionGate wallpaperLaunchGate,
            NotificationTarget notificationTarget) throws Exception {
        boolean disposableWallpaperCast = wallpaperLaunchGate != null;
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        if (replacement != null || controlledStartInProgress != null) throw new IllegalStateException("replacement_in_progress");
        RearCastViewport.validateContentRotation(rotation);
        VirtualCastRequest.density(Integer.toString(density));
        if (component != null) VirtualCastRequest.component(component);
        RearCastViewport safe = RearCastGeometry.read();
        if (!(full ? safe.full() : safe).key().equals(RearCastGeometry.decode(viewport).key())) throw new IllegalStateException("Rear output area changed before start");
        JSONObject plan = new JSONObject(notificationTarget == null
                ? renderPlan(task, component, density, compatibility, full)
                : renderPlanForPackage(notificationTarget.packageName, density, compatibility, full));
        if (!RearCastGeometry.decode(plan.toString()).renderKey().equals(RearCastGeometry.decode(viewport).renderKey()))
            throw new IllegalStateException("Rear render plan changed before start");
        if (surface == null || !surface.isValid() || owner == null || !owner.isBinderAlive()
                || (task < 0 ? component == null && notificationTarget == null : !service.isTaskOnDisplay(task, 0))
                || !service.isTaskOnDisplay(host, RearDisplay.requireId()))
            throw new IllegalStateException("Main task / rear host unavailable");
        if (disposableWallpaperCast) {
            ComponentName target = ComponentName.unflattenFromString(component);
            int rear = RearDisplay.requireId();
            ComponentName base = ComponentName.unflattenFromString(
                    java.util.Objects.toString(service.getTaskBaseComponent(host, rear), ""));
            if (!AiRearWallpaperSupport.supported() || target == null
                    || !AiRearAppCatalog.isCastTarget(RearSystemBridge.systemContext(), target, BuildConfig.APPLICATION_ID)
                    || !new ComponentName(BuildConfig.APPLICATION_ID,
                            "top.zainzh.rearscreen.VirtualRearHostActivity").equals(base)
                    || !(BuildConfig.APPLICATION_ID + ":" + host).equals(service.getForegroundAppOnDisplay(rear)))
                throw new SecurityException("parked_wallpaper_host_or_target_mismatch");
            if (current != null || !"stopped".equals(new JSONObject(input.status()).optString("state")))
                throw new IllegalStateException("parked_wallpaper_cast_or_input_busy");
            if (!wallpaperLaunchGate.isCurrent())
                throw new IllegalStateException("parked_wallpaper_authorization_changed");
        } else if (notificationTarget != null) {
            if (current != null || !"stopped".equals(new JSONObject(input.status()).optString("state")))
                throw new IllegalStateException("rear_notification_cast_busy");
            int rear = RearDisplay.requireId();
            if (!(BuildConfig.APPLICATION_ID + ":" + host).equals(service.getForegroundAppOnDisplay(rear)))
                throw new IllegalStateException("rear_notification_host_not_foreground");
        } else {
            stop(null);
            input.stop(null);
        }
        // Closing a legacy session may have released its host Surface while we waited.
        if (!surface.isValid() || !owner.isBinderAlive() || !service.isTaskOnDisplay(host, RearDisplay.requireId()))
            throw new IllegalStateException("Rear host changed during previous session cleanup");
        Session s = new Session(surface, reader, task, host, density, component, owner, gestures, safe,
                plan.getInt("renderDensity"), compatibility, rotation, full, null, 0L, null,
                disposableWallpaperCast, notificationTarget);
        s.wallpaperLaunchGate = wallpaperLaunchGate;
        current = s;
        try { s.begin(true); return s.status(); }
        catch (Exception e) { CompatibilityDiagnostics.record("virtual_start_failed", root(e).toString()); s.close("start_failed: " + root(e)); throw e; }
    }

    static int visibleLockedNotificationTask(RearDisplayCastOccupancy.Snapshot state,
            int targetDisplay, String packageName) {
        if (state.displayId != targetDisplay || state.failure != null || state.tasks.size() != 1
                || state.displayState != android.view.Display.STATE_ON) return -1;
        RearDisplayCastOccupancy.Task candidate = state.tasks.get(0);
        if (candidate.userId != 0 || !packageName.equals(candidate.basePackage)
                || !packageName.equals(candidate.topPackage) || !Boolean.TRUE.equals(candidate.visible)
                || !Boolean.TRUE.equals(candidate.requested) || !Boolean.FALSE.equals(candidate.sleeping)) return -1;
        return candidate.taskId;
    }

    private static Map<Integer, Integer> runningTaskDisplays() throws Exception {
        List<?> tasks = RearDisplayCastOccupancyReader.taskInfos(
                RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub"));
        Map<Integer, Integer> result = new HashMap<>();
        for (Object row : tasks) {
            if (!(row instanceof ActivityManager.RunningTaskInfo))
                throw new IllegalStateException("parked_wallpaper_task_inventory_unavailable");
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) row;
            result.put(info.taskId, info.getClass().getField("displayId").getInt(info));
        }
        return result;
    }

    /** Controlled empty start: unlike legacy start(), it never performs stop(null) on either owner. */
    String startControlled(Surface surface, String reader, int task, int host, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, String expectedBaseComponent,
            int expectedRearDisplayId, String expectedRearIdentity, String operationId,
            long ownerGeneration, String ownerIdentity) throws Exception {
        return startControlled(surface, reader, task, host, density, component, owner, gestures, viewport,
                compatibility, rotation, full, expectedBaseComponent, expectedRearDisplayId,
                expectedRearIdentity, operationId, ownerGeneration, ownerIdentity, null);
    }

    String startControlled(Surface surface, String reader, int task, int host, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, String expectedBaseComponent,
            int expectedRearDisplayId, String expectedRearIdentity, String operationId,
            long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate) throws Exception {
        return startControlled(surface, reader, task, host, density, component, owner, gestures, viewport,
                compatibility, rotation, full, expectedBaseComponent, expectedRearDisplayId,
                expectedRearIdentity, operationId, ownerGeneration, ownerIdentity, actionGate, false);
    }

    String startControlled(Surface surface, String reader, int task, int host, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, String expectedBaseComponent,
            int expectedRearDisplayId, String expectedRearIdentity, String operationId,
            long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            boolean deferLauncherRestore) throws Exception {
        return startControlled(surface, reader, task, host, density, component, owner, gestures, viewport,
                compatibility, rotation, full, expectedBaseComponent, expectedRearDisplayId,
                expectedRearIdentity, operationId, ownerGeneration, ownerIdentity, actionGate,
                deferLauncherRestore, null);
    }

    String startControlled(Surface surface, String reader, int task, int host, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, String expectedBaseComponent,
            int expectedRearDisplayId, String expectedRearIdentity, String operationId,
            long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            boolean deferLauncherRestore, IPhysicalCastReaderLifecycle readerLifecycle) throws Exception {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        if (deferLauncherRestore && readerLifecycle == null)
            throw new IllegalArgumentException("physical_reader_lifecycle_required");
        synchronized (this) {
            if (current != null || replacement != null || controlledStartInProgress != null)
                throw new IllegalStateException("controlled_start_owner_busy");
            controlledStartInProgress = operationId;
        }
        Session s = null;
        try {
            if (operationId == null || operationId.isEmpty() || ownerGeneration <= 0L
                    || ownerIdentity == null || ownerIdentity.isEmpty() || expectedRearIdentity == null
                    || expectedRearIdentity.isEmpty() || expectedRearDisplayId <= 0
                    || expectedRearDisplayId != RearDisplay.requireId()
                    || !expectedRearIdentity.equals(RearDisplay.identity()))
                throw new IllegalArgumentException("controlled_start_ticket_invalid");
            if (actionGate != null) {
                final boolean gateCurrent;
                try { gateCurrent = actionGate.isCurrent(); }
                catch (Exception unavailable) {
                    if (BuildConfig.DEBUG) android.util.Log.w("RearVirtualCast",
                            "stage=controlled_start_gate_rejected phase=entry reason=action_gate_unavailable");
                    throw unavailable;
                }
                if (!gateCurrent) {
                    if (BuildConfig.DEBUG) android.util.Log.w("RearVirtualCast",
                            "stage=controlled_start_gate_rejected phase=entry reason=action_gate_stale");
                    throw new IllegalStateException("controlled_start_gate_stale");
                }
            }
            JSONObject inputState = new JSONObject(input.status());
            if (!"stopped".equals(inputState.optString("state")))
                throw new IllegalStateException("controlled_start_reader_busy");
            RearCastViewport.validateContentRotation(rotation);
            VirtualCastRequest.density(Integer.toString(density));
            if (component != null) VirtualCastRequest.component(component);
            RearCastViewport safe = RearCastGeometry.read();
            if (!(full ? safe.full() : safe).samePhysicalArea(RearCastGeometry.decode(viewport)))
                throw new IllegalStateException("Rear output area changed before controlled start");
            JSONObject plan = new JSONObject(renderPlan(task, component, density, compatibility, full));
            if (!RearCastGeometry.decode(plan.toString()).renderKey().equals(RearCastGeometry.decode(viewport).renderKey()))
                throw new IllegalStateException("Rear render plan changed before controlled start");
            String actualTargetBase = task > 0 ? service.getTaskBaseComponent(task, 0) : null;
            if (surface == null || !surface.isValid() || owner == null || !owner.isBinderAlive()
                    || !ControlledCastTaskIdentity.matchesExact(task, expectedBaseComponent, task,
                        actualTargetBase, service.isTaskOnDisplay(task, 0))
                    || !service.isTaskOnDisplay(host, expectedRearDisplayId)
                    || !expectedRearIdentity.equals(RearDisplay.identity()))
                throw new IllegalStateException("Controlled target/host unavailable");
            s = new Session(surface, reader, task, host, density, component, owner, gestures, safe,
                    plan.getInt("renderDensity"), compatibility, rotation, full, operationId, ownerGeneration, ownerIdentity, false);
            s.deferLauncherRestore = deferLauncherRestore;
            s.physicalReaderLifecycle = readerLifecycle;
            s.expectedBaseComponent = canonicalComponent(expectedBaseComponent);
            s.controlledStartActionGate = actionGate;
            synchronized (this) {
                if (current != null || replacement != null || !operationId.equals(controlledStartInProgress))
                    throw new IllegalStateException("controlled_start_owner_changed");
                current = s;
            }
            if (readerLifecycle != null && !s.recordReaderLifecycle("SESSION_CREATED", "", 0L))
                throw new IllegalStateException("physical_session_receipt_rejected");
            s.begin(true);
            s.ensureControlledStartCurrent("ready");
            if (s.baseComponent == null || !canonicalComponent(s.baseComponent).equals(s.expectedBaseComponent))
                throw new IllegalStateException("controlled_start_target_component_changed");
            return s.status();
        } catch (Exception failure) {
            CompatibilityDiagnostics.record("controlled_virtual_start_failed", root(failure).toString());
            if (s != null) s.close("controlled_start_failed: " + root(failure), false);
            throw failure;
        } finally {
            synchronized (this) {
                if (operationId != null && operationId.equals(controlledStartInProgress)) controlledStartInProgress = null;
            }
        }
    }
    void stop(String id) {
        Replacement tx = replacement;
        if (tx != null && (id == null || id.equals(tx.previous.id) || id.equals(tx.next.id))) {
            try {
                if (!abortReplacement(tx.token)) {
                    android.util.Log.w("RearVirtualCast", "stop_deferred replacement=" + tx.token);
                    return;
                }
            } catch (Exception cleanupFailure) {
                CompatibilityDiagnostics.record("virtual_candidate_cleanup_failed", cleanupFailure.toString());
                return;
            }
        }
        Session s = current;
        if (s != null && (id == null || s.id.equals(id))) s.close("stopped");
    }
    void stopWallpaper(String id, IRearWallpaperOutputReleased receipt) {
        Session s = current;
        if (s == null || !id.equals(s.id) || !s.disposableWallpaperCast)
            throw new IllegalStateException("parked_wallpaper_release_identity_mismatch");
        s.close("stopped", false, receipt);
    }

    String status() {
        Session s = current;
        if (s != null) return s.status();
        synchronized (this) {
            // Start/replacement reserves this monitor before allocating a reader or VD. Keep the
            // resource snapshot coherent with that reservation instead of decorating old state.
            try { return VirtualCastTerminalStatus.noSessionManagerStatus(new JSONObject(last),
                    new JSONObject(input.status()), current == null, replacement == null,
                    controlledStartInProgress == null).toString(); }
            catch (Exception unknown) { return "{}"; }
        }
    }
    boolean notification(String id, long token, int left, int top, int right, int bottom) {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) return false;
        return input.notification(s.inputId, token, left, top, right, bottom, tapped -> {
            if (s.closed || s.gestures == null) return;
            try { s.gestures.onNotificationDismiss(s.id, tapped); }
            catch (RemoteException | RuntimeException e) { android.util.Log.w("RearVirtualCast", "Notification dismiss unavailable", e); }
        });
    }
    boolean updateSurface(String id, Surface surface) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) { if (surface != null) surface.release(); return false; }
        return s.updateSurface(surface);
    }
    String recoverHost(String id, int hostTask, int rearDisplayId, int action,
                       ISceneTaskActionGate actionGate) throws Exception {
        Session s = current;
        if (s == null || id == null || !s.id.equals(id)) return "stale_session";
        if (action != RECOVERY_WAKE_REAR && action != RECOVERY_PRESENT_HOST) return "invalid_action";
        return s.recoverHost(hostTask, rearDisplayId, action, actionGate);
    }
    void dismissMenu(String id) throws Exception {
        Session s = current;
        if (s != null && !s.closed && s.id.equals(id)) input.dismissMenu(s.inputId);
    }
    String rotate(String id, int direction) throws Exception {
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Rotation direction must be -1 or 1");
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) throw new IllegalStateException("Cast session ended");
        return s.rotate(direction);
    }
    String beginViewport(String id, boolean full) throws Exception {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) throw new IllegalStateException("Cast session ended");
        return s.beginViewport(full);
    }
    String finishViewport(String id, Surface surface) throws Exception {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) {
            if (surface != null) surface.release();
            throw new IllegalStateException("Cast session ended");
        }
        return s.finishViewport(surface);
    }
    boolean renew(String id) {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) return false;
        synchronized (s) { if (s.closed || !s.id.equals(id)) return false; s.touchLease(); return true; }
    }
    String setOutputAvailable(String id, boolean available, Surface replacement,
                              ISceneTaskActionGate actionGate) throws Exception {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) {
            if (replacement != null) replacement.release();
            return "stale_session";
        }
        return s.setOutputAvailable(available, replacement, actionGate);
    }
    private static Throwable root(Throwable e) { while (e.getCause() != null) e = e.getCause(); return e; }

    private final class Session {
        final String id = UUID.randomUUID().toString(), reader;
        final long generation = generationSequence.incrementAndGet();
        volatile int task;
        volatile String baseComponent;
        String expectedBaseComponent;
        final int host, density, requestedDensity;
        final String packageName, component;
        final String coordinatorOperationId;
        final long coordinatorOwnerGeneration;
        final String coordinatorOwnerIdentity;
        final boolean densityCompatibility;
        final boolean disposableWallpaperCast;
        final NotificationTarget notificationTarget;
        ISceneTaskActionGate wallpaperLaunchGate;
        // A separate-task launch must not dispose a pre-existing user task, even if an OEM reuses it.
        Map<Integer, Integer> tasksBeforeDisposableLaunch = Collections.emptyMap();
        final String physicalIdentity = RearDisplay.identity();
        final int physicalRearId = RearDisplay.requireId();
        volatile Surface surface;
        volatile boolean outputAvailable = true;
        volatile long viewportDeadline;
        final IBinder owner;
        final IRearGestureListener gestures;
        final RearCastViewport safeViewport;
        volatile RearCastViewport viewport;
        volatile RearCastViewport pendingViewport;
        volatile long viewportRevision = 1L;
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final IBinder.DeathRecipient death = () -> {
            Replacement tx = replacement;
            if (tx != null && tx.next == this) {
                try { abortReplacement(tx.token); }
                catch (Exception failure) { CompatibilityDiagnostics.record("virtual_candidate_cleanup_failed", failure.toString()); }
            } else close("owner_died", current == this);
        };
        volatile boolean closed;
        volatile boolean resourcesStopped;
        volatile boolean virtualDisplayReleased;
        // Keep the expected tasks through a timeout or a failed release reply.
        private VirtualCastNativeReturn nativeReturn;
        // Every state transition which can invalidate an in-flight health probe advances this
        // generation while holding the Session monitor. The 500 ms probe may do slow checks
        // outside the lock, but it may only close the exact state it inspected.
        final VirtualCastHealthGeneration healthGeneration = new VirtualCastHealthGeneration();
        final RearTouchActivityReporter touchActivityReporter;
        final AtomicBoolean touchActivityUnavailableLogged = new AtomicBoolean();
        final AtomicBoolean firstTouchActivityLogged = new AtomicBoolean();
        final AtomicBoolean touchActivityReportPending = new AtomicBoolean();
        final AtomicBoolean controlledStartGateRejectLogged = new AtomicBoolean();
        final java.util.concurrent.ExecutorService touchActivityExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "rear-touch-activity"); thread.setDaemon(true); return thread;
        });
        volatile long lastTouchActivityQueuedUptime = Long.MIN_VALUE;
        volatile long lease = SystemClock.uptimeMillis();
        volatile String phase = "starting", inputId = "";
        volatile String readerLifecycle = "NOT_REQUESTED", readerId = "";
        final PhysicalReaderReceiptGate readerReceiptGate = new PhysicalReaderReceiptGate();
        volatile long readerGeneration;
        volatile IPhysicalCastReaderLifecycle physicalReaderLifecycle;
        volatile ISceneTaskActionGate controlledStartActionGate;
        volatile boolean beginning, closeRequested;
        String sizePolicy = "disabled";
        RearVirtualIme keyboard;
        private boolean focusRequestLogged;
        private boolean focusErrorLogged;
        RearAppFreezeGuard freezeGuard;
        boolean ownsFreezeGuard = true;
        boolean deferLauncherRestore;
        boolean launcherEntry;
        int display = -1;
        volatile int contentRotation;
        RearTrustedVirtualDisplay outputDisplay;
        String notificationRoute = "";
        Session(Surface surface, String reader, int task, int host, int density, String component, IBinder owner,
                IRearGestureListener gestures, RearCastViewport safe, int renderDensity, boolean compatibility,
                int rotation, boolean full, String coordinatorOperationId, long coordinatorOwnerGeneration,
                String coordinatorOwnerIdentity, boolean disposableWallpaperCast) {
            this(surface, reader, task, host, density, component, owner, gestures, safe, renderDensity, compatibility,
                    rotation, full, coordinatorOperationId, coordinatorOwnerGeneration, coordinatorOwnerIdentity,
                    disposableWallpaperCast, null);
        }

        Session(Surface surface, String reader, int task, int host, int density, String component, IBinder owner,
                IRearGestureListener gestures, RearCastViewport safe, int renderDensity, boolean compatibility,
                int rotation, boolean full, String coordinatorOperationId, long coordinatorOwnerGeneration,
                String coordinatorOwnerIdentity, boolean disposableWallpaperCast, NotificationTarget notificationTarget) {
            this.notificationTarget = notificationTarget;
            this.disposableWallpaperCast = disposableWallpaperCast;
            this.surface = surface; this.reader = reader; this.task = task; this.host = host; this.owner = owner;
            this.gestures = gestures;
            this.viewport = RearCastCompatibility.viewport(full ? safe.full() : safe, density, renderDensity); this.safeViewport = safe;
            this.contentRotation = rotation;
            this.requestedDensity = density; this.density = renderDensity; this.component = component;
            this.densityCompatibility = compatibility;
            this.coordinatorOperationId = coordinatorOperationId;
            this.coordinatorOwnerGeneration = coordinatorOwnerGeneration;
            this.coordinatorOwnerIdentity = coordinatorOwnerIdentity;
            this.packageName = notificationTarget != null ? notificationTarget.packageName
                    : component != null ? component.substring(0, component.indexOf('/')) : service.getPackageNameFromTaskId(task);
            RearDisplayUserActivity.Sink activitySink = null;
            try { activitySink = RearDisplayUserActivity.shell(); }
            catch (Exception unavailable) {
                android.util.Log.i("RearVirtualCast", "touch_activity_api=unavailable reason=" + unavailable.getClass().getSimpleName());
            }
            touchActivityReporter = new RearTouchActivityReporter(activitySink, (displayId, hostTaskId, hostPackage) -> {
                if (!physicalIdentity.equals(RearDisplay.identity()) || !RearDisplay.isRear(displayId))
                    return RearTouchPresentationGate.Decision.SNAPSHOT_UNAVAILABLE;
                RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
                return RearTouchPresentationGate.inspect(snapshot, displayId, hostTaskId, hostPackage,
                        ignored -> physicalRearStillOn());
            });
        }
        void begin(boolean startInput) throws Exception {
            beginning = true;
            RearInputProxy.WallpaperTouch wallpaperTouch = null;
            try {
            ensureControlledStartCurrent("begin");
            if (coordinatorOwnerGeneration > 0L) {
                String sourceBase = task > 0 ? service.getTaskBaseComponent(task, 0) : null;
                if (!ControlledCastTaskIdentity.matchesExact(task, expectedBaseComponent, task,
                        sourceBase, service.isTaskOnDisplay(task, 0))
                        || physicalRearId != RearDisplay.requireId()
                        || !physicalIdentity.equals(RearDisplay.identity()))
                    throw new IllegalStateException("controlled_target_changed_before_move");
            }
            owner.linkToDeath(death, 0);
            if (disposableWallpaperCast) {
                wallpaperTouch = input.openWallpaperTouch(reader, host, this::requireWallpaperLaunchCurrent);
                wallpaperTouch.awaitNeutral(); // No temporary display, app, or grab while a contact is down.
                wallpaperTouch.requireCurrent();
            }
            String displayName = RearVirtualIme.DISPLAY_NAME_PREFIX + id;
            createOutputDisplay(displayName);
            // Apply the resolved orientation before moving the task or enabling hardware input.
            ensureControlledStartCurrent("before_rotation");
            applyDisplayRotation();
            ensureControlledStartCurrent("after_rotation");
            // Acquire before the target enters the virtual display. The independent process
            // restores its mirror entry even if this Shizuku service is killed abruptly.
            ensureControlledStartCurrent("before_freeze_guard");
            if (freezeGuard == null && !lockedNotification()) freezeGuard = RearAppFreezeGuard.start(packageName);
            ensureControlledStartCurrent("after_freeze_guard");
            if (coordinatorOwnerGeneration > 0L) {
                String sourceBase = service.getTaskBaseComponent(task, 0);
                ensureControlledStartCurrent("before_target_move");
                if (physicalRearId != RearDisplay.requireId() || !physicalIdentity.equals(RearDisplay.identity())
                        || !ControlledCastTaskIdentity.moveIfExact(task, expectedBaseComponent, task, sourceBase,
                            service.isTaskOnDisplay(task, 0), () -> service.moveTaskToDisplay(task, display)))
                    throw new IllegalStateException("controlled_target_changed_immediately_before_move");
                ensureControlledStartCurrent("after_target_move");
            } else if (task >= 0 && !service.moveTaskToDisplay(task, display)) {
                throw new IOException("Task move failed");
            }
            if (coordinatorOwnerGeneration > 0L && (task <= 0 || !ControlledCastTaskIdentity.matchesExact(task,
                    expectedBaseComponent, task, service.getTaskBaseComponent(task, display),
                    service.isTaskOnDisplay(task, display))))
                throw new IllegalStateException("controlled_target_changed_after_move");
            if (lockedNotification()) {
                launchLockedNotificationTarget(displayName);
            } else if (component != null || notificationTarget != null) {
                ensureControlledStartCurrent("before_target_launch");
                if (notificationTarget != null) {
                    if (!physicalIdentity.equals(RearDisplay.identity())
                            || !(BuildConfig.APPLICATION_ID + ":" + host).equals(service.getForegroundAppOnDisplay(physicalRearId))
                            || !notificationTarget.action.launch(display))
                        throw new IOException("rear_notification_target_rejected");
                } else if (disposableWallpaperCast) launchWallpaperTarget();
                else if (!service.executeShellCommand("am start --display " + display + (launcherEntry ? " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" : "") + " -n '" + component + "'"))
                    throw new IOException("Activity launch failed");
                long end = SystemClock.uptimeMillis() + 4000;
                boolean found = false;
                do {
                    String foreground = service.getForegroundAppOnDisplay(display);
                    if (foreground != null && foreground.startsWith(packageName + ":")) {
                        task = Integer.parseInt(foreground.substring(foreground.lastIndexOf(':') + 1));
                        found = service.isTaskOnDisplay(task, display);
                        if (found && disposableWallpaperCast && tasksBeforeDisposableLaunch.containsKey(task))
                            throw new IOException("parked_wallpaper_not_a_new_task");
                        if (found) break;
                    }
                    Thread.sleep(100);
                } while (SystemClock.uptimeMillis() < end);
                if (!found) throw new IOException("Target activity did not enter the virtual display");
                ensureControlledStartCurrent("after_target_launch");
            }
            if (coordinatorOwnerGeneration > 0L && (task <= 0 || !ControlledCastTaskIdentity.matchesExact(task,
                    expectedBaseComponent, task, service.getTaskBaseComponent(task, display),
                    service.isTaskOnDisplay(task, display))))
                throw new IllegalStateException("controlled_target_changed_after_launch");
            // This is an additional replacement identity signal, not a new precondition for
            // legacy casting. A controlled replacement fails closed if this read is unavailable.
            baseComponent = service.getTaskBaseComponent(task, display);
            if (startInput && keyboard != null && coordinatorOwnerGeneration == 0L
                    && component == null && task > 0 && baseComponent != null) {
                try {
                    boolean restored = RearImeStartupFocus.prepare(display, displayName, task,
                            () -> current == this && !closed && !closeRequested && owner.isBinderAlive()
                                    && surface != null && surface.isValid());
                    CompatibilityDiagnostics.record("virtual_ime_focus_prepare",
                            "display=" + display + " state=" + (restored ? "restored" : "skipped"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                } catch (Exception unavailable) {
                    CompatibilityDiagnostics.record("virtual_ime_focus_prepare", "display=" + display
                            + " state=unavailable reason=" + root(unavailable).getClass().getSimpleName());
                }
            }
            if (startInput) {
                ensureControlledStartCurrent("before_reader_start");
                if (physicalReaderLifecycle != null && !recordReaderLifecycle("REQUESTED", "", 0L))
                    throw new IOException("physical_reader_request_receipt_rejected");
                String inputStatus;
                if (disposableWallpaperCast) {
                    inputStatus = input.startWallpaper(wallpaperTouch, task, owner, display, inputFeedback(),
                            viewport, inputMenu(), contentRotation, this::reportAcceptedInteraction);
                } else inputStatus = coordinatorOwnerGeneration > 0L
                        ? input.startControlled(reader, task, owner, display, inputFeedback(), viewport,
                            inputMenu(), contentRotation, inputInteraction(),
                            physicalReaderLifecycle == null ? null : (state, readerId, readerGeneration, readerTask, readerDisplay) ->
                                    recordReaderLifecycle(state, readerId, readerGeneration))
                        : input.start(reader, task, owner, display, inputFeedback(), viewport,
                            inputMenu(), contentRotation, inputInteraction());
                JSONObject started = new JSONObject(inputStatus);
                inputId = started.getString("id");
                readerId = inputId;
                readerGeneration = started.optLong("generation", 0L);
                if (physicalReaderLifecycle != null) readerLifecycle = "READY";
                ensureControlledStartCurrent("after_reader_start");
                activate();
            } else {
                if (physicalReaderLifecycle != null && !recordReaderLifecycle("NO_READER", "", 0L))
                    throw new IOException("physical_no_reader_receipt_rejected");
                phase = "candidate_output";
                timer.schedule(() -> expireCandidate(this), 15, TimeUnit.SECONDS);
            }
            android.util.Log.i("RearVirtualCast", status());
            } finally {
                if (wallpaperTouch != null) wallpaperTouch.close();
                beginning = false;
                if (closeRequested && !closed) close("controlled_start_cancelled", false);
            }
        }

        private void requireWallpaperLaunchCurrent() throws Exception {
            if (closed || closeRequested || current != this || !owner.isBinderAlive()
                    || !physicalIdentity.equals(RearDisplay.identity()))
                throw new IllegalStateException("parked_wallpaper_backend_owner_changed");
            // A virtual app can become globally focused. Only the actual rear presenter may take
            // the physical reader, both while waiting and when the virtual app is ready.
            RearTouchPresentationGate.Decision visible = RearTouchPresentationGate.evaluate(
                    RearDisplayCastOccupancyReader.readTypedOnly(physicalRearId), physicalRearId, host,
                    BuildConfig.APPLICATION_ID);
            if (visible != RearTouchPresentationGate.Decision.ALLOW)
                throw new IllegalStateException("parked_wallpaper_rear_" + visible.name().toLowerCase(java.util.Locale.ROOT));
            if (!wallpaperLaunchGate.isCurrent())
                throw new IllegalStateException("parked_wallpaper_app_request_changed");
        }

        private boolean lockedNotification() {
            return notificationTarget != null && notificationTarget.locked;
        }

        private boolean usesDisposableOutput() {
            return disposableWallpaperCast || lockedNotification();
        }

        private void createOutputDisplay(String displayName) throws Exception {
            int flags = CAST_DISPLAY_FLAGS;
            if (usesDisposableOutput())
                flags |= RearTrustedVirtualDisplay.DESTROY_CONTENT_ON_REMOVAL;
            // A notification action must keep Android's lock-screen policy. The separate,
            // opted-in home route starts only a plain Launcher entry, like a wallpaper shortcut.
            if (notificationTarget != null && !notificationTarget.homeOnly)
                flags &= ~VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
            Object builder = RearTrustedVirtualDisplay.builder(displayName, viewport.bufferWidth,
                    viewport.bufferHeight, density, flags, surface);
            RearCastResizePolicy.Result resizePolicy = RearCastResizePolicy.build(builder, densityCompatibility);
            sizePolicy = resizePolicy.mode;
            android.util.Log.i("RearVirtualCast", "size_policy=" + sizePolicy + " requested=" + densityCompatibility);
            ensureControlledStartCurrent("before_virtual_display_create");
            outputDisplay = new RearTrustedVirtualDisplay();
            display = outputDisplay.create(resizePolicy.config);
            if (RearDisplay.isRear(display)) throw new IOException("Invalid virtual display: " + display);
            ensureControlledStartCurrent("after_virtual_display_create");
            try {
                RearVirtualIme localIme = RearVirtualIme.enable(display, physicalRearId);
                // Temporary lock-screen launches use Android's normal window focus. They do
                // not take part in the regular cast's focus handoff or task return to main.
                if (!usesDisposableOutput()) keyboard = localIme;
                CompatibilityDiagnostics.record("virtual_ime_policy", "display=" + display + " state=local");
            } catch (Exception e) {
                CompatibilityDiagnostics.record("virtual_ime_policy",
                        "display=" + display + " state=unavailable reason=" + root(e));
                // The cast remains usable by touch on ROMs without local IME support.
            }
        }

        private void applyDisplayRotation() throws Exception {
            command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
            command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(contentRotation));
            awaitRotation(contentRotation);
        }

        private void requireLockedNotificationRear() throws Exception {
            ensureControlledStartCurrent("locked_notification_target");
            if (physicalRearId != RearDisplay.requireId() || !physicalIdentity.equals(RearDisplay.identity()))
                throw new IOException("rear_notification_physical_display_changed");
        }

        private boolean isNotificationHostForeground() throws Exception {
            return (BuildConfig.APPLICATION_ID + ":" + host).equals(service.getForegroundAppOnDisplay(physicalRearId));
        }

        private void requireCurrentLockedNotification() throws Exception {
            requireLockedNotificationRear();
            if (!isNotificationHostForeground() || !notificationTarget.action.isCurrent())
                throw new IOException("rear_notification_no_longer_current");
        }

        private boolean snapshotBeforeNotificationLaunch() throws Exception {
            Map<Integer, Integer> placement = new HashMap<>();
            boolean sourceHasTask = false;
            for (SceneTaskIdentity.Record row : RearSystemBridge.runningTaskSnapshot()) {
                placement.put(row.taskId, row.displayId);
                if (belongsToNotificationSource(row.baseComponent) || belongsToNotificationSource(row.topComponent))
                    sourceHasTask = true;
            }
            tasksBeforeDisposableLaunch = placement;
            return sourceHasTask;
        }

        private boolean belongsToNotificationSource(String component) {
            return component != null && component.startsWith(packageName + "/");
        }

        private void launchLockedNotificationTarget(String displayName) throws Exception {
            boolean sourceHasTask = snapshotBeforeNotificationLaunch();
            requireCurrentLockedNotification();
            // The immutable notification can target the existing primary task. The explicit
            // launcher, in contrast, is restricted to entries that can start an independent task.
            if (!notificationTarget.homeOnly && !sourceHasTask && notificationTarget.action.launch(display)) {
                if (awaitLockedNotificationTarget()) {
                    notificationRoute = "original_action";
                    return;
                }
                requireCurrentLockedNotification();
                // Do not reuse a display that may still have a hidden or late-arriving detail.
                restoreUnexpectedReusedTasks();
                outputDisplay.close();
                display = -1;
                createOutputDisplay(displayName + "-source");
                applyDisplayRotation();
                snapshotBeforeNotificationLaunch();
            } else if (!notificationTarget.homeOnly && sourceHasTask) {
                android.util.Log.i("RearVirtualCast", "rear_notification_detail_skipped_existing_task");
            }
            requireCurrentLockedNotification();
            if (!notificationTarget.action.launchSource(display))
                throw new IOException("rear_notification_source_launch_rejected");
            if (!awaitLockedNotificationTarget())
                throw new IOException("rear_notification_source_unavailable");
            notificationRoute = notificationTarget.homeOnly ? "approved_source_home" : "source_launcher";
        }

        private boolean awaitLockedNotificationTarget() throws Exception {
            long deadline = SystemClock.uptimeMillis() + 2200L;
            do {
                requireLockedNotificationRear();
                // A target may consume its own notification immediately. Verification must
                // not require that notification to survive, and never grants another launch.
                if (!notificationTarget.action.mayVerifyLaunch(display))
                    throw new IOException("rear_notification_launch_owner_lost");
                RearDisplayCastOccupancy.Snapshot state = RearDisplayCastOccupancyReader.readTypedOnly(display);
                if (state.failure != null) throw new IOException("rear_notification_task_inventory_unavailable");
                int visibleTask = visibleLockedNotificationTask(state, display, packageName);
                if (visibleTask > 0) {
                    if (tasksBeforeDisposableLaunch.containsKey(visibleTask))
                        throw new IOException("rear_notification_target_reused_task");
                    if (isNotificationHostForeground() && notificationTarget.action.mayVerifyLaunch(display)) {
                        task = visibleTask;
                        return true;
                    }
                }
                Thread.sleep(80L);
            } while (SystemClock.uptimeMillis() < deadline);
            return false;
        }

        private void launchWallpaperTarget() throws Exception {
            android.content.Context context = RearSystemBridge.systemContext();
            ComponentName target = ComponentName.unflattenFromString(component);
            if (!AiRearAppCatalog.isCastTarget(context, target, BuildConfig.APPLICATION_ID)
                    || !physicalIdentity.equals(RearDisplay.identity())
                    || !(BuildConfig.APPLICATION_ID + ":" + host).equals(service.getForegroundAppOnDisplay(physicalRearId)))
                throw new IllegalStateException("parked_wallpaper_launch_no_longer_eligible");
            tasksBeforeDisposableLaunch = runningTaskDisplays();
            // KeyguardManager needs an attached app process for WindowManager's shared memory.
            // Recheck the actual lock state and accepted gesture in the prepared app before launch.
            requireWallpaperLaunchCurrent();
            // Isolate this temporary task from the user's open app and from their Recents.
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            // No shell text from the wallpaper. A real launcher component is passed as an argv element.
            DisplayRotationController.Result result = DisplayRotationController.runCommand(
                    "am", "start", "--display", Integer.toString(display), "--user", "0",
                    "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
                    "-f", "0x" + Integer.toHexString(flags), "-n", target.flattenToString());
            if (!RearSystemBridge.commandSucceeded(result))
                throw new IOException("parked_wallpaper_launch_refused");
        }

        void ensureControlledStartCurrent(String stage) throws Exception {
            if (closed || closeRequested) {
                logControlledStartGateReject(stage, "session_cancelled");
                throw new IllegalStateException("controlled_start_cancelled:" + stage);
            }
            ISceneTaskActionGate gate = controlledStartActionGate;
            if (gate == null) return;
            final boolean current;
            try { current = gate.isCurrent(); }
            catch (Exception unavailable) {
                logControlledStartGateReject(stage, "action_gate_unavailable");
                throw new IllegalStateException("controlled_start_gate_unavailable:" + stage, unavailable);
            }
            if (!current) {
                logControlledStartGateReject(stage, "action_gate_stale");
                throw new IllegalStateException("controlled_start_gate_stale:" + stage);
            }
            if ("before_target_move".equals(stage)) {
                final boolean targetCurrent;
                try { targetCurrent = gate.isCurrentBeforeTargetMove(); }
                catch (Exception unavailable) {
                    logControlledStartGateReject(stage, "target_gate_unavailable");
                    throw new IllegalStateException("controlled_start_target_gate_unavailable:" + stage, unavailable);
                }
                if (!targetCurrent) {
                    logControlledStartGateReject(stage, "target_gate_stale");
                    throw new IllegalStateException("controlled_start_target_gate_stale:" + stage);
                }
            }
        }
        private void logControlledStartGateReject(String stage, String reason) {
            if (BuildConfig.DEBUG && controlledStartGateRejectLogged.compareAndSet(false, true))
                android.util.Log.w("RearVirtualCast", "stage=controlled_start_gate_rejected phase=" + stage
                        + " reason=" + reason);
        }
        private boolean recordReaderLifecycle(String state, String exactReaderId, long exactGeneration) {
            IPhysicalCastReaderLifecycle listener = physicalReaderLifecycle;
            if (listener == null) return false;
            try {
                boolean saved = readerReceiptGate.record(state, exactReaderId, exactGeneration,
                        (event, reader, gen) -> {
                            try { return listener.onState(coordinatorOperationId, id, generation,
                                    event, reader, gen, task, physicalRearId, physicalIdentity,
                                    coordinatorOwnerGeneration, coordinatorOwnerIdentity); }
                            catch (RemoteException failure) { throw new IllegalStateException(failure); }
                        });
                if (!saved) return false;
                readerLifecycle = readerReceiptGate.state();
                readerId = readerReceiptGate.readerId();
                readerGeneration = readerReceiptGate.readerGeneration();
                return true;
            } catch (RuntimeException failure) {
                android.util.Log.w("RearVirtualCast", "reader lifecycle receipt rejected state=" + state, failure);
                return false;
            }
        }
        private RearTouchRouter.Feedback inputFeedback() {
            return (inward, y, active, ready, menuArmed) -> {
                if (gestures == null) return;
                try { gestures.onBackGesture(id, inward, y, active && !closed, ready && !closed, menuArmed && !closed); }
                // A missing visual consumer must never interrupt the App's input or delay a Back action.
                catch (RemoteException | RuntimeException e) { android.util.Log.w("RearVirtualCast", "Gesture feedback unavailable", e); }
            };
        }
        private RearTouchRouter.Menu inputMenu() {
            return new RearTouchRouter.Menu() {
                public void visible(boolean open) throws Exception {
                    if (gestures == null) throw new IOException("Menu consumer unavailable");
                    gestures.onCastMenu(id, open && !closed);
                    android.util.Log.i("RearVirtualCast", "menu_open=" + open + " id=" + id);
                }
                public void touch(int action, float x, float y, long down, long time) throws Exception {
                    if (gestures == null || closed) throw new IOException("Menu consumer unavailable");
                    gestures.onCastMenuTouch(id, action, x, y, down, time);
                }
            };
        }
        private void renewFreezeProtection() throws Exception {
            // Locked notification casts do not acquire a freeze guard.
            if (freezeGuard != null) freezeGuard.renew();
        }

        private void activate() throws Exception {
            renewFreezeProtection();
            touchLease();
            phase = "running";
            timer.scheduleAtFixedRate(() -> {
                HealthSnapshot checked = healthSnapshot();
                if (checked.closed) return;
                String failure = null;
                try {
                    long now = SystemClock.uptimeMillis();
                    if (now - checked.lease > 4000) {
                        closeIfLeaseSnapshotCurrent(checked, "lease_expired");
                        return;
                    }
                    else if (VirtualCastOutputRecoveryPolicy.expireViewportChange(
                            checked.viewportPending, checked.viewportDeadline, now)) failure = "viewport_reconnect_timeout";
                    else {
                        // Keep the captured Surface local; never dereference mutable Session.surface
                        // outside the Session monitor while updateSurface may be releasing it.
                        boolean invalidOutput = checked.outputAvailable
                                && (checked.surface == null || !checked.surface.isValid());
                        if (!physicalIdentity.equals(RearDisplay.identity()) || !owner.isBinderAlive() || invalidOutput
                                || !service.isTaskOnDisplay(host, RearDisplay.requireId())
                                || !service.isTaskOnDisplay(task, display) || !input.renew(inputId))
                            failure = "host_task_or_input_left";
                        else renewFreezeProtection();
                    }
                } catch (Exception e) { failure = root(e).toString(); }
                if (failure != null) closeIfHealthSnapshotCurrent(checked, failure);
            }, 500, 500, TimeUnit.MILLISECONDS);
        }
        private RearInputProxy.AcceptedInteraction inputInteraction() {
            if (keyboard == null) return this::reportAcceptedInteraction;
            return new RearInputProxy.AcceptedInteraction() {
                @Override public void beforeForwardedDown() { prepareInputFocus(); }
                @Override public void afterRouterFrameAccepted(long eventTimeUptime) { reportAcceptedInteraction(eventTimeUptime); }
            };
        }
        private void prepareInputFocus() {
            // Runs under the input session's lock. Do not take the cast session's lock; close()
            // stops input before moving the task and freeing its display.
            try {
                if (keyboard.beforeDown(() -> current == this && !closed && owner.isBinderAlive()
                        && outputAvailable && pendingViewport == null && surface != null && surface.isValid())
                        && !focusRequestLogged) {
                    focusRequestLogged = true;
                    CompatibilityDiagnostics.record("virtual_ime_focus", "display=" + display + " state=requested");
                }
            } catch (Exception e) {
                if (!focusErrorLogged) {
                    focusErrorLogged = true;
                    CompatibilityDiagnostics.record("virtual_ime_focus", "display=" + display + " state=unavailable reason=" + root(e));
                }
                // The observed failure is an IME-focus capability issue, not a failed touch.
            }
        }
        private void reportAcceptedInteraction(long eventTimeUptime) {
            if (current != this || closed || !owner.isBinderAlive() || host < 0
                    || !outputAvailable || pendingViewport != null || surface == null || !surface.isValid()) return;
            long queuedAt = SystemClock.uptimeMillis();
            long previous = lastTouchActivityQueuedUptime;
            if (previous != Long.MIN_VALUE && queuedAt >= previous
                    && queuedAt - previous < RearTouchActivityReporter.MIN_REPORT_INTERVAL_MS) return;
            if (!touchActivityReportPending.compareAndSet(false, true)) return;
            lastTouchActivityQueuedUptime = queuedAt;
            try {
                touchActivityExecutor.execute(() -> {
                    try { reportAcceptedTouchNow(eventTimeUptime); }
                    finally { touchActivityReportPending.set(false); }
                });
            } catch (java.util.concurrent.RejectedExecutionException closedExecutor) {
                touchActivityReportPending.set(false);
            }
        }
        private void reportAcceptedTouchNow(long eventTimeUptime) {
            RearTouchActivityReporter.Result result = touchActivityReporter.acceptedInteraction(eventTimeUptime,
                    display, physicalRearId, host, BuildConfig.APPLICATION_ID,
                    () -> current == this && !closed && owner.isBinderAlive(),
                    () -> current == this && !closed && owner.isBinderAlive()
                            && host >= 0
                            && outputAvailable && pendingViewport == null && surface != null && surface.isValid());
            if (result == RearTouchActivityReporter.Result.REPORTED
                    && firstTouchActivityLogged.compareAndSet(false, true)) {
                android.util.Log.i("RearVirtualCast", "touch_activity session=" + id + " display=" + physicalRearId
                        + " uptime=" + eventTimeUptime + " result=submitted");
            } else if (result == RearTouchActivityReporter.Result.UNAVAILABLE
                    && touchActivityUnavailableLogged.compareAndSet(false, true)) {
                android.util.Log.w("RearVirtualCast", "touch_activity session=" + id + " display=" + physicalRearId
                        + " uptime=" + eventTimeUptime + " result=unavailable");
            } else if (result == RearTouchActivityReporter.Result.HOST_NOT_PRESENTED) {
                android.util.Log.i("RearVirtualCast", "touch_activity session=" + id + " display=" + physicalRearId
                        + " uptime=" + eventTimeUptime + " result=not_presented gate=" + touchActivityReporter.lastDecision());
            }
        }
        private boolean physicalRearStillOn() {
            try {
                if (!physicalIdentity.equals(RearDisplay.identity()) || !RearDisplay.isRear(physicalRearId)) return false;
                Object info = RearDisplay.info(physicalRearId);
                return info.getClass().getField("state").getInt(info) == android.view.Display.STATE_ON;
            } catch (Exception unavailable) { return false; }
        }
        private final class HealthSnapshot {
            final long generation, leaseGeneration, lease, viewportDeadline;
            final Surface surface;
            final boolean closed, outputAvailable, viewportPending;
            HealthSnapshot(long generation, long leaseGeneration, long lease, long viewportDeadline, Surface surface,
                           boolean closed, boolean outputAvailable, boolean viewportPending) {
                this.generation = generation; this.leaseGeneration = leaseGeneration; this.lease = lease; this.viewportDeadline = viewportDeadline;
                this.surface = surface; this.closed = closed; this.outputAvailable = outputAvailable;
                this.viewportPending = viewportPending;
            }
        }
        private synchronized HealthSnapshot healthSnapshot() {
            return new HealthSnapshot(healthGeneration.capture(), healthGeneration.captureLease(), lease, viewportDeadline, surface, closed,
                    outputAvailable, pendingViewport != null);
        }
        private synchronized void touchLease() {
            lease = SystemClock.uptimeMillis();
            healthGeneration.renewLease();
        }
        private void closeIfHealthSnapshotCurrent(HealthSnapshot checked, String reason) {
            synchronized (this) {
                if (closed) return;
                healthGeneration.runIfCurrent(checked.generation, () -> close(reason));
            }
        }
        private void closeIfLeaseSnapshotCurrent(HealthSnapshot checked, String reason) {
            synchronized (this) {
                if (closed) return;
                healthGeneration.runIfLeaseCurrent(checked.leaseGeneration, () -> close(reason));
            }
        }
        private void command(String... args) throws Exception {
            DisplayRotationController.Result result = DisplayRotationController.runCommand(args);
            if (result.exitCode != 0) throw new IOException("Virtual display rotation: " + result.output);
        }
        private void awaitRotation(int rotation) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 1800;
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            do {
                Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
                if (info != null && info.getClass().getField("rotation").getInt(info) == rotation
                        && info.getClass().getField("logicalWidth").getInt(info) == viewport.contentWidth(rotation)
                        && info.getClass().getField("logicalHeight").getInt(info) == viewport.contentHeight(rotation)) return;
                Thread.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("Virtual display did not apply rotation " + rotation);
        }
        synchronized String rotate(int direction) throws Exception {
            if (closed || pendingViewport != null || !outputAvailable || !surface.isValid() || !owner.isBinderAlive())
                throw new IllegalStateException("Rear output unavailable for rotation");
            int previous = contentRotation, next = (previous + direction + 4) % 4;
            touchLease();
            if (!input.beginRotation(inputId)) throw new IOException("Input session ended");
            try {
                // Only our disposable display is changed. Surface pixels remain in the physical safe rectangle.
                command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
                command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(next));
                awaitRotation(next);
                if (!input.finishRotation(inputId, next, outputAvailable)) throw new IOException("Input session ended");
                contentRotation = next;
                viewportRevision++; healthGeneration.advance();
                android.util.Log.i("RearVirtualCast", "content_rotated id=" + id + " rotation=" + next);
                return status();
            } catch (Exception failure) {
                try {
                    command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(previous));
                    awaitRotation(previous);
                    if (!input.finishRotation(inputId, previous, outputAvailable)) throw new IOException("Input rollback failed");
                } catch (Exception rollback) { close("rotation_rollback_failed: " + root(rollback)); }
                throw failure;
            } finally { touchLease(); }
        }
        synchronized String beginViewport(boolean full) throws Exception {
            if (closed || pendingViewport != null || !outputAvailable || !surface.isValid() || !owner.isBinderAlive())
                throw new IllegalStateException("Rear output unavailable for viewport change");
            if (!safeViewport.key().equals(RearCastGeometry.read().key())) throw new IOException("Rear geometry changed");
            RearCastViewport next = RearCastCompatibility.viewport(full ? safeViewport.full() : safeViewport, requestedDensity, density);
            if (!input.beginRotation(inputId)) throw new IOException("Input session ended");
            pendingViewport = next;
            outputAvailable = false; viewportDeadline = SystemClock.uptimeMillis() + 5000; healthGeneration.advance();
            touchLease();
            android.util.Log.i("RearVirtualCast", "viewport_begin id=" + id + " target=" + next.key());
            return RearCastGeometry.encode(next);
        }
        synchronized String finishViewport(Surface next) throws Exception {
            boolean adopted = false;
            try {
                if (closed || pendingViewport == null || next == null || !next.isValid() || !owner.isBinderAlive())
                    throw new IllegalStateException("Viewport transaction unavailable");
                touchLease();
                // Resize the same display/task; fix its current rotation before applying the new native dimensions.
                command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
                command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(contentRotation));
                outputDisplay.resize(pendingViewport.bufferWidth, pendingViewport.bufferHeight, density);
                outputDisplay.setSurface(next);
                Surface previous = surface; surface = next; adopted = true; previous.release();
                viewport = pendingViewport;
                awaitRotation(contentRotation);
                if (!input.finishConfiguration(inputId, viewport, contentRotation, true)) throw new IOException("Input session ended");
                pendingViewport = null; outputAvailable = true; viewportRevision++; healthGeneration.advance();
                android.util.Log.i("RearVirtualCast", "viewport_changed id=" + id + " full=" + viewport.isFull() + " rotation=" + contentRotation);
                return status();
            } catch (Exception e) {
                // Never leave a stretched image with a different touch mapping, or a reader grabbed after a failed resize.
                close("viewport_failed: " + root(e)); throw e;
            } finally {
                if (!adopted && next != null) next.release();
                touchLease();
            }
        }
        synchronized boolean updateSurface(Surface next) throws Exception {
            VirtualCastOutputRecoveryPolicy.SurfaceUpdate decision = VirtualCastOutputRecoveryPolicy.surfaceUpdate(
                    closed, pendingViewport != null, next != null, next == null || next.isValid());
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.SESSION_CLOSED) {
                if (next != null) next.release(); return false;
            }
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.VIEWPORT_INTERRUPTED) {
                if (next != null) next.release();
                close("viewport_output_interrupted"); return false;
            }
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.LOST_TEMPORARILY) {
                if (outputAvailable || surface != null) {
                    if (outputAvailable)
                        outputDisplay.setSurface(null);
                    Surface previous = surface;
                    surface = null;
                    outputAvailable = false;
                    healthGeneration.advance();
                    if (previous != null) previous.release();
                    input.outputAvailable(inputId, false);
                    android.util.Log.i("RearVirtualCast", "output_reconnecting id=" + id);
                }
                return true;
            }
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.INVALID_REPLACEMENT) {
                next.release();
                throw new IOException("Replacement Surface invalid");
            }
            boolean adopted = false;
            try {
                outputDisplay.setSurface(next);
                Surface previous = surface;
                surface = next; adopted = true;
                outputAvailable = true; healthGeneration.advance();
                if (previous != null) previous.release();
                if (!input.outputAvailable(inputId, true)) throw new IOException("Input session ended during reconnect");
                android.util.Log.i("RearVirtualCast", "output_reconnected id=" + id);
                return true;
            } finally { if (!adopted) next.release(); }
        }
        synchronized String setOutputAvailable(boolean available, Surface replacement,
                                               ISceneTaskActionGate actionGate) throws Exception {
            if (closed || current != this || !owner.isBinderAlive()) {
                if (replacement != null && replacement != surface) replacement.release();
                return "stale_session";
            }
            if (pendingViewport != null || actionGate == null) {
                if (replacement != null && replacement != surface) replacement.release();
                return "cancelled";
            }
            int rearId = RearDisplay.requireId();
            int stateNow = rearDisplayState(rearId);
            if (!powerTransitionCurrent(available, stateNow, actionGate)) {
                if (replacement != null && replacement != surface) replacement.release();
                return "cancelled";
            }
            if (!available) {
                // Even if the VD flag already says paused, reconcile the reader too. A prior
                // ON attach may have been cancelled after its reader resumed but before the
                // output transition committed.
                // Release the rear input grab before the remote display update so the system
                // can receive its wake gesture even if setVirtualDisplaySurface is slow.
                if (!input.outputAvailable(inputId, false, () -> currentGate(actionGate))) return "cancelled";
                if (!outputAvailable) return "already_paused";
                outputAvailable = false; healthGeneration.advance();
                if (!sessionStillOwnsSleepingOutput(rearId)) return "cancelled";
                outputDisplay.setSurface(null);
                android.util.Log.i("RearVirtualCast", "power_output_paused id=" + id + " surface_retained=" + (surface != null));
                return "paused";
            }
            VirtualCastOutputPowerGate.SurfaceChoice choice = VirtualCastOutputPowerGate.chooseRestoreSurface(
                    surface != null && surface.isValid(), replacement != null, replacement != null && replacement.isValid());
            if (choice == VirtualCastOutputPowerGate.SurfaceChoice.INVALID_REPLACEMENT) {
                replacement.release(); return "surface_unavailable";
            }
            Surface candidate = choice == VirtualCastOutputPowerGate.SurfaceChoice.REPLACEMENT ? replacement
                    : choice == VirtualCastOutputPowerGate.SurfaceChoice.RETAINED ? surface : null;
            if (candidate == null) return "surface_unavailable";
            if (outputAvailable && candidate == surface) {
                if (replacement != null && replacement != surface) replacement.release();
                return "already_available";
            }
            if (!powerTransitionCurrent(true, rearDisplayState(rearId), actionGate)) {
                if (replacement != null && replacement != surface) replacement.release();
                return "cancelled";
            }
            outputDisplay.setSurface(candidate);
            Surface previous = surface;
            surface = candidate;
            if (previous != null && previous != candidate) previous.release();
            healthGeneration.advance();
            if (!powerTransitionCurrent(true, rearDisplayState(rearId), actionGate)
                    || !input.outputAvailable(inputId, true, () -> currentGate(actionGate))) {
                boolean cleanupOk = VirtualCastOutputPowerGate.rollbackCancelledRestore(
                        () -> {
                            if (!input.outputAvailable(inputId, false))
                                throw new IOException("Input session unavailable during cancelled restore");
                        },
                        () -> outputDisplay.setSurface(null));
                outputAvailable = false; healthGeneration.advance();
                if (!cleanupOk) {
                    close("cancelled_restore_cleanup_failed");
                    return "cleanup_failed";
                }
                return "cancelled";
            }
            outputAvailable = true; healthGeneration.advance();
            android.util.Log.i("RearVirtualCast", "power_output_resumed id=" + id + " same_session=true replacement=" + (candidate != previous));
            return "resumed";
        }
        private boolean powerTransitionCurrent(boolean available, int rearState,
                                               ISceneTaskActionGate actionGate) throws Exception {
            int rearId = RearDisplay.requireId();
            boolean taskStillOwned = service.isTaskOnDisplay(host, rearId);
            return VirtualCastOutputPowerGate.mayCommit(!closed && current == this, owner.isBinderAlive(),
                    taskStillOwned, rearState, available, () -> currentGate(actionGate));
        }
        private boolean sessionStillOwnsSleepingOutput(int rearId) throws Exception {
            return !closed && current == this && owner.isBinderAlive()
                    && service.isTaskOnDisplay(host, rearId)
                    && VirtualCastOutputPowerGate.isSleeping(rearDisplayState(rearId));
        }
        private boolean currentGate(ISceneTaskActionGate actionGate) {
            try { return actionGate != null && actionGate.isCurrent(); }
            catch (RemoteException denied) { return false; }
        }
        private String recordPresentationResult(VirtualCastRecoveryForeground.Decision decision,
                                               String result, int finalPower) {
            if (decision != null && decision.diagnostic != null) {
                android.util.Log.i("RearVirtualCast", "recovery_present "
                        + decision.diagnostic.format(decision.refusal, result, finalPower));
            }
            return result;
        }
        String recoverHost(int requestedHost, int rearDisplayId, int action,
                           ISceneTaskActionGate actionGate) throws Exception {
            if (action != RECOVERY_WAKE_REAR && action != RECOVERY_PRESENT_HOST) return "invalid_action";
            final long generation;
            synchronized (this) {
                if (closed || current != this || requestedHost != host || actionGate == null
                        || rearDisplayId != RearDisplay.requireId() || !owner.isBinderAlive()) return "stale_owner";
                generation = healthGeneration.capture();
            }
            if (!service.isTaskOnDisplay(host, rearDisplayId)) return "host_not_on_rear";
            VirtualCastRecoveryForeground.Decision presentation = null;
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancy.Snapshot.parse(
                    service.getDisplayCastOccupancy(rearDisplayId), rearDisplayId);
            if (action == RECOVERY_PRESENT_HOST) {
                presentation = VirtualCastRecoveryForeground.resolve(snapshot, rearDisplayId, host,
                        BuildConfig.APPLICATION_ID);
                if (presentation.disposition == VirtualCastRecoveryForeground.Disposition.HOST_ALREADY_FRONT)
                    return recordPresentationResult(presentation, "already_front", snapshot.displayState);
                if (presentation.disposition == VirtualCastRecoveryForeground.Disposition.OTHER_TASK)
                    return recordPresentationResult(presentation, "preserved_other_task", snapshot.displayState);
                if (presentation.disposition != VirtualCastRecoveryForeground.Disposition.FOCUS_HOST)
                    return recordPresentationResult(presentation, "foreground_unverified", snapshot.displayState);
            } else {
                // A sleeping rear has no meaningful visible top, so require the exact owned host
                // plus known rear launcher(s) and reject every unknown/third-party task.
                presentation = VirtualCastRecoveryForeground.resolveWake(snapshot, rearDisplayId, host,
                        BuildConfig.APPLICATION_ID);
                if (presentation.disposition == VirtualCastRecoveryForeground.Disposition.OTHER_TASK)
                    return "preserved_other_task";
                if (presentation.disposition != VirtualCastRecoveryForeground.Disposition.WAKE_ALLOWED)
                    return "foreground_unverified";
            }

            int powerState = rearDisplayState(rearDisplayId);
            if (action == RECOVERY_PRESENT_HOST) {
                if (powerState != android.view.Display.STATE_ON)
                    return recordPresentationResult(presentation, "rear_not_on", powerState);
            } else {
                if (!isSleepingDisplayState(powerState)) return powerState == android.view.Display.STATE_ON
                        ? "already_on" : "rear_state_unknown";
            }

            // The expensive task/foreground queries are complete. Keep the final state check,
            // caller gate and side effect adjacent; close/updateSurface cannot invalidate the
            // Session between this check and its own shell action.
            synchronized (this) {
                if (closed || current != this || generation != healthGeneration.capture()
                        || !owner.isBinderAlive() || requestedHost != host || rearDisplayId != RearDisplay.requireId())
                    return action == RECOVERY_PRESENT_HOST
                            ? recordPresentationResult(presentation, "stale_session", powerState) : "stale_session";
                if (!actionGate.isCurrent())
                    return action == RECOVERY_PRESENT_HOST
                            ? recordPresentationResult(presentation, "cancelled", powerState) : "cancelled";
                int now = rearDisplayState(rearDisplayId);
                if (action == RECOVERY_WAKE_REAR) {
                    if (!isSleepingDisplayState(now)) return now == android.view.Display.STATE_ON ? "already_on" : "rear_state_unknown";
                    return service.executeShellCommand("input -d " + rearDisplayId + " keyevent KEYCODE_WAKEUP")
                            ? "wake_requested" : "wake_rejected";
                }
                if (now != android.view.Display.STATE_ON)
                    return recordPresentationResult(presentation, "rear_not_on", now);
                String result = VirtualCastRecoveryForeground.perform(presentation, host, rearDisplayId,
                        taskId -> service.executeShellCommand("am task focus " + taskId));
                return recordPresentationResult(presentation, result, now);
            }
        }
        private int rearDisplayState(int displayId) throws Exception {
            JSONObject state = new JSONObject(RearDisplay.state());
            org.json.JSONArray displays = state.optJSONArray("displays");
            if (displays == null) return android.view.Display.STATE_UNKNOWN;
            for (int i = 0; i < displays.length(); i++) {
                JSONObject item = displays.optJSONObject(i);
                if (item != null && item.optInt("id", -1) == displayId)
                    return item.optInt("state", android.view.Display.STATE_UNKNOWN);
            }
            return android.view.Display.STATE_UNKNOWN;
        }
        private boolean isSleepingDisplayState(int state) {
            return state == android.view.Display.STATE_OFF || state == android.view.Display.STATE_DOZE
                    || state == android.view.Display.STATE_DOZE_SUSPEND || state == android.view.Display.STATE_ON_SUSPEND;
        }
        private void returnTasksToMain() throws Exception {
            List<Integer> tasks = new ArrayList<>();
            java.lang.Process p = new ProcessBuilder("am", "stack", "list").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                boolean ours = false; String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("RootTask ")) ours = line.matches(".*\\bdisplayId=" + display + "\\b.*");
                    if (ours && line.trim().startsWith("taskId=")) {
                        String value = line.trim().substring(7).split(":", 2)[0];
                        tasks.add(Integer.parseInt(value));
                    }
                }
            }
            if (!p.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly();
            if (p.exitValue() != 0) throw new IOException("virtual_task_snapshot_incomplete");
            for (int t : tasks) {
                if (!service.moveTaskToDisplay(t, 0) || !service.isTaskOnDisplay(t, 0))
                    throw new IOException("virtual_task_restore_unverified:" + t);
            }
        }
        private void restoreUnexpectedReusedTasks() throws Exception {
            for (Map.Entry<Integer, Integer> entry : runningTaskDisplays().entrySet()) {
                if (entry.getValue() != display) continue;
                Integer original = tasksBeforeDisposableLaunch.get(entry.getKey());
                if (original != null && (!service.moveTaskToDisplay(entry.getKey(), original)
                        || !service.isTaskOnDisplay(entry.getKey(), original)))
                    throw new IOException("disposable_display_preexisting_task_not_restored");
            }
        }
        void close(String reason) { close(reason, true); }
        void close(String reason, boolean restoreRearLauncher) { close(reason, restoreRearLauncher, null); }
        synchronized void close(String reason, boolean restoreRearLauncher, IRearWallpaperOutputReleased receipt) {
            if (closed && resourcesStopped) return;
            if (beginning) { closeRequested = true; return; }
            boolean firstClose = !closed;
            if (firstClose) {
                closed = true; healthGeneration.advance(); phase = reason; timer.shutdown();
                previousSessionClosed(this);
                touchActivityExecutor.shutdownNow();
            }
            String exactReader = inputId.isEmpty() ? readerId : inputId;
            boolean inputStopped = input.stopAndAwait(exactReader);
            if (inputStopped && physicalReaderLifecycle != null && !exactReader.isEmpty()) {
                if (recordReaderLifecycle("RELEASED", exactReader, readerGeneration)) readerLifecycle = "RELEASED";
                else inputStopped = false;
            }
            if (inputStopped && physicalReaderLifecycle != null && exactReader.isEmpty()
                    && !"NO_READER".equals(readerLifecycle)) {
                if ("NOT_REQUESTED".equals(readerLifecycle)) {
                    if (!recordReaderLifecycle("NO_READER", "", 0L)) inputStopped = false;
                } else inputStopped = false;
            }
            boolean displayCleanupComplete = true;
            // Also restore tasks newly opened by the app on our display. Never inspect/move other displays.
            if (display > 0 && !RearDisplay.isRear(display)) {
                displayCleanupComplete = false;
                try {
                    if (nativeReturn == null && !virtualDisplayReleased && inputStopped && keyboard != null
                            && current == this && coordinatorOwnerGeneration == 0L
                            && !usesDisposableOutput() && "stopped".equals(phase)) {
                        try {
                            nativeReturn = VirtualCastNativeReturn.prepare(display, task);
                        } catch (Exception unavailable) {
                            CompatibilityDiagnostics.record("virtual_ime_system_return",
                                    "display=" + display + " state=inventory_unavailable reason="
                                            + root(unavailable).getClass().getSimpleName());
                        }
                    }
                    if (!usesDisposableOutput() && !virtualDisplayReleased && nativeReturn == null)
                        returnTasksToMain();
                    if (!virtualDisplayReleased) {
                        if (usesDisposableOutput()) restoreUnexpectedReusedTasks();
                        // A disposable display may destroy only the tasks created for it.
                        outputDisplay.close();
                        virtualDisplayReleased = true;
                        if (disposableWallpaperCast && inputStopped && receipt != null) {
                            try { receipt.onReleased(id, host); }
                            catch (android.os.RemoteException lost) {
                                android.util.Log.w("RearVirtualCast", "Wallpaper release callback unavailable", lost);
                            }
                        }
                    }
                    if (nativeReturn != null) {
                        int count = nativeReturn.awaitOnMain();
                        CompatibilityDiagnostics.record("virtual_ime_system_return",
                                "display=" + display + " state=restored tasks=" + count);
                    }
                    if (disposableWallpaperCast && task > 0 && !tasksBeforeDisposableLaunch.containsKey(task)) {
                        long deadline = SystemClock.uptimeMillis() + 1200;
                        while (runningTaskDisplays().containsKey(task)) {
                            if (SystemClock.uptimeMillis() >= deadline)
                                throw new IOException("parked_wallpaper_task_not_released");
                            Thread.sleep(60);
                        }
                    }
                    displayCleanupComplete = true;
                } catch (Exception e) { android.util.Log.e("RearVirtualCast", "Task restoration", e); }
            }
            if (firstClose) owner.unlinkToDeath(death, 0);
            Surface oldSurface = surface;
            if (oldSurface != null) { oldSurface.release(); surface = null; }
            if (firstClose && ownsFreezeGuard && freezeGuard != null) freezeGuard.close();
            resourcesStopped = inputStopped && displayCleanupComplete && surface == null;
            if (resourcesStopped) outputAvailable = false;
            // Restore the physical rear only for this closing session, before a new start can proceed.
            try {
                if (resourcesStopped && restoreRearLauncher && !deferLauncherRestore && !disposableWallpaperCast
                        && physicalIdentity.equals(RearDisplay.identity())) {
                    String foreground = service.getForegroundAppOnDisplay(RearDisplay.requireId());
                    if (foreground == null || foreground.endsWith(":" + host)
                            || foreground.startsWith("com.xiaomi.subscreencenter:") || foreground.startsWith("com.xiaomi.mirror:"))
                        RearSystemBridge.restoreLauncher();
                    else CompatibilityDiagnostics.record("launcher_restore_skipped", "A newer rear task owns the display: " + foreground);
                }
            }
            catch (Exception e) { android.util.Log.w("RearVirtualCast", "Rear launcher restoration", e); }
            CompatibilityDiagnostics.record(resourcesStopped ? "virtual_closed" : "virtual_close_indeterminate", "reason=" + reason);
            if (firstTouchActivityLogged.get() || touchActivityUnavailableLogged.get())
                android.util.Log.i("RearVirtualCast", "touch_activity_summary session=" + id + " display="
                        + physicalRearId + " " + touchActivityReporter.summary());
            last = status();
            android.util.Log.i("RearVirtualCast", last);
            if (resourcesStopped && current == this) current = null;
        }
        // A stopped result means resource restoration has finished, including asynchronous watchdog closes.
        synchronized String status() {
            try { return VirtualCastTerminalStatus.write(new JSONObject().put("id", id)
                    .put("reason", phase).put("display", display).put("task", task).put("host", host)
                    .put("generation", generation)
                    .put("coordinatorOperationId", coordinatorOperationId == null ? "" : coordinatorOperationId)
                    .put("ownerGeneration", coordinatorOwnerGeneration)
                    .put("ownerIdentity", coordinatorOwnerIdentity == null ? "" : coordinatorOwnerIdentity)
                    .put("rearDisplayId", physicalRearId).put("rearIdentity", physicalIdentity)
                    .put("package", packageName).put("baseComponent", baseComponent)
                    .putOpt("notificationRoute", notificationRoute.isEmpty() ? null : notificationRoute)
                    .put("intendedBaseComponent", expectedBaseComponent)
                    .put("density", density).put("requestedDensity", requestedDensity)
                    .put("renderProfile", density == requestedDensity ? "layout_density" : "source_density")
                    .put("compatibility", densityCompatibility ? "source_density" : "none")
                    .put("sizePolicy", sizePolicy)
                    .put("freezeProtection", freezeGuard == null ? "unavailable" : freezeGuard.mode)
                    .put("contentRotation", contentRotation).put("viewport", new JSONObject(RearCastGeometry.encode(viewport)))
                    .put("viewportRevision", viewportRevision)
                    .put("fullViewport", viewport.isFull()).put("viewportChanging", pendingViewport != null)
                    .put("readerLifecycle", readerLifecycle).put("readerId", readerId)
                    .put("readerGeneration", readerGeneration)
                    .put("input", physicalReaderLifecycle != null
                            ? VirtualCastTerminalStatus.physicalReaderStatus(readerLifecycle, readerId,
                                readerGeneration, task, display, new JSONObject(input.status()))
                            : new JSONObject(input.status())), closed, resourcesStopped, outputAvailable).toString(); }
            catch (Exception e) { return "{}"; }
        }
    }
}
