package top.zainzh.rearscreen.platform

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock
import top.zainzh.rearscreen.BuildConfig

/** Typed owner boundary for SDK/Mod Cast. Legacy URI and Tile entry points do not use this coordinator. */
internal class ControlledCastCoordinator(
    private val targets: HostCastTargetResolver,
    private val authority: CastOwnerAuthority,
    private val activations: CastActivationVerifier,
    private val backend: ControlledCastBackend,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val randomId: () -> String = { UUID.randomUUID().toString() },
    private val work: Executor = ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(32), castThreadFactory("controlled-cast"), ThreadPoolExecutor.AbortPolicy()),
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(1,
        castThreadFactory("controlled-cast-expiry")),
    private val reportStartGateRejection: (String) -> Unit = { reason ->
        android.util.Log.w("ControlledCastCoordinator", "stage=start_action_gate_rejected reason=$reason")
    },
) : Closeable {
    companion object {
        private const val PREPARE_TTL_MS = 45_000L
        private const val CLOSED_TERMINAL_TTL_MS = 10 * 60_000L
        private const val MAX_CLOSED_TERMINALS = 64
    }

    private data class TicketRecord(
        val id: String,
        val generation: Long,
        val requestOwner: SessionOwner,
        val ownerKey: CastOwnerKey,
        val target: HostCastTarget,
        val observedOccupant: CastOccupantIdentity?,
        val expiresAt: Long,
        var observer: CastObserver?,
        var requestIsCurrent: () -> Boolean = { true },
        var committing: Boolean = false,
        var cancelled: Boolean = false,
        var operationId: String? = null,
        val startGateRejectLogged: AtomicBoolean? = if (BuildConfig.DEBUG) AtomicBoolean(false) else null
    )

    private data class SessionRecord(
        val handle: CastSessionHandle,
        val ownerKey: CastOwnerKey,
        val target: HostCastTarget,
        val backendSessionId: String,
        var state: CastSessionState,
        val presentationKind: CastPresentationKind,
        var viewport: AppliedCastViewport? = null,
        var failure: CastFailure? = null,
        var observer: CastObserver? = null,
        var closedAtElapsedRealtimeMs: Long? = null
    )

    private val lock = Any()
    private val sequence = AtomicLong(0L)
    private val tickets = LinkedHashMap<String, TicketRecord>()
    private val sessions = LinkedHashMap<String, SessionRecord>()
    private var closed = false

    /** Resolves an exact host task first, then creates a side-effect-free confirmation preview. */
    fun listTargetChoices(owner: SessionOwner): List<HostCastTargetChoice> {
        if (!authority.isCurrentForActivation(owner)) return emptyList()
        return try { targets.listChoices(owner) } catch (_: Exception) { emptyList() }
    }

    /** Host-only first-stage list. Hidden main tasks are never returned through SDK/Mod contracts. */
    fun listForegroundChoices(owner: SessionOwner): List<HostCastForegroundChoice> {
        if (!authority.isCurrentForActivation(owner)) return emptyList()
        return runCatching { targets.listForegroundChoices(owner) }.getOrDefault(emptyList())
    }

    fun prepare(owner: SessionOwner, request: CastTargetRequest): CastPrepareResult {
        if (!authority.isCurrentForActivation(owner)) return CastPrepareResult.Rejected(CastFailureCode.OWNER_STALE, false)
        val target = try { targets.resolve(owner, request) }
        catch (_: Exception) { null }
            ?: return CastPrepareResult.Rejected(CastFailureCode.TARGET_UNAVAILABLE, true)
        val occupant = try { backend.inspectOccupant(target.rearDisplayId, target.rearIdentity) }
        catch (_: Exception) { return CastPrepareResult.Rejected(CastFailureCode.OCCUPANCY_UNKNOWN, true) }
        if (!occupancyIsSafe(target, occupant))
            return CastPrepareResult.Rejected(CastFailureCode.DISPLAY_BUSY, false)
        val id = randomId()
        val generation = sequence.incrementAndGet()
        val expires = clock() + PREPARE_TTL_MS
        val record = TicketRecord(id, generation, owner, CastOwnerKey.from(owner), target,
            occupant, expires, null)
        synchronized(lock) {
            if (closed) return CastPrepareResult.Rejected(CastFailureCode.BACKEND_UNAVAILABLE, true)
            tickets[id] = record
        }
        scheduler.schedule({ expireTicket(id, generation) }, PREPARE_TTL_MS, TimeUnit.MILLISECONDS)
        return CastPrepareResult.Accepted(PreparedCastTicket(id, generation, expires, target, occupant))
    }

    /** The ticket is revalidated after confirmation; no task/display side effect occurs in prepare(). */
    fun commit(
        owner: SessionOwner,
        ticketId: String,
        generation: Long,
        activation: UserActivationToken,
        observer: CastObserver? = null,
        requestIsCurrent: () -> Boolean = { true }
    ): CastCommitResult {
        if (!isRequestCurrent(requestIsCurrent))
            return CastCommitResult.Rejected(CastFailureCode.TICKET_STALE, false)
        val record = synchronized(lock) {
            val current = tickets[ticketId]
            if (closed || current == null || current.generation != generation || current.cancelled
                || current.committing || current.expiresAt <= clock() || current.requestOwner != owner)
                return CastCommitResult.Rejected(CastFailureCode.TICKET_STALE, false)
            current.committing = true
            current.observer = observer
            current.requestIsCurrent = requestIsCurrent
            current
        }
        try {
            work.execute { startAfterConfirmation(record, activation) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { if (tickets[record.id] === record) record.committing = false }
            return CastCommitResult.Rejected(CastFailureCode.BACKEND_BUSY, true)
        }
        return CastCommitResult.Accepted(record.id, record.generation)
    }

    fun abort(owner: SessionOwner, ticketId: String, generation: Long): CastOperationResult {
        val record = synchronized(lock) {
            val found = tickets[ticketId]
            if (found == null || found.generation != generation || found.requestOwner != owner)
                return CastOperationResult.Rejected(CastFailureCode.TICKET_STALE, false)
            found.cancelled = true
            tickets.remove(ticketId)
            found
        }
        val operation = record.operationId
        if (operation != null) submitBestEffort { backend.abort(operation) }
        record.observer?.onCastEvent(CastEvent.Aborted(ticketId, generation))
        return CastOperationResult.Completed
    }

    fun query(owner: SessionOwner, handle: CastSessionHandle): CastQueryResult {
        val (record, initialState) = synchronized(lock) {
            pruneClosedSessionsLocked()
            val current = sessions[handle.sessionId]
            if (current == null || current.handle.generation != handle.generation
                || current.ownerKey != CastOwnerKey.from(owner)) return CastQueryResult.Rejected(CastFailureCode.OWNER_MISMATCH)
            // The backend's live presentation can vanish before teardown confirms
            // completion. An accepted close owns the state until it succeeds or fails.
            current.closeSnapshot()?.let { return it }
            current to current.state
        }
        val live = try { backend.query(record.backendSessionId, record.handle.generation) }
        catch (_: Exception) { null }
        return synchronized(lock) {
            pruneClosedSessionsLocked()
            if (sessions[handle.sessionId] !== record) return@synchronized CastQueryResult.Rejected(CastFailureCode.OWNER_MISMATCH)
            // A concurrent close or a successful replacement may have advanced
            // this exact record while the backend query was in flight.
            record.closeSnapshot()?.let { return@synchronized it }
            if (record.state != initialState || live == null || !live.matches(record.target))
                return@synchronized CastQueryResult.Snapshot(record.handle, CastSessionState.INDETERMINATE,
                    record.target, record.presentationKind, record.viewport, CastFailure(CastFailureCode.OWNER_UNVERIFIED, recoverable = false))
            CastQueryResult.Snapshot(record.handle, live.state, record.target, live.presentationKind,
                live.appliedViewport ?: record.viewport, live.failure)
        }
    }

    /** Only read with lock held; these states do not depend on a live presentation. */
    private fun SessionRecord.closeSnapshot(): CastQueryResult.Snapshot? {
        if (state != CastSessionState.CLOSING && state != CastSessionState.CLOSED) return null
        return CastQueryResult.Snapshot(handle, state, target, presentationKind, viewport, null)
    }

    fun close(owner: SessionOwner, handle: CastSessionHandle, observer: CastObserver? = null): CastOperationResult {
        val record = synchronized(lock) {
            pruneClosedSessionsLocked()
            val current = sessions[handle.sessionId]
            if (current == null || current.handle.generation != handle.generation
                || current.ownerKey != CastOwnerKey.from(owner))
                return CastOperationResult.Rejected(CastFailureCode.OWNER_MISMATCH, false)
            if (current.state == CastSessionState.CLOSED) return CastOperationResult.Completed
            if (current.state == CastSessionState.CLOSING) {
                if (observer != null) current.observer = observer
                return CastOperationResult.Accepted(current.handle.sessionId, current.handle.generation)
            }
            current.state = CastSessionState.CLOSING
            if (observer != null) current.observer = observer
            current
        }
        // A's explicit close owns the transition: cancel any B replacement first. The serialized
        // worker then runs exact A close after the replacement's own rollback returns.
        try {
            backend.cancelPendingReplacement(record.backendSessionId)
        } catch (_: Exception) {
            return rejectUnscheduledClose(record, CastFailureCode.BACKEND_FAILED)
        }
        try {
            work.execute {
                val result = try { backend.close(record.backendSessionId, record.handle.generation) }
                catch (_: Exception) { BackendCloseResult.Indeterminate }
                val notification = synchronized(lock) {
                    if (sessions[record.handle.sessionId] !== record) return@execute
                    when (result) {
                        BackendCloseResult.Closed -> {
                            record.state = CastSessionState.CLOSED
                            record.closedAtElapsedRealtimeMs = clock()
                            pruneClosedSessionsLocked()
                            val observer = record.observer
                            record.observer = null
                            observer to CastEvent.Closed(record.handle)
                        }
                        BackendCloseResult.Rejected, BackendCloseResult.Indeterminate -> {
                            record.state = CastSessionState.INDETERMINATE
                            val observer = record.observer
                            record.observer = null
                            observer to CastEvent.Failed(record.handle,
                                CastFailure(CastFailureCode.OWNER_UNVERIFIED, recoverable = false))
                        }
                    }
                }
                notification.first?.onCastEvent(notification.second)
            }
        } catch (_: RejectedExecutionException) {
            return rejectUnscheduledClose(record, CastFailureCode.BACKEND_BUSY)
        }
        return CastOperationResult.Accepted(record.handle.sessionId, record.handle.generation)
    }

    private fun rejectUnscheduledClose(record: SessionRecord, code: CastFailureCode): CastOperationResult.Rejected {
        synchronized(lock) {
            if (sessions[record.handle.sessionId] === record && record.state == CastSessionState.CLOSING) {
                record.state = CastSessionState.INDETERMINATE
                record.observer = null
            }
        }
        return CastOperationResult.Rejected(code, retryable = true)
    }

    /** Retains only recent backend-confirmed close facts; cleanup is lazy and bounded, with no worker. */
    private fun pruneClosedSessionsLocked() {
        val now = clock()
        sessions.entries.removeAll { (_, record) ->
            val closedAt = record.closedAtElapsedRealtimeMs
            closedAt != null && (closedAt > now || now - closedAt >= CLOSED_TERMINAL_TTL_MS)
        }
        val confirmed = sessions.values.filter { it.closedAtElapsedRealtimeMs != null }
            .sortedBy { it.closedAtElapsedRealtimeMs }
        if (confirmed.size > MAX_CLOSED_TERMINALS) {
            confirmed.take(confirmed.size - MAX_CLOSED_TERMINALS).forEach { sessions.remove(it.handle.sessionId) }
        }
    }

    /** Authorization revocation/uninstall is explicit; transport disconnect does not close a committed cast. */
    fun revokeOwner(owner: SessionOwner) {
        val ownerKey = CastOwnerKey.from(owner)
        val (pending, active) = synchronized(lock) {
            val pending = tickets.values.filter { it.ownerKey == ownerKey }
            pending.forEach { it.cancelled = true; tickets.remove(it.id) }
            pending to sessions.values.filter { it.ownerKey == ownerKey }
        }
        pending.forEach { ticket -> ticket.operationId?.let { submitBestEffort { backend.abort(it) } } }
        active.forEach { close(owner, it.handle) }
    }

    private fun startAfterConfirmation(record: TicketRecord, activation: UserActivationToken) {
        val stillTicket = synchronized(lock) {
            !closed && tickets[record.id] === record && !record.cancelled && record.expiresAt > clock()
        }
        if (!stillTicket) { rejectTicket(record, CastFailureCode.TICKET_STALE); return }
        if (!authority.isCurrentForActivation(record.requestOwner)) {
            rejectTicket(record, CastFailureCode.OWNER_STALE); return
        }
        if (!targets.isCurrent(record.target)) { rejectTicket(record, CastFailureCode.TARGET_CHANGED); return }
        val nowOccupant = try { backend.inspectOccupant(record.target.rearDisplayId, record.target.rearIdentity) }
        catch (_: Exception) { rejectTicket(record, CastFailureCode.OCCUPANCY_UNKNOWN); return }
        if (nowOccupant != record.observedOccupant || !occupancyIsSafe(record.target, nowOccupant)) {
            rejectTicket(record, CastFailureCode.OCCUPANCY_CHANGED); return
        }
        if (!targets.isCurrent(record.target)) { rejectTicket(record, CastFailureCode.TARGET_CHANGED); return }
        if (!isRequestCurrent(record)) { rejectTicket(record, CastFailureCode.TICKET_STALE); return }
        if (!activations.consume(record.requestOwner, record.id, record.generation, activation)) {
            rejectTicket(record, CastFailureCode.ACTIVATION_INVALID); return
        }
        val operationId = "cast-op-${randomId()}"
        val accepted = synchronized(lock) {
            if (closed || tickets[record.id] !== record || record.cancelled || record.expiresAt <= clock()) false
            else { record.operationId = operationId; true }
        }
        if (!accepted) { rejectTicket(record, CastFailureCode.TICKET_STALE); return }
        try {
            backend.start(operationId, record.ownerKey, record.generation, record.target, record.observedOccupant,
                isAuthorized = { isStartCurrent(record) },
                claimSideEffect = { claimStartSideEffect(record) }) { event -> onBackendEvent(record, event) }
            synchronized(lock) {
                if (record.cancelled) submitBestEffort { backend.abort(operationId) }
            }
        } catch (_: Exception) {
            submitBestEffort { backend.abort(operationId) }
            rejectTicket(record, CastFailureCode.BACKEND_FAILED)
        }
    }

    private fun isStartCurrent(record: TicketRecord): Boolean {
        val ticketFailure = synchronized(lock) {
            when {
                closed -> "coordinator_closed"
                tickets[record.id] !== record -> "ticket_not_current"
                record.cancelled -> "ticket_cancelled"
                record.expiresAt <= clock() -> "ticket_expired"
                record.operationId == null -> "operation_missing"
                else -> null
            }
        }
        val reason = ticketFailure
            ?: if (!authority.isCurrentForActivation(record.requestOwner)) "owner_stale"
            else if (!isRequestCurrent(record)) "request_stale"
            else null
        if (reason != null && BuildConfig.DEBUG && record.startGateRejectLogged?.compareAndSet(false, true) == true) {
            reportStartGateRejection(reason)
        }
        return reason == null
    }

    /** Replacement tickets belong to B; the observed incumbent may belong to another SDK owner A. */
    private fun sessionMatchesOccupant(session: SessionRecord, occupant: CastOccupantIdentity): Boolean =
        session.ownerKey.toString() == occupant.owner
            && session.backendSessionId == occupant.sessionId
            && session.handle.generation == occupant.ownerGeneration
            && session.target.mode == occupant.mode
            && session.target.taskId == occupant.taskId
            && session.target.baseComponent == occupant.baseComponent
            && session.target.rearDisplayId == occupant.rearDisplayId
            && session.target.rearIdentity == occupant.rearIdentity

    /** Linearizes a pending privileged effect against abort/revoke without holding the lock over IPC. */
    private fun claimStartSideEffect(record: TicketRecord): Boolean {
        if (!authority.isCurrentForActivation(record.requestOwner) || !isRequestCurrent(record)) return false
        return synchronized(lock) {
            if (closed || tickets[record.id] !== record || record.cancelled || record.expiresAt <= clock()) false
            else true
        }
    }

    private fun onBackendEvent(record: TicketRecord, event: BackendEvent) {
        when (event) {
            is BackendEvent.ReplacementReady -> {
                val expectedOccupant = record.observedOccupant
                val previous = synchronized(lock) {
                    sessions.values.firstOrNull { it.backendSessionId == event.previousBackendSessionId
                        && it.handle.generation == event.previousOwnerGeneration
                        && it.state == CastSessionState.READY
                        && expectedOccupant != null && sessionMatchesOccupant(it, expectedOccupant) }
                }
                if (previous == null || !event.actualTarget.sameTarget(record.target)
                    || !event.outputReady || !event.inputReady || event.backendSessionId.isBlank()
                    || (record.target.mode == CastMode.VIRTUAL && (event.presentationKind != CastPresentationKind.MANAGED_VIEWPORT
                        || event.appliedViewport == null))
                    || (record.target.mode == CastMode.DIRECT_PHYSICAL && (event.presentationKind != CastPresentationKind.SYSTEM_MANAGED
                        || event.appliedViewport != null))) {
                    submitBestEffort { backend.rollbackReplacement(event.backendSessionId) }
                    failTicket(record, CastFailure(CastFailureCode.OWNER_UNVERIFIED, recoverable = true),
                        CastSessionState.INDETERMINATE)
                    return
                }
                if (!isRequestCurrent(record)) {
                    submitBestEffort { backend.rollbackReplacement(event.backendSessionId) }
                    failTicket(record, CastFailure(CastFailureCode.TICKET_STALE, recoverable = false), CastSessionState.INDETERMINATE)
                    return
                }
                val handle = CastSessionHandle(record.id, record.generation)
                val session = SessionRecord(handle, record.ownerKey, record.target, event.backendSessionId,
                    CastSessionState.READY, event.presentationKind, event.appliedViewport, observer = record.observer)
                var previousObserver: CastObserver? = null
                val switched = synchronized(lock) {
                    val stillExactA = sessions[previous.handle.sessionId] === previous
                        && previous.state == CastSessionState.READY
                        && expectedOccupant != null && sessionMatchesOccupant(previous, expectedOccupant)
                    if (closed || tickets[record.id] !== record || record.cancelled
                        || record.expiresAt <= clock() || !stillExactA
                        || !backend.markReplacementAccepted(event.backendSessionId, record.generation,
                            record.ownerKey.toString())) false
                    else {
                        previousObserver = previous.observer
                        previous.observer = null
                        previous.state = CastSessionState.CLOSED
                        previous.closedAtElapsedRealtimeMs = clock()
                        tickets.remove(record.id)
                        sessions[handle.sessionId] = session
                        true
                    }
                }
                if (!switched) {
                    submitBestEffort { backend.rollbackReplacement(event.backendSessionId) }
                    failTicket(record, CastFailure(CastFailureCode.OWNER_UNVERIFIED, recoverable = true),
                        CastSessionState.INDETERMINATE)
                    return
                }
                // If persistence cannot retire the OWNER_B journal, it remains an exact recovery
                // record and blocks another physical replacement; the B session is still live.
                submitBestEffort { backend.confirmReplacement(event.backendSessionId) }
                previousObserver?.onCastEvent(CastEvent.Closed(previous.handle))
                record.observer?.onCastEvent(CastEvent.Ready(handle, record.target, event.presentationKind, event.appliedViewport))
            }
            is BackendEvent.Ready -> {
                if (!isRequestCurrent(record)) {
                    submitBestEffort { backend.abort(event.backendSessionId) }
                    failTicket(record, CastFailure(CastFailureCode.TICKET_STALE, recoverable = false),
                        CastSessionState.FAILED)
                    return
                }
                if (!event.actualTarget.sameTarget(record.target) || !event.outputReady || !event.inputReady
                    || event.backendSessionId.isBlank()
                    || (record.target.mode == CastMode.VIRTUAL &&
                        (event.presentationKind != CastPresentationKind.MANAGED_VIEWPORT || event.appliedViewport == null))
                    || (record.target.mode == CastMode.DIRECT_PHYSICAL &&
                        (event.presentationKind != CastPresentationKind.SYSTEM_MANAGED || event.appliedViewport != null))) {
                    val operation = record.operationId
                    submitBestEffort { backend.abort(operation ?: event.backendSessionId) }
                    failTicket(record, CastFailure(CastFailureCode.BACKEND_NOT_READY, recoverable = true),
                        CastSessionState.INDETERMINATE)
                    return
                }
                val handle = CastSessionHandle(record.id, record.generation)
                val session = SessionRecord(handle, record.ownerKey, record.target, event.backendSessionId,
                    CastSessionState.READY, event.presentationKind, event.appliedViewport, observer = record.observer)
                synchronized(lock) {
                    if (closed || tickets[record.id] !== record || record.cancelled) {
                        submitBestEffort { backend.abort(event.backendSessionId) }
                        return
                    }
                    pruneClosedSessionsLocked()
                    tickets.remove(record.id)
                    sessions[handle.sessionId] = session
                }
                record.observer?.onCastEvent(CastEvent.Ready(handle, record.target, event.presentationKind, event.appliedViewport))
            }
            is BackendEvent.Failed -> failTicket(record, event.failure,
                if (event.failure.code == CastFailureCode.OWNER_UNVERIFIED) CastSessionState.INDETERMINATE else CastSessionState.FAILED)
        }
    }

    private fun failTicket(record: TicketRecord, failure: CastFailure, terminalState: CastSessionState) {
        synchronized(lock) {
            if (tickets[record.id] !== record) return
            tickets.remove(record.id)
        }
        record.observer?.onCastEvent(CastEvent.CommitFailed(record.id, record.generation, failure, terminalState))
    }

    private fun rejectTicket(record: TicketRecord, code: CastFailureCode) =
        failTicket(record, CastFailure(code, recoverable = code in setOf(
            CastFailureCode.OCCUPANCY_UNKNOWN, CastFailureCode.BACKEND_BUSY, CastFailureCode.BACKEND_FAILED)),
            CastSessionState.FAILED)

    private fun isRequestCurrent(record: TicketRecord): Boolean = isRequestCurrent(record.requestIsCurrent)

    private fun isRequestCurrent(check: () -> Boolean): Boolean =
        try { check() } catch (_: Exception) { false }

    private fun occupancyIsSafe(target: HostCastTarget, occupant: CastOccupantIdentity?): Boolean =
        occupant == null || backend.canReplace(target, occupant)

    private fun expireTicket(id: String, generation: Long) {
        val expired = synchronized(lock) {
            val current = tickets[id]
            if (current == null || current.generation != generation || current.expiresAt > clock()) return
            current.cancelled = true
            tickets.remove(id)
            current
        }
        expired.operationId?.let { submitBestEffort { backend.abort(it) } }
        expired.observer?.onCastEvent(CastEvent.Expired(id, generation))
    }

    private fun submitBestEffort(action: () -> Unit) {
        try { work.execute(action) } catch (_: RejectedExecutionException) { /* bounded queue; backend's own expiry remains authoritative */ }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            tickets.values.forEach { it.cancelled = true }
            tickets.clear()
        }
        (work as? java.util.concurrent.ExecutorService)?.shutdown()
        scheduler.shutdownNow()
        // Committed sessions intentionally survive coordinator/transport close; host/backend owns them.
    }
}

