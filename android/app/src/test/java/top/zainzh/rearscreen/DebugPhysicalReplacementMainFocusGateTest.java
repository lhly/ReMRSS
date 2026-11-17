package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.Test;

public final class DebugPhysicalReplacementMainFocusGateTest {
    private static final String MAIN = "lane=b service_contract=96 dispatcher_segment=unique:0 "
            + "dispatcher_displayId=unique:0 dispatcher_mDisplayId=missing focus=main row_count=one";
    private static final String REAR = "lane=b service_contract=96 dispatcher_segment=unique:2 "
            + "dispatcher_displayId=unique:2 dispatcher_mDisplayId=missing focus=rear row_count=one";

    @Test public void observeReadsOnceAndNeverGatesOriginalMove() {
        FakeClock clock = new FakeClock();
        Sequence probe = new Sequence(clock, evidence(DebugPhysicalReplacementMainFocusGate.Classification.UNKNOWN,
                DebugPhysicalReplacementMainFocusGate.Reason.ROUTE_UNAVAILABLE));
        DebugPhysicalReplacementMainFocusGate.Decision decision =
                DebugPhysicalReplacementMainFocusGate.evaluate(DebugPhysicalReplacementMainFocusGate.Mode.OBSERVE,
                        probe, clock);
        assertTrue(decision.allowMove);
        assertEquals(1, decision.reads);
        assertEquals(0, clock.sleeps);
        assertEquals("route_unavailable", decision.reason.wire);
    }

    @Test public void exactMainAtBaselineOpensWithoutRetry() {
        FakeClock clock = new FakeClock();
        Sequence probe = new Sequence(clock, exactMain());
        DebugPhysicalReplacementMainFocusGate.Decision decision = gated(probe, clock);
        assertTrue(decision.allowMove);
        assertEquals(1, decision.reads);
        assertEquals(0L, decision.waitMs);
        assertEquals("exact_main", decision.reason.wire);
    }

    @Test public void oneNonMainSampleMayOpenOnlyOnSecondExactMainSample() {
        FakeClock clock = new FakeClock();
        Sequence probe = new Sequence(clock, classify(REAR), exactMain());
        DebugPhysicalReplacementMainFocusGate.Decision decision = gated(probe, clock);
        assertTrue(decision.allowMove);
        assertEquals(2, decision.reads);
        assertEquals(75L, decision.waitMs);
        assertEquals(1, clock.sleeps);
    }

    @Test public void twoNonMainSamplesCloseWithAtMostTwoReads() {
        FakeClock clock = new FakeClock();
        Sequence probe = new Sequence(clock, classify(REAR), classify(REAR), exactMain());
        DebugPhysicalReplacementMainFocusGate.Decision decision = gated(probe, clock);
        assertFalse(decision.allowMove);
        assertEquals("sample_limit", decision.reason.wire);
        assertEquals(2, decision.reads);
        assertEquals("a third exact MAIN read is never scheduled", 1, probe.remaining());
    }

    @Test public void unknownDoesNotRetryAndLateExactMainCannotOpen() {
        FakeClock unknownClock = new FakeClock();
        Sequence unknown = new Sequence(unknownClock, evidence(
                DebugPhysicalReplacementMainFocusGate.Classification.UNKNOWN,
                DebugPhysicalReplacementMainFocusGate.Reason.FOCUS_AMBIGUOUS), exactMain());
        DebugPhysicalReplacementMainFocusGate.Decision unknownDecision = gated(unknown, unknownClock);
        assertFalse(unknownDecision.allowMove);
        assertEquals(1, unknownDecision.reads);
        assertEquals(1, unknown.remaining());

        FakeClock lateClock = new FakeClock();
        Sequence late = new Sequence(lateClock, exactMain());
        late.readAdvanceMs = DebugPhysicalReplacementMainFocusGate.TIMEOUT_MS;
        DebugPhysicalReplacementMainFocusGate.Decision lateDecision = gated(late, lateClock);
        assertFalse(lateDecision.allowMove);
        assertEquals("deadline_expired", lateDecision.reason.wire);
        assertEquals(1, lateDecision.reads);
    }

    @Test public void revokedGuardAndInterruptedWaitCloseWithoutAnotherRead() {
        FakeClock canceledClock = new FakeClock();
        Sequence canceled = new Sequence(canceledClock, evidence(
                DebugPhysicalReplacementMainFocusGate.Classification.UNKNOWN,
                DebugPhysicalReplacementMainFocusGate.Reason.AUTHORIZATION_REVOKED), exactMain());
        DebugPhysicalReplacementMainFocusGate.Decision canceledDecision = gated(canceled, canceledClock);
        assertFalse(canceledDecision.allowMove);
        assertEquals("authorization_revoked", canceledDecision.reason.wire);
        assertEquals(1, canceledDecision.reads);
        assertEquals(1, canceled.remaining());

        InterruptClock interruptedClock = new InterruptClock();
        Sequence interrupted = new Sequence(interruptedClock, classify(REAR), exactMain());
        DebugPhysicalReplacementMainFocusGate.Decision interruptedDecision = gated(interrupted, interruptedClock);
        assertFalse(interruptedDecision.allowMove);
        assertEquals("interrupted", interruptedDecision.reason.wire);
        assertEquals(1, interruptedDecision.reads);
        assertEquals(1, interrupted.remaining());
        Thread.interrupted();
    }

