package top.zainzh.rearscreen;

import java.util.Objects;

/**
 * Internal staged ownership handoff. Preparation, backend action, reconciliation, and cleanup
 * happen outside the display-action gate. The gate only reserves/completes the exact owner claim.
 *
 * The port is responsible for reporting verified runtime facts. In particular, SENT is not a
 * successful move: the coordinator reconciles it against exact A/B identities before deciding.
 */
final class ControlledCastHandoff {
    enum Backend { PHYSICAL, VIRTUAL }
    enum MoveResult { VERIFIED_B, REJECTED, SENT_UNVERIFIED }
    enum ObservedOwner { EXACT_A, EXACT_B, OTHER_OR_UNKNOWN }
    enum Outcome { COMMITTED, ABORTED_A_RETAINED, INDETERMINATE }
    enum Stage { REQUEST, PREPARE, CANDIDATE_READY, FINAL_GATE, HANDOFF, RECONCILE, CLEANUP }

    static final class Identity {
        final Backend backend;
        final String owner;
        final String session;
        final long generation;
        final int taskId;
        final String baseComponent;
        final int rearDisplayId;

        Identity(Backend backend, String owner, String session, long generation, int taskId,
                 String baseComponent, int rearDisplayId) {
            this.backend = Objects.requireNonNull(backend);
            this.owner = Objects.requireNonNull(owner);
            this.session = Objects.requireNonNull(session);
            this.generation = generation;
            this.taskId = taskId;
            this.baseComponent = Objects.requireNonNull(baseComponent);
            this.rearDisplayId = rearDisplayId;
            if (owner.isEmpty() || session.isEmpty() || generation <= 0 || taskId <= 0
                    || baseComponent.isEmpty() || rearDisplayId <= 0)
                throw new IllegalArgumentException("Incomplete cast identity");
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity that = (Identity) other;
            return backend == that.backend && generation == that.generation && taskId == that.taskId
                    && rearDisplayId == that.rearDisplayId && owner.equals(that.owner)
                    && session.equals(that.session) && baseComponent.equals(that.baseComponent);
        }

        @Override public int hashCode() {
            return Objects.hash(backend, owner, session, generation, taskId, baseComponent, rearDisplayId);
        }
    }

    static final class Candidate {
        final Identity previous;
        final Identity next;
        final Object resource;
        final boolean outputReady;
        final boolean inputReady;

        Candidate(Identity previous, Identity next, Object resource, boolean outputReady,
                  boolean inputReady) {
            this.previous = Objects.requireNonNull(previous);
            this.next = Objects.requireNonNull(next);
            this.resource = Objects.requireNonNull(resource);
            this.outputReady = outputReady;
            this.inputReady = inputReady;
        }
    }

    interface Port {
        /** Read-only exact task/session/display owner check; may query the privileged backend. */
        boolean isCurrent(Identity identity) throws Exception;
        /** In-memory owner/generation check only; must be bounded and must not perform IPC. */
        boolean isCapturedOwnerCurrentFast(Identity identity);
        /** Prepare B without destroying or pausing A. On failure, the backend cleans partial B resources. */
        Candidate prepare(Identity previous, Identity requestedNext) throws Exception;
        /** Backend handoff may involve framework/IPC work; it is never called under the gate. */
        MoveResult handoff(Candidate candidate) throws Exception;
        /** Re-read exact runtime state after a rejected or uncertain handoff. */
        ObservedOwner reconcile(Identity previous, Identity next) throws Exception;
        /** Release only candidate-owned resources; idempotent and safe after owner change. */
        void releaseCandidate(Candidate candidate) throws Exception;
        /** Release A only after B is verified as owner and actually visible. */
        void releasePrevious(Identity previous) throws Exception;
    }

    interface Gate {
        /** Atomically reserve the captured A generation; null means busy/stale. Must be in-memory. */
        Reservation reserve(Identity previous, Identity next);
        /** Finish only this exact reservation; stale callbacks must return false. Must be in-memory. */
        boolean complete(Reservation reservation, Outcome outcome, Identity owner);
    }
    interface Reservation {
        /** Fast in-memory check, not an OEM/framework query. */
        boolean isCurrent();
    }

    static final class Result {
        final Outcome outcome;
        final Stage stage;
        final String reason;
        final Identity owner;
        final boolean candidateReleased;
        final boolean previousReleased;

        private Result(Outcome outcome, Stage stage, String reason, Identity owner,
                       boolean candidateReleased, boolean previousReleased) {
            this.outcome = outcome;
            this.stage = stage;
            this.reason = reason;
            this.owner = owner;
            this.candidateReleased = candidateReleased;
            this.previousReleased = previousReleased;
        }
    }

    private ControlledCastHandoff() { }