private fun castThreadFactory(name: String) = ThreadFactory { task ->
    Thread(task, "$name-${UUID.randomUUID()}").apply { isDaemon = true }
}

internal data class CastOwnerKey(
    val transportUid: Int,
    val transportPackageName: String,
    val transportCertificate: String,
    val principal: String
) {
    companion object {
        fun from(owner: SessionOwner): CastOwnerKey {
            val principal = when (val value = owner.principal) {
                is SessionPrincipal.AndroidClient -> "sdk:${value.uid}:${value.packageName}:${value.signingCertificateDigest}"
                is SessionPrincipal.SignedMod -> "mod:${value.authorFingerprint}:${value.modId}:${value.logicalSessionId}"
                is SessionPrincipal.HostFeature -> "host:${value.featureId}:${value.logicalSessionId}"
            }
            return CastOwnerKey(owner.transportUid, owner.transportPackageName,
                owner.transportSigningCertificateDigest, principal)
        }
    }
}

internal enum class CastMode { VIRTUAL, DIRECT_PHYSICAL }
internal enum class CastRegion { SAFE, FULL }
internal sealed interface CastTargetSelector {
    data object CurrentForeground : CastTargetSelector
    data class Component(val flattened: String) : CastTargetSelector
    /** Short-lived host-issued choice; keeps raw task IDs out of SDK/Mod requests. */
    data class ChoiceToken(val opaque: String) : CastTargetSelector
    /** One-shot Mod host selection; may be obscured only by the exact visible confirmation task. */
    data class ForegroundChoiceToken(val opaque: String, val confirmationTaskId: Int,
        val confirmationComponent: String) : CastTargetSelector
}

