package top.zainzh.rearscreen;

/** Owns the reversible preparation and the exact physical-cast commit boundary. */
final class PhysicalCastRequestFlow {
    enum MoveDisposition { VERIFIED_REAR, VERIFIED_NOT_REAR, UNKNOWN }
    enum MoveAttempt { VERIFIED, SENT_UNVERIFIED, REJECTED_BEFORE_SEND }
    static final class Target {
        final String packageName;
        final int taskId;

        Target(String packageName, int taskId) {
            this.packageName = packageName;
            this.taskId = taskId;
        }

        String legacyValue() { return packageName + ":" + taskId; }
    }

    interface Port {
        /** Apply only reversible request configuration; never stop an app, disable launcher, or start Keeper here. */
        void prepare() throws Exception;
        /** Launch the requested target and return the observed foreground package:task, or null if unresolved. */
        String launchAndObserve(int attempt) throws Exception;
        /** Distinguishes a local/preflight refusal from a move command whose final reply is uncertain. */
        MoveAttempt moveToRear(int taskId) throws Exception;
        /** Exact package/task/display readback after a refused or interrupted move. */
        default MoveDisposition reconcileMove(Target target) throws Exception { return MoveDisposition.UNKNOWN; }
        void projectionCommitted(int taskId, String packageName) throws Exception;
        void disableLauncher(Target target) throws Exception;
        boolean startKeeper(String packageTask) throws Exception;
        void rollbackPreparation();
        void postCommitFailure(Exception failure);
    }

    static final class Result {
        final Target target;
        final boolean committed;
        final boolean keeperStarted;
        final String failure;
        final boolean indeterminate;
        private Result(Target target, boolean committed, boolean keeperStarted, String failure, boolean indeterminate) {
            this.target = target;
            this.committed = committed;
            this.keeperStarted = keeperStarted;
            this.failure = failure;
            this.indeterminate = indeterminate;
        }
        static Result failed(String reason) { return new Result(null, false, false, reason, false); }
        static Result indeterminate(Target target, String reason) { return new Result(target, false, false, reason, true); }
        static Result committed(Target target, boolean keeperStarted) {
            return new Result(target, true, keeperStarted, null, false);
        }
    }

    private interface TargetResolver { Target resolve() throws Exception; }
    private PhysicalCastRequestFlow() {}

    static Result launchAndHandoff(String expectedPackage, int maxAttempts, Port port) {
        return execute(port, () -> {
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                Target target = parseExact(expectedPackage, port.launchAndObserve(attempt));
                if (target != null) return target;
            }
            return null;
        });
    }

    static Result prepareAndHandoff(Target target, Port port) {
        return execute(port, () -> target);
    }

    private static Result execute(Port port, TargetResolver resolver) {
        boolean preparationAttempted = false;
        boolean committed = false;
        boolean rollbackSafe = true;
        try {
            preparationAttempted = true;
            port.prepare();
            Target target = resolver.resolve();
            if (target == null) return Result.failed("target_unresolved");
            MoveAttempt attempt;
            try { attempt = port.moveToRear(target.taskId); }
            catch (Exception moveFailure) { attempt = MoveAttempt.SENT_UNVERIFIED; }
            if (attempt == MoveAttempt.REJECTED_BEFORE_SEND) return Result.failed("move_rejected_before_send");
            if (attempt != MoveAttempt.VERIFIED) {
                MoveDisposition disposition;
                try { disposition = port.reconcileMove(target); }
                catch (Exception unreadable) { disposition = MoveDisposition.UNKNOWN; }
                if (disposition == MoveDisposition.VERIFIED_REAR) { /* exact late success */ }
                else if (disposition == MoveDisposition.VERIFIED_NOT_REAR) return Result.failed("move_rejected");
                else {
                    rollbackSafe = false;
                    return Result.indeterminate(target, "move_unverified");
                }
            }

            // A verified move is the irreversible ownership boundary. A later maintenance error
            // cannot undo the display move or restore the previous Scene/notification owner.
            committed = true;
            runPostCommit(port, () -> port.projectionCommitted(target.taskId, target.packageName));
            boolean keeperStarted = false;
            try {
                keeperStarted = port.startKeeper(target.legacyValue());
                if (!keeperStarted) port.postCommitFailure(new IllegalStateException("Keeper start was not accepted"));
            } catch (Exception failure) { port.postCommitFailure(failure); }
            // Keep the system launcher available when there is no Keeper to own teardown.
            // The committed task remains returnable through the persistent exact-task record.
            if (keeperStarted) runPostCommit(port, () -> port.disableLauncher(target));
            return Result.committed(target, keeperStarted);
        } catch (Exception failure) {
            return Result.failed(failure.getClass().getSimpleName());
        } finally {
            if (preparationAttempted && !committed && rollbackSafe) {
                try { port.rollbackPreparation(); }
                catch (RuntimeException rollbackFailure) { port.postCommitFailure(rollbackFailure); }
            }
        }
    }

    private interface PostCommitAction { void run() throws Exception; }
    private static void runPostCommit(Port port, PostCommitAction action) {
        try { action.run(); }
        catch (Exception failure) { port.postCommitFailure(failure); }
    }

    private static Target parseExact(String expectedPackage, String observed) {
        if (observed == null) return null;
        int separator = observed.lastIndexOf(':');
        if (separator <= 0 || separator == observed.length() - 1
                || !expectedPackage.equals(observed.substring(0, separator))) return null;
        try {
            int task = Integer.parseInt(observed.substring(separator + 1));
            return task > 0 ? new Target(expectedPackage, task) : null;
        } catch (NumberFormatException invalidTask) { return null; }
    }
}