    static Result execute(Identity previous, Identity requestedNext, Port port, Gate gate) {
        if (previous == null || requestedNext == null || port == null || gate == null
                || previous.equals(requestedNext) || previous.rearDisplayId != requestedNext.rearDisplayId)
            return result(Outcome.ABORTED_A_RETAINED, Stage.REQUEST, "invalid_request", previous, false, false);

        Candidate candidate = null;
        boolean candidateReleased = false;
        boolean committed = false;
        Reservation reservation = null;
        ObservedOwner observedOwner = ObservedOwner.OTHER_OR_UNKNOWN;
        Stage stage = Stage.REQUEST;
        String reason = "";
        try {
            if (!port.isCurrent(previous))
                return result(Outcome.ABORTED_A_RETAINED, stage, "stale_previous_owner", previous, false, false);
            stage = Stage.PREPARE;
            candidate = port.prepare(previous, requestedNext);
            if (candidate == null || !previous.equals(candidate.previous)
                    || !requestedNext.equals(candidate.next) || !candidate.outputReady || !candidate.inputReady) {
                reason = "candidate_not_ready_or_identity_mismatch";
                stage = Stage.CANDIDATE_READY;
            } else {
                stage = Stage.FINAL_GATE;
                reservation = gate.reserve(previous, requestedNext);
                if (reservation == null || !reservation.isCurrent()
                        || !port.isCapturedOwnerCurrentFast(previous)) {
                    reason = "owner_changed_before_handoff";
                    stage = Stage.RECONCILE;
                } else {
                    stage = Stage.HANDOFF;
                    // This can be a slow privileged task move or bounded reader/output switch.
                    // The reservation prevents a competing owner transaction without holding
                    // DisplayActionGate while the external action is in flight.
                    MoveResult move = port.handoff(candidate);
                    stage = Stage.RECONCILE;
                    observedOwner = port.reconcile(previous, requestedNext);
                    if (observedOwner == ObservedOwner.EXACT_B) committed = true;
                    else if (observedOwner == ObservedOwner.EXACT_A)
                        reason = move == MoveResult.REJECTED ? "handoff_rejected" : "handoff_not_committed";
                    else reason = "handoff_owner_unverified";
                }
            }
        } catch (Exception failure) {
            stage = stage == Stage.HANDOFF ? Stage.RECONCILE : stage;
            reason = failure.getClass().getSimpleName();
            if (candidate != null) {
                try {
                    observedOwner = port.reconcile(previous, requestedNext);
                    if (observedOwner == ObservedOwner.EXACT_B) committed = true;
                    else if (observedOwner == ObservedOwner.OTHER_OR_UNKNOWN) reason = "handoff_owner_unverified";
                } catch (Exception unreadable) { reason = "handoff_owner_unverified"; }
            }
        }

        if (!committed && observedOwner == ObservedOwner.OTHER_OR_UNKNOWN && candidate != null) {
            try { observedOwner = port.reconcile(previous, requestedNext); }
            catch (Exception unreadable) { reason = "handoff_owner_unverified"; }
        }
        if (reservation != null && reservation.isCurrent()) {
            Outcome claimedOutcome = committed ? Outcome.COMMITTED
                    : (observedOwner == ObservedOwner.EXACT_A
                        ? Outcome.ABORTED_A_RETAINED : Outcome.INDETERMINATE);
            Identity claimedOwner = committed ? requestedNext
                    : (claimedOutcome == Outcome.ABORTED_A_RETAINED ? previous : null);
            if (!gate.complete(reservation, claimedOutcome, claimedOwner)) {
                committed = false;
                reason = "owner_claim_expired_after_handoff";
            }
        } else if (reservation != null) {
            committed = false;
            reason = "owner_claim_expired_after_handoff";
        }

        if (committed) {
            try {
                // A can be released only after the backend's exact B owner and ready output/input
                // have been confirmed. Failure is not allowed to turn a committed B into rollback.
                port.releasePrevious(previous);
                return result(Outcome.COMMITTED, Stage.CLEANUP, "", requestedNext, false, true);
            } catch (Exception releaseFailure) {
                return result(Outcome.COMMITTED, Stage.CLEANUP,
                        "previous_release_failed:" + releaseFailure.getClass().getSimpleName(),
                        requestedNext, false, false);
            }
        }

        boolean aStillOwned = false;
        try { aStillOwned = port.isCurrent(previous) && port.reconcile(previous, requestedNext) == ObservedOwner.EXACT_A; }
        catch (Exception ignored) { }
        if (candidate != null && candidateIsSafelyReleasable(port, previous, requestedNext)) {
            try { port.releaseCandidate(candidate); candidateReleased = true; }
            catch (Exception cleanupFailure) {
                if (reason.isEmpty()) reason = "candidate_release_failed:" + cleanupFailure.getClass().getSimpleName();
            }
        }
        Outcome outcome = aStillOwned ? Outcome.ABORTED_A_RETAINED : Outcome.INDETERMINATE;
        return result(outcome, reason.isEmpty() ? Stage.CLEANUP : stage,
                reason.isEmpty() ? (aStillOwned ? "cancelled" : "owner_unverified") : reason,
                aStillOwned ? previous : null, candidateReleased, false);
    }

    private static boolean candidateIsSafelyReleasable(Port port, Identity previous, Identity next) {
        // Only an exact observation that A still owns the output proves that candidate cleanup
        // cannot tear down a potentially visible B or an unknown successor.
        try { return port.reconcile(previous, next) == ObservedOwner.EXACT_A; }
        catch (Exception ignored) { return false; }
    }

    private static Result result(Outcome outcome, Stage stage, String reason, Identity owner,
                                 boolean candidateReleased, boolean previousReleased) {
        return new Result(outcome, stage, reason, owner, candidateReleased, previousReleased);
    }
}
