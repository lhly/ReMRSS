package top.zainzh.rearscreen;

import static org.junit.Assert.*;

import android.view.Display;
import java.util.Arrays;
import org.junit.Test;

public class ControlledCastStandbyGateTest {
    private static final String LAUNCHER =
            "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";

    @Test public void exactHiddenOemDesktopIsTheOnlyOffStandbyRowAllowed() {
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, false, false, false))));
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_ON, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, true, false))));
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, false, true))));
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, false, false, true))));
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_DOZE, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, false, false, true))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, null, false, true))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_DOZE, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, false, null))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF,
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, false, true),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, false, true))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF,
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, false, true),
                task("third.party", "third.party", "third.party/.MainActivity", "third.party/.MainActivity", false, false, false))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "third.party", "third.party", "third.party/.MainActivity", "third.party/.MainActivity", false, false, false))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
                "com.xiaomi.subscreencenter/.SettingsActivity", "com.xiaomi.subscreencenter/.SettingsActivity", false, false, false))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, true, false, false))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_OFF, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, false, null, false))));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_ON, task(
                "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER,
                "com.xiaomi.subscreencenter/.SettingsActivity", true, true, false))));
    }

    @Test public void unstablePowerAndUnknownSnapshotFailClosed() {
        assertFalse(TaskService.safeIdleStandbySnapshot(new RearDisplayCastOccupancy.Snapshot(
                7, Display.STATE_OFF, Display.STATE_ON, Display.STATE_OFF, Arrays.asList(), "activity_task", null)));
        assertFalse(TaskService.safeIdleStandbySnapshot(new RearDisplayCastOccupancy.Snapshot(
                7, Display.STATE_OFF, Display.STATE_OFF, Display.STATE_OFF, Arrays.asList(), "activity_task", "unavailable")));
    }

    @Test public void onlyStableKnownIdleOrOnPowerIsAccepted() {
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_DOZE)));
        assertTrue(TaskService.safeIdleStandbySnapshot(snapshot(Display.STATE_ON)));
        assertFalse(TaskService.safeIdleStandbySnapshot(snapshot(0)));
    }

    @Test public void controlledStartAllowsExactHostAndAtMostOneExactOemRowWithKnownVisibility() {
        String host = "top.zainzh.rearscreen/.VirtualRearHostActivity";
        RearDisplayCastOccupancy.Snapshot exact = snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER, false, false, false));
        assertTrue(TaskService.safeControlledStartSnapshot(exact, 20, host));
        RearDisplayCastOccupancy.Snapshot visibleButUnrequestedOem = snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        true, false, false));
        assertTrue("only the OEM visibility bit may differ after the verified host launch",
                TaskService.safeControlledStartSnapshot(visibleButUnrequestedOem, 20, host));
        assertFalse(TaskService.safeControlledStartSnapshot(exact, 21, host));
        assertFalse(TaskService.safeControlledStartSnapshot(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, "top.zainzh.rearscreen/.Other", true, true, false)), 20, host));
        assertFalse(TaskService.safeControlledStartSnapshot(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("third.party", "third.party", "third.party/.MainActivity",
                        "third.party/.MainActivity", false, false, false)), 20, host));
        assertFalse("unknown OEM visibility is not proof of safe coexistence", TaskService.safeControlledStartSnapshot(
                snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen",
                        "top.zainzh.rearscreen", host, host, true, true, false),
                        task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                                null, false, false)), 20, host));
        assertFalse("requested OEM launcher remains occupied", TaskService.safeControlledStartSnapshot(
                snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen",
                        "top.zainzh.rearscreen", host, host, true, true, false),
                        task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                                true, true, false)), 20, host));
        assertFalse("sleeping OEM launcher remains occupied", TaskService.safeControlledStartSnapshot(
                snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen",
                        "top.zainzh.rearscreen", host, host, true, true, false),
                        task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                                true, false, true)), 20, host));
        assertFalse("duplicate OEM launcher rows are ambiguous", TaskService.safeControlledStartSnapshot(
                snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen",
                        "top.zainzh.rearscreen", host, host, true, true, false),
                        task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                                false, false, false),
                        task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                                true, false, false)), 20, host));
        assertFalse("duplicate host rows remain ambiguous", TaskService.safeControlledStartSnapshot(
                snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen",
                        "top.zainzh.rearscreen", host, host, true, true, false),
                        new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                                host, host, true, true, false)), 20, host));
    }

    @Test public void controlledStartSnapshotRejectStagesPreserveTheAdmissionBoolean() {
        String host = "top.zainzh.rearscreen/.VirtualRearHostActivity";
        RearDisplayCastOccupancy.Snapshot ready = snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        false, false, false));
        assertSnapshotStage(ready, 20, host, TaskService.ControlledStartSnapshotStage.READY);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        true, false, false)), 20, host, TaskService.ControlledStartSnapshotStage.READY);
        assertSnapshotStage(snapshot(Display.STATE_ON), 20, host,
                TaskService.ControlledStartSnapshotStage.HOST_MISSING);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, "top.zainzh.rearscreen/.Other", true, true, false)), 20, host,
                TaskService.ControlledStartSnapshotStage.HOST_COMPONENT_MISMATCH);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, true)), 20, host,
                TaskService.ControlledStartSnapshotStage.HOST_FLAGS_MISMATCH);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
                        "com.xiaomi.subscreencenter/.Other", "com.xiaomi.subscreencenter/.Other", false, false, false)),
                20, host, TaskService.ControlledStartSnapshotStage.OEM_COMPONENT_MISMATCH);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        false, true, false)), 20, host, TaskService.ControlledStartSnapshotStage.OEM_FLAGS_MISMATCH);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        false, false, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        true, false, false)), 20, host, TaskService.ControlledStartSnapshotStage.OEM_DUPLICATE);
        assertSnapshotStage(snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("third.party", "third.party", "third.party/.MainActivity",
                        "third.party/.MainActivity", false, false, false)),
                20, host, TaskService.ControlledStartSnapshotStage.OTHER_ROW);
    }

    @Test public void physicalReplacementAdmissionRequiresExactLiveAAndKeepsTxn72ContractSeparate() {
        String host = "top.zainzh.rearscreen/.VirtualRearHostActivity";
        String aBase = "com.example.player/com.example.player.MainActivity";
        RearDisplayCastOccupancy.Task hostRow = new RearDisplayCastOccupancy.Task(20,
                "top.zainzh.rearscreen", "top.zainzh.rearscreen", host, host, true, true, false);
        RearDisplayCastOccupancy.Task aRow = new RearDisplayCastOccupancy.Task(41,
                "com.example.player", "com.example.player", aBase, aBase, true, true, false);
        RearDisplayCastOccupancy.Task oem = task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
                LAUNCHER, LAUNCHER, false, false, false);

        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.READY,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow, aRow, oem),
                        20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.OLD_A_MISSING,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow, oem),
                        20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.OLD_A_IDENTITY_MISMATCH,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow,
                        new RearDisplayCastOccupancy.Task(41, "com.example.player", "foreign.app",
                                aBase, "foreign.app/.Main", true, true, false)),
                        20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.READY,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow,
                        new RearDisplayCastOccupancy.Task(41, "com.example.player", "com.example.player",
                                aBase, aBase, false, false, false)), 20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.OLD_A_FLAGS_MISMATCH,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow,
                        new RearDisplayCastOccupancy.Task(41, "com.example.player", "com.example.player",
                                aBase, aBase, false, true, true)), 20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.OLD_A_DUPLICATE,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow, aRow,
                        new RearDisplayCastOccupancy.Task(41, "com.example.player", "com.example.player",
                                aBase, aBase, true, true, false)), 20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.OTHER_ROW,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow, aRow,
                        task("foreign.app", "foreign.app", "foreign.app/.Main", "foreign.app/.Main", true, true, false)),
                        20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.OEM_FLAGS_MISMATCH,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow, aRow,
                        task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                                false, true, false)), 20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.POWER_NOT_STABLE_ON,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_OFF, hostRow, aRow),
                        20, host, 7, 41, "com.example.player", aBase));
        assertEquals(TaskService.PhysicalReplacementStartSnapshotStage.SNAPSHOT_UNKNOWN,
                TaskService.physicalReplacementStartSnapshotRejectStage(snapshot(Display.STATE_ON, hostRow, aRow),
                        20, host, 8, 41, "com.example.player", aBase));
        assertFalse("txn72 remains host + optional OEM only",
                TaskService.safeControlledStartSnapshot(snapshot(Display.STATE_ON, hostRow, aRow), 20, host));
    }

    @Test public void controlledStartDiagnosticReportsOnlySanitizedTypedRowFacts() {
        String host = "top.zainzh.rearscreen/.VirtualRearHostActivity";
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot(Display.STATE_ON,
                new RearDisplayCastOccupancy.Task(20, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                        host, host, true, true, false),
                task("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", LAUNCHER, LAUNCHER,
                        false, true, false),
                task("third.party", "third.party", "third.party/.MainActivity",
                        "third.party/.MainActivity", false, false, false));
        assertEquals("host_exact=true host_rows=1 oem_exact=true oem_rows=1 other_rows=1 "
                        + "duplicate_rows=0 oem_visible=0 oem_requested=1 oem_sleeping=0 "
                        + "oem_flags_consistent=true",
                TaskService.controlledStartSnapshotDiagnostic(snapshot, 20, host));
    }

    private static void assertSnapshotStage(RearDisplayCastOccupancy.Snapshot snapshot, int host,
            String component, TaskService.ControlledStartSnapshotStage expected) {
        TaskService.ControlledStartSnapshotStage actual =
                TaskService.controlledStartSnapshotRejectStage(snapshot, host, component);
        assertEquals(expected, actual);
        assertEquals("diagnostic stage must remain equivalent to the original admission boolean",
                expected == TaskService.ControlledStartSnapshotStage.READY,
                TaskService.safeControlledStartSnapshot(snapshot, host, component));
    }

    private static RearDisplayCastOccupancy.Task task(String base, String top, String component,
            String topComponent,
            Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(9, base, top, component, topComponent, visible, requested, sleeping);
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int power, RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(7, power, power, power,
                Arrays.asList(tasks), "activity_task", null);
    }
}
