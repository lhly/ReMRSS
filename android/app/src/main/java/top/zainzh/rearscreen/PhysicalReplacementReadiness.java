package top.zainzh.rearscreen;

/** One bounded readiness window after the final exact B dispatch; never wakes or repeats a move. */
final class PhysicalReplacementReadiness {
    static final long NOT_STARTED = Long.MIN_VALUE;
    enum Sample { READY, PENDING, REJECTED }
    enum Outcome { READY, REJECTED, UNKNOWN }
    interface Probe { Sample read() throws Exception; }
    interface Clock { long elapsedRealtime(); void sleep(long millis) throws InterruptedException; }
    interface TimeoutPolicy { long timeoutMillis(); }
    interface SampleSchedule { long notBeforeElapsedMs(); }

    private PhysicalReplacementReadiness() { }

    /** The staged Debug experiment starts visible-read timing only after acknowledged focus. */
    static long readinessStartedAt(long moveReturnedElapsedMs, boolean stagedFocus,
            boolean focusAcknowledged, long focusReturnedElapsedMs) {
        if (!stagedFocus) return moveReturnedElapsedMs;
        return focusAcknowledged && focusReturnedElapsedMs >= moveReturnedElapsedMs
                ? focusReturnedElapsedMs : NOT_STARTED;
    }

    /** Gate only the newly added third exact read; the two-read baseline is unchanged. */
    static boolean mayStartThirdPresentationRead(long startedElapsedMs, long nowElapsedMs,
            long timeoutMillis) {
        if (timeoutMillis <= 0 || nowElapsedMs < startedElapsedMs) return false;
        long deadline = startedElapsedMs > Long.MAX_VALUE - timeoutMillis
                ? Long.MAX_VALUE : startedElapsedMs + timeoutMillis;
        return nowElapsedMs < deadline;
    }

    static long thirdReadWindowTimeoutMillis(long baseTimeoutMillis, int exactMainStaleReplies,
            int presentationReads, boolean extendedThirdSampleUsed) {
        return baseTimeoutMillis > 0 && exactMainStaleReplies >= 2
                && presentationReads == 2 && !extendedThirdSampleUsed
                ? baseTimeoutMillis + 500L : baseTimeoutMillis;
    }

    static long thirdPresentationReadNotBeforeElapsedMs(long startedElapsedMs,
            int exactMainStaleReplies, int presentationReads, boolean delayAlreadyScheduled) {
        if (exactMainStaleReplies < 2 || presentationReads != 2 || delayAlreadyScheduled)
            return Long.MIN_VALUE;
        return startedElapsedMs > Long.MAX_VALUE - 700L ? Long.MAX_VALUE : startedElapsedMs + 700L;
    }

    static Outcome await(Probe probe, Clock clock, long timeoutMillis, int maxSamples, long intervalMillis) {
        if (probe == null || clock == null || timeoutMillis <= 0 || maxSamples <= 0 || intervalMillis < 0)
            return Outcome.UNKNOWN;
        return awaitFrom(probe, clock, clock.elapsedRealtime(), timeoutMillis, maxSamples, intervalMillis);
    }

    static Outcome awaitFrom(Probe probe, Clock clock, long startedElapsedMs,
            long timeoutMillis, int maxSamples, long intervalMillis) {
        return awaitFrom(probe, clock, startedElapsedMs, () -> timeoutMillis, maxSamples, intervalMillis);
    }

    static Outcome awaitFrom(Probe probe, Clock clock, long startedElapsedMs,
            TimeoutPolicy timeoutPolicy, int maxSamples, long intervalMillis) {
        return awaitFrom(probe, clock, startedElapsedMs, timeoutPolicy, () -> Long.MIN_VALUE,
                maxSamples, intervalMillis);
    }

    static Outcome awaitFrom(Probe probe, Clock clock, long startedElapsedMs,
            TimeoutPolicy timeoutPolicy, SampleSchedule schedule, int maxSamples, long intervalMillis) {
        if (probe == null || clock == null || timeoutPolicy == null
                || schedule == null || timeoutPolicy.timeoutMillis() <= 0 || maxSamples <= 0 || intervalMillis < 0)
            return Outcome.UNKNOWN;
        long started = startedElapsedMs;
        for (int sample = 1; sample <= maxSamples; sample++) {
            long notBefore = schedule.notBeforeElapsedMs();
            if (notBefore != Long.MIN_VALUE && clock.elapsedRealtime() < notBefore) {
                long wait = notBefore - clock.elapsedRealtime();
                long remaining = deadline(started, timeoutPolicy.timeoutMillis()) - clock.elapsedRealtime();
                if (remaining <= 0) return Outcome.UNKNOWN;
                try { clock.sleep(Math.min(wait, remaining)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Outcome.UNKNOWN;
                }
            }
            if (sample > 1 && clock.elapsedRealtime() >= deadline(started, timeoutPolicy.timeoutMillis()))
                return Outcome.UNKNOWN;
            final Sample observed;
            try { observed = probe.read(); }
            catch (Exception unknown) { return Outcome.UNKNOWN; }
            // Window limits starting another read, not the completion time of an exact
            // read already in flight. Production probes have their own bounded sample.
            if (observed == Sample.READY) return Outcome.READY;
            if (observed != Sample.PENDING) return Outcome.REJECTED;
            // Recompute after the reply: the second exact stale result can grant the
            // one remaining read even if that second result completes after the base deadline.
            if (sample == maxSamples) return Outcome.UNKNOWN;
            long remaining = deadline(started, timeoutPolicy.timeoutMillis()) - clock.elapsedRealtime();
            if (remaining <= 0) return Outcome.UNKNOWN;
            try { clock.sleep(Math.min(intervalMillis, remaining)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Outcome.UNKNOWN;
            }
        }
        return Outcome.UNKNOWN;
    }

    private static long deadline(long started, long timeoutMillis) {
        if (timeoutMillis <= 0) return started;
        return started > Long.MAX_VALUE - timeoutMillis ? Long.MAX_VALUE : started + timeoutMillis;
    }
}