internal data class HostCastTargetChoice(val opaqueToken: String, val label: String, val packageName: String,
    val taskId: Int = -1, val baseComponent: String = "")

/** Host-only choice to foreground an existing main task before the ordinary visible-target recheck. */
internal data class HostCastForegroundChoice(val opaqueToken: String, val label: String, val packageName: String)

/** Display labels are derived only for the host picker; opaque choice tokens remain the selection identity. */
internal data class ForegroundChoiceLabelCandidate(
    val packageName: String,
    val applicationLabel: String,
    val activityLabel: String,
    val shortComponentName: String,
    /** Stable tie-breaker used only to order indistinguishable rows; never shown to the user. */
    val stableOrder: Int,
)

/** Keep the familiar application label for single choices; disambiguate repeated tasks within one app. */
internal fun foregroundChoiceLabels(candidates: List<ForegroundChoiceLabelCandidate>): List<String> {
    val labels = MutableList(candidates.size) { "" }
    candidates.indices.groupBy { candidates[it].packageName }.values.forEach { group ->
        if (group.size == 1) {
            val index = group.single()
            labels[index] = candidates[index].applicationLabel.ifBlank { candidates[index].packageName }
            return@forEach
        }
        val appLabel = candidates[group.first()].applicationLabel.ifBlank { candidates[group.first()].packageName }
        val activityLabels = group.groupingBy { candidates[it].activityLabel.trim() }.eachCount()
        val readable = group.associateWith { index ->
            val candidate = candidates[index]
            val activityLabel = candidate.activityLabel.trim()
            if (activityLabel.isNotEmpty() && activityLabel != appLabel && activityLabels[activityLabel] == 1)
                activityLabel else candidate.shortComponentName
        }
        val componentDuplicates = readable.entries.groupBy { it.value }.filterValues { it.size > 1 }
        val preliminary = group.associateWith { index ->
            val value = readable.getValue(index)
            val duplicateGroup = componentDuplicates[value]
            val detail = if (duplicateGroup == null) value else {
                val ordered = duplicateGroup.map { it.key }.sortedWith(
                    compareBy<Int>({ candidates[it].stableOrder }, { candidates[it].shortComponentName }, { it }))
                "$value · ${ordered.indexOf(index) + 1}"
            }
            "$appLabel · $detail"
        }
        val finalCollisions = preliminary.entries.groupBy { it.value }.filterValues { it.size > 1 }
        group.forEach { index ->
            val value = preliminary.getValue(index)
            val collision = finalCollisions[value]
            if (collision == null) {
                labels[index] = value
            } else {
                val ordered = collision.map { it.key }.sortedWith(
                    compareBy<Int>({ candidates[it].stableOrder }, { candidates[it].shortComponentName }, { it }))
                labels[index] = "$value · 行${ordered.indexOf(index) + 1}"
            }
        }
    }
    return labels
}

