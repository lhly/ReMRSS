package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class ControlledLauncherRestorePolicyTest {
    private static final int DISPLAY = 1;
    private static final String COMPONENT = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
    private static final RearDisplayCastOccupancy.ExactLauncherMatcher EXACT_OEM = task ->
            task != null && task.userId == 0 && "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && task.basePackage.equals(task.topPackage) && COMPONENT.equals(task.baseComponent)
                    && COMPONENT.equals(task.topComponent);

    @Test public void exactHiddenOrAsleepOemIsPresentButNeverEmpty() {
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER,
                classify(snapshot(Display.STATE_ON, task(false, false, false))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER,
                classify(snapshot(Display.STATE_ON, task(true, true, false))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER,
                classify(snapshot(Display.STATE_OFF, task(true, false, true))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER,
                classify(snapshot(Display.STATE_DOZE, task(false, false, true))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.EMPTY,
                classify(snapshot(Display.STATE_ON, Collections.emptyList())));
    }

    @Test public void hiddenForeignDuplicateOrUnknownRowsNeverAuthorizeLauncherDispatch() {
        RearDisplayCastOccupancy.Task foreign = new RearDisplayCastOccupancy.Task(22,
                "other.pkg", "other.pkg", "other.pkg/.Main", "other.pkg/.Main", 0,
                false, false, false);
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.UNKNOWN,
                classify(snapshot(Display.STATE_ON, foreign)));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.UNKNOWN,
                classify(snapshot(Display.STATE_ON, task(false, false, false), task(false, false, false))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.UNKNOWN,
                classify(snapshot(Display.STATE_ON, task(false, null, false))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.UNKNOWN,
                classify(snapshot(Display.STATE_ON, task(false, true, false))));
    }

    @Test public void unstablePowerOrTopologyCannotBeTreatedAsEmptyOrOemPresent() {
        RearDisplayCastOccupancy.Snapshot unstable = new RearDisplayCastOccupancy.Snapshot(
                DISPLAY, Display.STATE_ON, Display.STATE_ON, Display.STATE_OFF,
                Collections.emptyList(), "activity_task", null);
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.UNKNOWN,
                classify(unstable));
        assertFalse(RearDisplayCastOccupancy.sameStableTypedSnapshot(
                snapshot(Display.STATE_ON, task(false, false, false)),
                snapshot(Display.STATE_ON, task(true, true, false))));
        assertTrue(RearDisplayCastOccupancy.sameStableTypedSnapshot(
                snapshot(Display.STATE_ON, task(false, false, false)),
                snapshot(Display.STATE_ON, task(false, false, false))));
    }

    @Test public void onlyTwoStableEmptySnapshotsAuthorizeStart() {
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreAction.NO_OP,
                action(snapshot(Display.STATE_ON, task(false, false, false)),
                        snapshot(Display.STATE_ON, task(false, false, false))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreAction.NO_OP,
                action(snapshot(Display.STATE_OFF, task(true, false, true)),
                        snapshot(Display.STATE_OFF, task(true, false, true))));
        assertEquals(RearDisplayCastOccupancy.ControlledLauncherRestoreAction.START,
                action(snapshot(Display.STATE_ON, Collections.emptyList()),
                        snapshot(Display.STATE_ON, Collections.emptyList())));
        assertEquals("hidden foreign tasks never collapse to empty",
                RearDisplayCastOccupancy.ControlledLauncherRestoreAction.REJECT,
                action(snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(22,
                                "other.pkg", "other.pkg", "other.pkg/.Main", "other.pkg/.Main", 0,
                                false, false, false)),
                        snapshot(Display.STATE_ON, new RearDisplayCastOccupancy.Task(22,
                                "other.pkg", "other.pkg", "other.pkg/.Main", "other.pkg/.Main", 0,
                                false, false, false))));
        assertEquals("topology change between empty and an OEM row is rejected",
                RearDisplayCastOccupancy.ControlledLauncherRestoreAction.REJECT,
                action(snapshot(Display.STATE_ON, Collections.emptyList()),
                        snapshot(Display.STATE_ON, task(false, false, false))));
    }

    private static RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition classify(
            RearDisplayCastOccupancy.Snapshot snapshot) {
        return RearDisplayCastOccupancy.controlledLauncherRestoreDisposition(snapshot, DISPLAY, EXACT_OEM);
    }

    private static RearDisplayCastOccupancy.ControlledLauncherRestoreAction action(
            RearDisplayCastOccupancy.Snapshot first, RearDisplayCastOccupancy.Snapshot second) {
        return RearDisplayCastOccupancy.controlledLauncherRestoreAction(first, second, DISPLAY, EXACT_OEM);
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int power, RearDisplayCastOccupancy.Task... tasks) {
        return snapshot(power, Arrays.asList(tasks));
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int power,
            java.util.List<RearDisplayCastOccupancy.Task> tasks) {
        return new RearDisplayCastOccupancy.Snapshot(DISPLAY, power, power, power, tasks, "activity_task", null);
    }

    private static RearDisplayCastOccupancy.Task task(Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(7, "com.xiaomi.subscreencenter",
                "com.xiaomi.subscreencenter", COMPONENT, COMPONENT, 0,
                visible, requested, sleeping);
    }
}
