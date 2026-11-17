package top.zainzh.rearscreen;

/** Returns one exact committed physical task without taking over any newer rear owner. */
final class PhysicalCastReturnFlow {
    enum Result { STOPPED, STALE, MOVE_FAILED, RESTORE_FAILED, STOP_FAILED, INDETERMINATE }
    enum TaskState { REAR, MAIN, ABSENT, UNKNOWN }

    interface Port {
        boolean isExactTaskOnRear(int taskId, String packageName) throws Exception;
        boolean moveExactTaskToMain(int taskId) throws Exception;
        /** Strict new controlled callers verify the move; legacy callers retain their old command contract. */
        default boolean isExactTaskOnMain(int taskId, String packageName) throws Exception { return true; }
        boolean mayRestoreLauncher() throws Exception;
        boolean restoreLauncher() throws Exception;
        boolean stopKeeperForExactTask(String packageTask, long ownerGeneration);
        void clearExactOwnership(int taskId, long ownerGeneration);
        /** Controlled callers opt into typed placement and verified ownership-CAS cleanup. */
        default boolean isStrictControlledReturn() { return false; }
        default TaskState inspectExactTask(int taskId, String packageName) throws Exception {
            return isExactTaskOnRear(taskId, packageName) ? TaskState.REAR : TaskState.UNKNOWN;
        }
        default boolean clearExactOwnershipVerified(int taskId, long ownerGeneration) {
            clearExactOwnership(taskId, ownerGeneration); return true;
        }
    }

    private PhysicalCastReturnFlow() {}

    static Result stop(String packageTask, boolean keeperOwnsTask, boolean launcherDisabled,
                       long ownerGeneration, Port port)
            throws Exception {
        int separator = packageTask == null ? -1 : packageTask.lastIndexOf(':');
        if (separator <= 0 || separator == packageTask.length() - 1) return Result.STALE;
        String packageName = packageTask.substring(0, separator);
        int taskId;
        try { taskId = Integer.parseInt(packageTask.substring(separator + 1)); }
        catch (NumberFormatException invalid) { return Result.STALE; }
        if (taskId <= 0 || packageName.isBlank()) return Result.STALE;

        if (port.isStrictControlledReturn())
            return stopControlled(taskId, packageName, packageTask, keeperOwnsTask, launcherDisabled,
                    ownerGeneration, port);

        boolean onRear = port.isExactTaskOnRear(taskId, packageName);
        if (onRear && (!port.moveExactTaskToMain(taskId) || !port.isExactTaskOnMain(taskId, packageName)))
            return Result.MOVE_FAILED;
        if (!onRear && !keeperOwnsTask && !launcherDisabled) {
            port.clearExactOwnership(taskId, ownerGeneration);
            return Result.STALE;
        }

        boolean restorationSafe = false;
        boolean restorationCheckFailed = false;
        if (launcherDisabled) {
            try { restorationSafe = port.mayRestoreLauncher(); }
            catch (Exception failure) { restorationCheckFailed = true; }
        }
        boolean restoreAttempted = launcherDisabled && restorationSafe;
        boolean restored = !restoreAttempted;
        if (restoreAttempted) {
            try { restored = port.restoreLauncher(); }
            catch (Exception failure) { restored = false; }
        }
        boolean clearOwnerRecord = !launcherDisabled || (restorationSafe && restored);
        if (keeperOwnsTask && !port.stopKeeperForExactTask(packageTask, ownerGeneration))
            return Result.STOP_FAILED;
        if (!restored || restorationCheckFailed) return Result.RESTORE_FAILED;
        if (clearOwnerRecord) port.clearExactOwnership(taskId, ownerGeneration);
        return Result.STOPPED;
    }

    private static Result stopControlled(int taskId, String packageName, String packageTask,
            boolean keeperOwnsTask, boolean launcherDisabled, long ownerGeneration, Port port) throws Exception {
        TaskState state = port.inspectExactTask(taskId, packageName);
        if (state == TaskState.UNKNOWN) return Result.INDETERMINATE;
        if (state == TaskState.REAR) {
            if (!port.moveExactTaskToMain(taskId)) {
                TaskState afterFailure = port.inspectExactTask(taskId, packageName);
                if (afterFailure == TaskState.UNKNOWN || afterFailure == TaskState.REAR) return Result.INDETERMINATE;
                if (afterFailure != TaskState.MAIN && afterFailure != TaskState.ABSENT) return Result.INDETERMINATE;
            }
            state = port.inspectExactTask(taskId, packageName);
            if (state != TaskState.MAIN && state != TaskState.ABSENT) return Result.INDETERMINATE;
        }
        if (state != TaskState.MAIN && state != TaskState.ABSENT) return Result.INDETERMINATE;

        if (launcherDisabled) {
            boolean safe;
            try { safe = port.mayRestoreLauncher(); }
            catch (Exception unknown) { return Result.INDETERMINATE; }
            if (!safe) return Result.INDETERMINATE;
            boolean restored;
            try { restored = port.restoreLauncher(); }
            catch (Exception failed) { return Result.INDETERMINATE; }
            if (!restored) return Result.INDETERMINATE;
        }
        // Controlled return must resolve the pending/claimed Keeper race even when the initial
        // in-memory snapshot said no Keeper owned the task.
        if (!port.stopKeeperForExactTask(packageTask, ownerGeneration)) return Result.INDETERMINATE;
        if (!port.clearExactOwnershipVerified(taskId, ownerGeneration)) return Result.INDETERMINATE;
        return Result.STOPPED;
    }
}
