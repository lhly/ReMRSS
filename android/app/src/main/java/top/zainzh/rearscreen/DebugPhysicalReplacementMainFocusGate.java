package top.zainzh.rearscreen;

/** Debug-only exact MAIN-focus comparison gate used only before the RC5 physical B move. */
final class DebugPhysicalReplacementMainFocusGate {
    static final long TIMEOUT_MS = 1_500L;
    static final long RETRY_INTERVAL_MS = 75L;
    static final int MAX_READS = 2;

    enum Mode { OBSERVE, GATED }
    enum Classification {
        EXACT_MAIN("exact_main"), OTHER_KNOWN("other_known"), UNKNOWN("unknown");
        final String wire;
        Classification(String wire) { this.wire = wire; }
    }
    enum Reason {
        EXACT_MAIN("exact_main"), FOCUS_NOT_MAIN("focus_not_main"), FOCUS_AMBIGUOUS("focus_ambiguous"),
        DISPATCHER_NOT_MAIN("dispatcher_not_main"), DISPATCHER_FIELDS_CONFLICT("dispatcher_fields_conflict"),
        DISPATCHER_ROW_COUNT_INVALID("dispatcher_row_count_invalid"), ROUTE_UNAVAILABLE("route_unavailable"),
        ROUTE_INVALID("route_invalid"), TOP_NOT_EXACT("top_not_exact"), TASK_NOT_MAIN("task_not_main"),
        PRECONDITION_UNKNOWN("precondition_unknown"), AUTHORIZATION_REVOKED("authorization_revoked"),
        RESERVATION_LOST("reservation_lost"), JOURNAL_CHANGED("journal_changed"), OWNER_CHANGED("owner_changed"),
        REAR_IDENTITY_CHANGED("rear_identity_changed"), TARGET_CHANGED("target_changed"),
        INTERRUPTED("interrupted"), DEADLINE_EXPIRED("deadline_expired"), SAMPLE_LIMIT("sample_limit"),
        ROLLBACK_UNVERIFIED("rollback_unverified"), ROLLBACK_COMPLETE("rollback_complete"),
        TRANSACTION_COMMITTED("transaction_committed"), UNKNOWN("unknown");
        final String wire;
        Reason(String wire) { this.wire = wire; }
    }

    interface Probe { Evidence read() throws Exception; }
    interface Clock { long elapsedRealtime(); void sleep(long millis) throws InterruptedException; }

    static final class Evidence {
        final Classification classification;
        final Reason reason;
        Evidence(Classification classification, Reason reason) {
            this.classification = classification == null ? Classification.UNKNOWN : classification;
            this.reason = reason == null ? Reason.UNKNOWN : reason;
        }
    }

    static final class Decision {
        final Mode mode;
        final boolean allowMove;
        final int reads;
        final long waitMs;
        final Evidence evidence;
        final Reason reason;
        Decision(Mode mode, boolean allowMove, int reads, long waitMs, Evidence evidence, Reason reason) {
            this.mode = mode; this.allowMove = allowMove; this.reads = reads;
            this.waitMs = Math.max(0L, waitMs); this.evidence = evidence; this.reason = reason;
        }
    }

    private DebugPhysicalReplacementMainFocusGate() { }

    /** Parse only the fixed privacy-safe route vocabulary; unknown syntax never opens the gate. */
    static Evidence classify(String payload, boolean sameExactMainTask, boolean exactTop,
            boolean exactPresentationReady) {
        if (!sameExactMainTask) return new Evidence(Classification.UNKNOWN, Reason.TASK_NOT_MAIN);
        if (!exactTop) return new Evidence(Classification.UNKNOWN, Reason.TOP_NOT_EXACT);
        if (!exactPresentationReady) return new Evidence(Classification.UNKNOWN, Reason.PRECONDITION_UNKNOWN);
        String safe = PhysicalTaskPresentationEvidence.validateRouteProbeV1(payload, "b");
        if (safe == null) return new Evidence(Classification.UNKNOWN,
                payload == null ? Reason.ROUTE_UNAVAILABLE : Reason.ROUTE_INVALID);
        String segment = field(safe, "dispatcher_segment");
        String display = field(safe, "dispatcher_displayId");
        String mDisplay = field(safe, "dispatcher_mDisplayId");
        String focus = field(safe, "focus");
        String rows = field(safe, "row_count");
        if ("conflict".equals(segment) || "conflict".equals(display) || "conflict".equals(mDisplay))
            return new Evidence(Classification.UNKNOWN, Reason.DISPATCHER_FIELDS_CONFLICT);
        if (!"one".equals(rows))
            return new Evidence(Classification.UNKNOWN, Reason.DISPATCHER_ROW_COUNT_INVALID);
        if ("missing".equals(segment) || "missing".equals(display))
            return new Evidence(Classification.UNKNOWN, Reason.ROUTE_INVALID);
        if ("missing".equals(mDisplay) == false && !mDisplay.startsWith("unique:"))
            return new Evidence(Classification.UNKNOWN, Reason.ROUTE_INVALID);
        if ((display.startsWith("unique:") && mDisplay.startsWith("unique:") && !display.equals(mDisplay))
                || (segment.startsWith("unique:") && display.startsWith("unique:") && !segment.equals(display))
                || (segment.startsWith("unique:") && mDisplay.startsWith("unique:") && !segment.equals(mDisplay)))
            return new Evidence(Classification.UNKNOWN, Reason.DISPATCHER_FIELDS_CONFLICT);
        if (!"unique:0".equals(segment) || !"unique:0".equals(display)
                || (!"missing".equals(mDisplay) && !"unique:0".equals(mDisplay)))
            return new Evidence(Classification.OTHER_KNOWN, Reason.DISPATCHER_NOT_MAIN);
        if ("main".equals(focus)) return new Evidence(Classification.EXACT_MAIN, Reason.EXACT_MAIN);
        if ("none".equals(focus) || "rear".equals(focus))
            return new Evidence(Classification.OTHER_KNOWN, Reason.FOCUS_NOT_MAIN);
        if ("both".equals(focus)) return new Evidence(Classification.UNKNOWN, Reason.FOCUS_AMBIGUOUS);
        return new Evidence(Classification.UNKNOWN, Reason.ROUTE_INVALID);
    }