internal data class CastTargetRequest(
    val selector: CastTargetSelector,
    val mode: CastMode,
    val region: CastRegion? = null,
    val contentRotation: Int? = null,
    val requestedDensityDpi: Int? = null
)

/** Resolver-issued exact identity; callers cannot nominate a task ID and claim it is the current host task. */
internal class HostCastTarget internal constructor(
    val proofId: String,
    val taskId: Int,
    val baseComponent: String,
    val requestedComponent: String?,
    val mainDisplayId: Int,
    val rearDisplayId: Int,
    val rearIdentity: String,
    val mode: CastMode,
    val region: CastRegion,
    val contentRotation: Int,
    val requestedDensityDpi: Int?,
    val expectedTopComponent: String? = null,
    val confirmationTaskId: Int? = null,
    val confirmationComponent: String? = null
) {
    fun sameTarget(other: HostCastTarget): Boolean = taskId == other.taskId && baseComponent == other.baseComponent
        && requestedComponent == other.requestedComponent && mainDisplayId == other.mainDisplayId
        && rearDisplayId == other.rearDisplayId && rearIdentity == other.rearIdentity && mode == other.mode
        && expectedTopComponent == other.expectedTopComponent && confirmationTaskId == other.confirmationTaskId
        && confirmationComponent == other.confirmationComponent
        && region == other.region && contentRotation == other.contentRotation
        && requestedDensityDpi == other.requestedDensityDpi
}

