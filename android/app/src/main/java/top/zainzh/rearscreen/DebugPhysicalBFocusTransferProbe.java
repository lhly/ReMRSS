package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;

/** One-shot Debug-only authorization and result slot for the exact RC5 physical A/B focus-transfer experiment. */
final class DebugPhysicalBFocusTransferProbe {
    private static final Gate GATE = new Gate();
    private DebugPhysicalBFocusTransferProbe() { }

    static boolean arm(int aTask, long aGeneration, int bTask, int displayId, String identity,
            String aPackage, String aBase, int aProcessPid, int bProcessPid) {
        return BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".debug")
                && GATE.arm(aTask, aGeneration, bTask, displayId, identity, aPackage, aBase,
                        aProcessPid, bProcessPid);
    }

    static int[] dispatchProcessPids(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId, Stage stage) {
        return BuildConfig.DEBUG ? GATE.dispatchProcessPids(aTask, generation, bTask, displayId,
                identity, operationId, stage) : null;
    }

    static String safeBottomDispatchReason(String reason) {
        switch (reason == null ? "" : reason) {
            case "debug_fixture_unavailable": case "debug_disabled": case "caller_rejected":
            case "invalid_arguments": case "bottom_binding_busy": case "rear_identity_mismatch":
            case "typed_snapshot_unavailable": case "a_topology_mismatch":
            case "b_main_identity_mismatch": case "a_presentation_mismatch":
            case "b_presentation_mismatch": case "process_pair_mismatch":
            case "inventory_check_failed": case "root_snapshot_unavailable":
            case "exact_root_mismatch": case "source_location_mismatch": case "atms_unavailable":
            case "move_method_unavailable": case "reflection_access_denied":
            case "reflection_invoke_rejected": case "final_gate_rejected":
            case "dispatch_gate_rejected": case "invoke_reply_unknown": case "invoked":
            case "bottom_pair_readback_unknown": case "bottom_root_readback_unknown":
            case "bottom_binding_store_failed": case "bottom_operation_unknown":
            case "exact_pair_rear_readback":
                return reason;
            default: return "unknown";
        }
    }

    static String bottomReasonFromResult(JSONObject result) {
        return result == null ? "unknown"
                : safeBottomDispatchReason(result.optString("reason", ""));
    }

    static boolean claim(int aTask, long aGeneration, int bTask, int displayId, String identity,
            String aPackage, String aBase, String bPackage, String bBase, String operationId) {
        return BuildConfig.DEBUG && GATE.claim(aTask, aGeneration, bTask, displayId, identity,
                aPackage, aBase, bPackage, bBase, operationId);
    }

    static boolean dispatchCurrent(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId, Stage stage) {
        return BuildConfig.DEBUG && GATE.dispatchCurrent(aTask, generation, bTask, displayId,
                identity, operationId, stage);
    }

    static boolean bottomVerifiedCurrent(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId) {
        return BuildConfig.DEBUG && GATE.bottomVerifiedCurrent(aTask, generation, bTask, displayId,
                identity, operationId);
    }

    static boolean beginDispatch(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId, Stage stage) {
        return BuildConfig.DEBUG && GATE.beginDispatch(aTask, generation, bTask, displayId,
                identity, operationId, stage);
    }

    static boolean recordBottomReadback(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId) {
        return GATE.recordBottomReadback(aTask, generation, bTask, displayId, identity, operationId);
    }

    static boolean recordFocusRequest(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId) {
        return GATE.recordFocusRequest(aTask, generation, bTask, displayId, identity, operationId);
    }

    static boolean recordReady(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId) {
        return GATE.recordReady(aTask, generation, bTask, displayId, identity, operationId);
    }

    static String awaitOutcome(long timeoutMs) throws InterruptedException { return GATE.awaitOutcome(timeoutMs); }

    static boolean exactRearPairWithAFront(JSONObject snapshot, int aTask, String aPackage, String aBase,
            int bTask, String bPackage, String bBase, int rearDisplay) {
        if (snapshot == null || aTask <= 0 || bTask <= 0 || aTask == bTask
                || !"ok".equals(snapshot.optString("status")) || snapshot.optInt("display", -1) != rearDisplay
                || snapshot.optInt("power", -1) != Display.STATE_ON
                || snapshot.optInt("powerBefore", -1) != Display.STATE_ON
                || snapshot.optInt("powerAfter", -1) != Display.STATE_ON) return false;
        String source = snapshot.optString("source");
        if (!("activity_task".equals(source) || "activity_task_focused_stack".equals(source)
                || "activity_focused_stack".equals(source))) return false;
        JSONArray rows = snapshot.optJSONArray("tasks");
        if (rows == null || (rows.length() != 2 && rows.length() != 3)) return false;
        JSONObject a = null, b = null, desktop = null;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optInt("task", -1) <= 0 || !ids.add(row.optInt("task", -1))) return false;
            int task = row.optInt("task", -1);
            if (task == aTask) { if (a != null) return false; a = row; }
            if (task == bTask) { if (b != null) return false; b = row; }
            if (task != aTask && task != bTask) { if (desktop != null) return false; desktop = row; }
        }
        return exactIdentityRow(a, aPackage, aBase) && exactIdentityRow(b, bPackage, bBase)
                && knownVisibility(a) && knownVisibility(b)
                && Boolean.TRUE.equals(a.optBoolean("visible"))
                && Boolean.TRUE.equals(a.optBoolean("requested"))
                && Boolean.FALSE.equals(a.optBoolean("sleeping"))
                && Boolean.FALSE.equals(b.optBoolean("visible"))
                && Boolean.FALSE.equals(b.optBoolean("requested"))
                && Boolean.FALSE.equals(b.optBoolean("sleeping"))
                && (desktop == null || exactKnownHiddenDesktopRow(desktop));
    }

    /** Client-side observation only; the service binds this desktop to the earlier exact pre-move row. */
    private static boolean exactKnownHiddenDesktopRow(JSONObject row) {
        String pkg = "com.xiaomi.subscreencenter";
        String component = pkg + "/com.xiaomi.subscreencenter.SubScreenLauncher";
        return row != null && row.optInt("userId", -1) == 0
                && pkg.equals(row.optString("base", "")) && pkg.equals(row.optString("top", ""))
                && component.equals(row.optString("baseComponent", ""))
                && component.equals(row.optString("topComponent", ""))
                && knownVisibility(row) && Boolean.FALSE.equals(row.optBoolean("visible"))
                && Boolean.FALSE.equals(row.optBoolean("requested"))
                && Boolean.FALSE.equals(row.optBoolean("sleeping"));
    }

    private static boolean exactIdentityRow(JSONObject row, String expectedPackage, String expectedBase) {
        return row != null && expectedPackage.equals(row.optString("base", ""))
                && expectedPackage.equals(row.optString("top", ""))
                && expectedBase.equals(row.optString("baseComponent", ""))
                && expectedBase.equals(row.optString("topComponent", ""));
    }
    private static boolean knownVisibility(JSONObject row) {
        return row != null && row.has("visible") && !row.isNull("visible")
                && row.has("requested") && !row.isNull("requested")
                && row.has("sleeping") && !row.isNull("sleeping");
    }

    static boolean exactAWithValidatedSystemDesktop(RearDisplayCastOccupancy.Snapshot snapshot,
            int rearDisplay, int aTask, String aPackage, String aBase,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        return PhysicalReplacementBottomFocus.exactAWithValidatedSystemDesktop(
                snapshot, rearDisplay, aTask, aPackage, aBase, desktopPermit);
    }

    static PhysicalReplacementBottomFocus.DesktopIdentity exactSystemDesktopIdentity(
            RearDisplayCastOccupancy.Snapshot snapshot, int rearDisplay, int aTask,
            String aPackage, String aBase, RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        return PhysicalReplacementBottomFocus.exactSystemDesktopIdentity(
                snapshot, rearDisplay, aTask, aPackage, aBase, desktopPermit);
    }

    static boolean exactRearAFrontBHidden(RearDisplayCastOccupancy.Snapshot snapshot,
            int aTask, String aPackage, String aBase, int bTask, String bPackage, String bBase,
            int rearDisplay, PhysicalReplacementBottomFocus.DesktopIdentity expectedDesktop,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        return PhysicalReplacementBottomFocus.exactRearAFrontBHidden(snapshot, aTask, aPackage, aBase,
                bTask, bPackage, bBase, rearDisplay, expectedDesktop, desktopPermit);
    }

    /** One-shot inter-IPC binding. Expiration can reject an old focus request, never authorize it. */
    static final class BottomBindingSlot {
        private final long ttlMs;
        private Binding active;
        BottomBindingSlot(long ttlMs) { this.ttlMs = ttlMs; }

        synchronized boolean availableForNew(long nowMs) {
            expire(nowMs);
            return active == null;
        }

        synchronized boolean store(int a, long generation, int b, int display, String identity,
                String operation, PhysicalReplacementBottomFocus.DesktopIdentity desktop, long nowMs) {
            expire(nowMs);
            if (active != null || a <= 0 || generation <= 0 || b <= 0 || display <= 0
                    || identity == null || operation == null || operation.isBlank() || desktop == null) return false;
            active = new Binding(a, generation, b, display, identity, operation, desktop, nowMs);
            return true;
        }

        synchronized PhysicalReplacementBottomFocus.DesktopIdentity take(int a, long generation, int b, int display,
                String identity, String operation, long nowMs) {
            expire(nowMs);
            if (active == null || !active.matches(a, generation, b, display, identity, operation)) return null;
            PhysicalReplacementBottomFocus.DesktopIdentity desktop = active.desktop;
            active = null;
            return desktop;
        }

        synchronized boolean clearExact(int a, long generation, int b, int display,
                String identity, String operation, long nowMs) {
            expire(nowMs);
            if (active == null || !active.matches(a, generation, b, display, identity, operation)) return false;
            active = null;
            return true;
        }

        private void expire(long nowMs) {
            if (active != null && (ttlMs <= 0L || nowMs < active.createdAtMs
                    || nowMs - active.createdAtMs >= ttlMs)) active = null;
        }

        private static final class Binding {
            final int a, b, display;
            final long generation, createdAtMs;
            final String identity, operation;
            final PhysicalReplacementBottomFocus.DesktopIdentity desktop;
            Binding(int a, long generation, int b, int display, String identity, String operation,
                    PhysicalReplacementBottomFocus.DesktopIdentity desktop, long createdAtMs) {
                this.a = a; this.generation = generation; this.b = b; this.display = display;
                this.identity = identity; this.operation = operation; this.desktop = desktop;
                this.createdAtMs = createdAtMs;
            }
            boolean matches(int a, long generation, int b, int display, String identity, String operation) {
                return this.a == a && this.generation == generation && this.b == b && this.display == display
                        && eq(this.identity, identity) && eq(this.operation, operation);
            }
        }
    }

    static boolean exactProcessPidPair(int expectedA, int actualA, int expectedB, int actualB) {
        return PhysicalReplacementBottomFocus.exactProcessPidPair(expectedA, actualA, expectedB, actualB);
    }

    static boolean exactHiddenPairAndBoundPids(RearDisplayCastOccupancy.Snapshot snapshot,
            int aTask, String aPackage, String aBase, int bTask, String bPackage, String bBase,
            int rearDisplay, PhysicalReplacementBottomFocus.DesktopIdentity expectedDesktop,
            RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit,
            int expectedA, int actualA, int expectedB, int actualB) {
        return PhysicalReplacementBottomFocus.exactHiddenPairAndBoundPids(snapshot, aTask, aPackage, aBase,
                bTask, bPackage, bBase, rearDisplay, expectedDesktop, desktopPermit,
                expectedA, actualA, expectedB, actualB);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    static boolean exactDebugHostUid(boolean debugBuild, String applicationId, int callingUid, int hostUid) {
        return BuildConfig.DEBUG && debugBuild && applicationId != null && applicationId.endsWith(".debug")
                && callingUid > 0 && hostUid > 0 && callingUid == hostUid;
    }

    static void recordTransactionTerminal(int aTask, long generation, int bTask, int displayId,
            String identity, String operationId, ControlledPhysicalCastOperations.StartResult result) {
        GATE.recordTransactionTerminal(aTask, generation, bTask, displayId, identity, operationId, result);
    }

    static boolean cleanupVerified(Context context, ITaskService service) {
        return BuildConfig.DEBUG && context != null && service != null
                && GATE.cleanupVerified(context.getApplicationContext(), service);
    }

    static void clear() { GATE.clear(); }

    enum Stage { BOTTOM, FOCUS }
    private enum State { EMPTY, ARMED, CLAIMED, BOTTOM_DISPATCHING, BOTTOM_VERIFIED,
        FOCUS_DISPATCHING, FOCUS_SENT, READY, TERMINAL }

    static final class Gate {
        private int aTask, bTask, expectedBTask, displayId;
        private int aProcessPid, bProcessPid;
        private long aGeneration;
        private String identity, aPackage, aBase, bPackage, bBase, operationId, outcome;
        private State state = State.EMPTY;
        private boolean licenseValid;
        private boolean terminalVerified;

        synchronized boolean arm(int a, long generation, int expectedB, int display, String selectedIdentity,
                String oldPackage, String oldBase, int oldProcessPid, int newProcessPid) {
            if (state != State.EMPTY || a <= 0 || expectedB <= 0 || a == expectedB || generation <= 0L
                    || display <= 0 || selectedIdentity == null || !selectedIdentity.startsWith(display + ":")
                    || oldProcessPid <= 0 || newProcessPid <= 0 || oldProcessPid == newProcessPid
                    || !"dev.remrss.sample.rc5physicala".equals(oldPackage)
                    || !"dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity".equals(oldBase)) return false;
            aTask = a; expectedBTask = expectedB; aGeneration = generation; displayId = display;
            aProcessPid = oldProcessPid; bProcessPid = newProcessPid;
            identity = selectedIdentity; aPackage = oldPackage; aBase = oldBase;
            licenseValid = true; state = State.ARMED;
            return true;
        }

        synchronized boolean claim(int a, long generation, int b, int display, String selectedIdentity,
                String oldPackage, String oldBase, String newPackage, String newBase, String operation) {
            if (state != State.ARMED || b <= 0 || b != expectedBTask || a != aTask || generation != aGeneration
                    || display != displayId || !eq(identity, selectedIdentity) || !eq(aPackage, oldPackage)
                    || !eq(aBase, oldBase) || !"dev.remrss.sample.rc5physicalb".equals(newPackage)
                    || !"dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(newBase)
                    || operation == null || operation.isBlank()) return false;
            bTask = b; bPackage = newPackage; bBase = newBase; operationId = operation;
            state = State.CLAIMED;
            return true;
        }

        synchronized boolean beginDispatch(int a, long generation, int b, int display, String selectedIdentity,
                String operation, Stage stage) {
            if (!matches(a, generation, b, display, selectedIdentity, operation) || !licenseValid) return false;
            if (stage == Stage.BOTTOM && state == State.CLAIMED) {
                state = State.BOTTOM_DISPATCHING;
                return true;
            }
            if (stage == Stage.FOCUS && state == State.BOTTOM_VERIFIED) {
                state = State.FOCUS_DISPATCHING;
                return true;
            }
            return false;
        }

        synchronized boolean dispatchCurrent(int a, long generation, int b, int display, String selectedIdentity,
                String operation, Stage stage) {
            return matches(a, generation, b, display, selectedIdentity, operation) && licenseValid
                    && ((stage == Stage.BOTTOM && state == State.BOTTOM_DISPATCHING)
                        || (stage == Stage.FOCUS && state == State.FOCUS_DISPATCHING));
        }

        synchronized int[] dispatchProcessPids(int a, long generation, int b, int display,
                String selectedIdentity, String operation, Stage stage) {
            if (!matches(a, generation, b, display, selectedIdentity, operation) || !licenseValid
                    || !((stage == Stage.BOTTOM && state == State.BOTTOM_DISPATCHING)
                        || (stage == Stage.FOCUS && state == State.FOCUS_DISPATCHING))) return null;
            return new int[]{aProcessPid, bProcessPid};
        }

        synchronized boolean bottomVerifiedCurrent(int a, long generation, int b, int display,
                String selectedIdentity, String operation) {
            return matches(a, generation, b, display, selectedIdentity, operation)
                    && licenseValid && state == State.BOTTOM_VERIFIED;
        }

        synchronized boolean recordBottomReadback(int a, long generation, int b, int display,
                String selectedIdentity, String operation) {
            if (!matches(a, generation, b, display, selectedIdentity, operation) || !licenseValid
                    || state != State.BOTTOM_DISPATCHING) return false;
            state = State.BOTTOM_VERIFIED;
            return true;
        }

        synchronized boolean recordFocusRequest(int a, long generation, int b, int display,
                String selectedIdentity, String operation) {
            if (!matches(a, generation, b, display, selectedIdentity, operation) || !licenseValid
                    || state != State.FOCUS_DISPATCHING) return false;
            state = State.FOCUS_SENT;
            return true;
        }

        synchronized boolean recordReady(int a, long generation, int b, int display,
                String selectedIdentity, String operation) {
            if (!matches(a, generation, b, display, selectedIdentity, operation) || !licenseValid
                    || state != State.FOCUS_SENT) return false;
            state = State.READY;
            outcome = "ready";
            notifyAll();
            return true;
        }

        synchronized String awaitOutcome(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
            while (state != State.READY && state != State.TERMINAL) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0L) return null;
                wait(remaining);
            }
            return outcome;
        }

        synchronized void recordTransactionTerminal(int a, long generation, int b, int display,
                String selectedIdentity, String operation, ControlledPhysicalCastOperations.StartResult result) {
            if (state == State.EMPTY || state == State.ARMED || state == State.TERMINAL
                    || !matches(a, generation, b, display, selectedIdentity, operation)) return;
            boolean verified = result != null && !result.indeterminate;
            String txn = result != null && result.committed ? "transaction_committed"
                    : verified ? "rollback_complete" : "rollback_unverified";
            terminalVerified = verified;
            if (state != State.READY) outcome = "not_ready";
            LogHolder.log("event=transaction_terminal cleanup=" + (verified ? "verified" : "unverified")
                    + " result=" + txn + " b_stage=" + state.name().toLowerCase(java.util.Locale.ROOT));
            state = State.TERMINAL;
            notifyAll();
        }

        boolean cleanupVerified(Context context, ITaskService service) {
            final int expectedA, expectedB, expectedDisplay;
            final String selectedIdentity, expectedABase, expectedBBase;
            synchronized (this) {
                if (state != State.TERMINAL || !terminalVerified) return false;
                expectedA = aTask; expectedB = bTask; expectedDisplay = displayId;
                selectedIdentity = identity; expectedABase = aBase; expectedBBase = bBase;
            }
            try {
                SharedPreferences owner = context.getSharedPreferences("platform_display_ownership", Context.MODE_PRIVATE);
                boolean ownerCleared = !owner.contains("physical_projection_task")
                        && !owner.getBoolean("physical_projection_controlled", false)
                        && !owner.getBoolean("physical_projection_keeper_start_pending", false)
                        && !owner.getBoolean("physical_projection_keeper_start_claimed", false)
                        && !owner.getBoolean("physical_projection_closing", false)
                        && !owner.contains("physical_replacement_token");
                return ownerCleared
                        && exactMain(service.queryExactTaskPlacement(expectedA), expectedA, expectedABase,
                                expectedDisplay, selectedIdentity)
                        && exactMain(service.queryExactTaskPlacement(expectedB), expectedB, expectedBBase,
                                expectedDisplay, selectedIdentity);
            } catch (Exception | LinkageError unknown) { return false; }
        }

        synchronized void clear() {
            if (state == State.BOTTOM_DISPATCHING || state == State.BOTTOM_VERIFIED
                    || state == State.FOCUS_DISPATCHING || state == State.FOCUS_SENT || state == State.READY) {
                licenseValid = false;
                notifyAll();
                return;
            }
            state = State.EMPTY; licenseValid = false; terminalVerified = false; outcome = null;
            aTask = 0; bTask = 0; expectedBTask = 0; displayId = 0; aGeneration = 0L;
            aProcessPid = 0; bProcessPid = 0;
            identity = null; aPackage = null; aBase = null; bPackage = null; bBase = null; operationId = null;
            notifyAll();
        }

        private boolean matches(int a, long generation, int b, int display, String selectedIdentity, String operation) {
            return aTask == a && aGeneration == generation && bTask == b && displayId == display
                    && eq(identity, selectedIdentity) && eq(operationId, operation);
        }

        private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
        private static boolean exactMain(String raw, int task, String base, int display, String identity)
                throws org.json.JSONException {
            if (raw == null) return false;
            JSONObject row = new JSONObject(raw);
            int separator = identity.indexOf(':');
            if (separator <= 0 || separator == identity.length() - 1) return false;
            int selectedId;
            try { selectedId = Integer.parseInt(identity.substring(0, separator)); }
            catch (NumberFormatException invalid) { return false; }
            int slash = base.indexOf('/');
            return "present".equals(row.optString("status")) && row.optInt("task", -1) == task
                    && row.optInt("display", -1) == 0 && row.optInt("selectedId", -1) == selectedId
                    && identity.substring(separator + 1).equals(row.optString("selectedUnique", ""))
                    && row.optBoolean("rootSnapshotKnown", false) && row.optBoolean("rootSole", false)
                    && slash > 0 && base.substring(0, slash).equals(row.optString("package", ""))
                    && base.equals(row.optString("base", "")) && display == selectedId;
        }

        private static final class LogHolder {
            static void log(String suffix) {
                android.util.Log.i("ControlledPhysicalCast", "physical_b_focus_transfer v=1 lane=b " + suffix);
            }
        }
    }
}
