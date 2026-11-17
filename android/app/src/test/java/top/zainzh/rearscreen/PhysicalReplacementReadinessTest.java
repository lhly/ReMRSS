package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.json.JSONObject;
import org.junit.Test;

public final class PhysicalReplacementReadinessTest {
    @Test public void expectedVisibilityAndDrawTransitionCanSettleOnceWithinWindow() {
        FakeClock clock = new FakeClock();
        Deque<PhysicalReplacementReadiness.Sample> samples = new ArrayDeque<>(Arrays.asList(
                PhysicalReplacementReadiness.Sample.PENDING,
                PhysicalReplacementReadiness.Sample.PENDING,
                PhysicalReplacementReadiness.Sample.READY));
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(
                () -> { clock.samples++; return samples.removeFirst(); }, clock, 350L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.READY, result);
        assertEquals(3, clock.samples);
        assertEquals(2, clock.sleeps);
    }

    @Test public void r34TimingAdmitsOnlyOneThirdTransientObservationAndRechecksLocalGuards() {
        R34Clock clock = new R34Clock();
        int[] ownerChecks = {0}, journalChecks = {0}, displayChecks = {0};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(() -> {
            clock.starts.add(clock.now);
            ownerChecks[0]++;
            journalChecks[0]++;
            displayChecks[0]++;
            clock.now += clock.readDurations[clock.starts.size() - 1];
            return clock.starts.size() == 3
                    ? PhysicalReplacementReadiness.Sample.READY
                    : PhysicalReplacementReadiness.Sample.PENDING;
        }, clock, 400L, 4, 75L);

        assertEquals(PhysicalReplacementReadiness.Outcome.READY, result);
        assertEquals(Arrays.asList(4L, 197L, 354L), clock.starts);
        assertTrue(PhysicalReplacementReadiness.mayStartThirdPresentationRead(
                0L, clock.starts.get(2), 400L));
        assertEquals(3, ownerChecks[0]);
        assertEquals(3, journalChecks[0]);
        assertEquals(3, displayChecks[0]);
        assertEquals(2, clock.sleeps);
        assertEquals(3, clock.starts.size());
        assertEquals(429L, clock.now); // the in-flight exact third read may finish after the admission window
    }

    @Test public void r34ThirdPendingReadCannotAdmitAFourthRead() {
        R34Clock clock = new R34Clock();
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(() -> {
            clock.starts.add(clock.now);
            clock.now += clock.readDurations[clock.starts.size() - 1];
            return PhysicalReplacementReadiness.Sample.PENDING;
        }, clock, 400L, 4, 75L);

        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertEquals(Arrays.asList(4L, 197L, 354L), clock.starts);
        assertEquals(3, clock.starts.size());
        assertEquals(2, clock.sleeps);
        assertEquals(429L, clock.now);
    }

    @Test public void inventoryThatCrosses400msAdmissionDoesNotStartThirdExactQuery() {
        FakeClock clock = new FakeClock();
        clock.now = 390L;
        int exactQueries = 2;
        assertEquals(390L, clock.elapsedRealtime()); // this sample entered inside the window
        clock.now += 15L; // selected-display and inventory reads finish after the deadline

        if (PhysicalReplacementReadiness.mayStartThirdPresentationRead(0L,
                clock.elapsedRealtime(), 400L)) exactQueries++;

        assertEquals(405L, clock.elapsedRealtime());
        assertEquals(2, exactQueries);
    }

    @Test public void unknownOrForeignSampleStopsWithoutRetrying() {
        FakeClock clock = new FakeClock();
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(
                () -> { clock.samples++; return PhysicalReplacementReadiness.Sample.REJECTED; },
                clock, 350L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.REJECTED, result);
        assertEquals(1, clock.samples);
        assertEquals(0, clock.sleeps);
    }

