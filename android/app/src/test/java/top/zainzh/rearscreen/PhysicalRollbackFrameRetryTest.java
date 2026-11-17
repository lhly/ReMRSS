package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import android.view.Display;

public final class PhysicalRollbackFrameRetryTest {
    private static final String FAILURE = "replacement_readiness_timeout_or_unknown";
    private static final String PACKAGE = "dev.remrss.sample.rc5physicala";
    private static final String BASE = PACKAGE + "/dev.remrss.physical.MainActivity";
    private static final String IDENTITY = "1:rear";

    @Test public void exactDrawTransitionFailureGetsOneReadOnlyRetryAfterExactGuards() throws Exception {
        JSONObject first = unknown("window_not_drawn_and_shown");
        JSONObject second = ready();
        FakePort port = new FakePort(true, second);

        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                FAILURE, first, port);

        assertSame(second, result);
        assertEquals(1, port.revalidations);
        assertEquals(1, port.reads);
        assertEquals(Arrays.asList("revalidate", "exact_query", "local_revalidate"), port.events);
        assertTrue(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                result, 58085, PACKAGE, BASE, 1, IDENTITY));
    }

    @Test public void reservationRevokedWhileExactReadIsBlockedDiscardsItsReply() throws Exception {
        JSONObject first = unknown("window_not_drawn_and_shown");
        JSONObject replyAfterRevocation = ready();
        FakePort port = new FakePort(true, replyAfterRevocation);
        port.revokeDuringRead = true;

        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                FAILURE, first, port);

        assertSame(first, result);
        assertEquals(1, port.reads);
        assertEquals(Arrays.asList("revalidate", "exact_query", "local_revalidate"), port.events);
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                result, 58085, PACKAGE, BASE, 1, IDENTITY));
    }

    @Test public void retryCommitRequiresTheOriginalExactAOwnerAndNeverAcceptsBOrOwnerAbsent() {
        assertTrue(ControlledPhysicalCastOperations.retryRollbackOwnerStateAllowsCommit(
                12L, 0L, 12L));
        assertFalse(ControlledPhysicalCastOperations.retryRollbackOwnerStateAllowsCommit(
                12L, 22L, 12L));
        assertFalse(ControlledPhysicalCastOperations.retryRollbackOwnerStateAllowsCommit(
                0L, 0L, 12L));
        assertFalse(ControlledPhysicalCastOperations.retryRollbackOwnerStateAllowsCommit(
                11L, 0L, 12L));
    }

    @Test public void otherFailureReasonsDoNotReadOrRetry() throws Exception {
        JSONObject first = unknown("input_flags_rejected");
        FakePort port = new FakePort(true, ready());

        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                FAILURE, first, port);

        assertSame(first, result);
        assertEquals(0, port.revalidations);
        assertEquals(0, port.reads);
    }

    @Test public void changedJournalOwnerOrTopologyVetoesRetry() throws Exception {
        JSONObject first = unknown("window_not_drawn_and_shown");
        FakePort port = new FakePort(false, ready());

        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                FAILURE, first, port);

        assertSame(first, result);
        assertEquals(1, port.revalidations);
        assertEquals(0, port.reads);
    }

    @Test public void otherTransactionFailureDoesNotEnterDrawRetryPath() throws Exception {
        JSONObject first = unknown("window_not_drawn_and_shown");
        FakePort port = new FakePort(true, ready());

        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                "replacement_readiness_changed", first, port);

        assertSame(first, result);
        assertEquals(0, port.revalidations);
        assertEquals(0, port.reads);
    }

    @Test public void retryWithUnknownInputConfigRemainsUnverified() throws Exception {
        JSONObject first = unknown("window_not_drawn_and_shown");
        JSONObject second = unknown("input_flags_rejected");
        FakePort port = new FakePort(true, second);

        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                FAILURE, first, port);

        assertSame(second, result);
        assertEquals(1, port.reads);
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                result, 58085, PACKAGE, BASE, 1, IDENTITY));
    }

    @Test public void exactKnownAInventoryCanAuthorizeOneReadOnlyDrawObservationButNotOwnerCommit()
            throws Exception {
        JSONObject preDrawInventory = rearSnapshot(aRow(false, true, false));
        assertTrue(ControlledPhysicalCastOperations.exactPhysicalTaskObservationSnapshotWithHiddenLauncher(
                preDrawInventory, 58085, PACKAGE, BASE, 1, (name, user) -> true));
        assertFalse(ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                preDrawInventory, 58085, PACKAGE, BASE, 1, (name, user) -> true));
        assertFalse(ControlledPhysicalCastOperations.exactRestoredPhysicalFrameProof(preDrawInventory,
                unknown("window_not_drawn_and_shown"), 58085, PACKAGE, BASE, 1, IDENTITY));

        JSONObject afterDrawInventory = rearSnapshot(aRow(true, true, false));
        JSONObject exactReadyPresentation = ready();
        assertTrue(ControlledPhysicalCastOperations.exactPhysicalTaskObservationSnapshotWithHiddenLauncher(
                afterDrawInventory, 58085, PACKAGE, BASE, 1, (name, user) -> true));
        assertTrue(ControlledPhysicalCastOperations.exactRestoredPhysicalFrameProof(afterDrawInventory,
                exactReadyPresentation, 58085, PACKAGE, BASE, 1, IDENTITY));
    }

    @Test public void retryObservationRejectsUnknownForeignAndActiveOemRows() throws Exception {
        JSONObject unknownA = aRow(false, true, false);
        unknownA.remove("sleeping");
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalTaskObservationSnapshotWithHiddenLauncher(
                rearSnapshot(unknownA), 58085, PACKAGE, BASE, 1, (name, user) -> true));

        JSONObject foreign = new JSONObject().put("task", 61234).put("base", "com.foreign.owner")
                .put("top", "com.foreign.owner").put("baseComponent", "com.foreign.owner/.Main")
                .put("topComponent", "com.foreign.owner/.Main").put("userId", 0)
                .put("visible", false).put("requested", false).put("sleeping", false);
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalTaskObservationSnapshotWithHiddenLauncher(
                rearSnapshot(aRow(true, true, false), foreign), 58085, PACKAGE, BASE, 1,
                (name, user) -> false));

        JSONObject activeLauncher = launcherRow(true, false, false);
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalTaskObservationSnapshotWithHiddenLauncher(
                rearSnapshot(aRow(false, true, false), activeLauncher), 58085, PACKAGE, BASE, 1,
                (name, user) -> true));

        JSONObject hiddenLauncher = launcherRow(false, false, false);
        assertTrue(ControlledPhysicalCastOperations.exactPhysicalTaskObservationSnapshotWithHiddenLauncher(
                rearSnapshot(aRow(false, true, false), hiddenLauncher), 58085, PACKAGE, BASE, 1,
                (name, user) -> "com.oem.rearhome/.HomeActivity".equals(name) && user == 0));
    }

    @Test public void secondNoDrawOrUnreadyReadStillCannotPassFullOwnerCommitProof() throws Exception {
        JSONObject first = unknown("window_not_drawn_and_shown");
        JSONObject second = unknown("window_not_drawn_and_shown");
        FakePort port = new FakePort(true, second);
        JSONObject result = ControlledPhysicalCastOperations.retryTransientRollbackOldFrameOnce(
                FAILURE, first, port);
        assertSame(second, result);
        assertEquals(1, port.reads);
        JSONObject knownPreDrawInventory = rearSnapshot(aRow(false, true, false));
        assertFalse(ControlledPhysicalCastOperations.exactRestoredPhysicalFrameProof(knownPreDrawInventory,
                result, 58085, PACKAGE, BASE, 1, IDENTITY));
    }

    private static JSONObject unknown(String reason) throws Exception {
        return new JSONObject().put("status", "unknown").put("reason", reason);
    }

    private static JSONObject ready() throws Exception {
        return new JSONObject().put("status", "ready").put("task", 58085)
                .put("package", PACKAGE).put("base", BASE).put("display", 1)
                .put("selectedId", 1).put("selectedUnique", "rear")
                .put("windowId", "bcbc927").put("drawn", true).put("inputReady", true);
    }

    private static JSONObject rearSnapshot(JSONObject... rows) throws Exception {
        return new JSONObject().put("status", "ok").put("source", "activity_task")
                .put("display", 1).put("power", Display.STATE_ON)
                .put("powerBefore", Display.STATE_ON).put("powerAfter", Display.STATE_ON)
                .put("tasks", new JSONArray(rows));
    }

    private static JSONObject aRow(boolean visible, boolean requested, boolean sleeping) throws Exception {
        return new JSONObject().put("task", 58085).put("base", PACKAGE).put("top", PACKAGE)
                .put("baseComponent", BASE).put("topComponent", BASE).put("userId", 0)
                .put("visible", visible).put("requested", requested).put("sleeping", sleeping);
    }

    private static JSONObject launcherRow(boolean visible, boolean requested, boolean sleeping) throws Exception {
        return new JSONObject().put("task", 61235).put("base", "com.oem.rearhome")
                .put("top", "com.oem.rearhome").put("baseComponent", "com.oem.rearhome/.HomeActivity")
                .put("topComponent", "com.oem.rearhome/.HomeActivity").put("userId", 0)
                .put("visible", visible).put("requested", requested).put("sleeping", sleeping);
    }

    private static final class FakePort implements ControlledPhysicalCastOperations.RollbackOldFrameRetryPort {
        final boolean exactGuards;
        final JSONObject response;
        final List<String> events = new ArrayList<>();
        int revalidations, reads;
        boolean localAuthority = true, revokeDuringRead;
        FakePort(boolean exactGuards, JSONObject response) {
            this.exactGuards = exactGuards; this.response = response;
        }
        @Override public boolean revalidateExactRollbackState() {
            revalidations++; events.add("revalidate"); return exactGuards;
        }
        @Override public boolean revalidateLocalRetryAuthority() {
            events.add("local_revalidate"); return localAuthority;
        }
        @Override public JSONObject queryExactOldPresentation() {
            reads++; events.add("exact_query");
            if (revokeDuringRead) localAuthority = false;
            return response;
        }
    }
}