    @Test public void routeRequiresExactTaskTopAndUnambiguousMainFields() {
        assertEquals(DebugPhysicalReplacementMainFocusGate.Classification.EXACT_MAIN,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN, true, true, true).classification);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.DISPATCHER_FIELDS_CONFLICT,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN.replace("dispatcher_displayId=unique:0",
                        "dispatcher_displayId=conflict"), true, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.DISPATCHER_FIELDS_CONFLICT,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN.replace("dispatcher_mDisplayId=missing",
                        "dispatcher_mDisplayId=unique:2"), true, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.DISPATCHER_FIELDS_CONFLICT,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN.replace("dispatcher_segment=unique:0",
                        "dispatcher_segment=unique:2"), true, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.DISPATCHER_ROW_COUNT_INVALID,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN.replace("row_count=one", "row_count=multiple"),
                        true, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.FOCUS_NOT_MAIN,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN.replace("focus=main", "focus=none"),
                        true, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.TOP_NOT_EXACT,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN, true, false, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.TASK_NOT_MAIN,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN, false, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.ROUTE_UNAVAILABLE,
                DebugPhysicalReplacementMainFocusGate.classify(null, true, true, true).reason);
        assertEquals(DebugPhysicalReplacementMainFocusGate.Reason.PRECONDITION_UNKNOWN,
                DebugPhysicalReplacementMainFocusGate.classify(MAIN, true, true, false).reason);
    }

    @Test public void requestCarriesOnlyFixedModeAndClaimedGatedLicenseIsOneShot() {
        assertEquals("2:local:sample|rc5-main-focus-mode=gated",
                DebugPhysicalReplacementMainFocusGate.encodeRequestIdentity("2:local:sample",
                        DebugPhysicalReplacementMainFocusGate.Mode.GATED));
        DebugPhysicalReplacementMainFocusProbe.Gate gate = new DebugPhysicalReplacementMainFocusProbe.Gate();
        String identity = "2:local:sample";
        String aPackage = "dev.remrss.sample.rc5physicala";
        String aBase = aPackage + "/dev.remrss.physical.MainActivity";
        String bPackage = "dev.remrss.sample.rc5physicalb";
        String bBase = bPackage + "/dev.remrss.physical.MainActivity";
        assertTrue(gate.arm(42, 7L, 43, 2, identity, aPackage, aBase,
                DebugPhysicalReplacementMainFocusGate.Mode.GATED));
        assertEquals(DebugPhysicalReplacementMainFocusGate.Mode.GATED,
                gate.claim(42, 7L, 43, 2, identity, aPackage, aBase, bPackage, bBase, "op-1"));
        assertFalse(gate.consumeDispatchLicense("other-op"));
        assertTrue(gate.consumeDispatchLicense("op-1"));
        gate.clear();
        assertFalse(gate.consumeDispatchLicense("op-1"));

        DebugPhysicalReplacementMainFocusProbe.Gate cleared = new DebugPhysicalReplacementMainFocusProbe.Gate();
        assertTrue(cleared.arm(42, 7L, 43, 2, identity, aPackage, aBase,
                DebugPhysicalReplacementMainFocusGate.Mode.GATED));
        assertEquals(DebugPhysicalReplacementMainFocusGate.Mode.GATED,
                cleared.claim(42, 7L, 43, 2, identity, aPackage, aBase, bPackage, bBase, "op-2"));
        cleared.clear();
        assertFalse("clear invalidates a not-yet-consumed test dispatch license",
                cleared.consumeDispatchLicense("op-2"));
    }

    private static DebugPhysicalReplacementMainFocusGate.Decision gated(Sequence probe, FakeClock clock) {
        return DebugPhysicalReplacementMainFocusGate.evaluate(
                DebugPhysicalReplacementMainFocusGate.Mode.GATED, probe, clock);
    }
    private static DebugPhysicalReplacementMainFocusGate.Evidence exactMain() { return classify(MAIN); }
    private static DebugPhysicalReplacementMainFocusGate.Evidence classify(String route) {
        return DebugPhysicalReplacementMainFocusGate.classify(route, true, true, true);
    }
    private static DebugPhysicalReplacementMainFocusGate.Evidence evidence(
            DebugPhysicalReplacementMainFocusGate.Classification classification,
            DebugPhysicalReplacementMainFocusGate.Reason reason) {
        return new DebugPhysicalReplacementMainFocusGate.Evidence(classification, reason);
    }

    private static class FakeClock implements DebugPhysicalReplacementMainFocusGate.Clock {
        long now;
        int sleeps;
        @Override public long elapsedRealtime() { return now; }
        @Override public void sleep(long millis) throws InterruptedException { sleeps++; now += millis; }
    }
    private static final class InterruptClock extends FakeClock {
        @Override public void sleep(long millis) throws InterruptedException { throw new InterruptedException(); }
    }
    private static final class Sequence implements DebugPhysicalReplacementMainFocusGate.Probe {
        final Queue<DebugPhysicalReplacementMainFocusGate.Evidence> values = new ArrayDeque<>();
        final FakeClock clock;
        long readAdvanceMs;
        Sequence(FakeClock clock, DebugPhysicalReplacementMainFocusGate.Evidence... evidence) {
            this.clock = clock;
            for (DebugPhysicalReplacementMainFocusGate.Evidence value : evidence) values.add(value);
        }
        @Override public DebugPhysicalReplacementMainFocusGate.Evidence read() {
            clock.now += readAdvanceMs;
            return values.remove();
        }
        int remaining() { return values.size(); }
    }
}
