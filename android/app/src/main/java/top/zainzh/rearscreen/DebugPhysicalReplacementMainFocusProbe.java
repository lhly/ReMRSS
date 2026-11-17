package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Opt-in exact RC5 A/B main-focus comparison, present in Debug but never self-armed. */
final class DebugPhysicalReplacementMainFocusProbe {
    private static final Gate GATE = new Gate();
    private DebugPhysicalReplacementMainFocusProbe() { }

    static boolean arm(int aTask, long aGeneration, int bTask, int displayId, String identity,
            String aPackage, String aBase, DebugPhysicalReplacementMainFocusGate.Mode mode) {
        return BuildConfig.DEBUG && GATE.arm(aTask, aGeneration, bTask, displayId, identity,
                aPackage, aBase, mode);
    }

    static DebugPhysicalReplacementMainFocusGate.Mode claim(int aTask, long aGeneration, int bTask,
            int displayId, String identity, String aPackage, String aBase, String bPackage, String bBase,
            String operationId) {
        return BuildConfig.DEBUG ? GATE.claim(aTask, aGeneration, bTask, displayId, identity,
                aPackage, aBase, bPackage, bBase, operationId) : null;
    }

    static void recordDecision(DebugPhysicalReplacementMainFocusGate.Decision decision) {
        GATE.recordDecision(decision);
    }
    static boolean consumeDispatchLicense(String operationId) {
        return BuildConfig.DEBUG && GATE.consumeDispatchLicense(operationId);
    }
    static String awaitDecision(long timeoutMs) throws InterruptedException { return GATE.awaitDecision(timeoutMs); }
    static void recordTransactionTerminal(int aTask, long aGeneration, int bTask, int displayId,
            String identity, String operationId, ControlledPhysicalCastOperations.StartResult result) {
        GATE.recordTransactionTerminal(aTask, aGeneration, bTask, displayId, identity, operationId, result);
    }
    static boolean cleanupVerified(Context context, ITaskService service) {
        return BuildConfig.DEBUG && context != null && service != null
                && GATE.cleanupVerified(context.getApplicationContext(), service);
    }
    static void clear() { GATE.clear(); }

    static final class Gate {
        private int aTask, bTask, expectedBTask, displayId;
        private long aGeneration;
        private String identity, aPackage, aBase, bPackage, bBase, result, claimedOperationId;
        private DebugPhysicalReplacementMainFocusGate.Mode mode;
        private boolean armed, claimed, decisionReady, transactionFinished, licenseValid, licenseConsumed;

        synchronized boolean arm(int oldTask, long generation, int expectedCandidateTask,
                int display, String selectedIdentity,
                String oldPackage, String oldBase, DebugPhysicalReplacementMainFocusGate.Mode selectedMode) {
            if (armed || claimed || oldTask <= 0 || expectedCandidateTask <= 0
                    || expectedCandidateTask == oldTask || generation <= 0L || display <= 0
                    || blank(selectedIdentity) || !selectedIdentity.startsWith(display + ":")
                    || !"dev.remrss.sample.rc5physicala".equals(oldPackage)
                    || !"dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity".equals(oldBase)
                    || selectedMode == null) return false;
            aTask = oldTask; expectedBTask = expectedCandidateTask; aGeneration = generation;
            displayId = display; identity = selectedIdentity;
            aPackage = oldPackage; aBase = oldBase; mode = selectedMode;
            armed = true; claimed = false; decisionReady = false; transactionFinished = false; result = null;
            licenseValid = true; licenseConsumed = false;
            return true;
        }

        synchronized DebugPhysicalReplacementMainFocusGate.Mode claim(int oldTask, long generation,
                int candidateTask, int display, String selectedIdentity, String oldPackage, String oldBase,
                String candidatePackage, String candidateBase, String operationId) {
            if (!armed || claimed || candidateTask <= 0 || candidateTask != expectedBTask || oldTask == candidateTask
                    || oldTask != aTask || generation != aGeneration || display != displayId
                    || !identity.equals(selectedIdentity) || !aPackage.equals(oldPackage)
                    || !aBase.equals(oldBase) || !"dev.remrss.sample.rc5physicalb".equals(candidatePackage)
                    || !"dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(candidateBase)
                    || blank(operationId))
                return null;
            armed = false; claimed = true; bTask = candidateTask; bPackage = candidatePackage; bBase = candidateBase;
            claimedOperationId = operationId;
            return mode;
        }

        synchronized void recordDecision(DebugPhysicalReplacementMainFocusGate.Decision decision) {
            if (!claimed || decisionReady || decision == null) return;
            result = decision.mode == DebugPhysicalReplacementMainFocusGate.Mode.OBSERVE
                    ? "observe_only" : decision.allowMove ? "gate_open" : "gate_closed";
            decisionReady = true;
            notifyAll();
        }

        synchronized boolean consumeDispatchLicense(String operationId) {
            if (!claimed || mode != DebugPhysicalReplacementMainFocusGate.Mode.GATED
                    || !licenseValid || licenseConsumed || !equal(claimedOperationId, operationId)) return false;
            licenseConsumed = true;
            return true;
        }

