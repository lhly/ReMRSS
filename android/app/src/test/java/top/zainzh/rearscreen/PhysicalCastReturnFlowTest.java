package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class PhysicalCastReturnFlowTest {
    @Test public void physicalReturnRequiresThisReplacementAndItsLiveReader() throws Exception {
        JSONObject input = new JSONObject().put("id", "inputB").put("generation", 7L).put("task", 41)
                .put("display", 12).put("state", "running").put("readerLifecycle", "READY")
                .put("outputAvailable", true);
        JSONObject status = new JSONObject().put("id", "castB").put("generation", 8L)
                .put("coordinatorOperationId", "opB").put("ownerGeneration", 5L).put("ownerIdentity", "ownerB")
                .put("rearDisplayId", 1).put("rearIdentity", "1:rear").put("task", 41).put("display", 12)
                .put("baseComponent", "test.b/test.b.Content").put("intendedBaseComponent", "test.b/test.b.Content")
                .put("readerId", "inputB").put("readerGeneration", 7L).put("readerLifecycle", "READY")
                .put("state", "running").put("outputAvailable", true).put("input", input);
        assertTrue(matchesReturningB(status));
        for (String field : new String[]{"id", "generation", "ownerGeneration", "ownerIdentity", "task",
                "rearIdentity", "readerId", "readerGeneration", "state", "outputAvailable", "baseComponent"}) {
            assertFalse(field, matchesReturningB(new JSONObject(status.toString()).put(field, "stale")));
        }
        for (String field : new String[]{"id", "generation", "task", "display", "state", "outputAvailable"}) {
            assertFalse(field, matchesReturningB(new JSONObject(status.toString())
                    .put("input", new JSONObject(input.toString()).put(field, "stale"))));
        }
    }

    private static boolean matchesReturningB(JSONObject state) {
        return VirtualCastTerminalStatus.matchesRunningPhysicalReplacement(state, 1, "1:rear", "opB", 5L,
                "ownerB", "castB", 8L, 41, "test.b/test.b.Content", "inputB", 7L);
    }

    @Test public void livePhysicalReplacementReaderMustMatchActualProxy() throws Exception {
        JSONObject proxy = new JSONObject().put("id", "readerB").put("generation", 9L)
                .put("task", 12).put("display", 22).put("state", "running").put("outputAvailable", true);
        assertTrue(VirtualCastTerminalStatus.physicalReaderStatus("READY", "readerB", 9L, 12, 22,
                proxy).getBoolean("outputAvailable"));
        assertFalse(VirtualCastTerminalStatus.physicalReaderStatus("READY", "readerB", 9L, 12, 22,
                new JSONObject(proxy.toString()).put("outputAvailable", false)).getBoolean("outputAvailable"));
        for (String key : new String[]{"id", "generation", "task", "display", "state", "outputAvailable"}) {
            JSONObject different = new JSONObject(proxy.toString()).put(key, "changed");
            JSONObject result = VirtualCastTerminalStatus.physicalReaderStatus("READY", "readerB", 9L, 12, 22,
                    different);
            assertEquals(key, "unknown", result.getString("state"));
            assertFalse(key, result.getBoolean("outputAvailable"));
        }
    }

    @Test public void noSessionStatusRequiresCoherentIdleManagerAndStoppedInput() throws Exception {
        JSONObject oldStopped = new JSONObject("{\"state\":\"stopped\"}");
        JSONObject stoppedInput = new JSONObject("{\"state\":\"stopped\",\"resourcesStopped\":true,\"outputAvailable\":false}");
        JSONObject idle = VirtualCastTerminalStatus.noSessionManagerStatus(
                oldStopped, stoppedInput, true, true, true);
        assertTrue(idle.optBoolean("managerResourcesStopped"));
        assertEquals("stopped", idle.optJSONObject("managerInput").optString("state"));
        assertFalse(idle.has("resourcesStopped"));
        assertTrue(idle.has("managerResourcesStopped"));

        assertFalse(VirtualCastTerminalStatus.noSessionManagerStatus(
                oldStopped, stoppedInput, true, false, true).optBoolean("managerResourcesStopped"));
        assertFalse(VirtualCastTerminalStatus.noSessionManagerStatus(
                oldStopped, stoppedInput, true, true, false).optBoolean("managerResourcesStopped"));
        assertFalse(VirtualCastTerminalStatus.noSessionManagerStatus(oldStopped,
                new JSONObject("{\"state\":\"running\",\"resourcesStopped\":false,\"outputAvailable\":true}"),
                true, true, true).optBoolean("managerResourcesStopped"));
        assertFalse(VirtualCastTerminalStatus.noSessionManagerStatus(
                new JSONObject("{\"state\":\"unknown\"}"), stoppedInput, true, true, true)
                .optBoolean("managerResourcesStopped"));

        JSONObject priorTerminal = new JSONObject("{\"state\":\"stopped\",\"id\":\"virtual-A\","
                + "\"outputAvailable\":false,\"input\":{\"state\":\"stopped\","
                + "\"readerLifecycle\":\"NO_READER\",\"task\":57724,\"display\":1}}");
        JSONObject augmented = VirtualCastTerminalStatus.noSessionManagerStatus(
                priorTerminal, stoppedInput, true, true, true);
        assertEquals("virtual-A", augmented.optString("id"));
        assertEquals("NO_READER", augmented.optJSONObject("input").optString("readerLifecycle"));
        assertEquals(57724, augmented.optJSONObject("input").optInt("task"));
        assertEquals("stopped", augmented.optJSONObject("managerInput").optString("state"));
        assertTrue(augmented.optBoolean("managerResourcesStopped"));
    }

    private static final String TARGET = "dev.remrss.sample.consumer:81";

    @Test public void actualResourceTerminalSerializerFeedsExactP2vRollbackConsumer() throws Exception {
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal journal =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "ACCEPTED_VIRTUAL", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "virtual-b", true, 9L, "owner-b", 12L,
                        "", "RELEASED", "input-b", 1L, "NONE");
        JSONObject input = VirtualCastTerminalStatus.write(new JSONObject().put("id", "input-b")
                .put("task", 42).put("generation", 1L).put("display", 18), true, true, true);
        JSONObject cast = VirtualCastTerminalStatus.write(new JSONObject().put("id", "virtual-b")
                .put("coordinatorOperationId", "op").put("ownerGeneration", 9L).put("ownerIdentity", "owner-b")
                .put("generation", 12L).put("task", 42).put("package", "b.pkg")
                .put("baseComponent", "b.pkg/.Main").put("intendedBaseComponent", "b.pkg/.Main")
                .put("rearDisplayId", 4).put("rearIdentity", "4:rear")
                .put("display", 18).put("readerLifecycle", "RELEASED").put("readerId", "input-b")
                .put("readerGeneration", 1L)
                .put("input", input), true, true, true);
        assertTrue(ControlledPhysicalCastOperations.matchesVirtualTerminal(cast, journal));
        JSONObject augmentedCast = VirtualCastTerminalStatus.noSessionManagerStatus(cast,
                new JSONObject("{\"state\":\"stopped\",\"resourcesStopped\":true,\"outputAvailable\":false}"),
                true, true, true);
        assertTrue("manager readback must not overwrite exact released reader ownership",
                ControlledPhysicalCastOperations.matchesVirtualTerminal(augmentedCast, journal));

        JSONObject liveInput = VirtualCastTerminalStatus.write(new JSONObject().put("id", "input-b")
                .put("task", 42).put("generation", 1L).put("display", 18), true, false, true);
        JSONObject incomplete = VirtualCastTerminalStatus.write(new JSONObject().put("id", "virtual-b")
                .put("coordinatorOperationId", "op").put("ownerGeneration", 9L).put("ownerIdentity", "owner-b")
                .put("generation", 12L).put("task", 42).put("package", "b.pkg")
                .put("baseComponent", "b.pkg/.Main").put("intendedBaseComponent", "b.pkg/.Main")
                .put("rearDisplayId", 4).put("rearIdentity", "4:rear")
                .put("display", 18).put("readerLifecycle", "RELEASED").put("readerId", "input-b")
                .put("readerGeneration", 1L)
                .put("input", liveInput), true, false, true);
        assertEquals("stopping", liveInput.optString("state"));
        assertTrue(liveInput.optBoolean("outputAvailable"));
        assertFalse(ControlledPhysicalCastOperations.matchesVirtualTerminal(incomplete, journal));
    }

    @Test public void managerReadbackPreservesExactNoReaderTerminalForOriginalP2vConsumer() throws Exception {
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal journal =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "ACCEPTED_VIRTUAL", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "virtual-b", true, 9L, "owner-b", 12L,
                        "", "NO_READER", "", 0L, "NONE");
        JSONObject oldNoReader = new JSONObject().put("state", "stopped").put("outputAvailable", false)
                .put("id", "virtual-b").put("coordinatorOperationId", "op")
                .put("ownerGeneration", 9L).put("ownerIdentity", "owner-b").put("generation", 12L)
                .put("task", 42).put("package", "b.pkg").put("baseComponent", "b.pkg/.Main")
                .put("intendedBaseComponent", "b.pkg/.Main").put("rearDisplayId", 4)
                .put("rearIdentity", "4:rear").put("display", 18).put("readerLifecycle", "NO_READER")
                .put("readerId", "").put("readerGeneration", 0L)
                .put("input", VirtualCastTerminalStatus.physicalReaderStatus("NO_READER", "", 0L,
                        42, 18, new JSONObject("{\"state\":\"stopped\",\"outputAvailable\":false}")));
        JSONObject augmented = VirtualCastTerminalStatus.noSessionManagerStatus(oldNoReader,
                new JSONObject("{\"state\":\"stopped\",\"resourcesStopped\":true,\"outputAvailable\":false}"),
                true, true, true);
        assertEquals("NO_READER", augmented.optString("readerLifecycle"));
        assertEquals(42, augmented.optJSONObject("input").optInt("task"));
        assertTrue(augmented.optBoolean("managerResourcesStopped"));
        assertTrue(ControlledPhysicalCastOperations.matchesVirtualTerminal(augmented, journal));
    }

    private static final class FakePort implements PhysicalCastReturnFlow.Port {
        final List<String> events = new ArrayList<>();
        boolean onRear = true;
        boolean moveSucceeds = true;
        boolean exactMain = true;
        boolean launcherSafe = true;
        boolean launcherRestoreSucceeds = true;
        boolean launcherRestoreThrows;
        boolean keeperStopSucceeds = true;
        boolean strict;
        boolean clearSucceeds = true;
        PhysicalCastReturnFlow.TaskState exactState = PhysicalCastReturnFlow.TaskState.REAR;

        @Override public boolean isExactTaskOnRear(int taskId, String packageName) {
            events.add("check:" + taskId + ":" + packageName);
            return onRear;
        }
        @Override public boolean moveExactTaskToMain(int taskId) {
            events.add("move:" + taskId);
            if (moveSucceeds) { onRear = false; exactState = PhysicalCastReturnFlow.TaskState.MAIN; }
            return moveSucceeds;
        }
        @Override public boolean isExactTaskOnMain(int taskId, String packageName) { events.add("check_main:" + taskId + ":" + packageName); return exactMain; }
        @Override public boolean mayRestoreLauncher() { events.add("restore_gate"); return launcherSafe; }
        @Override public boolean restoreLauncher() {
            events.add("restore_launcher");
            if (launcherRestoreThrows) throw new IllegalStateException("ITaskService transaction failed");
            return launcherRestoreSucceeds;
        }
        @Override public boolean stopKeeperForExactTask(String task, long generation) {
            events.add("stop_keeper:" + task + ":" + generation);
            return keeperStopSucceeds;
        }
        @Override public void clearExactOwnership(int taskId, long generation) { events.add("clear:" + taskId + ":" + generation); }
        @Override public boolean isStrictControlledReturn() { return strict; }
        @Override public PhysicalCastReturnFlow.TaskState inspectExactTask(int taskId, String pkg) {
            events.add("inspect:" + exactState);
            return exactState;
        }
        @Override public boolean clearExactOwnershipVerified(int taskId, long generation) {
            events.add("clear_cas:" + taskId + ":" + generation);
            return clearSucceeds;
        }
    }

    @Test public void directPhysicalReadyRequiresExactVisibleRequestedAwakeTaskOnPoweredRear() throws Exception {
        JSONObject snapshot = new JSONObject("{\"status\":\"ok\",\"source\":\"activity_task\",\"display\":7,"
                + "\"power\":2,\"powerBefore\":2,\"powerAfter\":2,\"tasks\":[{\"task\":81,"
                + "\"base\":\"dev.remrss.sample.consumer\",\"top\":\"dev.remrss.sample.consumer\","
                + "\"visible\":true,\"requested\":true,\"sleeping\":false}]}");
        assertTrue(ControlledPhysicalCastOperations.isVisibleTaskSnapshot(snapshot, 81,
                "dev.remrss.sample.consumer", 7));
    }

    @Test public void directPhysicalReadinessRejectsHiddenUnknownAndAmbiguousRows() throws Exception {
        String prefix = "{\"status\":\"ok\",\"source\":\"activity_task\",\"display\":7,\"power\":2,\"powerBefore\":2,\"powerAfter\":2,\"tasks\":[";
        String target = "{\"task\":81,\"base\":\"dev.remrss.sample.consumer\",\"top\":\"dev.remrss.sample.consumer\",\"visible\":true,\"requested\":true,\"sleeping\":false}";
        assertFalse(ControlledPhysicalCastOperations.isVisibleTaskSnapshot(new JSONObject(prefix +
                target.replace("\"visible\":true", "\"visible\":false") + "]}"), 81,
                "dev.remrss.sample.consumer", 7));
        assertFalse(ControlledPhysicalCastOperations.isVisibleTaskSnapshot(new JSONObject(prefix +
                target.replace("\"requested\":true", "\"requested\":null") + "]}"), 81,
                "dev.remrss.sample.consumer", 7));
        assertFalse(ControlledPhysicalCastOperations.isVisibleTaskSnapshot(new JSONObject(prefix + target + "," + target + "]}"),
                81, "dev.remrss.sample.consumer", 7));
        assertFalse(ControlledPhysicalCastOperations.isVisibleTaskSnapshot(new JSONObject((prefix + target + "]}")
                .replace("\"powerAfter\":2", "\"powerAfter\":0")), 81,
                "dev.remrss.sample.consumer", 7));
    }

    @Test public void committedTaskRemainsReturnableWhenKeeperWasNeverCreated() throws Exception {
        FakePort port = new FakePort();
        PhysicalCastReturnFlow.Result result = PhysicalCastReturnFlow.stop(TARGET, false, false, 14L, port);

        assertEquals(PhysicalCastReturnFlow.Result.STOPPED, result);
        assertEquals("check:81:dev.remrss.sample.consumer,move:81,check_main:81:dev.remrss.sample.consumer,clear:81:14",
                String.join(",", port.events));
        assertFalse("fallback return does not touch Launcher state it never disabled",
                port.events.contains("restore_launcher"));
    }

    @Test public void launcherIsRestoredOnlyForRecordedOwnerAndOnlyWhenRearHasNoNewOwner() throws Exception {
        FakePort owned = new FakePort();
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, true, 14L, owned));
        assertTrue(owned.events.contains("restore_launcher"));

        FakePort newerOwner = new FakePort();
        newerOwner.launcherSafe = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, true, 14L, newerOwner));
        assertFalse("a newer rear owner keeps its Launcher state", newerOwner.events.contains("restore_launcher"));
        assertFalse("teardown will not make a second restore decision", newerOwner.events.contains("restore_launcher"));
    }

    @Test public void failedMovePreservesKeeperAndCommittedReturnRecord() throws Exception {
        FakePort port = new FakePort();
        port.moveSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.MOVE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("stop_keeper:")));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("clear:")));
        assertFalse(port.events.contains("restore_launcher"));
    }

    @Test public void unverifiedReturnMovePreservesKeeperAndOwnerRecord() throws Exception {
        FakePort port = new FakePort();
        port.exactMain = false;
        assertEquals(PhysicalCastReturnFlow.Result.MOVE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("stop_keeper:")));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("clear:")));
        assertFalse(port.events.contains("restore_launcher"));
    }

    @Test public void vanishedTaskCanStillReleaseOwnedLauncherButCannotMoveAReusedTask() throws Exception {
        FakePort port = new FakePort();
        port.onRear = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, true, 14L, port));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("move:")));
        assertTrue(port.events.contains("restore_launcher"));
        assertTrue(port.events.contains("clear:81:14"));
    }

    @Test public void failedRestoreStillStopsExactKeeperButRetainsCleanupRecordForRetry() throws Exception {
        FakePort port = new FakePort();
        port.launcherRestoreSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.RESTORE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertTrue("authorized same-owner Keeper teardown still runs", port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse("failed launcher restore keeps the precise cleanup record", port.events.stream().anyMatch(e -> e.startsWith("clear:")));
    }

    @Test public void thrownPrivilegedLauncherRestoreStillStopsKeeperAndRetainsExactCleanupRecord() throws Exception {
        FakePort port = new FakePort();
        port.launcherRestoreThrows = true;
        assertEquals(PhysicalCastReturnFlow.Result.RESTORE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertTrue("restore RemoteException must not bypass same-generation Keeper teardown",
                port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse("the exact launcher cleanup record remains available for retry",
                port.events.stream().anyMatch(event -> event.startsWith("clear:")));
    }

    @Test public void failedActualKeeperStopDoesNotClearEffectiveOwnerRecordOrReportStopped() throws Exception {
        FakePort port = new FakePort();
        port.keeperStopSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOP_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, false, 14L, port));
        assertFalse(port.events.stream().anyMatch(e -> e.startsWith("clear:")));
    }

    @Test public void refusedLauncherRestoreIsCarriedThroughSameOwnerTeardownWithoutClearingRetryRecord() throws Exception {
        FakePort port = new FakePort();
        port.launcherSafe = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertTrue(port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse("preserve newer owner and its launcher state", port.events.contains("restore_launcher"));
        assertFalse("retain a generation-scoped launcher cleanup record for a later safe return", port.events.stream().anyMatch(e -> e.startsWith("clear:")));
    }

    @Test public void controlledUnknownPlacementNeverReportsStoppedOrClearsRecord() throws Exception {
        FakePort port = new FakePort(); port.strict = true;
        port.exactState = PhysicalCastReturnFlow.TaskState.UNKNOWN;
        assertEquals(PhysicalCastReturnFlow.Result.INDETERMINATE,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("clear")));
        assertFalse(port.events.contains("restore_launcher"));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("stop_keeper:")));
    }

    @Test public void controlledReturnRequiresSuccessfulExactOwnershipCas() throws Exception {
        FakePort port = new FakePort(); port.strict = true; port.clearSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.INDETERMINATE,
                PhysicalCastReturnFlow.stop(TARGET, false, false, 14L, port));
        assertTrue(port.events.contains("move:81"));
        assertTrue(port.events.stream().anyMatch(event -> event.startsWith("clear_cas:")));
    }

    @Test public void controlledAlreadyAbsentTaskCanBeReleasedOnlyAfterConfirmedCleanup() throws Exception {
        FakePort port = new FakePort(); port.strict = true;
        port.exactState = PhysicalCastReturnFlow.TaskState.ABSENT;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, false, 14L, port));
        assertFalse(port.events.contains("move:81"));
        assertTrue(port.events.stream().anyMatch(event -> event.startsWith("clear_cas:")));
    }

    @Test public void controlledExactMainTaskCanBeClosedInPlaceWithoutAnotherMove() throws Exception {
        FakePort port = new FakePort(); port.strict = true;
        port.exactState = PhysicalCastReturnFlow.TaskState.MAIN;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, true, false, 14L, port));
        assertFalse("an already-returned exact task must not be moved again", port.events.contains("move:81"));
        assertTrue(port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertTrue(port.events.stream().anyMatch(event -> event.startsWith("clear_cas:")));
    }

    @Test public void claimedKeeperMustBeStoppedExactlyBeforeOwnerCasCanComplete() throws Exception {
        FakePort port = new FakePort(); port.strict = true; port.keeperStopSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.INDETERMINATE,
                PhysicalCastReturnFlow.stop(TARGET, true, false, 14L, port));
        assertTrue(port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("clear_cas:")));
    }
}
