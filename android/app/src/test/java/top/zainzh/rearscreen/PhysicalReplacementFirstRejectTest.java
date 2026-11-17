package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class PhysicalReplacementFirstRejectTest {
    private static final String A = "fixture.a/dev.fixture.Main";
    private static final String B = "fixture.b/dev.fixture.Main";

    @Test public void instrumentationIsExactDeviceDebugOnly() {
        assertTrue(PhysicalReplacementFirstReject.enabled(true, "popsicle", "2509FPN0BC"));
        assertFalse(PhysicalReplacementFirstReject.enabled(false, "popsicle", "2509FPN0BC"));
        assertFalse(PhysicalReplacementFirstReject.enabled(true, "unknown", "2509FPN0BC"));
        assertFalse(PhysicalReplacementFirstReject.enabled(true, "popsicle", "other"));
    }

    @Test public void summaryRetainsIncompleteAndMovedEvidenceWithoutLeakingUnknownFields() {
        assertEquals("physical_replace phase=bottom_dispatch event=bottom_dispatch result=incomplete reason=focus_rejected_before_send b_moved=1",
                PhysicalReplacementFirstReject.summary("incomplete", "focus_rejected_before_send", true));
        assertEquals("physical_replace phase=bottom_dispatch event=bottom_dispatch result=unknown reason=unknown b_moved=0",
                PhysicalReplacementFirstReject.summary("anything", "package/com.secret:123", false));
        assertEquals("physical_replace phase=bottom_dispatch event=bottom_dispatch result=requested reason=bottom_focus_requested b_moved=1",
                PhysicalReplacementFirstReject.summary("requested", "bottom_focus_requested", true));
    }

    @Test public void hostGateReasonUsesExistingShortCircuitFacts() {
        assertEquals("host_authority", PhysicalReplacementFirstReject.hostGateReason(false, true, true, true));
        assertEquals("host_claim", PhysicalReplacementFirstReject.hostGateReason(true, false, true, true));
        assertEquals("host_target_current", PhysicalReplacementFirstReject.hostGateReason(true, true, false, true));
        assertEquals("host_reservation", PhysicalReplacementFirstReject.hostGateReason(true, true, true, false));
    }

    @Test public void sourcePresentationClassificationIsBoundedAndSideSpecific() {
        assertEquals("a_presentation_window_not_drawn",
                PhysicalReplacementFirstReject.presentationReason("window_not_drawn_and_shown", "a"));
        assertEquals("b_presentation_input_main_stale",
                PhysicalReplacementFirstReject.presentationReason("input_main_display_stale", "b"));
        assertEquals("b_presentation_other",
                PhysicalReplacementFirstReject.presentationReason("arbitrary OEM text", "b"));
    }

    @Test public void sourceInventoryClassificationExplainsAAndBVisibilityAndIdentity() {
        RearDisplayCastOccupancy.Task a = task(11, "fixture.a", A, true, true, false);
        RearDisplayCastOccupancy.Snapshot rear = snapshot(1, a);
        assertNull(PhysicalReplacementFirstReject.rearAInventoryReason(rear, 1, 11, "fixture.a", A));
        assertEquals("a_not_visible", PhysicalReplacementFirstReject.rearAInventoryReason(
                snapshot(1, task(11, "fixture.a", A, false, false, false)), 1, 11, "fixture.a", A));
        assertEquals("a_identity", PhysicalReplacementFirstReject.rearAInventoryReason(
                snapshot(1, task(11, "foreign", A, true, true, false)), 1, 11, "fixture.a", A));

        RearDisplayCastOccupancy.Task b = task(12, "fixture.b", B, true, true, false);
        assertNull(PhysicalReplacementFirstReject.mainBInventoryReason(snapshot(0, b), 12, "fixture.b", B));
        assertEquals("b_not_visible_main", PhysicalReplacementFirstReject.mainBInventoryReason(
                snapshot(0, task(12, "fixture.b", B, false, false, false)), 12, "fixture.b", B));
        assertEquals("b_identity", PhysicalReplacementFirstReject.mainBInventoryReason(
                snapshot(0, task(19, "fixture.b", B, true, true, false)), 12, "fixture.b", B));
    }

    @Test public void missingVisibilityFlagsAreUnknownForBothSourceTasks() {
        Boolean[][] states = new Boolean[][] {
                {null, true, false}, {true, null, false}, {true, true, null}
        };
        for (Boolean[] state : states) {
            assertEquals("snapshot_unknown", PhysicalReplacementFirstReject.rearAInventoryReason(
                    snapshot(1, task(11, "fixture.a", A, state[0], state[1], state[2])),
                    1, 11, "fixture.a", A));
            assertEquals("snapshot_unknown", PhysicalReplacementFirstReject.mainBInventoryReason(
                    snapshot(0, task(12, "fixture.b", B, state[0], state[1], state[2])),
                    12, "fixture.b", B));
        }
    }

    @Test public void firstSourceRejectDoesNotLetUnreachedBUnknownOverrideRejectedA() {
        RearDisplayCastOccupancy.Snapshot rear = snapshot(1,
                task(11, "fixture.a", A, false, false, false));
        RearDisplayCastOccupancy.Snapshot mainUnknown = new RearDisplayCastOccupancy.Snapshot(
                0, Display.STATE_ON, Collections.emptyList(), "unknown_source", null);
        assertEquals("a_not_visible", PhysicalReplacementFirstReject.firstInventoryFailure(
                rear, 1, 11, "fixture.a", A, false, mainUnknown, 12, "fixture.b", B,
                null, null));
        assertEquals("snapshot_unknown", PhysicalReplacementFirstReject.mainBInventoryReason(
                mainUnknown, 12, "fixture.b", B));
    }

    @Test public void malformedInventoryIsUnknownNotAVisibleOrHiddenGuess() {
        RearDisplayCastOccupancy.Task a = task(11, "fixture.a", A, true, true, false);
        assertEquals("snapshot_unknown", PhysicalReplacementFirstReject.rearAInventoryReason(
                new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_ON, Collections.singletonList(a),
                        "unknown_source", null), 1, 11, "fixture.a", A));
        assertEquals("snapshot_unknown", PhysicalReplacementFirstReject.mainBInventoryReason(
                snapshot(0, task(12, "fixture.b", B, true, true, false),
                        task(12, "fixture.b", B, true, true, false)), 12, "fixture.b", B));
    }

    private static RearDisplayCastOccupancy.Task task(int id, String pkg, String component,
            Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(id, pkg, pkg, component, component, 0,
                visible, requested, sleeping);
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int display,
            RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(display, Display.STATE_ON,
                Arrays.asList(tasks), "activity_task", null);
    }
}
