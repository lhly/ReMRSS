package top.zainzh.rearscreen;

/** Debug-only one-shot late failure after B is visibly focused and before A is touched. */
final class DebugPhysicalReplacementLateFailure {
    private static final Gate GATE = new Gate();
    private DebugPhysicalReplacementLateFailure() { }

    static boolean arm(int aTask, long aGeneration, int displayId, String identity,
            String aPackage, String bBase) {
        return arm(aTask, aGeneration, displayId, identity, aPackage, bBase, 0);
    }

    static boolean arm(int aTask, long aGeneration, int displayId, String identity,
            String aPackage, String bBase, int expectedBTask) {
        return BuildConfig.DEBUG && GATE.arm(aTask, aGeneration, displayId, identity,
                aPackage, bBase, expectedBTask);
    }

    static boolean consume(int aTask, long aGeneration, int bTask, int displayId,
            String identity, String bPackage, String bBase) {
        return BuildConfig.DEBUG && GATE.consume(aTask, aGeneration, bTask, displayId,
                identity, bPackage, bBase);
    }

    static void recordRollbackVerified(int aTask, long aGeneration, int bTask, int displayId,
            String identity) {
        GATE.recordRollbackVerified(aTask, aGeneration, bTask, displayId, identity);
    }

    static boolean awaitRollbackVerified(long timeoutMs) throws InterruptedException {
        return GATE.awaitRollbackVerified(timeoutMs);
    }

    static boolean awaitRollbackVerified(long timeoutMs, int aTask, long aGeneration,
            int bTask, int displayId, String identity) throws InterruptedException {
        return GATE.awaitRollbackVerified(timeoutMs, aTask, aGeneration, bTask, displayId, identity);
    }

    static void clearIfNotConsumed() { GATE.clearIfNotConsumed(); }

    static final class Gate {
        private int aTask, displayId, expectedBTask, consumedBTask;
        private long aGeneration;
        private String identity, aPackage, bBase, consumedIdentity;
        private boolean armed, rollbackVerified;

        synchronized boolean arm(int task, long generation, int display, String selectedIdentity,
                String oldPackage, String expectedBBase) {
            return arm(task, generation, display, selectedIdentity, oldPackage, expectedBBase, 0);
        }

        synchronized boolean arm(int task, long generation, int display, String selectedIdentity,
                String oldPackage, String expectedBBase, int exactCandidateTask) {
            if (armed || task <= 0 || generation <= 0 || display <= 0 || blank(selectedIdentity)
                    || !selectedIdentity.startsWith(display + ":")
                    || !"dev.remrss.sample.rc5physicala".equals(oldPackage)
                    || !"dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(expectedBBase)
                    || exactCandidateTask < 0 || exactCandidateTask == task) return false;
            aTask = task; aGeneration = generation; displayId = display;
            identity = selectedIdentity; aPackage = oldPackage; bBase = expectedBBase;
            expectedBTask = exactCandidateTask; consumedBTask = 0; consumedIdentity = null;
            rollbackVerified = false; armed = true;
            return true;
        }

        synchronized void recordRollbackVerified(int task, long generation, int candidateTask,
                int display, String selectedIdentity) {
            if (rollbackVerified || !consumedExactTuple(task, generation, candidateTask, display, selectedIdentity)) return;
            rollbackVerified = true;
            notifyAll();
        }

        synchronized boolean awaitRollbackVerified(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
            while (!rollbackVerified) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0L) return false;
                wait(remaining);
            }
            return true;
        }

        synchronized boolean awaitRollbackVerified(long timeoutMs, int task, long generation,
                int candidateTask, int display, String selectedIdentity) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
            while (!rollbackVerified || !consumedExactTuple(task, generation, candidateTask, display, selectedIdentity)) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0L) return false;
                wait(remaining);
            }
            return true;
        }

        synchronized void clearIfNotConsumed() {
            if (armed) armed = false;
        }

        synchronized boolean consume(int task, long generation, int candidateTask, int display,
                String selectedIdentity, String candidatePackage, String candidateBase) {
            if (!armed || task != aTask || generation != aGeneration || candidateTask <= 0
                    || task == candidateTask || display != displayId || !identity.equals(selectedIdentity)
                    || (expectedBTask > 0 && candidateTask != expectedBTask)
                    || !"dev.remrss.sample.rc5physicalb".equals(candidatePackage)
                    || !bBase.equals(candidateBase)) return false;
            armed = false;
            consumedBTask = candidateTask;
            consumedIdentity = selectedIdentity;
            return true;
        }

        private boolean consumedExactTuple(int task, long generation, int candidateTask,
                int display, String selectedIdentity) {
            return aTask == task && aGeneration == generation && consumedBTask == candidateTask
                    && displayId == display && display == displayId
                    && equal(identity, selectedIdentity) && equal(consumedIdentity, selectedIdentity);
        }

        private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
        private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
    }
}