internal interface HostCastTargetResolver {
    fun resolve(owner: SessionOwner, request: CastTargetRequest): HostCastTarget?
    fun isCurrent(target: HostCastTarget): Boolean
    fun listChoices(owner: SessionOwner): List<HostCastTargetChoice> = emptyList()
    fun listForegroundChoices(owner: SessionOwner): List<HostCastForegroundChoice> = emptyList()
}

internal data class CastOccupantIdentity @JvmOverloads constructor(
    val mode: CastMode,
    val owner: String,
    val sessionId: String,
    /** Generation assigned by the privileged backend session itself. */
    val generation: Long,
    /** Caller/owner generation issued by ControlledCastCoordinator. */
    val ownerGeneration: Long,
    val taskId: Int,
    val hostTaskId: Int,
    val inputSessionId: String,
    val baseComponent: String,
    val rearDisplayId: Int,
    val rearIdentity: String,
    val source: CastOccupantSource = CastOccupantSource.UNKNOWN
)

internal enum class CastOccupantSource { UNKNOWN, VIRTUAL_OWNER, PHYSICAL_PROJECTION_OWNER, BLOCKED_TASK_ROW }

internal data class PreparedCastTicket(
    val ticketId: String,
    val generation: Long,
    val expiresAtElapsedRealtimeMs: Long,
    val target: HostCastTarget,
    val observedOccupant: CastOccupantIdentity?
)

