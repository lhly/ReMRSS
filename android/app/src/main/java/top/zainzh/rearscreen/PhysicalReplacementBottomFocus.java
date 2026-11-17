package top.zainzh.rearscreen;

import android.view.Display;

/** Shared strict predicates for the verified-device physical replacement bottom→focus path. */
final class PhysicalReplacementBottomFocus {
    private PhysicalReplacementBottomFocus() { }

    static boolean supportedDevice(String device, String model) {
        return RearShellCatalog.colors(device).length > 0
                && "popsicle".equals(device) && "2509FPN0BC".equals(model);
    }

    static final class DesktopIdentity {
        final int taskId, userId;
        final String basePackage, topPackage, baseComponent, topComponent;
        final boolean exists;
        DesktopIdentity(RearDisplayCastOccupancy.Task task) {
            taskId = task.taskId; userId = task.userId; basePackage = task.basePackage;
            topPackage = task.topPackage; baseComponent = task.baseComponent; topComponent = task.topComponent;
            exists = true;
        }
        private DesktopIdentity() {
            taskId = userId = -1; basePackage = topPackage = baseComponent = topComponent = null;
            exists = false;
        }
        static DesktopIdentity absent() { return new DesktopIdentity(); }
        boolean matches(RearDisplayCastOccupancy.Task task) {
            return exists && task != null && task.taskId == taskId && task.userId == userId
                    && eq(basePackage, task.basePackage) && eq(topPackage, task.topPackage)
                    && eq(baseComponent, task.baseComponent) && eq(topComponent, task.topComponent);
        }
        boolean sameAs(DesktopIdentity other) {
            return other != null && exists == other.exists
                    && (!exists || (taskId == other.taskId && userId == other.userId
                        && eq(basePackage, other.basePackage) && eq(topPackage, other.topPackage)
                        && eq(baseComponent, other.baseComponent) && eq(topComponent, other.topComponent)));
        }
        boolean present(RearDisplayCastOccupancy.Snapshot snapshot) {
            if (!exists || snapshot == null || snapshot.tasks == null) return false;
            for (RearDisplayCastOccupancy.Task task : snapshot.tasks) if (matches(task)) return true;
            return false;
        }
    }

    static boolean exactAWithValidatedSystemDesktop(RearDisplayCastOccupancy.Snapshot snapshot,
            int rearDisplay, int aTask, String aPackage, String aBase,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        return exactSystemDesktopIdentity(snapshot, rearDisplay, aTask, aPackage, aBase, desktopPermit) != null;
    }

    static boolean exactVisibleMainTask(RearDisplayCastOccupancy.Snapshot snapshot,
            int taskId, String packageName, String baseComponent) {
        if (!stableTypedSnapshot(snapshot) || snapshot.displayId != 0 || taskId <= 0
                || packageName == null || baseComponent == null || snapshot.tasks == null) return false;
        RearDisplayCastOccupancy.Task found = null;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (RearDisplayCastOccupancy.Task row : snapshot.tasks) {
            if (row == null || row.taskId <= 0 || !ids.add(row.taskId)) return false;
            if (row.taskId == taskId) {
                if (found != null) return false;
                found = row;
            }
        }
        return found != null && exactTask(found, taskId, packageName, baseComponent)
                && Boolean.TRUE.equals(found.visible) && Boolean.TRUE.equals(found.requested)
                && Boolean.FALSE.equals(found.sleeping);
    }

