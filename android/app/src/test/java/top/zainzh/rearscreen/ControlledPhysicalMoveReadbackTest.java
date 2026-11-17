package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.junit.Test;

public final class ControlledPhysicalMoveReadbackTest {
    @Test public void invocationReplyLostButExactTargetReadbackConfirmsSingleMove() {
        FakeClock clock = new FakeClock();
        Deque<ControlledPhysicalMoveReadback.Placement> reads = new ArrayDeque<>(Arrays.asList(
                ControlledPhysicalMoveReadback.Placement.SOURCE_EXACT,
                ControlledPhysicalMoveReadback.Placement.TARGET_EXACT));
        ControlledPhysicalMoveReadback.Outcome outcome = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.MAY_HAVE_BEEN_SENT,
                () -> { clock.readCount++; return reads.removeFirst(); }, clock, 350L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.CONFIRMED, outcome);
        assertEquals(2, clock.readCount);
        assertEquals(1, clock.sleepCount);
    }

    @Test public void exactSourceAtDeadlineRemainsUnknownWithoutStartingAnotherRead() {
        FakeClock clock = new FakeClock();
        ControlledPhysicalMoveReadback.Outcome outcome = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.MAY_HAVE_BEEN_SENT,
                () -> { clock.readCount++; return ControlledPhysicalMoveReadback.Placement.SOURCE_EXACT; },
                clock, 100L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.UNKNOWN, outcome);
        assertEquals(1, clock.readCount);
        assertEquals(1, clock.sleepCount);
    }

    @Test public void exactTargetStartedWithinWindowMayCompleteAt351ms() {
        FakeClock clock = new FakeClock();
        ControlledPhysicalMoveReadback.Outcome outcome = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.SENT,
                () -> { clock.readCount++; clock.now = 351L;
                    return ControlledPhysicalMoveReadback.Placement.TARGET_EXACT; },
                clock, 350L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.CONFIRMED, outcome);
        assertEquals(1, clock.readCount);
    }

    @Test public void expiredMoveWindowDoesNotStartAnotherRead() {
        FakeClock clock = new FakeClock();
        ControlledPhysicalMoveReadback.Outcome outcome = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.MAY_HAVE_BEEN_SENT,
                () -> { clock.readCount++; return ControlledPhysicalMoveReadback.Placement.SOURCE_EXACT; },
                clock, 100L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.UNKNOWN, outcome);
        assertEquals(1, clock.readCount);
        assertEquals(1, clock.sleepCount);
    }

    @Test public void foreignReadbackStopsImmediatelyAndUnknownNeverConfirmsAlone() {
        FakeClock clock = new FakeClock();
        ControlledPhysicalMoveReadback.Outcome foreign = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.SENT,
                () -> { clock.readCount++; return ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN; },
                clock, 350L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.UNKNOWN, foreign);
        assertEquals(1, clock.readCount);
        assertEquals(0, clock.sleepCount);

        FakeClock unknownClock = new FakeClock();
        ControlledPhysicalMoveReadback.Outcome unknown = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.MAY_HAVE_BEEN_SENT,
                () -> { unknownClock.readCount++; return ControlledPhysicalMoveReadback.Placement.UNKNOWN; },
                unknownClock, 350L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.UNKNOWN, unknown);
        assertEquals(3, unknownClock.readCount);
    }

    @Test public void incompleteTaskSnapshotMayConvergeOnlyToExactTargetWithinWindow() {
        FakeClock clock = new FakeClock();
        Deque<ControlledPhysicalMoveReadback.Placement> reads = new ArrayDeque<>(Arrays.asList(
                ControlledPhysicalMoveReadback.Placement.UNKNOWN,
                ControlledPhysicalMoveReadback.Placement.TARGET_EXACT));
        ControlledPhysicalMoveReadback.Outcome outcome = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.SENT,
                () -> { clock.readCount++; return reads.removeFirst(); }, clock, 350L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.CONFIRMED, outcome);
        assertEquals(2, clock.readCount);
    }

    @Test public void preSendRejectionDoesNotReadOrRetry() {
        FakeClock clock = new FakeClock();
        ControlledPhysicalMoveReadback.Outcome outcome = ControlledPhysicalMoveReadback.confirm(
                ControlledPhysicalMoveReadback.Dispatch.REJECTED_BEFORE_SEND,
                () -> { throw new AssertionError("pre-send rejection must not read back"); },
                clock, 350L, 3, 100L);
        assertEquals(ControlledPhysicalMoveReadback.Outcome.REJECTED, outcome);
        assertEquals(0, clock.readCount);
    }

    @Test public void actualTypedPlacementClassifierRequiresExactSingleRootAndIdentity() {
        List<SceneTaskIdentity.Record> exactRear = Collections.singletonList(
                new SceneTaskIdentity.Record(42, 1, "b.pkg/.Main", "b.pkg/.Main"));
        String rearRoot = "RootTask id=7 displayId=1\n taskId=42: b.pkg/.Main\n";
        assertEquals(ControlledPhysicalMoveReadback.Placement.TARGET_EXACT,
                TaskService.classifyControlledPhysicalMovePlacement(exactRear, rearRoot, true,
                        42, "b.pkg", "b.pkg/.Main", 0, 1));
        assertEquals(ControlledPhysicalMoveReadback.Placement.SOURCE_EXACT,
                TaskService.classifyControlledPhysicalMovePlacement(exactRear,
                        "RootTask id=7 displayId=1\n taskId=42: b.pkg/.Main\n", true,
                        42, "b.pkg", "b.pkg/.Main", 1, 0));
        assertEquals(ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN,
                TaskService.classifyControlledPhysicalMovePlacement(exactRear,
                        "RootTask id=7 displayId=1\n taskId=42: b.pkg/.Main\n taskId=43: other/.Main\n", true,
                        42, "b.pkg", "b.pkg/.Main", 0, 1));
        assertEquals(ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN,
                TaskService.classifyControlledPhysicalMovePlacement(
                        Collections.singletonList(new SceneTaskIdentity.Record(42, 1,
                                "other/.Main", "other/.Main")), rearRoot, true,
                        42, "b.pkg", "b.pkg/.Main", 0, 1));
        assertEquals(ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN,
                TaskService.classifyControlledPhysicalMovePlacement(exactRear,
                        "RootTask id=7 displayId=1\n taskId=42: other/.Main\n", true,
                        42, "b.pkg", "b.pkg/.Main", 0, 1));
        assertEquals(ControlledPhysicalMoveReadback.Placement.UNKNOWN,
                TaskService.classifyControlledPhysicalMovePlacement(Collections.emptyList(), rearRoot, true,
                        42, "b.pkg", "b.pkg/.Main", 0, 1));
        assertEquals(ControlledPhysicalMoveReadback.Placement.UNKNOWN,
                TaskService.classifyControlledPhysicalMovePlacement(exactRear, rearRoot, false,
                        42, "b.pkg", "b.pkg/.Main", 0, 1));
    }

    @Test public void finalSelectedDisplayIsCheckedAfterRootProofAndMustRemainExact() throws Exception {
        org.json.JSONObject same = new org.json.JSONObject("{\"selectedId\":4,\"selectedUnique\":\"rear\"}");
        org.json.JSONObject switched = new org.json.JSONObject("{\"selectedId\":5,\"selectedUnique\":\"other\"}");
        assertEquals(ControlledPhysicalMoveReadback.Placement.TARGET_EXACT,
                TaskService.finalizeControlledMoveReadback(
                        ControlledPhysicalMoveReadback.Placement.TARGET_EXACT, same, 4, "rear"));
        assertEquals(ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN,
                TaskService.finalizeControlledMoveReadback(
                        ControlledPhysicalMoveReadback.Placement.TARGET_EXACT, switched, 4, "rear"));
        assertEquals(ControlledPhysicalMoveReadback.Placement.UNKNOWN,
                TaskService.finalizeControlledMoveReadback(
                        ControlledPhysicalMoveReadback.Placement.UNKNOWN, same, 4, "rear"));
        assertEquals(ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN,
                TaskService.finalizeControlledMoveReadback(
                        ControlledPhysicalMoveReadback.Placement.TARGET_EXACT,
                        new org.json.JSONObject("{\"error\":\"unknown\"}"), 4, "rear"));
    }

    private static final class FakeClock implements ControlledPhysicalMoveReadback.Clock {
        long now;
        int readCount, sleepCount;
        @Override public long elapsedRealtime() { return now; }
        @Override public void sleep(long millis) { sleepCount++; now += millis; }
    }
}
