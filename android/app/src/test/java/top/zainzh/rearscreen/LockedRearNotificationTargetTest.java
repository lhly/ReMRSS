package top.zainzh.rearscreen;

import android.content.pm.ActivityInfo;
import android.view.Display;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LockedRearNotificationTargetTest {
    private static final String SOURCE = "com.example.messages";
    private static final int VIRTUAL = 22;

    @Test public void acceptsOnlyAVisibleTaskFromTheSelectedSourceOnTheExpectedDisplay() {
        RearDisplayCastOccupancy.Task visible = task(SOURCE, SOURCE, 0, true, true, false);
        assertEquals(41, accepted(VIRTUAL, Display.STATE_ON, visible));
        assertEquals(-1, accepted(0, Display.STATE_ON, visible));
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_OFF, visible));
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_ON, task(SOURCE, SOURCE, 10, true, true, false)));
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_ON, task(SOURCE, "com.example.other", 0, true, true, false)));
    }

    @Test public void aHiddenOrUnconfirmedDetailCannotPreventFallback() {
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_ON, task(SOURCE, SOURCE, 0, false, true, false)));
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_ON, task(SOURCE, SOURCE, 0, true, true, true)));
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_ON, task(SOURCE, SOURCE, 0, true, null, false)));
        assertEquals(-1, accepted(VIRTUAL, Display.STATE_ON, task(SOURCE, SOURCE, 0, true, true, null)));
    }

    @Test public void launcherMustSupportANewIndependentTaskEvenIfTheAppIsAlreadyOpen() {
        int none = ActivityInfo.DOCUMENT_LAUNCH_NONE;
        assertTrue(RearNotificationCastRequest.canStartIndependentLauncher(ActivityInfo.LAUNCH_MULTIPLE, none, 0));
        assertTrue(RearNotificationCastRequest.canStartIndependentLauncher(ActivityInfo.LAUNCH_SINGLE_TOP, none, 0));
        assertFalse(RearNotificationCastRequest.canStartIndependentLauncher(ActivityInfo.LAUNCH_SINGLE_TASK, none, 0));
        assertFalse(RearNotificationCastRequest.canStartIndependentLauncher(ActivityInfo.LAUNCH_SINGLE_INSTANCE, none, 0));
        assertFalse(RearNotificationCastRequest.canStartIndependentLauncher(ActivityInfo.LAUNCH_MULTIPLE,
                ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING, 0));
        assertFalse(RearNotificationCastRequest.canStartIndependentLauncher(ActivityInfo.LAUNCH_SINGLE_TOP, none,
                ActivityInfo.FLAG_ALLOW_TASK_REPARENTING));
    }

    @Test public void securePrimaryDisplayAllowsSystemAodWithoutAcceptingInteractiveOrUnknownStates() {
        for (int state : new int[]{Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND}) {
            assertTrue(RearNotificationCastRequest.mayPresentWhileLocked(state, true, true));
            assertFalse(RearNotificationCastRequest.mayPresentWhileLocked(state, false, true));
            assertFalse(RearNotificationCastRequest.mayPresentWhileLocked(state, true, false));
        }
        for (int state : new int[]{Display.STATE_ON, Display.STATE_ON_SUSPEND, Display.STATE_UNKNOWN}) {
            assertFalse(RearNotificationCastRequest.mayPresentWhileLocked(state, true, true));
        }
    }

    @Test public void anApprovedHomeCanUseAnyOfTheThreeExitModes() {
        RearNotificationExitMode manual = RearNotificationExitMode.MANUAL;
        RearNotificationExitMode unlock = RearNotificationExitMode.MAIN_UNLOCK;
        RearNotificationExitMode wake = RearNotificationExitMode.MAIN_WAKE;
        for (int state : new int[]{Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND}) {
            assertTrue(manual.keepsOutput(state, true, true, false));
            assertTrue(unlock.keepsOutput(state, true, true, false));
            assertTrue(wake.keepsOutput(state, true, true, false));
        }
        assertTrue(manual.keepsOutput(Display.STATE_ON, true, true, false));
        assertTrue(unlock.keepsOutput(Display.STATE_ON, true, true, false));
        assertFalse(wake.keepsOutput(Display.STATE_ON, true, true, false));
        for (RearNotificationExitMode automatic : new RearNotificationExitMode[]{unlock, wake}) {
            assertFalse(automatic.keepsOutput(Display.STATE_ON, false, true, false));
            assertFalse(automatic.keepsOutput(Display.STATE_ON, true, false, false));
            assertFalse(automatic.keepsOutput(Display.STATE_OFF, true, true, true));
        }
        assertTrue(manual.keepsOutput(Display.STATE_ON, false, false, true));
        assertTrue(manual.keepsOutput(Display.STATE_OFF, true, true, false)); // A subsequent lock.
        assertFalse(wake.keepsOutput(Display.STATE_UNKNOWN, true, true, false));
    }

    @Test public void onlyTheVerifiedHomeHostOnItsPhysicalRearCanRecoverOutput() {
        assertTrue(RearNotificationCastRequest.ownsRecoverableRear(true, 1, 1));
        assertFalse(RearNotificationCastRequest.ownsRecoverableRear(false, 1, 1));
        assertFalse(RearNotificationCastRequest.ownsRecoverableRear(true, 1, Display.INVALID_DISPLAY));
        assertFalse(RearNotificationCastRequest.ownsRecoverableRear(true, 1, Display.DEFAULT_DISPLAY));
        assertFalse(RearNotificationCastRequest.ownsRecoverableRear(true, 1, VIRTUAL));
        assertFalse(RearNotificationCastRequest.ownsRecoverableRear(true, Display.DEFAULT_DISPLAY, Display.DEFAULT_DISPLAY));
    }

    @Test public void lockedRecoveryAcceptsOnlyTheExactHostActivityInThePrimaryUser() {
        String pkg = BuildConfig.APPLICATION_ID;
        String host = pkg + "/" + VirtualRearHostActivity.class.getName();
        assertTrue(exactRecoveryHost(host, host, 0));
        assertFalse(exactRecoveryHost(host, pkg + "/.MainActivity", 0));
        assertFalse(exactRecoveryHost(pkg + "/.MainActivity", host, 0));
        assertFalse(exactRecoveryHost(host, host, 10));
        assertFalse(exactRecoveryHost(host, null, 0));
    }

    @Test public void closingACandidateOrRetiredCastDoesNotStopTheLiveReader() {
        assertFalse(RearVirtualCast.ownsReaderAtClose(true, false)); // Preview never adopted a reader.
        assertFalse(RearVirtualCast.ownsReaderAtClose(false, true)); // Old owner after handoff.
        assertTrue(RearVirtualCast.ownsReaderAtClose(false, false)); // Committed or ordinary owner.
    }

    @Test public void persistedExitValuesAreStableAndTheDefaultIsManual() {
        assertEquals(RearNotificationExitMode.MANUAL, RearNotificationExitMode.fromValue(null));
        assertEquals(RearNotificationExitMode.MANUAL, RearNotificationExitMode.fromValue("manual"));
        assertEquals(RearNotificationExitMode.MANUAL, RearNotificationExitMode.fromValue("unrecognized"));
        assertEquals(RearNotificationExitMode.MAIN_UNLOCK, RearNotificationExitMode.fromValue("main_unlock"));
        assertEquals(RearNotificationExitMode.MAIN_WAKE, RearNotificationExitMode.fromValue("main_wake"));
    }

    private static int accepted(int display, int state, RearDisplayCastOccupancy.Task task) {
        return RearVirtualCast.visibleLockedNotificationTask(new RearDisplayCastOccupancy.Snapshot(
                display, state, List.of(task), "activity_task", null), VIRTUAL, SOURCE);
    }

    private static boolean exactRecoveryHost(String base, String top, int user) {
        String pkg = BuildConfig.APPLICATION_ID;
        RearDisplayCastOccupancy.Task row = new RearDisplayCastOccupancy.Task(
                41, pkg, pkg, base, top, user, false, false, false);
        var snapshot = new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_ON, List.of(row),
                "activity_task", null);
        return RearVirtualCast.isExactLockedRecoveryHost(snapshot, 41);
    }

    private static RearDisplayCastOccupancy.Task task(String base, String top, int user,
            Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(41, base, top, base + "/Home", top + "/Detail",
                user, visible, requested, sleeping);
    }
}