internal data class CastSessionHandle(val sessionId: String, val generation: Long)

internal data class AppliedCastViewport(
    val revision: Long,
    val rearDisplayId: Int,
    val region: CastRegion,
    val densityDpi: Int,
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val displayRotation: Int,
    val contentRotation: Int,
    val windowLeftPx: Int,
    val windowTopPx: Int,
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val inputScaleX: Float,
    val inputSkewX: Float,
    val inputScaleY: Float,
    val inputSkewY: Float,
    val inputTranslateX: Float,
    val inputTranslateY: Float
)

internal enum class CastSessionState { PREPARING, READY, CLOSING, CLOSED, FAILED, INDETERMINATE }
internal enum class CastPresentationKind { MANAGED_VIEWPORT, SYSTEM_MANAGED }
internal enum class CastFailureCode {
    OWNER_STALE, OWNER_MISMATCH, OWNER_UNVERIFIED, TARGET_UNAVAILABLE, TARGET_CHANGED, BACKEND_UNAVAILABLE,
    OCCUPANCY_UNKNOWN, OCCUPANCY_CHANGED, DISPLAY_BUSY, TICKET_STALE, ACTIVATION_INVALID,
    BACKEND_BUSY, BACKEND_FAILED, BACKEND_NOT_READY
}
internal data class CastFailure(val code: CastFailureCode, val recoverable: Boolean)

