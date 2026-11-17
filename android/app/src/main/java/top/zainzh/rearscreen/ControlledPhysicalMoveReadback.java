package top.zainzh.rearscreen;

/** Bounded exact readback for a single physical root move; it never retries the move itself. */
final class ControlledPhysicalMoveReadback {
    enum Dispatch { REJECTED_BEFORE_SEND, SENT, MAY_HAVE_BEEN_SENT }
    enum Placement { TARGET_EXACT, SOURCE_EXACT, OTHER_OR_FOREIGN, UNKNOWN }
    enum Outcome { REJECTED, CONFIRMED, UNKNOWN }

    interface Reader { Placement read() throws Exception; }
    interface Clock { long elapsedRealtime(); void sleep(long millis) throws InterruptedException; }

    private ControlledPhysicalMoveReadback() { }

    static Outcome confirm(Dispatch dispatch, Reader reader, Clock clock,
            long timeoutMillis, int maxReads, long intervalMillis) {
        if (dispatch == Dispatch.REJECTED_BEFORE_SEND) return Outcome.REJECTED;
        if (dispatch == null || reader == null || clock == null || timeoutMillis <= 0
                || maxReads <= 0 || intervalMillis < 0) return Outcome.UNKNOWN;
        final long started = clock.elapsedRealtime();
        final long deadline = started > Long.MAX_VALUE - timeoutMillis
                ? Long.MAX_VALUE : started + timeoutMillis;
        for (int read = 1; read <= maxReads; read++) {
            // The deadline governs whether another read may begin. A read already in flight
            // may finish after it and still prove the exact target; UNKNOWN/SOURCE never do.
            if (read > 1 && clock.elapsedRealtime() >= deadline) return Outcome.UNKNOWN;
            final Placement placement;
            try { placement = reader.read(); }
            catch (Exception unknown) { return Outcome.UNKNOWN; }
            if (placement == Placement.TARGET_EXACT) return Outcome.CONFIRMED;
            if (placement == Placement.OTHER_OR_FOREIGN) return Outcome.UNKNOWN;
            if (placement != Placement.SOURCE_EXACT && placement != Placement.UNKNOWN)
                return Outcome.UNKNOWN;
            if (read == maxReads || clock.elapsedRealtime() >= deadline) return Outcome.UNKNOWN;
            long remaining = deadline - clock.elapsedRealtime();
            if (remaining <= 0) return Outcome.UNKNOWN;
            try { clock.sleep(Math.min(intervalMillis, remaining)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Outcome.UNKNOWN;
            }
        }
        return Outcome.UNKNOWN;
    }
}