    static DesktopIdentity exactSystemDesktopIdentity(RearDisplayCastOccupancy.Snapshot snapshot,
            int rearDisplay, int aTask, String aPackage, String aBase,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        if (!stableRearSnapshot(snapshot) || aTask <= 0 || desktopPermit == null
                || snapshot.displayId != rearDisplay || snapshot.tasks == null
                || (snapshot.tasks.size() != 1 && snapshot.tasks.size() != 2)) return null;
        RearDisplayCastOccupancy.Task a = null, launcher = null;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task == null) return null;
            if (task.taskId == aTask) {
                if (a != null) return null;
                a = task;
            } else {
                if (launcher != null || !desktopPermit.matches(task)) return null;
                launcher = task;
            }
        }
        if (!exactTask(a, aTask, aPackage, aBase) || !Boolean.TRUE.equals(a.visible)
                || !Boolean.TRUE.equals(a.requested) || !Boolean.FALSE.equals(a.sleeping)) return null;
        if (launcher == null) return DesktopIdentity.absent();
        return desktopPermit.matches(launcher) && launcher.clearlyHidden(snapshot.displayState)
                && launcher.userId >= 0 ? new DesktopIdentity(launcher) : null;
    }

    enum BottomCheck {
        READY, GATE_CHANGED, REAR_IDENTITY, REAR_SNAPSHOT, MAIN_SNAPSHOT,
        A_STILL_MAIN, B_STILL_MAIN, TASK_INVENTORY, A_IDENTITY, B_IDENTITY,
        DESKTOP_IDENTITY, B_NOT_HIDDEN, DESKTOP_NOT_HIDDEN, A_NOT_VISIBLE,
        REAR_WAKING, A_BECOMING_VISIBLE, ROOT_INVENTORY, A_REDRAWING, A_PRESENTATION,
        PROCESS_IDENTITY, SETTLEMENT_EXPIRED, QUERY_FAILED;

        boolean maySettle() {
            return this == REAR_WAKING || this == A_BECOMING_VISIBLE || this == A_REDRAWING;
        }
    }

    interface SettlementClock {
        long nanoTime();
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Observe only. No calls to move, focus or wake. Extra shell reads share
     * one deadline; synchronous Binder reads cannot be preempted, but no late
     * observation can authorize a focus request.
     */
    static BottomCheck awaitBottomReady(java.util.function.LongFunction<BottomCheck> observe,
            SettlementClock clock) {
        if (Thread.currentThread().isInterrupted()) return BottomCheck.QUERY_FAILED;
        BottomCheck observed = observe.apply(Long.MAX_VALUE);
        if (Thread.currentThread().isInterrupted()) return BottomCheck.QUERY_FAILED;
        if (!observed.maySettle()) return observed;
        final long deadline = clock.nanoTime() + 240_000_000;
        for (int attempt = 0; attempt < 3 && observed.maySettle(); attempt++) {
            if (deadline - clock.nanoTime() <= 40_000_000) return BottomCheck.SETTLEMENT_EXPIRED;
            try {
                clock.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return BottomCheck.QUERY_FAILED;
            }
            if (clock.nanoTime() >= deadline) return BottomCheck.SETTLEMENT_EXPIRED;
            BottomCheck next = observe.apply(deadline);
            if (Thread.currentThread().isInterrupted()) return BottomCheck.QUERY_FAILED;
            if (clock.nanoTime() >= deadline) return BottomCheck.SETTLEMENT_EXPIRED;
            observed = next;
        }
        return observed;
    }

    static boolean exactRearAFrontBHidden(RearDisplayCastOccupancy.Snapshot snapshot,
            int aTask, String aPackage, String aBase, int bTask, String bPackage, String bBase,
            int rearDisplay, DesktopIdentity expectedDesktop,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        return rearPairCheck(snapshot, aTask, aPackage, aBase, bTask, bPackage, bBase,
                rearDisplay, expectedDesktop, desktopPermit) == BottomCheck.READY;
    }

    static BottomCheck rearPairCheck(RearDisplayCastOccupancy.Snapshot snapshot,
            int aTask, String aPackage, String aBase, int bTask, String bPackage, String bBase,
            int rearDisplay, DesktopIdentity expectedDesktop,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        if (!typedSnapshot(snapshot) || rearDisplay <= 0 || snapshot.displayId != rearDisplay
                || snapshot.tasks == null) return BottomCheck.REAR_SNAPSHOT;
        if (aTask <= 0 || bTask <= 0 || aTask == bTask || expectedDesktop == null || desktopPermit == null
                || (snapshot.tasks.size() != 2 && !(expectedDesktop.exists && snapshot.tasks.size() == 3)))
            return BottomCheck.TASK_INVENTORY;
        RearDisplayCastOccupancy.Task a = null, b = null, desktop = null;
        java.util.Set<Integer> taskIds = new java.util.HashSet<>();
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task == null || task.taskId <= 0 || !taskIds.add(task.taskId))
                return BottomCheck.TASK_INVENTORY;
            if (task.taskId == aTask) a = task;
            else if (task.taskId == bTask) b = task;
            else {
                if (desktop != null || !expectedDesktop.matches(task) || !desktopPermit.matches(task))
                    return BottomCheck.DESKTOP_IDENTITY;
                desktop = task;
            }
        }
        if (!exactTask(a, aTask, aPackage, aBase)) return BottomCheck.A_IDENTITY;
        if (!exactTask(b, bTask, bPackage, bBase)) return BottomCheck.B_IDENTITY;
        // Passing STATE_ON here checks only the three task flags. Stable ON is
        // still independently required to authorize a focus request below.
        if (!b.clearlyHidden(Display.STATE_ON)) return BottomCheck.B_NOT_HIDDEN;
        if (desktop != null && !desktop.clearlyHidden(Display.STATE_ON)) return BottomCheck.DESKTOP_NOT_HIDDEN;
        if (!stableRearSnapshot(snapshot)) {
            boolean knownWake = snapshot.powerAfter == Display.STATE_ON
                    && (snapshot.powerBefore == Display.STATE_OFF || snapshot.powerBefore == Display.STATE_DOZE
                        || snapshot.powerBefore == Display.STATE_DOZE_SUSPEND);
            return knownWake ? BottomCheck.REAR_WAKING : BottomCheck.REAR_SNAPSHOT;
        }
        if (!Boolean.TRUE.equals(a.requested) || !Boolean.FALSE.equals(a.sleeping))
            return BottomCheck.A_NOT_VISIBLE;
        if (Boolean.FALSE.equals(a.visible)) return BottomCheck.A_BECOMING_VISIBLE;
        return Boolean.TRUE.equals(a.visible) ? BottomCheck.READY : BottomCheck.A_NOT_VISIBLE;
    }

    static boolean exactProcessPidPair(int expectedA, int actualA, int expectedB, int actualB) {
        return expectedA > 0 && expectedB > 0 && expectedA != expectedB
                && actualA == expectedA && actualB == expectedB;
    }

    static boolean exactHiddenPairAndBoundPids(RearDisplayCastOccupancy.Snapshot snapshot,
            int aTask, String aPackage, String aBase, int bTask, String bPackage, String bBase,
            int rearDisplay, DesktopIdentity expectedDesktop,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit,
            int expectedA, int actualA, int expectedB, int actualB) {
        return exactRearAFrontBHidden(snapshot, aTask, aPackage, aBase, bTask, bPackage, bBase,
                rearDisplay, expectedDesktop, desktopPermit)
                && exactProcessPidPair(expectedA, actualA, expectedB, actualB);
    }

    enum Dispatch { REJECTED_BEFORE_SEND, SENT, MAY_HAVE_BEEN_SENT }
    interface Actions {
        Dispatch moveBottom();
        boolean verifyBottom();
        Dispatch requestFocus();
    }
    static final class Outcome {
        final String status, reason;
        final boolean bMoved;
        Outcome(String status, String reason, boolean bMoved) {
            this.status = status; this.reason = reason; this.bMoved = bMoved;
        }
    }

    /** Sequence exactly one bottom move, one confirming readback, then at most one focus request. */
    static Outcome run(boolean supported, Actions actions) {
        if (!supported) return new Outcome("unsupported", "capability_unavailable", false);
        if (actions == null) return new Outcome("rejected", "invalid_actions", false);
        final Dispatch bottom;
        try { bottom = actions.moveBottom(); }
        catch (RuntimeException unknown) { return new Outcome("unknown", "bottom_reply_unknown", true); }
        if (bottom == Dispatch.REJECTED_BEFORE_SEND)
            return new Outcome("rejected", "bottom_rejected_before_send", false);
        if (bottom != Dispatch.SENT) return new Outcome("unknown", "bottom_reply_unknown", true);
        final boolean verified;
        try { verified = actions.verifyBottom(); }
        catch (RuntimeException unknown) { return new Outcome("incomplete", "bottom_readback_unknown", true); }
        if (!verified) return new Outcome("incomplete", "bottom_readback_unknown", true);
        final Dispatch focus;
        try { focus = actions.requestFocus(); }
        catch (RuntimeException unknown) { return new Outcome("unknown", "focus_reply_unknown", true); }
        if (focus == Dispatch.REJECTED_BEFORE_SEND)
            return new Outcome("incomplete", "focus_rejected_before_send", true);
        if (focus != Dispatch.SENT) return new Outcome("unknown", "focus_reply_unknown", true);
        return new Outcome("requested", "bottom_focus_requested", true);
    }

    private static boolean stableRearSnapshot(RearDisplayCastOccupancy.Snapshot snapshot) {
        return stableTypedSnapshot(snapshot) && snapshot.displayId > 0;
    }

    private static boolean stableTypedSnapshot(RearDisplayCastOccupancy.Snapshot snapshot) {
        return typedSnapshot(snapshot) && snapshot.displayState == Display.STATE_ON
                && snapshot.powerBefore == Display.STATE_ON && snapshot.powerAfter == Display.STATE_ON;
    }

    private static boolean typedSnapshot(RearDisplayCastOccupancy.Snapshot snapshot) {
        return snapshot != null && snapshot.failure == null && snapshot.displayId >= 0
                && ("activity_task".equals(snapshot.source) || "activity_task_focused_stack".equals(snapshot.source)
                    || "activity_focused_stack".equals(snapshot.source));
    }

    private static boolean exactTask(RearDisplayCastOccupancy.Task task, int id, String packageName, String base) {
        return task != null && task.taskId == id && task.userId == 0 && packageName.equals(task.basePackage)
                && packageName.equals(task.topPackage) && base.equals(task.baseComponent)
                && base.equals(task.topComponent);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
}
