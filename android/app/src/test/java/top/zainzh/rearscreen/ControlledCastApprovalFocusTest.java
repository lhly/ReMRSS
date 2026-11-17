package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.util.List;
import org.junit.Test;

public class ControlledCastApprovalFocusTest {
    private static final String PICKER = "test.host/test.host.ModPicker";
    private static final String BASE = "test.source/test.source.MainActivity";
    private static final String TOP = "test.source/test.source.ContentActivity";

    @Test public void pickerMustStillBeVisibleAndAwakeOnMain() {
        RearDisplayCastOccupancy.Task exact = picker(true, true, false);
        assertNotNull(ControlledCastApprovalFocus.visiblePicker(main(exact), 18, PICKER));
        assertNull(ControlledCastApprovalFocus.visiblePicker(main(picker(false, true, false)), 18, PICKER));
        assertNull(ControlledCastApprovalFocus.visiblePicker(main(picker(true, false, false)), 18, PICKER));
        assertNull(ControlledCastApprovalFocus.visiblePicker(main(picker(true, true, true)), 18, PICKER));
        assertNull(ControlledCastApprovalFocus.visiblePicker(main(exact, exact), 18, PICKER));
        assertNull(ControlledCastApprovalFocus.visiblePicker(main(exact), 19, PICKER));
        assertNull(ControlledCastApprovalFocus.visiblePicker(new RearDisplayCastOccupancy.Snapshot(
                0, Display.STATE_ON, Display.STATE_DOZE, Display.STATE_ON, List.of(exact),
                "activity_task", null), 18, PICKER));
    }

    @Test public void targetTopIdentityMustSurviveTheWindowCheck() {
        RearDisplayCastOccupancy.Task original = target(0, TOP);
        assertTrue(ControlledCastApprovalFocus.exactTarget(main(original), 23, BASE, TOP, 0));
        assertFalse(ControlledCastApprovalFocus.exactTarget(main(target(0, BASE)), 23, BASE, TOP, 0));
        assertFalse(ControlledCastApprovalFocus.exactTarget(main(target(10, TOP)), 23, BASE, TOP, 0));
        assertFalse(ControlledCastApprovalFocus.exactTarget(main(original, original), 23, BASE, TOP, 0));
    }

    private static RearDisplayCastOccupancy.Snapshot main(RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(0, Display.STATE_ON, List.of(tasks), "activity_task", null);
    }

    private static RearDisplayCastOccupancy.Task picker(Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(18, "test.host", "test.host", PICKER, PICKER, 0,
                visible, requested, sleeping);
    }

    private static RearDisplayCastOccupancy.Task target(int userId, String top) {
        return new RearDisplayCastOccupancy.Task(23, "test.source", "test.source", BASE, top, userId,
                true, true, false);
    }
}