internal sealed interface CastPrepareResult {
    data class Accepted(val ticket: PreparedCastTicket) : CastPrepareResult
    data class Rejected(val code: CastFailureCode, val retryable: Boolean) : CastPrepareResult
}
internal sealed interface CastCommitResult {
    data class Accepted(val ticketId: String, val generation: Long) : CastCommitResult
    data class Rejected(val code: CastFailureCode, val retryable: Boolean) : CastCommitResult
}
internal sealed interface CastOperationResult {
    data object Completed : CastOperationResult
    data class Accepted(val operationId: String, val generation: Long) : CastOperationResult
    data class Rejected(val code: CastFailureCode, val retryable: Boolean) : CastOperationResult
}
internal sealed interface CastQueryResult {
    data class Snapshot(
        val handle: CastSessionHandle,
        val state: CastSessionState,
        val target: HostCastTarget,
        val presentationKind: CastPresentationKind,
        val appliedViewport: AppliedCastViewport?,
        val failure: CastFailure?
    ) : CastQueryResult
    data class Rejected(val code: CastFailureCode) : CastQueryResult
}

internal interface CastObserver { fun onCastEvent(event: CastEvent) }
internal sealed interface CastEvent {
    data class Ready(val handle: CastSessionHandle, val target: HostCastTarget,
                     val presentationKind: CastPresentationKind, val appliedViewport: AppliedCastViewport?) : CastEvent
    data class CommitFailed(val ticketId: String, val generation: Long, val failure: CastFailure, val state: CastSessionState) : CastEvent
    data class Expired(val ticketId: String, val generation: Long) : CastEvent
    data class Aborted(val ticketId: String, val generation: Long) : CastEvent
    data class Closed(val handle: CastSessionHandle) : CastEvent
    data class Failed(val handle: CastSessionHandle, val failure: CastFailure) : CastEvent
}

