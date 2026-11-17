package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import android.view.Display;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class TaskServicePhysicalPlacementTest {
    @Test public void explicitMixedReturnDispatchRejectsForeignRearAndLiveCandidate() throws Exception {
        String aBase = "a.pkg/a.pkg.Main", bBase = "b.pkg/b.pkg.Main";
        RearDisplayCastOccupancy.Task a = new RearDisplayCastOccupancy.Task(
                41, "a.pkg", "a.pkg", aBase, aBase, 0, true, true, false);
        RearDisplayCastOccupancy.Task b = new RearDisplayCastOccupancy.Task(
                42, "b.pkg", "b.pkg", bBase, bBase, 0, true, true, false);
        RearDisplayCastOccupancy.Snapshot main = snapshot(0, b);
        RearDisplayCastOccupancy.Snapshot rear = snapshot(4, a);
        JSONObject stopped = new JSONObject().put("state", "stopped").put("managerResourcesStopped", true)
                .put("managerInput", new JSONObject().put("state", "stopped")
                        .put("resourcesStopped", true).put("outputAvailable", false));
        assertTrue(TaskService.canDispatchVirtualRollbackReturn(main, rear, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase, stopped));
        assertFalse(TaskService.canDispatchVirtualRollbackReturn(main, snapshot(4, a, b), 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase, stopped));
        assertFalse(TaskService.canDispatchVirtualRollbackReturn(snapshot(0), rear, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase, stopped));
        assertFalse(TaskService.canDispatchVirtualRollbackReturn(main, rear, 4,
                41, "a.pkg", aBase, 42, "b.pkg", "b.pkg/b.pkg.Other", stopped));
        assertFalse(TaskService.canDispatchVirtualRollbackReturn(main, rear, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase, stopped.put("managerResourcesStopped", false)));
    }

    @Test public void physicalAFirstMoveDispatchRequiresExactTwoTaskRearAndNoDuplicateMainRows() {
        String aBase = "a.pkg/a.pkg.Main", bBase = "b.pkg/b.pkg.Main";
        RearDisplayCastOccupancy.Task aHidden = new RearDisplayCastOccupancy.Task(
                41, "a.pkg", "a.pkg", aBase, aBase, 0, false, false, false);
        RearDisplayCastOccupancy.Task bVisible = new RearDisplayCastOccupancy.Task(
                42, "b.pkg", "b.pkg", bBase, bBase, 0, true, true, false);
        RearDisplayCastOccupancy.Snapshot rear = snapshot(4, aHidden, bVisible);
        RearDisplayCastOccupancy.Snapshot main = snapshot(0);
        assertTrue(TaskService.canDispatchPhysicalReplacementAReturn(rear, main, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementAReturn(snapshot(4, aHidden, bVisible,
                new RearDisplayCastOccupancy.Task(43, "foreign", "foreign", "foreign/foreign.Main",
                        "foreign/foreign.Main", 0, true, true, false)), main, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementAReturn(rear, snapshot(0, aHidden), 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementAReturn(rear, snapshot(0, bVisible), 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementAReturn(snapshot(4, aHidden,
                new RearDisplayCastOccupancy.Task(42, "foreign", "foreign", bBase, bBase, 0, true, true, false)),
                main, 4, 41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementAReturn(snapshot(4,
                new RearDisplayCastOccupancy.Task(41, "a.pkg", "a.pkg", aBase, aBase, 0, true, true, false),
                bVisible), main, 4, 41, "a.pkg", aBase, 42, "b.pkg", bBase));
    }

    @Test public void physicalARestoreDispatchRequiresBMainAndRearEmptyOrExactLauncher() {
        String aBase = "a.pkg/a.pkg.Main", bBase = "b.pkg/b.pkg.Main";
        RearDisplayCastOccupancy.Task a = new RearDisplayCastOccupancy.Task(
                41, "a.pkg", "a.pkg", aBase, aBase, 0, true, true, false);
        RearDisplayCastOccupancy.Task b = new RearDisplayCastOccupancy.Task(
                42, "b.pkg", "b.pkg", bBase, bBase, 0, true, true, false);
        RearDisplayCastOccupancy.Snapshot main = snapshot(0, a, b);
        RearDisplayCastOccupancy.Snapshot rear = snapshot(4);
        assertTrue(TaskService.canDispatchPhysicalReplacementRestore(main, rear, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementRestore(snapshot(0, a), rear, 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementRestore(main, snapshot(4, a), 4,
                41, "a.pkg", aBase, 42, "b.pkg", bBase));
        assertFalse(TaskService.canDispatchPhysicalReplacementRestore(snapshot(0, a,
                new RearDisplayCastOccupancy.Task(42, "foreign", "foreign", bBase, bBase, 0, true, true, false)),
                rear, 4, 41, "a.pkg", aBase, 42, "b.pkg", bBase));
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int display, RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(display, Display.STATE_ON, Arrays.asList(tasks),
                "activity_task", null);
    }

    @Test public void taskSnapshotMissesAreUnknownNotProofOfAbsence() {
        assertEquals("unknown", TaskService.exactTaskSnapshotDisposition(Collections.emptyList(), 81));
        assertEquals("unknown", TaskService.exactTaskSnapshotDisposition(null, 81));
        assertEquals("unknown", TaskService.exactTaskSnapshotDisposition(
                Arrays.asList(new SceneTaskIdentity.Record(82, 4, "other/.Main", null)), 81)); // truncated/filtered miss
    }

    @Test public void malformedNullDuplicateAndUnidentifiableRowsFailClosed() {
        List<SceneTaskIdentity.Record> nullRow = Arrays.asList((SceneTaskIdentity.Record) null);
        assertEquals("unknown", TaskService.exactTaskSnapshotDisposition(nullRow, 81));
        assertEquals("unknown", TaskService.exactTaskSnapshotDisposition(
                Arrays.asList(new SceneTaskIdentity.Record(81, 4, null, null)), 81));
        assertEquals("unknown", TaskService.exactTaskSnapshotDisposition(Arrays.asList(
                new SceneTaskIdentity.Record(81, 4, "reader/.Main", null),
                new SceneTaskIdentity.Record(81, 4, "reader/.Main", null)), 81));
    }

    @Test public void oneExactTypedRowProvesPresence() {
        assertEquals("present", TaskService.exactTaskSnapshotDisposition(
                Arrays.asList(new SceneTaskIdentity.Record(81, 4, "reader/.Main", "reader/.Main")), 81));
    }
}
