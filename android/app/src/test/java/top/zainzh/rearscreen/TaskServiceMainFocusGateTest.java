package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.util.Collections;
import org.junit.Test;

public class TaskServiceMainFocusGateTest {
    private static final String BASE = "sample.target/sample.target.MainActivity";
    private static final String TOP = "sample.target/sample.target.DetailActivity";

    @Test public void exactKnownStableHiddenMainTaskMayFocusThenMustBecomeVisibleAndRequested() {
        assertTrue("host chooser accepts explicit hidden non-sleeping task before focus",
                TaskService.isExactMainTaskFocusSnapshot(snapshot(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE), 42, BASE, TOP, false));
        assertTrue("focus is confirmed only after requested and visible are both true",
                TaskService.isExactMainTaskFocusSnapshot(snapshot(Boolean.TRUE, Boolean.TRUE, Boolean.FALSE), 42, BASE, TOP, true));
    }

    @Test public void unknownMixedSleepingWrongIdentityOrPowerAndDuplicateFailClosed() {
        assertFalse(TaskService.isExactMainTaskFocusSnapshot(snapshot(null, Boolean.FALSE, Boolean.FALSE), 42, BASE, TOP, false));
        assertFalse(TaskService.isExactMainTaskFocusSnapshot(snapshot(Boolean.FALSE, null, Boolean.FALSE), 42, BASE, TOP, false));
        assertFalse("mixed visible/requested state is ambiguous",
                TaskService.isExactMainTaskFocusSnapshot(snapshot(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE), 42, BASE, TOP, false));
        assertFalse("sleeping tasks are never focused", TaskService.isExactMainTaskFocusSnapshot(
                snapshot(Boolean.FALSE, Boolean.FALSE, Boolean.TRUE), 42, BASE, TOP, false));
        assertFalse("hidden state cannot satisfy post-focus confirmation",
                TaskService.isExactMainTaskFocusSnapshot(snapshot(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE), 42, BASE, TOP, true));
        assertFalse(TaskService.isExactMainTaskFocusSnapshot(snapshot(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE), 42,
                "sample.target/sample.target.Other", TOP, false));
        RearDisplayCastOccupancy.Snapshot duplicate = new RearDisplayCastOccupancy.Snapshot(0, Display.STATE_ON,
                Display.STATE_ON, Display.STATE_ON, java.util.Arrays.asList(task(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE),
                        task(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE)), "test", null);
        assertFalse(TaskService.isExactMainTaskFocusSnapshot(duplicate, 42, BASE, TOP, false));
        RearDisplayCastOccupancy.Snapshot off = new RearDisplayCastOccupancy.Snapshot(0, Display.STATE_OFF,
                Display.STATE_OFF, Display.STATE_OFF,
                Collections.singletonList(task(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE)), "test", null);
        assertFalse(TaskService.isExactMainTaskFocusSnapshot(off, 42, BASE, TOP, false));
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Snapshot(0, Display.STATE_ON, Display.STATE_ON, Display.STATE_ON,
                Collections.singletonList(task(visible, requested, sleeping)), "test", null);
    }
    private static RearDisplayCastOccupancy.Task task(Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(42, "sample.target", "sample.target", BASE, TOP,
                visible, requested, sleeping);
    }
}