    @Test public void onlyTwoExactSameBMainDisplayStaleRepliesOpenExistingThirdReadUntil900ms() {
        PhysicalReplacementReadiness.Clock clock = new ReplayClock(154L, 323L, 403L);
        int[] exactStale = {0};
        int[] reads = {0};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
            reads[0]++;
            if (reads[0] == 1 || reads[0] == 2) {
                exactStale[0]++;
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            assertEquals(403L, clock.elapsedRealtime());
            return PhysicalReplacementReadiness.Sample.READY;
        }, clock, 0L, () -> PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                400L, exactStale[0], reads[0], false), 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.READY, result);
        assertEquals(3, reads[0]); // the original third read, not an added retry
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 2, 3, false));
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 2, 2, true));
        assertEquals(900L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 2, 2, false));
    }

    @Test public void r47SchedulesThirdExactReadAt700msByReadStateNotOuterSampleNumber() {
        assertR47TimingReplay(false);
        assertR47TimingReplay(true);
    }

    private static void assertR47TimingReplay(boolean inventoryOnlyPrefix) {
        FakeClock clock = new FakeClock(); // B move return is elapsed time zero; sleep advances time normally.
        int[] exactMainStale = {0};
        int[] presentationReads = {0};
        int[] samples = {0};
        boolean[] delayScheduled = {false};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
            samples[0]++;
            if (inventoryOnlyPrefix && samples[0] == 1) {
                clock.now += 35L;
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            int read = ++presentationReads[0];
            if (read == 1) {
                assertEquals(inventoryOnlyPrefix ? 110L : 0L, clock.elapsedRealtime());
                exactMainStale[0]++;
                if (!inventoryOnlyPrefix) clock.now += 110L;
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            if (read == 2) {
                assertEquals(185L, clock.elapsedRealtime());
                exactMainStale[0]++;
                clock.now += 105L;
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            assertEquals(3, read);
            assertEquals(700L, clock.elapsedRealtime());
            return PhysicalReplacementReadiness.Sample.READY;
        }, clock, 0L, () -> PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                400L, exactMainStale[0], presentationReads[0], presentationReads[0] >= 3), () -> {
            long target = PhysicalReplacementReadiness.thirdPresentationReadNotBeforeElapsedMs(
                    0L, exactMainStale[0], presentationReads[0], delayScheduled[0]);
            if (target != Long.MIN_VALUE) delayScheduled[0] = true;
            return target;
        }, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.READY, result);
        assertEquals(inventoryOnlyPrefix ? 4 : 3, samples[0]);
        assertEquals(3, presentationReads[0]);
        assertTrue(delayScheduled[0]);
        assertEquals(Long.MIN_VALUE, PhysicalReplacementReadiness.thirdPresentationReadNotBeforeElapsedMs(
                0L, 2, 2, delayScheduled[0]));
        assertEquals(900L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 2, 2, false));
    }

    @Test public void fastPathAndNonExactStaleKeepOriginalWindowAndDoNotWaitFor700ms() {
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 0, 2, false));
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 1, 2, false));
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 2, 1, false));
        FakeClock clock = new FakeClock();
        int[] reads = {0};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
            reads[0]++;
            return PhysicalReplacementReadiness.Sample.PENDING;
        }, clock, 0L, () -> 400L, () -> Long.MIN_VALUE, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertTrue(clock.now < 700L);
        assertEquals(4, reads[0]);
    }

    @Test public void interruptedScheduledThirdReadStopsWithoutStartingIt() {
        FakeClock clock = new FakeClock() {
            @Override public void sleep(long millis) throws InterruptedException {
                if (now + millis >= 700L) throw new InterruptedException("guard interrupted");
                super.sleep(millis);
            }
        };
        int[] exactStale = {0};
        int[] reads = {0};
        boolean[] delayScheduled = {false};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
            reads[0]++;
            exactStale[0]++;
            return PhysicalReplacementReadiness.Sample.PENDING;
        }, clock, 110L, () -> PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                400L, exactStale[0], reads[0], false), () -> {
                    long target = PhysicalReplacementReadiness.thirdPresentationReadNotBeforeElapsedMs(
                            110L, exactStale[0], reads[0], delayScheduled[0]);
                    if (target != Long.MIN_VALUE) delayScheduled[0] = true;
                    return target;
                },
                4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertEquals(2, reads[0]);
        assertTrue(Thread.interrupted());
    }

    @Test public void thirdSampleStartedInExtensionButStillPendingDoesNotBuyAnotherSample() {
        ReplayClock clock = new ReplayClock(154L, 323L, 403L);
        int[] staleReplies = {0};
        int[] reads = {0};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
            reads[0]++;
            if (reads[0] <= 2) {
                staleReplies[0]++;
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            return PhysicalReplacementReadiness.Sample.PENDING; // e.g. B inventory is no longer ready
        }, clock, 0L,
                () -> PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                        400L, staleReplies[0], reads[0], reads[0] >= 3), 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertEquals(3, reads[0]);
    }

    @Test public void secondExactStaleReplyAfter400msStillAllowsOnlyThirdRead() {
        for (long secondFinished : new long[]{425L, 600L}) {
            for (boolean thirdReady : new boolean[]{true, false}) {
                FakeClock clock = new FakeClock();
                int[] staleReplies = {0};
                int[] reads = {0};
                PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
                    int read = ++reads[0];
                    if (read == 1) {
                        clock.now = 154L;
                        staleReplies[0]++;
                        return PhysicalReplacementReadiness.Sample.PENDING;
                    }
                    if (read == 2) {
                        assertEquals(229L, clock.now);
                        clock.now = secondFinished;
                        staleReplies[0]++;
                        return PhysicalReplacementReadiness.Sample.PENDING;
                    }
                    assertEquals(3, read);
                    assertEquals(secondFinished + 75L, clock.now);
                    return thirdReady ? PhysicalReplacementReadiness.Sample.READY
                            : PhysicalReplacementReadiness.Sample.PENDING;
                }, clock, 0L, () -> PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(
                        400L, staleReplies[0], reads[0], reads[0] >= 3), 4, 75L);
                assertEquals(3, reads[0]);
                assertEquals(thirdReady ? PhysicalReplacementReadiness.Outcome.READY
                        : PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
            }
        }
    }

    @Test public void staleFromWrongBOrPhaseDoesNotExtend400msWindow() throws Exception {
        JSONObject exact = new JSONObject().put("status", "unknown")
                .put("reason", "input_main_display_stale").put("task", 77)
                .put("package", "example.b").put("base", "example.b/.Main")
                .put("display", 9).put("selectedId", 9).put("selectedUnique", "rear-9");
        assertTrue(ControlledPhysicalCastOperations.isExactMainDisplayStaleReply(
                exact, 77, "example.b", "example.b/.Main", 9, "9:rear-9"));
        assertEquals(false, ControlledPhysicalCastOperations.isExactMainDisplayStaleReply(
                exact, 78, "example.b", "example.b/.Main", 9, "9:rear-9"));
        assertEquals(false, ControlledPhysicalCastOperations.isExactMainDisplayStaleReply(
                new JSONObject(exact.toString()).put("selectedUnique", "other-rear"),
                77, "example.b", "example.b/.Main", 9, "9:rear-9"));
        assertEquals(false, ControlledPhysicalCastOperations.isExactMainDisplayStaleReply(
                new JSONObject(exact.toString()).put("status", "ready"),
                77, "example.b", "example.b/.Main", 9, "9:rear-9"));
        assertEquals(false, ControlledPhysicalCastOperations.isExactMainDisplayStaleReply(
                new JSONObject(exact.toString()).put("reason", "input_not_ready"),
                77, "example.b", "example.b/.Main", 9, "9:rear-9"));

        PhysicalReplacementReadiness.Clock clock = new ReplayClock(154L, 323L, 403L);
        int[] reads = {0};
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.awaitFrom(() -> {
            reads[0]++;
            if (reads[0] == 3) {
                assertEquals(false, PhysicalReplacementReadiness.mayStartThirdPresentationRead(
                        0L, clock.elapsedRealtime(), 400L));
                return PhysicalReplacementReadiness.Sample.PENDING;
            }
            return PhysicalReplacementReadiness.Sample.PENDING;
        }, clock, 0L, () -> 400L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertEquals(2, reads[0]);
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 1, 2, false));
        assertEquals(400L, PhysicalReplacementReadiness.thirdReadWindowTimeoutMillis(400L, 2, 2, true));
    }

    @Test public void stuckPendingAndLateReadyRemainUnknownAfterFiniteWindow() {
        FakeClock clock = new FakeClock();
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(
                () -> { clock.samples++; return PhysicalReplacementReadiness.Sample.PENDING; },
                clock, 150L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertEquals(2, clock.samples);
        assertEquals(2, clock.sleeps);
    }

    @Test public void exactReadySampleStartedInsideWindowMayFinishAt351ms() {
        FakeClock clock = new FakeClock();
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(
                () -> { clock.samples++; clock.now = 351L; return PhysicalReplacementReadiness.Sample.READY; },
                clock, 350L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.READY, result);
        assertEquals(1, clock.samples);
    }

    @Test public void stagedFocusStartsReadyWindowAfterFocusReplyNotSlowBottomMove() {
        long bottomReturned = 1_000L;
        // Bottom dispatch consumes 358ms and the acknowledged focus call another 50ms.
        long focusReturned = bottomReturned + 408L;
        long start = PhysicalReplacementReadiness.readinessStartedAt(
                bottomReturned, true, true, focusReturned);
        assertEquals(focusReturned, start);
        assertFalse(PhysicalReplacementReadiness.mayStartThirdPresentationRead(
                bottomReturned, focusReturned + 1L, 400L));
        assertTrue(PhysicalReplacementReadiness.mayStartThirdPresentationRead(
                start, focusReturned + 1L, 400L));

        FakeClock clock = new FakeClock();
        clock.now = focusReturned + 1L;
        PhysicalReplacementReadiness.Outcome outcome = PhysicalReplacementReadiness.awaitFrom(
                () -> { clock.samples++; return PhysicalReplacementReadiness.Sample.READY; },
                clock, start, 400L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.READY, outcome);
        assertEquals(1, clock.samples);
    }

    @Test public void unacknowledgedFocusHasNoReadinessStart() {
        long start = PhysicalReplacementReadiness.readinessStartedAt(1_000L, true, false, 1_358L);
        assertEquals(PhysicalReplacementReadiness.NOT_STARTED, start);
    }

    @Test public void expiredWindowDoesNotStartAnotherRead() {
        FakeClock clock = new FakeClock();
        PhysicalReplacementReadiness.Outcome result = PhysicalReplacementReadiness.await(
                () -> { clock.samples++; return PhysicalReplacementReadiness.Sample.PENDING; },
                clock, 75L, 4, 75L);
        assertEquals(PhysicalReplacementReadiness.Outcome.UNKNOWN, result);
        assertEquals(1, clock.samples);
        assertEquals(1, clock.sleeps);
    }

    private static final class R34Clock implements PhysicalReplacementReadiness.Clock {
        long now = 4L;
        final long[] readDurations = {118L, 82L, 75L};
        final java.util.List<Long> starts = new java.util.ArrayList<>();
        int sleeps;
        @Override public long elapsedRealtime() { return now; }
        @Override public void sleep(long millis) throws InterruptedException { sleeps++; now += millis; }
    }

    private static class FakeClock implements PhysicalReplacementReadiness.Clock {
        long now;
        int samples, sleeps;
        @Override public long elapsedRealtime() { return now; }
        @Override public void sleep(long millis) throws InterruptedException { sleeps++; now += millis; }
    }

    private static final class ReplayClock implements PhysicalReplacementReadiness.Clock {
        long now;
        final long[] nextStarts;
        int sleepIndex;
        ReplayClock(long... starts) { nextStarts = starts; now = starts[0]; }
        @Override public long elapsedRealtime() { return now; }
        @Override public void sleep(long millis) {
            if (sleepIndex + 1 < nextStarts.length) now = nextStarts[++sleepIndex];
            else now += millis;
        }
    }
}
