package top.zainzh.rearscreen;

import android.view.Display;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhysicalReplacementBottomFocusTest {
    private static final String PKG = "sample.candidate";
    private static final String BASE = PKG + "/sample.CandidateActivity";

    @Test public void verifiedModelAndExistingDeviceProfileAreBothRequired() {
        assertTrue(PhysicalReplacementBottomFocus.supportedDevice("popsicle", "2509FPN0BC"));
        assertFalse(PhysicalReplacementBottomFocus.supportedDevice("popsicle", "other-model"));
        assertFalse(PhysicalReplacementBottomFocus.supportedDevice("unknown", "2509FPN0BC"));
        assertFalse(PhysicalReplacementBottomFocus.supportedDevice("pandora", "2509FPN0BC"));
        assertFalse(PhysicalReplacementBottomFocus.supportedDevice(null, "2509FPN0BC"));
    }

    @Test public void productionMainTaskPredicateRequiresExactVisibleReadyTypedRow() {
        RearDisplayCastOccupancy.Task candidate = task(41, PKG, BASE, true, true, false);
        RearDisplayCastOccupancy.Snapshot valid = snapshot(0, candidate,
                task(42, "other.app", "other.app/.Activity", false, false, false));
        assertTrue("unrelated main-screen task does not invalidate the exact candidate",
                PhysicalReplacementBottomFocus.exactVisibleMainTask(valid, 41, PKG, BASE));
        assertFalse(PhysicalReplacementBottomFocus.exactVisibleMainTask(valid, 99, PKG, BASE));
        assertFalse(PhysicalReplacementBottomFocus.exactVisibleMainTask(
                snapshot(0, task(41, PKG, BASE, false, false, false)), 41, PKG, BASE));
        assertFalse(PhysicalReplacementBottomFocus.exactVisibleMainTask(
                snapshot(0, task(41, PKG, BASE, true, null, false)), 41, PKG, BASE));
        RearDisplayCastOccupancy.Snapshot duplicate = snapshot(0, candidate,
                task(41, PKG, BASE, true, true, false));
        assertFalse(PhysicalReplacementBottomFocus.exactVisibleMainTask(duplicate, 41, PKG, BASE));
        RearDisplayCastOccupancy.Snapshot failed = new RearDisplayCastOccupancy.Snapshot(
                0, Display.STATE_ON, Display.STATE_ON, Display.STATE_ON, null,
                "activity_task", "query_failed");
        assertFalse(PhysicalReplacementBottomFocus.exactVisibleMainTask(failed, 41, PKG, BASE));
    }

    @Test public void bottomReadbackMustSucceedBeforeOneFocusAndUncertainActionNeverRetries() {
        List<String> calls = new ArrayList<>();
        PhysicalReplacementBottomFocus.Outcome success = PhysicalReplacementBottomFocus.run(true,
                actions(calls, PhysicalReplacementBottomFocus.Dispatch.SENT, true,
                        PhysicalReplacementBottomFocus.Dispatch.SENT));
        assertEquals(Arrays.asList("bottom", "readback", "focus"), calls);
        assertEquals("requested", success.status);
        assertTrue(success.bMoved);

        calls.clear();
        PhysicalReplacementBottomFocus.Outcome noReadback = PhysicalReplacementBottomFocus.run(true,
                actions(calls, PhysicalReplacementBottomFocus.Dispatch.SENT, false,
                        PhysicalReplacementBottomFocus.Dispatch.SENT));
        assertEquals(Arrays.asList("bottom", "readback"), calls);
        assertEquals("incomplete", noReadback.status);
        assertTrue(noReadback.bMoved);

        calls.clear();
        PhysicalReplacementBottomFocus.Outcome uncertainBottom = PhysicalReplacementBottomFocus.run(true,
                actions(calls, PhysicalReplacementBottomFocus.Dispatch.MAY_HAVE_BEEN_SENT, true,
                        PhysicalReplacementBottomFocus.Dispatch.SENT));
        assertEquals(Arrays.asList("bottom"), calls);
        assertEquals("unknown", uncertainBottom.status);
        assertTrue(uncertainBottom.bMoved);

        calls.clear();
        PhysicalReplacementBottomFocus.Outcome rejectedBottom = PhysicalReplacementBottomFocus.run(true,
                actions(calls, PhysicalReplacementBottomFocus.Dispatch.REJECTED_BEFORE_SEND, true,
                        PhysicalReplacementBottomFocus.Dispatch.SENT));
        assertEquals(Arrays.asList("bottom"), calls);
        assertEquals("rejected", rejectedBottom.status);
        assertFalse(rejectedBottom.bMoved);

        calls.clear();
        PhysicalReplacementBottomFocus.Outcome uncertainFocus = PhysicalReplacementBottomFocus.run(true,
                actions(calls, PhysicalReplacementBottomFocus.Dispatch.SENT, true,
                        PhysicalReplacementBottomFocus.Dispatch.MAY_HAVE_BEEN_SENT));
        assertEquals(Arrays.asList("bottom", "readback", "focus"), calls);
        assertEquals("unknown", uncertainFocus.status);
        assertTrue(uncertainFocus.bMoved);
    }

    @Test public void unsupportedDeviceDoesNotInvokeEitherStage() {
        List<String> calls = new ArrayList<>();
        PhysicalReplacementBottomFocus.Outcome unsupported = PhysicalReplacementBottomFocus.run(false,
                actions(calls, PhysicalReplacementBottomFocus.Dispatch.SENT, true,
                        PhysicalReplacementBottomFocus.Dispatch.SENT));
        assertTrue(calls.isEmpty());
        assertEquals("unsupported", unsupported.status);
        assertFalse(unsupported.bMoved);
    }

    private static final String A = "sample.incumbent";
    private static final String A_BASE = A + "/sample.IncumbentActivity";

    @Test public void wakingNeverCountsAsStableButExactHiddenPairCanBeObservedAgain() {
        var a = task(21, A, A_BASE, true, true, false);
        var b = task(41, PKG, BASE, false, false, false);
        var waking = new RearDisplayCastOccupancy.Snapshot(1, 0, Display.STATE_OFF, Display.STATE_ON,
                Arrays.asList(a, b), "activity_task", null);
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.REAR_WAKING, rearCheck(waking));
        assertFalse(PhysicalReplacementBottomFocus.exactRearAFrontBHidden(
                waking, 21, A, A_BASE, 41, PKG, BASE, 1,
                PhysicalReplacementBottomFocus.DesktopIdentity.absent(), ignored -> false));
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.READY, rearCheck(snapshot(1, a, b)));
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.A_BECOMING_VISIBLE,
                rearCheck(snapshot(1, task(21, A, A_BASE, false, true, false), b)));
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.A_NOT_VISIBLE,
                rearCheck(snapshot(1, task(21, A, A_BASE, null, true, false), b)));
    }

    @Test public void wakeCannotMaskForeignIdentityDuplicateOrCandidateVisibility() {
        var a = task(21, A, A_BASE, true, true, false);
        var b = task(41, PKG, BASE, false, false, false);
        var visibleB = task(41, PKG, BASE, false, true, false);
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.B_NOT_HIDDEN, rearCheck(
                new RearDisplayCastOccupancy.Snapshot(1, 0, Display.STATE_DOZE, Display.STATE_ON,
                        Arrays.asList(a, visibleB), "activity_task", null)));
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.TASK_INVENTORY, rearCheck(snapshot(1, a, b, b)));
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.B_IDENTITY, rearCheck(snapshot(1, a,
                task(41, "other.app", "other.app/.Other", false, false, false))));
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.REAR_SNAPSHOT, rearCheck(
                new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_OFF, Arrays.asList(a, b), "activity_task", null)));
    }

    @Test public void redrawUsesFreshProofWithoutRepeatingMoveOrFocus() {
        var clock = new FakeClock();
        var calls = new ArrayList<String>();
        var samples = Arrays.asList(PhysicalReplacementBottomFocus.BottomCheck.REAR_WAKING,
                PhysicalReplacementBottomFocus.BottomCheck.A_REDRAWING,
                PhysicalReplacementBottomFocus.BottomCheck.READY).iterator();
        var result = PhysicalReplacementBottomFocus.run(true, new PhysicalReplacementBottomFocus.Actions() {
            public PhysicalReplacementBottomFocus.Dispatch moveBottom() {
                calls.add("move"); return PhysicalReplacementBottomFocus.Dispatch.SENT;
            }
            public boolean verifyBottom() {
                return PhysicalReplacementBottomFocus.awaitBottomReady(deadline -> {
                    assertEquals(calls.size() == 1 ? Long.MAX_VALUE : 240_000_000L, deadline);
                    calls.add("read"); return samples.next();
                }, clock) == PhysicalReplacementBottomFocus.BottomCheck.READY;
            }
            public PhysicalReplacementBottomFocus.Dispatch requestFocus() {
                calls.add("focus"); return PhysicalReplacementBottomFocus.Dispatch.SENT;
            }
        });
        assertEquals("requested", result.status);
        assertEquals(List.of("move", "read", "read", "read", "focus"), calls);
        assertEquals(80, clock.now);
    }

    @Test public void conflictAndExpiredObservationNeverAuthorizeFocus() {
        var clock = new FakeClock();
        var samples = Arrays.asList(PhysicalReplacementBottomFocus.BottomCheck.A_REDRAWING,
                PhysicalReplacementBottomFocus.BottomCheck.B_IDENTITY,
                PhysicalReplacementBottomFocus.BottomCheck.READY).iterator();
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.B_IDENTITY,
                PhysicalReplacementBottomFocus.awaitBottomReady(deadline -> samples.next(), clock));
        assertTrue(samples.hasNext());
        assertEquals(40, clock.now);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(PhysicalReplacementBottomFocus.BottomCheck.SETTLEMENT_EXPIRED,
                PhysicalReplacementBottomFocus.awaitBottomReady(deadline -> {
                    if (reads.getAndIncrement() == 0) return PhysicalReplacementBottomFocus.BottomCheck.A_REDRAWING;
                    clock.now += 220;
                    return PhysicalReplacementBottomFocus.BottomCheck.READY;
                }, clock));
        assertEquals(2, reads.get());
    }

    private static PhysicalReplacementBottomFocus.BottomCheck rearCheck(RearDisplayCastOccupancy.Snapshot rows) {
        return PhysicalReplacementBottomFocus.rearPairCheck(rows, 21, A, A_BASE, 41, PKG, BASE, 1,
                PhysicalReplacementBottomFocus.DesktopIdentity.absent(), ignored -> false);
    }

    private static final class FakeClock implements PhysicalReplacementBottomFocus.SettlementClock {
        long now;
        public long nanoTime() { return now * 1_000_000; }
        public void sleep(long millis) { now += millis; }
    }

    private static PhysicalReplacementBottomFocus.Actions actions(List<String> calls,
            PhysicalReplacementBottomFocus.Dispatch bottom, boolean readback,
            PhysicalReplacementBottomFocus.Dispatch focus) {
        return new PhysicalReplacementBottomFocus.Actions() {
            @Override public PhysicalReplacementBottomFocus.Dispatch moveBottom() {
                calls.add("bottom"); return bottom;
            }
            @Override public boolean verifyBottom() { calls.add("readback"); return readback; }
            @Override public PhysicalReplacementBottomFocus.Dispatch requestFocus() {
                calls.add("focus"); return focus;
            }
        };
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int display,
            RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(display, Display.STATE_ON,
                Display.STATE_ON, Display.STATE_ON, Arrays.asList(tasks), "activity_task", null);
    }

    private static RearDisplayCastOccupancy.Task task(int id, String pkg, String base,
            Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(id, pkg, pkg, base, base, 0,
                visible, requested, sleeping);
    }
}