        synchronized String awaitDecision(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
            while (!decisionReady) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0L) return null;
                wait(remaining);
            }
            return result;
        }

        synchronized void recordTransactionTerminal(int terminalA, long terminalGeneration, int terminalB,
                int terminalDisplay, String terminalIdentity, String operationId,
                ControlledPhysicalCastOperations.StartResult terminal) {
            if (!claimed || !decisionReady || transactionFinished || terminalA != aTask
                    || terminalGeneration != aGeneration || terminalB != bTask || terminalDisplay != displayId
                    || !equal(identity, terminalIdentity) || !equal(claimedOperationId, operationId)) return;
            boolean committed = terminal != null && terminal.committed;
            boolean verified = terminal != null && !terminal.indeterminate;
            String transactionResult = committed ? "transaction_committed"
                    : verified ? "rollback_complete" : "rollback_unverified";
            LogHolder.log("mode=" + mode.name().toLowerCase(java.util.Locale.ROOT)
                    + " event=transaction_terminal cleanup=" + (verified ? "verified" : "unverified")
                    + " result=" + transactionResult);
            transactionFinished = true;
            notifyAll();
        }

        boolean cleanupVerified(Context context, ITaskService service) {
            final int expectedA, expectedB, expectedDisplay;
            final String expectedIdentity, expectedABase, expectedBBase;
            synchronized (this) {
                if ((!armed && !claimed) || (claimed && (!decisionReady || !transactionFinished))
                        || aTask <= 0) return false;
                expectedA = aTask; expectedB = bTask; expectedDisplay = displayId;
                expectedIdentity = identity; expectedABase = aBase; expectedBBase = bBase;
            }
            if (expectedB <= 0) return false;
            try {
                SharedPreferences owner = context.getSharedPreferences("platform_display_ownership", Context.MODE_PRIVATE);
                boolean ownerCleared = !owner.contains("physical_projection_task")
                        && !owner.getBoolean("physical_projection_controlled", false)
                        && !owner.getBoolean("physical_projection_keeper_start_pending", false)
                        && !owner.getBoolean("physical_projection_keeper_start_claimed", false)
                        && !owner.getBoolean("physical_projection_closing", false)
                        && !owner.contains("physical_replacement_token");
                return ownerCleared
                        && exactMainPlacement(service.queryExactTaskPlacement(expectedA), expectedA,
                                expectedABase, expectedDisplay, expectedIdentity)
                        && exactMainPlacement(service.queryExactTaskPlacement(expectedB), expectedB,
                                expectedBBase, expectedDisplay, expectedIdentity);
            } catch (Exception | LinkageError unavailable) { return false; }
        }

        private static boolean exactMainPlacement(String raw, int expectedTask, String expectedBase,
                int expectedDisplay, String expectedIdentity) throws org.json.JSONException {
            if (raw == null || expectedBase == null || expectedIdentity == null) return false;
            JSONObject row = new JSONObject(raw);
            int separator = expectedIdentity.indexOf(':');
            if (separator <= 0 || separator == expectedIdentity.length() - 1) return false;
            int selectedId;
            try { selectedId = Integer.parseInt(expectedIdentity.substring(0, separator)); }
            catch (NumberFormatException invalidIdentity) { return false; }
            return "present".equals(row.optString("status"))
                    && row.optInt("task", -1) == expectedTask
                    && row.optInt("display", -1) == android.view.Display.DEFAULT_DISPLAY
                    && packageOf(expectedBase).equals(row.optString("package", ""))
                    && expectedBase.equals(row.optString("base", ""))
                    && row.optBoolean("rootSnapshotKnown", false) && row.optBoolean("rootSole", false)
                    && selectedId == expectedDisplay && row.optInt("selectedId", -1) == selectedId
                    && expectedIdentity.substring(separator + 1).equals(row.optString("selectedUnique", ""));
        }

        private static String packageOf(String component) {
            int separator = component == null ? -1 : component.indexOf('/');
            return separator <= 0 ? "" : component.substring(0, separator);
        }

        synchronized void clear() {
            if (claimed && !transactionFinished) {
                // Clearing an observer cannot erase a claimed invocation or bypass its final dispatch gate.
                licenseValid = false;
                notifyAll();
                return;
            }
            armed = false; claimed = false; decisionReady = false; transactionFinished = false;
            licenseValid = false; licenseConsumed = false;
            aTask = 0; bTask = 0; expectedBTask = 0; displayId = 0; aGeneration = 0L;
            identity = null; aPackage = null; aBase = null; bPackage = null; bBase = null;
            claimedOperationId = null;
            mode = null; result = null; notifyAll();
        }

        private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
        private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
    }

    private static final class LogHolder {
        static void log(String suffix) {
            android.util.Log.i("ControlledPhysicalCast", "physical_main_focus_gate v=1 lane=b " + suffix);
        }
    }
}
