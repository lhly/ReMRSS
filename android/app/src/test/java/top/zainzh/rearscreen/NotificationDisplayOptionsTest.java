package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotificationDisplayOptionsTest {
    @Test public void durationUnitsStayWithinThePickerContract() {
        assertEquals(1, NotificationDisplayOptions.durationSeconds(1, false));
        assertEquals(60, NotificationDisplayOptions.durationSeconds(60, false));
        assertEquals(60, NotificationDisplayOptions.durationSeconds(1, true));
        assertEquals(3600, NotificationDisplayOptions.durationSeconds(60, true));
        try {
            NotificationDisplayOptions.durationSeconds(61, true);
            fail("picker values above 60 must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void confirmingWithoutChangingWheelsPreservesLegacyDurations() {
        assertEquals(90, NotificationDisplayOptions.durationAfterConfirmation(90, false, 2, true));
        assertEquals(3_661, NotificationDisplayOptions.durationAfterConfirmation(3_661, false, 60, true));
        assertEquals(120, NotificationDisplayOptions.durationAfterConfirmation(90, true, 2, true));
        assertEquals(2_147_483_647_000L, NotificationDisplayOptions.configuredDurationMillis(Integer.MAX_VALUE));
    }

    @Test public void persistentModeIsExclusiveToOrdinaryNotifications() {
        assertTrue(NotificationDisplayOptions.hasAutomaticFinish(false, false));
        assertFalse(NotificationDisplayOptions.hasAutomaticFinish(false, true));
        assertTrue(NotificationDisplayOptions.hasAutomaticFinish(true, true));
        assertTrue(NotificationDisplayOptions.hasAutomaticFinish(true, false));
    }

    @Test public void tapActionsDistinguishBusyRearFromTheNotificationPresentation() {
        assertEquals(NotificationTapAction.Effect.OPEN_MAIN, NotificationTapAction.MAIN.effect(false));
        assertEquals(NotificationTapAction.Effect.OPEN_MAIN, NotificationTapAction.MAIN.effect(true));
        assertEquals(NotificationTapAction.Effect.OPEN_REAR, NotificationTapAction.REAR.effect(false));
        assertEquals(NotificationTapAction.Effect.REPLACE_REAR, NotificationTapAction.REAR.effect(true));
        assertEquals(NotificationTapAction.Effect.OPEN_REAR, NotificationTapAction.REAR_IDLE.effect(false));
        assertEquals(NotificationTapAction.Effect.DISMISS, NotificationTapAction.REAR_IDLE.effect(true));
        assertEquals(NotificationTapAction.Effect.DISMISS, NotificationTapAction.DISMISS.effect(false));
        assertEquals(NotificationTapAction.Effect.DISMISS, NotificationTapAction.DISMISS.effect(true));
    }

    @Test public void openingAnAppAlreadyVisibleOnRearOnlyDismissesItsCapsule() {
        int on = android.view.Display.STATE_ON;
        String source = "example.chat";
        var same = new RearDisplayCastOccupancy.Task(23, "example.previous", source, null, null,
                0, true, true, false);
        var visible = new RearDisplayCastOccupancy.Snapshot(256, on, java.util.List.of(same),
                "activity_task", null);
        var rear = NotificationTapAction.Effect.REPLACE_REAR;
        var main = NotificationTapAction.Effect.OPEN_MAIN;
        assertEquals(NotificationTapAction.Effect.DISMISS, rear.forRearForeground(source, visible));
        assertEquals(main, main.forRearForeground(source, visible));
        assertEquals(rear, rear.forRearForeground("example.other", visible));
        assertEquals(rear, rear.forRearForeground(source, null));
        assertEquals(rear, rear.forRearForeground(source, new RearDisplayCastOccupancy.Snapshot(
                0, on, java.util.List.of(same), "activity_task", null)));
        assertEquals(rear, rear.forRearForeground(source, new RearDisplayCastOccupancy.Snapshot(
                256, on, java.util.List.of(same), "am_stack_list", null)));
        var other = new RearDisplayCastOccupancy.Task(24, "example.other", "example.other", null, null,
                0, true, true, false);
        assertEquals(rear, rear.forRearForeground(source, new RearDisplayCastOccupancy.Snapshot(
                256, on, java.util.List.of(same, other), "activity_task", null)));
        var hidden = new RearDisplayCastOccupancy.Task(24, "example.other", "example.other", null, null,
                0, false, false, false);
        assertEquals(NotificationTapAction.Effect.DISMISS, rear.forRearForeground(source,
                new RearDisplayCastOccupancy.Snapshot(256, on, java.util.List.of(hidden, same), "activity_task", null)));
    }

    @Test public void legacyRearChoiceKeepsItsIdleBehaviorAndExplicitValuesWin() {
        assertEquals(NotificationTapAction.MAIN, NotificationTapAction.resolve(null, false, false));
        assertEquals(NotificationTapAction.REAR_IDLE, NotificationTapAction.resolve(null, false, true));
        assertEquals(NotificationTapAction.DISMISS, NotificationTapAction.resolve(null, true, true));
        assertEquals(NotificationTapAction.REAR, NotificationTapAction.resolve("rear", true, false));
        assertEquals(NotificationTapAction.REAR_IDLE, NotificationTapAction.resolve("unknown", false, true));
    }

    @Test public void permanentModeRequiresARealRemovalIdentity() {
        assertTrue(NotificationDisplayOptions.shouldKeepUntilRemoved(true, "key", 1));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(true, null, 1));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(true, "", 1));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(true, "key", 0));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(false, "key", 1));
    }

    @Test public void removalMustMatchTheCurrentPersistentGeneration() {
        assertTrue(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 22, true, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k1", 11, true, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 21, true, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 22, false, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 22, true, Long.MIN_VALUE));
    }
}