internal interface CastOwnerAuthority {
    /** Pending approval/commit must remain attached to the active SDK connection or trusted Mod grant. */
    fun isCurrentForActivation(owner: SessionOwner): Boolean
}

internal fun interface CastActivationVerifier {
    fun consume(owner: SessionOwner, ticketId: String, generation: Long, token: UserActivationToken): Boolean
}

internal interface ControlledCastBackend {
    fun inspectOccupant(rearDisplayId: Int, rearIdentity: String): CastOccupantIdentity?
    /** True only if this exact identity is still backed by a registered virtual owner/generation. */
    fun isExactControlledVirtualOwner(occupant: CastOccupantIdentity): Boolean = false
    fun canReplace(target: HostCastTarget, occupant: CastOccupantIdentity): Boolean
    /**
     * Starts asynchronously and reports Ready only after exact output and input are actually ready.
     * The adapter must atomically recheck target identity and compare-and-set expectedOccupant before
     * any operation that can affect the old session; an unsupported CAS is a refusal, never stop-first.
     */
    fun start(operationId: String, owner: CastOwnerKey, generation: Long, target: HostCastTarget,
              expectedOccupant: CastOccupantIdentity?, isAuthorized: () -> Boolean,
              claimSideEffect: () -> Boolean, callback: (BackendEvent) -> Unit)
    fun abort(operationId: String)
    fun query(backendSessionId: String, generation: Long): BackendRuntimeSnapshot?
    fun close(backendSessionId: String, generation: Long): BackendCloseResult
    fun confirmReplacement(backendSessionId: String): Boolean = true
    /** Local durable acceptance marker; implementations must not perform remote/device I/O here. */
    fun markReplacementAccepted(backendSessionId: String, coordinatorGeneration: Long, ownerIdentity: String): Boolean = true
    fun cancelPendingReplacement(incumbentBackendSessionId: String): Boolean = false
    fun rollbackReplacement(backendSessionId: String): Boolean { abort(backendSessionId); return false }
}

internal sealed interface BackendEvent {
    data class ReplacementReady(
        val previousBackendSessionId: String,
        val previousOwnerGeneration: Long,
        val backendSessionId: String,
        val actualTarget: HostCastTarget,
        val outputReady: Boolean,
        val inputReady: Boolean,
        val presentationKind: CastPresentationKind,
        val appliedViewport: AppliedCastViewport?
    ) : BackendEvent
    data class Ready(
        val backendSessionId: String,
        val actualTarget: HostCastTarget,
        val outputReady: Boolean,
        val inputReady: Boolean,
        val presentationKind: CastPresentationKind,
        val appliedViewport: AppliedCastViewport?
    ) : BackendEvent
    data class Failed(val failure: CastFailure) : BackendEvent
}

internal data class BackendRuntimeSnapshot(
    val state: CastSessionState,
    val actualTarget: HostCastTarget,
    val presentationKind: CastPresentationKind,
    val appliedViewport: AppliedCastViewport?,
    val failure: CastFailure?
) {
    fun matches(expected: HostCastTarget): Boolean = actualTarget.sameTarget(expected)
}

internal enum class BackendCloseResult { Closed, Rejected, Indeterminate }
