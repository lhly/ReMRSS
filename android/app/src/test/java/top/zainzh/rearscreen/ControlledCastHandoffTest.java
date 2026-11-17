package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ControlledCastHandoffTest {
    private static final ControlledCastHandoff.Identity A = identity("A", 1, 31, "a/.Main");
    private static final ControlledCastHandoff.Identity B = identity("B", 2, 42, "b/.Main");

    private static ControlledCastHandoff.Identity identity(String session, long generation, int task,
                                                            String component) {
        return new ControlledCastHandoff.Identity(ControlledCastHandoff.Backend.VIRTUAL,
                "owner-" + session, session, generation, task, component, 7);
    }

    private static class Fake implements ControlledCastHandoff.Port {
        final List<String> events = new ArrayList<>();
        ControlledCastHandoff.ObservedOwner owner = ControlledCastHandoff.ObservedOwner.EXACT_A;
        ControlledCastHandoff.ObservedOwner ownerAfterHandoff = ControlledCastHandoff.ObservedOwner.EXACT_A;
        ControlledCastHandoff.MoveResult move = ControlledCastHandoff.MoveResult.REJECTED;
        boolean outputReady = true, inputReady = true, releaseThrows;
        ControlledCastHandoff.Candidate candidate;

        @Override public boolean isCurrent(ControlledCastHandoff.Identity identity) {
            events.add("current:" + identity.session);
            return identity.equals(A) && owner == ControlledCastHandoff.ObservedOwner.EXACT_A;
        }
        @Override public boolean isCapturedOwnerCurrentFast(ControlledCastHandoff.Identity identity) {
            events.add("fast_current:" + identity.session);
            return identity.equals(A) && owner == ControlledCastHandoff.ObservedOwner.EXACT_A;
        }
        @Override public ControlledCastHandoff.Candidate prepare(ControlledCastHandoff.Identity previous,
                                                                  ControlledCastHandoff.Identity next) {
            events.add("prepare");
            candidate = new ControlledCastHandoff.Candidate(previous, next, "B-owned-resource",
                    outputReady, inputReady);
            return candidate;
        }
        @Override public ControlledCastHandoff.MoveResult handoff(ControlledCastHandoff.Candidate candidate) {
            events.add("handoff");
            if (move == ControlledCastHandoff.MoveResult.VERIFIED_B)
                owner = ControlledCastHandoff.ObservedOwner.EXACT_B;
            else if (move == ControlledCastHandoff.MoveResult.SENT_UNVERIFIED)
                owner = ownerAfterHandoff;
            return move;
        }
        @Override public ControlledCastHandoff.ObservedOwner reconcile(ControlledCastHandoff.Identity previous,
                                                                       ControlledCastHandoff.Identity next) {
            events.add("reconcile"); return owner;
        }
        @Override public void releaseCandidate(ControlledCastHandoff.Candidate candidate) {
            events.add("release:B");
            if (releaseThrows) throw new IllegalStateException("cleanup");
        }
        @Override public void releasePrevious(ControlledCastHandoff.Identity previous) {
            events.add("release:A");
        }
    }

    public static final class FakeComponent {
        private final String value;
        FakeComponent(String value) { this.value = value; }
        public String flattenToString() { return value; }
    }
    public static final class FakeTaskRow {
        public final int taskId, displayId;
        public final Object baseActivity;
        FakeTaskRow(int task, int display, String base) {
            taskId = task; displayId = display; baseActivity = new FakeComponent(base);
        }
    }

    @Test public void fullCandidateComparisonIgnoresRenderDensityButNotSafeAreaInsets() {
        RearCastViewport safe = RearCastViewport.safe(720, 1280, 0, 440, 10, 20, 10, 30);
        RearCastViewport fullAtOtherDensity = safe.full().buffered(1080, 1920);
        assertTrue(safe.samePhysicalArea(safe));
        assertFalse("A's safe baseline must not be compared as though it were FULL", safe.samePhysicalArea(fullAtOtherDensity));
        assertTrue("B FULL may render with a different buffer/density while retaining the physical full area",
                safe.full().samePhysicalArea(fullAtOtherDensity));
    }

    @Test public void exactTaskBaseRequiresOneMatchingTypedRowAndPreservesFlattenedIdentity() {
        List<Object> rows = new ArrayList<>();
        rows.add(new FakeTaskRow(31, 7, "test.a/.Main"));
        rows.add(new FakeTaskRow(31, 0, "test.a/.Other"));
        assertEquals("test.a/test.a.Main", RearDisplayCastOccupancyReader.exactTaskBaseComponent(rows, 31, 7));
        assertEquals(null, RearDisplayCastOccupancyReader.exactTaskBaseComponent(rows, 31, 8));
        rows.add(new FakeTaskRow(31, 7, "test.a/.Main"));
        assertEquals(null, RearDisplayCastOccupancyReader.exactTaskBaseComponent(rows, 31, 7));
    }

    private static ControlledCastHandoff.Gate directGate() {
        return new ControlledCastHandoff.Gate() {
            final class Token implements ControlledCastHandoff.Reservation {
                boolean current = true;
                @Override public boolean isCurrent() { return current; }
            }
            @Override public ControlledCastHandoff.Reservation reserve(ControlledCastHandoff.Identity previous,
                                                                       ControlledCastHandoff.Identity next) {
                return new Token();
            }
            @Override public boolean complete(ControlledCastHandoff.Reservation reservation,
                                              ControlledCastHandoff.Outcome outcome,
                                              ControlledCastHandoff.Identity owner) {
                ((Token) reservation).current = false;
                return true;
            }
        };
    }

    @Test public void lateFailureKeepsExactAAndReleasesOnlyB() {
        Fake fake = new Fake();
        fake.move = ControlledCastHandoff.MoveResult.SENT_UNVERIFIED;
        ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, fake, directGate());
        assertEquals(ControlledCastHandoff.Outcome.ABORTED_A_RETAINED, result.outcome);
        assertEquals(A, result.owner);
        assertTrue(result.candidateReleased);
        assertFalse(result.previousReleased);
        assertEquals(1, fake.events.stream().filter("release:B"::equals).count());
        assertFalse(fake.events.contains("release:A"));
    }

    @Test public void sentButUnverifiedMoveThatActuallyReachedBCommitsAfterExactReconciliation() {
        Fake fake = new Fake();
        fake.move = ControlledCastHandoff.MoveResult.SENT_UNVERIFIED;
        // The command changes the real owner before its reply is lost.
        fake.ownerAfterHandoff = ControlledCastHandoff.ObservedOwner.EXACT_B;
        ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, fake, directGate());
        assertEquals(ControlledCastHandoff.Outcome.COMMITTED, result.outcome);
        assertEquals(B, result.owner);
        assertTrue(result.previousReleased);
        assertFalse(result.candidateReleased);
        assertTrue(fake.events.contains("release:A"));
        assertFalse(fake.events.contains("release:B"));
    }

    @Test public void candidateWithoutRealOutputOrInputReadinessNeverEntersHandoff() {
        for (boolean output : new boolean[] {false, true}) {
            Fake fake = new Fake();
            fake.outputReady = output;
            fake.inputReady = !output;
            ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, fake, directGate());
            assertEquals(ControlledCastHandoff.Outcome.ABORTED_A_RETAINED, result.outcome);
            assertFalse(fake.events.contains("handoff"));
            assertFalse(fake.events.contains("release:A"));
            assertTrue(fake.events.contains("release:B"));
        }
    }

    @Test public void unknownOwnerDoesNotDestroyCandidateThatMayAlreadyBeVisible() {
        Fake fake = new Fake();
        fake.move = ControlledCastHandoff.MoveResult.SENT_UNVERIFIED;
        fake.ownerAfterHandoff = ControlledCastHandoff.ObservedOwner.OTHER_OR_UNKNOWN;
        ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, fake, directGate());
        assertEquals(ControlledCastHandoff.Outcome.INDETERMINATE, result.outcome);
        assertEquals(null, result.owner);
        assertFalse(result.candidateReleased);
        assertFalse(fake.events.contains("release:A"));
        assertFalse(fake.events.contains("release:B"));
    }

    @Test public void mismatchedCandidateIdentityIsReleasedWithoutTouchingA() {
        Fake fake = new Fake() {
            @Override public ControlledCastHandoff.Candidate prepare(ControlledCastHandoff.Identity previous,
                                                                      ControlledCastHandoff.Identity next) {
                events.add("prepare");
                return new ControlledCastHandoff.Candidate(previous, identity("stale", 9, 44, "x/.Main"),
                        "B-owned-resource", true, true);
            }
        };
        ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, fake, directGate());
        assertEquals(ControlledCastHandoff.Outcome.ABORTED_A_RETAINED, result.outcome);
        assertTrue(result.candidateReleased);
        assertFalse(fake.events.contains("handoff"));
        assertFalse(fake.events.contains("release:A"));
    }

    @Test public void slowBackendHandoffRunsOutsideTheDisplayActionGate() {
        Fake fake = new Fake();
        fake.move = ControlledCastHandoff.MoveResult.VERIFIED_B;
        final boolean[] insideGate = {false};
        ControlledCastHandoff.Gate gate = new ControlledCastHandoff.Gate() {
            final class Token implements ControlledCastHandoff.Reservation {
                @Override public boolean isCurrent() { return true; }
            }
            @Override public ControlledCastHandoff.Reservation reserve(ControlledCastHandoff.Identity previous,
                                                                       ControlledCastHandoff.Identity next) {
                insideGate[0] = true;
                try { return new Token(); } finally { insideGate[0] = false; }
            }
            @Override public boolean complete(ControlledCastHandoff.Reservation reservation,
                                              ControlledCastHandoff.Outcome outcome,
                                              ControlledCastHandoff.Identity owner) {
                insideGate[0] = true;
                try { return true; } finally { insideGate[0] = false; }
            }
        };
        ControlledCastHandoff.Port wrapped = new ControlledCastHandoff.Port() {
            @Override public boolean isCurrent(ControlledCastHandoff.Identity identity) throws Exception {
                return fake.isCurrent(identity);
            }
            @Override public boolean isCapturedOwnerCurrentFast(ControlledCastHandoff.Identity identity) {
                return fake.isCapturedOwnerCurrentFast(identity);
            }
            @Override public ControlledCastHandoff.Candidate prepare(ControlledCastHandoff.Identity previous,
                                                                      ControlledCastHandoff.Identity next) throws Exception {
                return fake.prepare(previous, next);
            }
            @Override public ControlledCastHandoff.MoveResult handoff(ControlledCastHandoff.Candidate candidate) {
                assertFalse("backend handoff must never hold the display gate", insideGate[0]);
                return fake.handoff(candidate);
            }
            @Override public ControlledCastHandoff.ObservedOwner reconcile(ControlledCastHandoff.Identity previous,
                                                                           ControlledCastHandoff.Identity next) throws Exception {
                return fake.reconcile(previous, next);
            }
            @Override public void releaseCandidate(ControlledCastHandoff.Candidate candidate) throws Exception {
                fake.releaseCandidate(candidate);
            }
            @Override public void releasePrevious(ControlledCastHandoff.Identity previous) throws Exception {
                fake.releasePrevious(previous);
            }
        };
        ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, wrapped, gate);
        assertEquals(ControlledCastHandoff.Outcome.COMMITTED, result.outcome);
    }

    @Test public void expiredOwnerClaimCannotCommitOrTearDownEitherSide() {
        Fake fake = new Fake();
        fake.move = ControlledCastHandoff.MoveResult.VERIFIED_B;
        ControlledCastHandoff.Gate gate = new ControlledCastHandoff.Gate() {
            final class Token implements ControlledCastHandoff.Reservation {
                boolean current = true;
                @Override public boolean isCurrent() { return current; }
            }
            Token token;
            @Override public ControlledCastHandoff.Reservation reserve(ControlledCastHandoff.Identity previous,
                                                                       ControlledCastHandoff.Identity next) {
                token = new Token(); return token;
            }
            @Override public boolean complete(ControlledCastHandoff.Reservation reservation,
                                              ControlledCastHandoff.Outcome outcome,
                                              ControlledCastHandoff.Identity owner) {
                return false; // A newer owner/generation won while backend work was in flight.
            }
        };
        ControlledCastHandoff.Result result = ControlledCastHandoff.execute(A, B, fake, gate);
        assertEquals(ControlledCastHandoff.Outcome.INDETERMINATE, result.outcome);
        assertFalse(result.previousReleased);
        assertFalse(result.candidateReleased);
        assertFalse(fake.events.contains("release:A"));
        assertFalse(fake.events.contains("release:B"));
    }
}