    static String encodeRequestIdentity(String selectedRearIdentity, Mode mode) {
        if (selectedRearIdentity == null || mode == null) return null;
        return selectedRearIdentity + "|rc5-main-focus-mode="
                + mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    static Decision evaluate(Mode mode, Probe probe, Clock clock) {
        if (mode == null || probe == null || clock == null)
            return new Decision(mode, false, 0, 0L,
                    new Evidence(Classification.UNKNOWN, Reason.PRECONDITION_UNKNOWN), Reason.PRECONDITION_UNKNOWN);
        long started = clock.elapsedRealtime();
        Evidence first = readOrUnknown(probe);
        long elapsed = nonNegativeElapsed(started, clock.elapsedRealtime());
        if (mode == Mode.OBSERVE)
            return new Decision(mode, true, 1, elapsed, first, first.reason);
        if (elapsed >= TIMEOUT_MS)
            return new Decision(mode, false, 1, elapsed, first, Reason.DEADLINE_EXPIRED);
        if (first.classification == Classification.EXACT_MAIN)
            return new Decision(mode, true, 1, elapsed, first, Reason.EXACT_MAIN);
        if (first.classification == Classification.UNKNOWN)
            return new Decision(mode, false, 1, elapsed, first, first.reason);
        long remaining = TIMEOUT_MS - elapsed;
        if (remaining <= 0L)
            return new Decision(mode, false, 1, elapsed, first, Reason.DEADLINE_EXPIRED);
        try { clock.sleep(Math.min(RETRY_INTERVAL_MS, remaining)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Decision(mode, false, 1, nonNegativeElapsed(started, clock.elapsedRealtime()),
                    first, Reason.INTERRUPTED);
        }
        if (clock.elapsedRealtime() - started >= TIMEOUT_MS)
            return new Decision(mode, false, 1, nonNegativeElapsed(started, clock.elapsedRealtime()),
                    first, Reason.DEADLINE_EXPIRED);
        Evidence second = readOrUnknown(probe);
        elapsed = nonNegativeElapsed(started, clock.elapsedRealtime());
        if (elapsed >= TIMEOUT_MS)
            return new Decision(mode, false, 2, elapsed, second, Reason.DEADLINE_EXPIRED);
        if (second.classification == Classification.EXACT_MAIN)
            return new Decision(mode, true, 2, elapsed, second, Reason.EXACT_MAIN);
        Reason reason = second.classification == Classification.UNKNOWN
                ? second.reason : elapsed >= TIMEOUT_MS ? Reason.DEADLINE_EXPIRED : Reason.SAMPLE_LIMIT;
        return new Decision(mode, false, 2, elapsed, second, reason);
    }

    private static Evidence readOrUnknown(Probe probe) {
        try {
            Evidence result = probe.read();
            return result == null ? new Evidence(Classification.UNKNOWN, Reason.PRECONDITION_UNKNOWN) : result;
        } catch (Exception | LinkageError unavailable) {
            return new Evidence(Classification.UNKNOWN, Reason.PRECONDITION_UNKNOWN);
        }
    }

    private static long nonNegativeElapsed(long started, long now) {
        return now < started ? 0L : now - started;
    }

    private static String field(String payload, String key) {
        String prefix = key + "=";
        for (String token : payload.split(" ")) if (token.startsWith(prefix)) return token.substring(prefix.length());
        return "";
    }
}
