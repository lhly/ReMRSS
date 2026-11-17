package top.zainzh.rearscreen.platform

import android.content.Context
import top.zainzh.rearscreen.ITaskService
import top.zainzh.rearscreen.RearDisplay
import org.json.JSONObject

/**
 * Small compatibility boundary for existing URI/Tile projection ownership.
 * Scene sessions remain owned by RearSceneCoordinator; this records only the task that the
 * existing physical-cast path deliberately moved, and validates that identity before blocking a Scene.
 */
internal object LegacyDisplayOccupancy {
    private const val PREFS = "platform_display_ownership"
    private const val TASK = "physical_projection_task"
    private const val PACKAGE = "physical_projection_package"
    private const val BASE_COMPONENT = "physical_projection_base_component"
    private const val CONTROLLED = "physical_projection_controlled"
    private const val DISPLAY = "physical_projection_display"
    private const val DISPLAY_IDENTITY = "physical_projection_identity"
    private const val LAUNCHER_DISABLED = "physical_projection_launcher_disabled"
    private const val KEEPER_START_PENDING = "physical_projection_keeper_start_pending"
    private const val KEEPER_START_CLAIMED = "physical_projection_keeper_start_claimed"
    private const val CONTROLLED_OWNER_CLOSING = "physical_projection_closing"
    private const val GENERATION = "physical_projection_generation"
    private const val GENERATION_SEQUENCE = "physical_projection_generation_sequence"
    private const val REPLACEMENT_TOKEN = "physical_replacement_token"
    private const val REPLACEMENT_PHASE = "physical_replacement_phase"
    private const val REPLACEMENT_A_RETURN_MOVE_AUTHORIZED = "physical_replacement_a_return_move_authorized"
    private const val REPLACEMENT_A_RETURN_MOVE_PENDING = "physical_replacement_a_return_move_pending"
    private const val REPLACEMENT_OLD_TASK = "physical_replacement_old_task"
    private const val REPLACEMENT_OLD_PACKAGE = "physical_replacement_old_package"
    private const val REPLACEMENT_OLD_BASE = "physical_replacement_old_base"
    private const val REPLACEMENT_OLD_DISPLAY = "physical_replacement_old_display"
    private const val REPLACEMENT_OLD_IDENTITY = "physical_replacement_old_identity"
    private const val REPLACEMENT_OLD_GENERATION = "physical_replacement_old_generation"
    private const val REPLACEMENT_OLD_LAUNCHER = "physical_replacement_old_launcher"
    private const val REPLACEMENT_NEW_TASK = "physical_replacement_new_task"
    private const val REPLACEMENT_NEW_PACKAGE = "physical_replacement_new_package"
    private const val REPLACEMENT_NEW_BASE = "physical_replacement_new_base"
    private const val REPLACEMENT_NEW_GENERATION = "physical_replacement_new_generation"
    private const val REPLACEMENT_NEW_SESSION = "physical_replacement_new_session"
    private const val REPLACEMENT_NEW_VIRTUAL = "physical_replacement_new_virtual"
    private const val REPLACEMENT_NEW_COORDINATOR_GENERATION = "physical_replacement_new_coordinator_generation"
    private const val REPLACEMENT_NEW_OWNER_IDENTITY = "physical_replacement_new_owner_identity"
    private const val REPLACEMENT_NEW_BACKEND_GENERATION = "physical_replacement_new_backend_generation"
    private const val REPLACEMENT_VIRTUAL_BASELINE = "physical_replacement_virtual_baseline"
    private const val REPLACEMENT_VIRTUAL_READER_STATE = "physical_replacement_virtual_reader_state"
    private const val REPLACEMENT_VIRTUAL_READER_ID = "physical_replacement_virtual_reader_id"
    private const val REPLACEMENT_VIRTUAL_READER_GENERATION = "physical_replacement_virtual_reader_generation"
    private const val REPLACEMENT_DECISION = "physical_replacement_decision"
    private val lock = Any()
    private val ownershipEpoch = LegacyProjectionOwnershipEpoch()

    /** Durable exact rollback authority. A pending journal blocks new physical claims until resolved. */
    internal data class PhysicalReplacementJournal(
        val token: String, val phase: String,
        val oldTask: Int, val oldPackage: String, val oldBase: String, val displayId: Int,
        val identity: String, val oldGeneration: Long, val oldLauncherDisabled: Boolean,
        val newTask: Int, val newPackage: String, val newBase: String, val newGeneration: Long,
        val newSessionId: String, val newVirtual: Boolean,
        val newCoordinatorGeneration: Long, val newOwnerIdentity: String, val newBackendGeneration: Long,
        val virtualBaseline: String = "",
        val readerState: String = "NOT_REQUESTED", val readerId: String = "", val readerGeneration: Long = 0L,
        val decision: String = "NONE"
    )

    @JvmStatic fun beginPhysicalReplacement(context: Context, token: String, oldTask: Int,
        oldPackage: String, oldBase: String, displayId: Int, identity: String, oldGeneration: Long,
        oldLauncherDisabled: Boolean, newTask: Int, newPackage: String, newBase: String,
        newVirtual: Boolean, newCoordinatorGeneration: Long, newOwnerIdentity: String,
        virtualBaseline: String): PhysicalReplacementJournal? = synchronized(lock) {
        if (token.isBlank() || oldTask <= 0 || newTask <= 0 || oldPackage.isBlank() || newPackage.isBlank()
            || oldBase.isBlank() || newBase.isBlank() || displayId <= 0 || identity.isBlank()
            || oldGeneration <= 0L || oldTask == newTask || newCoordinatorGeneration <= 0L
            || newOwnerIdentity.isBlank() || token.length > 160) return@synchronized null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(REPLACEMENT_TOKEN, null) != null || prefs.getInt(TASK, -1) != oldTask
            || prefs.getString(PACKAGE, null) != oldPackage || prefs.getString(BASE_COMPONENT, null) != oldBase
            || prefs.getInt(DISPLAY, -1) != displayId || prefs.getString(DISPLAY_IDENTITY, null) != identity
            || prefs.getLong(GENERATION, 0L) != oldGeneration || !prefs.getBoolean(CONTROLLED, false)) return@synchronized null
        val newGeneration = prefs.getLong(GENERATION_SEQUENCE, 0L) + 1L
        val committed = prefs.edit().putLong(GENERATION_SEQUENCE, newGeneration)
            .putString(REPLACEMENT_TOKEN, token).putString(REPLACEMENT_PHASE, "PREPARING")
            .putBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false)
            .putBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)
            .putInt(REPLACEMENT_OLD_TASK, oldTask).putString(REPLACEMENT_OLD_PACKAGE, oldPackage)
            .putString(REPLACEMENT_OLD_BASE, oldBase).putInt(REPLACEMENT_OLD_DISPLAY, displayId)
            .putString(REPLACEMENT_OLD_IDENTITY, identity).putLong(REPLACEMENT_OLD_GENERATION, oldGeneration)
            .putBoolean(REPLACEMENT_OLD_LAUNCHER, oldLauncherDisabled)
            .putInt(REPLACEMENT_NEW_TASK, newTask).putString(REPLACEMENT_NEW_PACKAGE, newPackage)
            .putString(REPLACEMENT_NEW_BASE, newBase).putLong(REPLACEMENT_NEW_GENERATION, newGeneration)
            .putString(REPLACEMENT_NEW_SESSION, "").putBoolean(REPLACEMENT_NEW_VIRTUAL, newVirtual)
            .putLong(REPLACEMENT_NEW_COORDINATOR_GENERATION, newCoordinatorGeneration)
            .putString(REPLACEMENT_NEW_OWNER_IDENTITY, newOwnerIdentity)
            .putLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L)
            .putString(REPLACEMENT_VIRTUAL_BASELINE, virtualBaseline)
            .putString(REPLACEMENT_VIRTUAL_READER_STATE, "NOT_REQUESTED")
            .putString(REPLACEMENT_VIRTUAL_READER_ID, "")
            .putLong(REPLACEMENT_VIRTUAL_READER_GENERATION, 0L)
            .putString(REPLACEMENT_DECISION, "NONE")
            .commit()
        if (!committed) return@synchronized null
        PhysicalReplacementJournal(token, "PREPARING", oldTask, oldPackage, oldBase, displayId,
            identity, oldGeneration, oldLauncherDisabled, newTask, newPackage, newBase, newGeneration, "", newVirtual,
            newCoordinatorGeneration, newOwnerIdentity, 0L, virtualBaseline, "NOT_REQUESTED", "", 0L, "NONE")
    }

    @JvmStatic fun physicalReplacementJournal(context: Context): PhysicalReplacementJournal? = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        readPhysicalReplacementJournal(p)
    }

    private fun readPhysicalReplacementJournal(p: android.content.SharedPreferences): PhysicalReplacementJournal? {
        val token = p.getString(REPLACEMENT_TOKEN, null) ?: return null
        val phase = p.getString(REPLACEMENT_PHASE, null) ?: return null
        val oldPkg = p.getString(REPLACEMENT_OLD_PACKAGE, null) ?: return null
        val oldBase = p.getString(REPLACEMENT_OLD_BASE, null) ?: return null
        val identity = p.getString(REPLACEMENT_OLD_IDENTITY, null) ?: return null
        val newPkg = p.getString(REPLACEMENT_NEW_PACKAGE, null) ?: return null
        val newBase = p.getString(REPLACEMENT_NEW_BASE, null) ?: return null
        val newOwnerIdentity = p.getString(REPLACEMENT_NEW_OWNER_IDENTITY, null) ?: return null
        return PhysicalReplacementJournal(token, phase, p.getInt(REPLACEMENT_OLD_TASK, -1), oldPkg, oldBase,
            p.getInt(REPLACEMENT_OLD_DISPLAY, -1), identity, p.getLong(REPLACEMENT_OLD_GENERATION, 0L),
            p.getBoolean(REPLACEMENT_OLD_LAUNCHER, false), p.getInt(REPLACEMENT_NEW_TASK, -1),
            newPkg, newBase, p.getLong(REPLACEMENT_NEW_GENERATION, 0L), p.getString(REPLACEMENT_NEW_SESSION, "").orEmpty(),
            p.getBoolean(REPLACEMENT_NEW_VIRTUAL, false), p.getLong(REPLACEMENT_NEW_COORDINATOR_GENERATION, 0L),
            newOwnerIdentity, p.getLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L),
            p.getString(REPLACEMENT_VIRTUAL_BASELINE, "").orEmpty(),
            p.getString(REPLACEMENT_VIRTUAL_READER_STATE, "NOT_REQUESTED").orEmpty(),
            p.getString(REPLACEMENT_VIRTUAL_READER_ID, "").orEmpty(),
            p.getLong(REPLACEMENT_VIRTUAL_READER_GENERATION, 0L),
            p.getString(REPLACEMENT_DECISION, "NONE").orEmpty())
    }

    @JvmStatic fun setPhysicalReplacementSession(context: Context, token: String, expectedPhase: String,
            sessionId: String, backendGeneration: Long): Boolean = synchronized(lock) {
        if (sessionId.isBlank() || backendGeneration <= 0L) return@synchronized false
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(REPLACEMENT_TOKEN, null) != token || p.getString(REPLACEMENT_PHASE, null) != expectedPhase)
            return@synchronized false
        if (p.getString(REPLACEMENT_DECISION, "NONE") != "NONE") return@synchronized false
        val currentSession = p.getString(REPLACEMENT_NEW_SESSION, "").orEmpty()
        val currentGeneration = p.getLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L)
        if (!PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed(
                currentSession, currentGeneration, sessionId, backendGeneration)) return@synchronized false
        if (currentSession == sessionId && currentGeneration == backendGeneration) return@synchronized true
        p.edit().putString(REPLACEMENT_NEW_SESSION, sessionId)
            .putLong(REPLACEMENT_NEW_BACKEND_GENERATION, backendGeneration).commit()
    }

    /** One-way physical owner-session CAS; accepts only this journal's exact B task/generation tuple. */
    @JvmStatic fun recordPhysicalReplacementOwnerSession(context: Context, token: String,
        sessionId: String, generation: Long): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val live = readPhysicalReplacementJournal(p) ?: return@synchronized false
        val expectedSession = "physical:${live.newTask}:${live.newGeneration}"
        if (live.token != token || live.phase != "OWNER_B" || live.newVirtual || live.decision != "NONE"
            || !p.getBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false)
            || generation != live.newGeneration || sessionId != expectedSession
            || p.getInt(TASK, -1) != live.newTask || p.getString(PACKAGE, null) != live.newPackage
            || p.getString(BASE_COMPONENT, null) != live.newBase || p.getInt(DISPLAY, -1) != live.displayId
            || p.getString(DISPLAY_IDENTITY, null) != live.identity || p.getLong(GENERATION, 0L) != live.newGeneration
            || !p.getBoolean(CONTROLLED, false)) return@synchronized false
        val currentSession = p.getString(REPLACEMENT_NEW_SESSION, "").orEmpty()
        val currentGeneration = p.getLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L)
        if (currentSession.isNotBlank() || currentGeneration > 0L)
            return@synchronized currentSession == expectedSession && currentGeneration == live.newGeneration
        p.edit().putString(REPLACEMENT_NEW_SESSION, expectedSession)
            .putLong(REPLACEMENT_NEW_BACKEND_GENERATION, live.newGeneration).commit()
    }

    /** Minimal synchronous receipt from TaskService; never performs remote I/O or waits on an action gate. */
    @JvmStatic fun recordPhysicalReplacementReaderState(context: Context, token: String,
        virtualSessionId: String, virtualGeneration: Long, state: String, readerId: String,
        readerGeneration: Long, taskId: Int, displayId: Int, rearIdentity: String,
        ownerGeneration: Long, ownerIdentity: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(REPLACEMENT_TOKEN, null) != token
            || p.getBoolean(REPLACEMENT_NEW_VIRTUAL, false).not()
            || p.getInt(REPLACEMENT_NEW_TASK, -1) != taskId
            || p.getInt(REPLACEMENT_OLD_DISPLAY, -1) != displayId
            || p.getString(REPLACEMENT_OLD_IDENTITY, null) != rearIdentity
            || p.getLong(REPLACEMENT_NEW_COORDINATOR_GENERATION, 0L) != ownerGeneration
            || p.getString(REPLACEMENT_NEW_OWNER_IDENTITY, null) != ownerIdentity
            || virtualSessionId.isBlank() || virtualGeneration <= 0L) return@synchronized false
        val phase = p.getString(REPLACEMENT_PHASE, null)
        if (phase == null || !PhysicalReplacementLifecycle.readerEventAllowed(phase, state))
            return@synchronized false
        val decision = p.getString(REPLACEMENT_DECISION, "NONE").orEmpty()
        if ((decision == "ROLLBACK" && state !in setOf("RELEASED", "NO_READER"))
            || (decision == "ACCEPT" && state != "RELEASED")) return@synchronized false
        val currentSession = p.getString(REPLACEMENT_NEW_SESSION, "").orEmpty()
        val currentBackendGeneration = p.getLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L)
        if ((currentSession.isNotEmpty() && currentSession != virtualSessionId)
            || (currentBackendGeneration > 0L && currentBackendGeneration != virtualGeneration)) return@synchronized false
        val oldState = p.getString(REPLACEMENT_VIRTUAL_READER_STATE, "NOT_REQUESTED").orEmpty()
        val oldReader = p.getString(REPLACEMENT_VIRTUAL_READER_ID, "").orEmpty()
        val oldReaderGeneration = p.getLong(REPLACEMENT_VIRTUAL_READER_GENERATION, 0L)
        val nextReader = readerId ?: ""
        if (!physicalReaderTransitionAllowed(oldState, oldReader, oldReaderGeneration,
                state, nextReader, readerGeneration)) return@synchronized false
        val committed = p.edit().putString(REPLACEMENT_NEW_SESSION, virtualSessionId)
            .putLong(REPLACEMENT_NEW_BACKEND_GENERATION, virtualGeneration)
            .putString(REPLACEMENT_VIRTUAL_READER_STATE, if (state == "SESSION_CREATED") oldState else state)
            .putString(REPLACEMENT_VIRTUAL_READER_ID, nextReader)
            .putLong(REPLACEMENT_VIRTUAL_READER_GENERATION, readerGeneration).commit()
        committed
    }

    internal fun physicalReaderTransitionAllowed(oldState: String, oldReader: String, oldGeneration: Long,
        nextState: String, nextReader: String, nextGeneration: Long): Boolean {
        return PhysicalReplacementLifecycle.readerTransitionAllowed(oldState, oldReader, oldGeneration,
            nextState, nextReader, nextGeneration)
    }

    /** A's single Keeper stays alive while the replacement transaction is pending, even when B is foreground. */
    @JvmStatic fun isPhysicalReplacementProtecting(context: Context, taskId: Int, generation: Long): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(REPLACEMENT_TOKEN, null) != null
            && p.getInt(REPLACEMENT_OLD_TASK, -1) == taskId
            && p.getLong(REPLACEMENT_OLD_GENERATION, 0L) == generation
            && p.getInt(TASK, -1) == taskId && p.getLong(GENERATION, 0L) == generation
    }

    @JvmStatic fun advancePhysicalReplacement(context: Context, token: String, expectedPhase: String,
        nextPhase: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(REPLACEMENT_TOKEN, null) != token || p.getString(REPLACEMENT_PHASE, null) != expectedPhase)
            return@synchronized false
        if (!PhysicalReplacementLifecycle.phaseTransitionAllowed(expectedPhase, nextPhase,
                p.getBoolean(REPLACEMENT_NEW_VIRTUAL, false),
                p.getString(REPLACEMENT_VIRTUAL_READER_STATE, "NOT_REQUESTED").orEmpty())) return@synchronized false
        p.edit().putString(REPLACEMENT_PHASE, nextPhase).commit()
    }

    /** Durable PENDING intent set only at the final gate; not evidence that A was dispatched. */
    @JvmStatic fun authorizePhysicalAReturnMove(context: Context,
        expected: PhysicalReplacementJournal): PhysicalReplacementJournal? = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val live = readPhysicalReplacementJournal(p) ?: return@synchronized null
        val ownerExact = p.getInt(TASK, -1) == expected.oldTask
            && p.getString(PACKAGE, null) == expected.oldPackage
            && p.getString(BASE_COMPONENT, null) == expected.oldBase
            && p.getInt(DISPLAY, -1) == expected.displayId
            && p.getString(DISPLAY_IDENTITY, null) == expected.identity
            && p.getLong(GENERATION, 0L) == expected.oldGeneration
            && p.getBoolean(CONTROLLED, false)
        if (!PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(expected, live, ownerExact,
                p.getBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false)
                    || p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)))
            return@synchronized null
        if (!p.edit().putBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, true).commit()) return@synchronized null
        readPhysicalReplacementJournal(p)?.takeIf { it == expected }
    }

    /** Promote the pre-dispatch ticket only after the privileged move call may have been sent. */
    @JvmStatic fun recordPhysicalAReturnMoveMayHaveBeenSent(context: Context,
        expected: PhysicalReplacementJournal): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (readPhysicalReplacementJournal(p) != expected || expected.newVirtual
            || expected.phase != "A_RETURNING" || expected.decision != "NONE"
            || !p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)) return@synchronized false
        p.edit().putBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, true)
            .putBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false).commit()
            && readPhysicalReplacementJournal(p) == expected
            && p.getBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false)
            && !p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)
    }

    /** Readback of the one-shot move-authorization bit, bound to the exact live journal ticket. */
    @JvmStatic fun physicalAReturnMoveAuthorized(context: Context,
        expected: PhysicalReplacementJournal): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        readPhysicalReplacementJournal(p) == expected
            && PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(expected.newVirtual,
                expected.phase, expected.decision,
                p.getBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false),
                p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false))
    }

    /** Pending pre-dispatch intent is diagnostic only; recovery must never treat it as a sent move. */
    @JvmStatic fun physicalAReturnMovePending(context: Context,
        expected: PhysicalReplacementJournal): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        readPhysicalReplacementJournal(p) == expected
            && p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)
            && !p.getBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false)
            && !expected.newVirtual && expected.phase == "A_RETURNING" && expected.decision == "NONE"
    }

    /** Remove only the move marker owned by the exact still-live A_RETURNING physical journal. */
    @JvmStatic fun revokePhysicalAReturnMoveAuthorization(context: Context,
        expected: PhysicalReplacementJournal): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (readPhysicalReplacementJournal(p) != expected || expected.newVirtual
            || expected.phase != "A_RETURNING" || expected.decision != "NONE") return@synchronized false
        if (p.getBoolean(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED, false)) return@synchronized false
        if (!p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)) return@synchronized true
        p.edit().putBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false).commit()
            && readPhysicalReplacementJournal(p) == expected
            && !p.getBoolean(REPLACEMENT_A_RETURN_MOVE_PENDING, false)
    }

    /** Exact session/reader-bound CAS used only after the physical→virtual B-ready receipt. */
    @JvmStatic fun advancePhysicalReplacementToBReady(context: Context,
        expected: PhysicalReplacementJournal): PhysicalReplacementJournal? =
        advancePhysicalToVirtualPhase(context, expected, "PREPARING", "B_READY")

    /** Narrow first-A-move CAS; returns the freshly committed A_RETURNING journal for the mutation boundary. */
    @JvmStatic fun advancePhysicalReplacementToAReturning(context: Context,
        expected: PhysicalReplacementJournal): PhysicalReplacementJournal? =
        advancePhysicalToVirtualPhase(context, expected, "B_READY", "A_RETURNING")

    private fun advancePhysicalToVirtualPhase(context: Context, expected: PhysicalReplacementJournal,
        from: String, to: String): PhysicalReplacementJournal? = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val live = readPhysicalReplacementJournal(p) ?: return@synchronized null
        if (!PhysicalReplacementLifecycle.p2vPhaseCasAllowed(expected, live, from, to)
            || !hasClaimedReplacementOldOwner(expected, live, p)) return@synchronized null
        if (!p.edit().putString(REPLACEMENT_PHASE, to).commit()) return@synchronized null
        readPhysicalReplacementJournal(p)?.takeIf { it == expected.copy(phase = to) }
    }

    /** Local durable handoff barrier, called inside Coordinator's final state lock; no Binder/device work. */
    @JvmStatic fun markPhysicalReplacementAccepted(context: Context, sessionId: String,
            ownerGeneration: Long, ownerIdentity: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString(REPLACEMENT_TOKEN, null) ?: return@synchronized false
        val phase = p.getString(REPLACEMENT_PHASE, null)
        val next = when (phase) {
            "OWNER_B" -> "ACCEPTED_B"
            "OWNER_VIRTUAL" -> "ACCEPTED_VIRTUAL"
            else -> return@synchronized false
        }
        if (p.getString(REPLACEMENT_NEW_SESSION, null) != sessionId
            || p.getLong(REPLACEMENT_NEW_COORDINATOR_GENERATION, 0L) != ownerGeneration
            || p.getString(REPLACEMENT_NEW_OWNER_IDENTITY, null) != ownerIdentity
            || p.getLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L) <= 0L
            || !PhysicalReplacementLifecycle.phaseTransitionAllowed(phase.orEmpty(), next,
                p.getBoolean(REPLACEMENT_NEW_VIRTUAL, false),
                p.getString(REPLACEMENT_VIRTUAL_READER_STATE, "NOT_REQUESTED").orEmpty())) return@synchronized false
        val nextDecision = PhysicalReplacementLifecycle.nextDecision(
            p.getString(REPLACEMENT_DECISION, "NONE").orEmpty(), "ACCEPT") ?: return@synchronized false
        p.edit().putString(REPLACEMENT_DECISION, nextDecision).putString(REPLACEMENT_PHASE, next).commit()
    }

    /** First-writer-wins decision shared by SDK acceptance and every explicit rollback entry. */
    @JvmStatic fun claimPhysicalReplacementRollback(context: Context, token: String, sessionId: String,
        backendGeneration: Long, coordinatorGeneration: Long, ownerIdentity: String, taskId: Int,
        baseComponent: String, displayId: Int, rearIdentity: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!PhysicalReplacementLifecycle.exactRollbackTicket(token, p.getString(REPLACEMENT_TOKEN, null).orEmpty(),
                sessionId, p.getString(REPLACEMENT_NEW_SESSION, "").orEmpty(),
                backendGeneration, p.getLong(REPLACEMENT_NEW_BACKEND_GENERATION, 0L),
                coordinatorGeneration, p.getLong(REPLACEMENT_NEW_COORDINATOR_GENERATION, 0L),
                ownerIdentity, p.getString(REPLACEMENT_NEW_OWNER_IDENTITY, "").orEmpty(),
                taskId, p.getInt(REPLACEMENT_NEW_TASK, -1),
                baseComponent, p.getString(REPLACEMENT_NEW_BASE, "").orEmpty(),
                displayId, p.getInt(REPLACEMENT_OLD_DISPLAY, -1),
                rearIdentity, p.getString(REPLACEMENT_OLD_IDENTITY, "").orEmpty())) return@synchronized false
        val phase = p.getString(REPLACEMENT_PHASE, null)
        if (phase !in setOf("PREPARING", "B_READY", "A_RETURNING", "A_RETURNED", "OWNER_VIRTUAL", "OWNER_A"))
            return@synchronized false
        val nextDecision = PhysicalReplacementLifecycle.nextDecision(
            p.getString(REPLACEMENT_DECISION, "NONE").orEmpty(), "ROLLBACK") ?: return@synchronized false
        if (p.getString(REPLACEMENT_DECISION, "NONE") == nextDecision) true
        else p.edit().putString(REPLACEMENT_DECISION, nextDecision).commit()
    }

    /** Accepted B closes only itself; only pre-acceptance journal sessions may invoke rollback. */
    @JvmStatic fun replacementCloseRoute(phase: String, requestedSession: String,
            oldSession: String, newSession: String): String = when {
        phase == "ACCEPTED_B" && requestedSession == newSession -> "CLOSE_NEW_OWNER"
        phase in setOf("ACCEPTED_B", "ACCEPTED_VIRTUAL") -> "STALE_OWNER"
        requestedSession == oldSession || (newSession.isNotBlank() && requestedSession == newSession) -> "RECOVER_PENDING"
        else -> "STALE_OWNER"
    }

    /** Process-death recovery is phase-driven: accepted B is never rolled back into A. */
    @JvmStatic fun replacementRecoveryRoute(phase: String, newVirtual: Boolean, sessionRecorded: Boolean): String = when {
        phase == "ACCEPTED_B" -> "KEEP_ACCEPTED_B"
        phase == "ACCEPTED_VIRTUAL" && sessionRecorded -> "CLOSE_ACCEPTED_VIRTUAL"
        phase == "ACCEPTED_VIRTUAL" -> "UNKNOWN"
        phase in setOf("PREPARING", "B_READY", "A_RETURNING", "A_RETURNED", "OWNER_B", "OWNER_VIRTUAL", "OWNER_A")
            && newVirtual && !sessionRecorded && phase != "PREPARING" -> "UNKNOWN"
        phase in setOf("PREPARING", "B_READY", "A_RETURNING", "A_RETURNED", "OWNER_B", "OWNER_VIRTUAL", "OWNER_A") -> "ROLLBACK_TO_A"
        else -> "UNKNOWN"
    }

    @JvmStatic fun acceptedVirtualCloseRetryMatches(journal: PhysicalReplacementJournal?,
        sessionId: String, backendGeneration: Long, coordinatorGeneration: Long,
        ownerIdentity: String, taskId: Int, baseComponent: String,
        displayId: Int, rearIdentity: String): Boolean =
        journal != null && journal.newVirtual && journal.phase == "ACCEPTED_VIRTUAL"
            && journal.decision == "ACCEPT"
            && journal.newSessionId == sessionId && journal.newBackendGeneration == backendGeneration
            && journal.newCoordinatorGeneration == coordinatorGeneration
            && journal.newOwnerIdentity == ownerIdentity && journal.newTask == taskId
            && journal.newBase == baseComponent && journal.displayId == displayId
            && journal.identity == rearIdentity

    /** Commit B as the sole owner only after caller proves B ready and A returned/cleaned. */
    @JvmStatic fun commitPhysicalReplacementOwner(context: Context, token: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(REPLACEMENT_TOKEN, null) != token || p.getString(REPLACEMENT_DECISION, "NONE") != "NONE"
            || !canCommitPhysicalReplacementSnapshot(
                p.getString(REPLACEMENT_PHASE, null), p.getInt(TASK, -1), p.getLong(GENERATION, 0L),
                p.getBoolean(CONTROLLED, false), p.getInt(REPLACEMENT_OLD_TASK, -1),
                p.getLong(REPLACEMENT_OLD_GENERATION, 0L))) return@synchronized false
        val generation = p.getLong(REPLACEMENT_NEW_GENERATION, 0L)
        val committed = p.edit().putInt(TASK, p.getInt(REPLACEMENT_NEW_TASK, -1))
            .putString(PACKAGE, p.getString(REPLACEMENT_NEW_PACKAGE, null))
            .putString(BASE_COMPONENT, p.getString(REPLACEMENT_NEW_BASE, null))
            .putInt(DISPLAY, p.getInt(REPLACEMENT_OLD_DISPLAY, -1))
            .putString(DISPLAY_IDENTITY, p.getString(REPLACEMENT_OLD_IDENTITY, null))
            .putLong(GENERATION, generation).putBoolean(CONTROLLED, true)
            .putBoolean(LAUNCHER_DISABLED, p.getBoolean(REPLACEMENT_OLD_LAUNCHER, false))
            .putBoolean(KEEPER_START_PENDING, false).putBoolean(KEEPER_START_CLAIMED, false)
            .putBoolean(CONTROLLED_OWNER_CLOSING, false).putString(REPLACEMENT_PHASE, "OWNER_B")
            .commit()
        if (committed) ownershipEpoch.projectionCommitted()
        committed
    }

    /** Restore A as owner after proven compensation; journal stays if persistence fails. */
    @JvmStatic fun rollbackPhysicalReplacementOwner(context: Context, token: String,
        retainClaimedKeeper: Boolean): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(REPLACEMENT_TOKEN, null) != token
            || p.getString(REPLACEMENT_PHASE, null) in setOf("ACCEPTED_B", "ACCEPTED_VIRTUAL")) return@synchronized false
        if (p.getBoolean(REPLACEMENT_NEW_VIRTUAL, false)
            && p.getString(REPLACEMENT_DECISION, "NONE") != "ROLLBACK") return@synchronized false
        val currentTask = p.getInt(TASK, -1)
        val currentGeneration = p.getLong(GENERATION, 0L)
        val ownerA = currentTask == p.getInt(REPLACEMENT_OLD_TASK, -1)
            && currentGeneration == p.getLong(REPLACEMENT_OLD_GENERATION, 0L)
        val ownerB = currentTask == p.getInt(REPLACEMENT_NEW_TASK, -1)
            && currentGeneration == p.getLong(REPLACEMENT_NEW_GENERATION, 0L)
        if (!ownerA && !ownerB && !(currentTask <= 0 && currentGeneration <= 0L)) return@synchronized false
        val keepClaim = retainClaimedKeeper && ownerA
        if (retainClaimedKeeper && (!keepClaim || p.getBoolean(KEEPER_START_PENDING, false)
                || p.getBoolean(CONTROLLED_OWNER_CLOSING, false)
                || p.getString(PACKAGE, null) != p.getString(REPLACEMENT_OLD_PACKAGE, null)
                || p.getString(BASE_COMPONENT, null) != p.getString(REPLACEMENT_OLD_BASE, null)
                || p.getInt(DISPLAY, -1) != p.getInt(REPLACEMENT_OLD_DISPLAY, -1)
                || p.getString(DISPLAY_IDENTITY, null) != p.getString(REPLACEMENT_OLD_IDENTITY, null)
                || !p.getBoolean(CONTROLLED, false))) return@synchronized false
        val committed = p.edit().putInt(TASK, p.getInt(REPLACEMENT_OLD_TASK, -1))
            .putString(PACKAGE, p.getString(REPLACEMENT_OLD_PACKAGE, null))
            .putString(BASE_COMPONENT, p.getString(REPLACEMENT_OLD_BASE, null))
            .putInt(DISPLAY, p.getInt(REPLACEMENT_OLD_DISPLAY, -1))
            .putString(DISPLAY_IDENTITY, p.getString(REPLACEMENT_OLD_IDENTITY, null))
            .putLong(GENERATION, p.getLong(REPLACEMENT_OLD_GENERATION, 0L))
            .putBoolean(CONTROLLED, true).putBoolean(LAUNCHER_DISABLED, p.getBoolean(REPLACEMENT_OLD_LAUNCHER, false))
            .putBoolean(KEEPER_START_PENDING, false).putBoolean(KEEPER_START_CLAIMED, keepClaim)
            .putBoolean(CONTROLLED_OWNER_CLOSING, false).putString(REPLACEMENT_PHASE, "OWNER_A")
            .commit()
        if (committed) ownershipEpoch.projectionCommitted()
        committed
    }

    /** Clear only terminal journal state after exact resources and owner state are verified. */
    @JvmStatic fun finishPhysicalReplacement(context: Context, token: String, terminalPhase: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(REPLACEMENT_TOKEN, null) != token || p.getString(REPLACEMENT_PHASE, null) != terminalPhase
            || terminalPhase !in setOf("OWNER_A", "ACCEPTED_B", "ACCEPTED_VIRTUAL")) return@synchronized false
        if ((terminalPhase == "OWNER_A" && p.getBoolean(REPLACEMENT_NEW_VIRTUAL, false)
                && p.getString(REPLACEMENT_DECISION, "NONE") != "ROLLBACK")
            || (terminalPhase != "OWNER_A" && p.getString(REPLACEMENT_DECISION, "NONE") != "ACCEPT"))
            return@synchronized false
        val oldGeneration = p.getLong(REPLACEMENT_OLD_GENERATION, 0L)
        val finished = p.edit().remove(REPLACEMENT_TOKEN).remove(REPLACEMENT_PHASE).remove(REPLACEMENT_OLD_TASK)
            .remove(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED)
            .remove(REPLACEMENT_A_RETURN_MOVE_PENDING)
            .remove(REPLACEMENT_OLD_PACKAGE).remove(REPLACEMENT_OLD_BASE).remove(REPLACEMENT_OLD_DISPLAY)
            .remove(REPLACEMENT_OLD_IDENTITY).remove(REPLACEMENT_OLD_GENERATION).remove(REPLACEMENT_OLD_LAUNCHER)
            .remove(REPLACEMENT_NEW_TASK).remove(REPLACEMENT_NEW_PACKAGE).remove(REPLACEMENT_NEW_BASE)
            .remove(REPLACEMENT_NEW_GENERATION).remove(REPLACEMENT_NEW_SESSION).remove(REPLACEMENT_NEW_VIRTUAL)
            .remove(REPLACEMENT_NEW_COORDINATOR_GENERATION).remove(REPLACEMENT_NEW_OWNER_IDENTITY)
            .remove(REPLACEMENT_NEW_BACKEND_GENERATION).remove(REPLACEMENT_VIRTUAL_BASELINE)
            .remove(REPLACEMENT_VIRTUAL_READER_STATE).remove(REPLACEMENT_VIRTUAL_READER_ID)
            .remove(REPLACEMENT_VIRTUAL_READER_GENERATION).remove(REPLACEMENT_DECISION).commit()
        if (finished && oldGeneration > 0L) top.zainzh.rearscreen.RearScreenKeeperService
            .clearControlledPhysicalRecoveryNotification(context, oldGeneration)
        finished
    }

    /** Atomically retires an explicitly closed, unaccepted replacement after exact resource readback. */
    @JvmStatic fun finishUnacceptedPhysicalReplacementClose(
        context: Context, expected: PhysicalReplacementJournal
    ): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!PhysicalReplacementLifecycle.canCloseUnacceptedReplacement(expected)
            || expected.newTask <= 0 || expected.newGeneration <= 0L
            || readPhysicalReplacementJournal(p) != expected
            || p.getLong(GENERATION_SEQUENCE, 0L) != expected.newGeneration
            || p.getInt(TASK, -1) != expected.oldTask
            || p.getString(PACKAGE, null) != expected.oldPackage
            || p.getString(BASE_COMPONENT, null) != expected.oldBase
            || p.getInt(DISPLAY, -1) != expected.displayId
            || p.getString(DISPLAY_IDENTITY, null) != expected.identity
            || p.getLong(GENERATION, 0L) != expected.oldGeneration
            || !p.getBoolean(CONTROLLED, false)
            || !p.getBoolean(CONTROLLED_OWNER_CLOSING, false)
            || p.getBoolean(KEEPER_START_PENDING, false)
            || p.getBoolean(KEEPER_START_CLAIMED, false)) return@synchronized false
        val finished = p.edit()
            .remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
            .remove(DISPLAY).remove(DISPLAY_IDENTITY).remove(LAUNCHER_DISABLED)
            .remove(KEEPER_START_PENDING).remove(KEEPER_START_CLAIMED).remove(CONTROLLED_OWNER_CLOSING)
            .remove(GENERATION)
            .remove(REPLACEMENT_TOKEN).remove(REPLACEMENT_PHASE).remove(REPLACEMENT_A_RETURN_MOVE_AUTHORIZED)
            .remove(REPLACEMENT_A_RETURN_MOVE_PENDING)
            .remove(REPLACEMENT_OLD_TASK)
            .remove(REPLACEMENT_OLD_PACKAGE).remove(REPLACEMENT_OLD_BASE).remove(REPLACEMENT_OLD_DISPLAY)
            .remove(REPLACEMENT_OLD_IDENTITY).remove(REPLACEMENT_OLD_GENERATION).remove(REPLACEMENT_OLD_LAUNCHER)
            .remove(REPLACEMENT_NEW_TASK).remove(REPLACEMENT_NEW_PACKAGE).remove(REPLACEMENT_NEW_BASE)
            .remove(REPLACEMENT_NEW_GENERATION).remove(REPLACEMENT_NEW_SESSION).remove(REPLACEMENT_NEW_VIRTUAL)
            .remove(REPLACEMENT_NEW_COORDINATOR_GENERATION).remove(REPLACEMENT_NEW_OWNER_IDENTITY)
            .remove(REPLACEMENT_NEW_BACKEND_GENERATION).remove(REPLACEMENT_VIRTUAL_BASELINE)
            .remove(REPLACEMENT_VIRTUAL_READER_STATE).remove(REPLACEMENT_VIRTUAL_READER_ID)
            .remove(REPLACEMENT_VIRTUAL_READER_GENERATION).remove(REPLACEMENT_DECISION).commit()
        if (finished) top.zainzh.rearscreen.RearScreenKeeperService
            .clearControlledPhysicalRecoveryNotification(context, expected.oldGeneration)
        finished
    }

    /** Reopens an exact old owner only when the explicit close found a live Keeper or changed authority. */
    @JvmStatic fun cancelUnacceptedPhysicalReplacementClose(
        context: Context, expected: PhysicalReplacementJournal
    ): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!PhysicalReplacementLifecycle.canCloseUnacceptedReplacement(expected)
            || readPhysicalReplacementJournal(p) != expected
            || p.getInt(TASK, -1) != expected.oldTask || p.getString(PACKAGE, null) != expected.oldPackage
            || p.getString(BASE_COMPONENT, null) != expected.oldBase || p.getInt(DISPLAY, -1) != expected.displayId
            || p.getString(DISPLAY_IDENTITY, null) != expected.identity
            || p.getLong(GENERATION, 0L) != expected.oldGeneration
            || !p.getBoolean(CONTROLLED, false) || !p.getBoolean(CONTROLLED_OWNER_CLOSING, false)
            || p.getBoolean(KEEPER_START_PENDING, false)) return@synchronized false
        p.edit().putBoolean(CONTROLLED_OWNER_CLOSING, false).commit()
    }

    @JvmStatic fun isCurrentExplicitVirtualRollbackClose(context: Context,
        expected: PhysicalReplacementJournal): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        PhysicalReplacementLifecycle.isPreparingVirtualRollback(expected.phase, expected.decision, expected.newVirtual)
            && readPhysicalReplacementJournal(p) == expected
            && p.getString(BASE_COMPONENT, null) == expected.oldBase
            && physicalKeeperOwnerMatches("${expected.oldPackage}:${expected.oldTask}", expected.displayId,
                expected.identity, expected.oldGeneration, p)
            && p.getBoolean(CONTROLLED_OWNER_CLOSING, false) && !p.getBoolean(KEEPER_START_PENDING, false)
    }

    /** Retire A's physical record only after its task, Keeper, launcher resource and virtual Keeper moved. */
    @JvmStatic fun clearPhysicalOwnerForVirtualReplacement(context: Context, token: String): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!PhysicalReplacementLifecycle.phaseTransitionAllowed("A_RETURNED", "OWNER_VIRTUAL", true,
                p.getString(REPLACEMENT_VIRTUAL_READER_STATE, "NOT_REQUESTED").orEmpty())) return@synchronized false
        if (p.getString(REPLACEMENT_TOKEN, null) != token || p.getString(REPLACEMENT_PHASE, null) != "A_RETURNED"
            || p.getString(REPLACEMENT_DECISION, "NONE") != "NONE"
            || p.getInt(TASK, -1) != p.getInt(REPLACEMENT_OLD_TASK, -1)
            || p.getLong(GENERATION, 0L) != p.getLong(REPLACEMENT_OLD_GENERATION, 0L)
            || !p.getBoolean(CONTROLLED, false)) return@synchronized false
        p.edit().remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
            .remove(DISPLAY).remove(DISPLAY_IDENTITY).remove(LAUNCHER_DISABLED)
            .remove(KEEPER_START_PENDING).remove(KEEPER_START_CLAIMED).remove(CONTROLLED_OWNER_CLOSING)
            .remove(GENERATION).putString(REPLACEMENT_PHASE, "OWNER_VIRTUAL").commit()
    }

    fun epoch(): Long = ownershipEpoch.current()

    fun noteProjectionStarted() { synchronized(lock) { ownershipEpoch.projectionCommitted() } }

    fun recordPhysicalProjection(context: Context, taskId: Int, packageName: String, displayId: Int) {
        require(taskId > 0 && packageName.isNotBlank() && displayId > 0)
        val physicalIdentity = runCatching { RearDisplay.identity() }.getOrDefault("")
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previousGeneration = prefs.getLong(GENERATION, 0L)
            val generation = prefs.getLong(GENERATION_SEQUENCE, 0L) + 1L
            prefs.edit()
                .putLong(GENERATION_SEQUENCE, generation)
                .putLong(GENERATION, generation)
                .putInt(TASK, taskId)
                .putString(PACKAGE, packageName)
                .remove(BASE_COMPONENT)
                .putInt(DISPLAY, displayId)
                .putString(DISPLAY_IDENTITY, physicalIdentity)
                .putBoolean(LAUNCHER_DISABLED, false)
                .putBoolean(CONTROLLED, false)
                .putBoolean(KEEPER_START_PENDING, false)
                .putBoolean(KEEPER_START_CLAIMED, false)
                .putBoolean(CONTROLLED_OWNER_CLOSING, false)
                .apply()
            ownershipEpoch.projectionCommitted()
            if (previousGeneration > 0L) top.zainzh.rearscreen.RearScreenKeeperService
                .clearControlledPhysicalRecoveryNotification(context, previousGeneration)
        }
    }

    /** Controlled Cast claims only an empty lease row; it never overwrites an existing owner. */
    @JvmStatic fun recordPhysicalProjectionIfEmpty(context: Context, taskId: Int, packageName: String,
                                                    baseComponent: String,
                                                    displayId: Int, approvedIdentity: String,
                                                    authoritativeSelectedId: Int,
                                                    authoritativeSelectedUnique: String): Long? = synchronized(lock) {
        require(taskId > 0 && packageName.isNotBlank() && baseComponent.isNotBlank() && displayId > 0)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(REPLACEMENT_TOKEN, null) != null) return@synchronized null
        if (!canClaimPhysicalProjectionIfEmpty(prefs.getInt(TASK, -1), displayId, approvedIdentity,
                authoritativeSelectedId, authoritativeSelectedUnique)) return@synchronized null
        val previousGeneration = prefs.getLong(GENERATION, 0L)
        val generation = prefs.getLong(GENERATION_SEQUENCE, 0L) + 1L
        val committed = prefs.edit().putLong(GENERATION_SEQUENCE, generation).putLong(GENERATION, generation)
            .putInt(TASK, taskId).putString(PACKAGE, packageName).putString(BASE_COMPONENT, baseComponent)
            .putInt(DISPLAY, displayId).putString(DISPLAY_IDENTITY, approvedIdentity)
            .putBoolean(CONTROLLED, true).putBoolean(LAUNCHER_DISABLED, false)
            .putBoolean(KEEPER_START_PENDING, false).putBoolean(KEEPER_START_CLAIMED, false)
            .putBoolean(CONTROLLED_OWNER_CLOSING, false).commit()
        if (!committed) return@synchronized null
        ownershipEpoch.projectionCommitted()
        if (previousGeneration > 0L) top.zainzh.rearscreen.RearScreenKeeperService
            .clearControlledPhysicalRecoveryNotification(context, previousGeneration)
        generation
    }

    @JvmStatic fun physicalProjectionGeneration(context: Context, taskId: Int, displayId: Int): Long =
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (taskId <= 0 || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId)
                return@synchronized 0L
            val current = prefs.getLong(GENERATION, 0L)
            if (current > 0L) return@synchronized current
            val migrated = prefs.getLong(GENERATION_SEQUENCE, 0L) + 1L
            if (!prefs.edit().putLong(GENERATION_SEQUENCE, migrated).putLong(GENERATION, migrated).commit())
                return@synchronized 0L
            migrated
        }

    /** Exact owner identity used before a replacement journal may authorize moving A again. */
    @JvmStatic fun physicalProjectionMatches(context: Context, taskId: Int, packageName: String,
        baseComponent: String, displayId: Int, identity: String, generation: Long): Boolean = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        generation > 0L && p.getInt(TASK, -1) == taskId && p.getString(PACKAGE, null) == packageName
            && p.getString(BASE_COMPONENT, null) == baseComponent && p.getInt(DISPLAY, -1) == displayId
            && p.getString(DISPLAY_IDENTITY, null) == identity && p.getLong(GENERATION, 0L) == generation
            && p.getBoolean(CONTROLLED, false)
    }

    /** Tri-state exact old-owner read bound to this rollback ticket and current phase. */
    @JvmStatic fun physicalReplacementOwnerFact(context: Context,
        journal: PhysicalReplacementJournal): String = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phase = p.getString(REPLACEMENT_PHASE, null).orEmpty()
        if (p.getString(REPLACEMENT_TOKEN, null) != journal.token || phase != journal.phase
            || p.getString(REPLACEMENT_DECISION, "NONE") !in setOf("NONE", "ROLLBACK")) return@synchronized "UNKNOWN"
        PhysicalReplacementLifecycle.replacementOwnerFact(phase,
            p.getInt(TASK, -1), p.getString(PACKAGE, null), p.getString(BASE_COMPONENT, null),
            p.getInt(DISPLAY, -1), p.getString(DISPLAY_IDENTITY, null), p.getLong(GENERATION, 0L),
            p.getBoolean(CONTROLLED, false), journal.oldTask, journal.oldPackage, journal.oldBase,
            journal.displayId, journal.identity, journal.oldGeneration)
    }

    internal data class ControlledPhysicalProjectionIdentity(val taskId: Int, val packageName: String,
        val baseComponent: String, val displayId: Int, val identity: String, val generation: Long)

    /** Privacy-safe provenance captured from the same prefs + exact placement read used by admission. */
    internal data class PhysicalProjectionAdmission(
        val disposition: String,
        val taskId: Int = -1,
        val generationMatches: Boolean = false,
        val placement: String = "unknown",
        internal val storedGeneration: Long = 0L,
        val transactionPending: Boolean = false
    )

    internal data class PhysicalProjectionAdmissionRead(
        val hasOwner: Boolean,
        val packageTask: String?,
        val diagnostic: PhysicalProjectionAdmission
    )

    private data class PhysicalProjectionRecordSnapshot(
        val taskId: Int, val packageName: String?, val displayId: Int, val identity: String?,
        val generation: Long, val controlled: Boolean, val launcherDisabled: Boolean,
        val replacementPending: Boolean, val recoveryPending: Boolean
    )

    /** Combines the legacy has/task reads so admission and its redacted trace share one exact placement read. */
    @JvmStatic fun readPhysicalProjectionAdmission(context: Context, service: ITaskService,
        selectedRearDisplay: Int): PhysicalProjectionAdmissionRead {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val record = synchronized(lock) { PhysicalProjectionRecordSnapshot(
            prefs.getInt(TASK, -1), prefs.getString(PACKAGE, null), prefs.getInt(DISPLAY, -1),
            prefs.getString(DISPLAY_IDENTITY, null), prefs.getLong(GENERATION, 0L),
            prefs.getBoolean(CONTROLLED, false), prefs.getBoolean(LAUNCHER_DISABLED, false),
            prefs.getString(REPLACEMENT_TOKEN, null) != null,
            prefs.getBoolean(KEEPER_START_PENDING, false) || prefs.getBoolean(KEEPER_START_CLAIMED, false)
                || prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false))
        }
        val taskId = record.taskId
        val packageName = record.packageName
        val displayId = record.displayId
        val identity = record.identity
        val generation = record.generation
        val controlled = record.controlled
        val launcherDisabled = record.launcherDisabled
        if (taskId <= 0 && (record.replacementPending || record.recoveryPending))
            return PhysicalProjectionAdmissionRead(true, null,
                PhysicalProjectionAdmission("foreign_unknown", taskId, false,
                    if (record.replacementPending) "replacement_pending" else "recovery_pending", generation))
        if (taskId <= 0) return PhysicalProjectionAdmissionRead(false, null,
            PhysicalProjectionAdmission("no_record", placement = "none"))
        if (packageName.isNullOrBlank() || displayId != selectedRearDisplay || identity.isNullOrBlank())
            return PhysicalProjectionAdmissionRead(true, null,
                PhysicalProjectionAdmission("foreign_unknown", taskId, false, "unknown"))
        val placement = exactPhysicalOwnerPlacement(service, taskId, packageName, displayId, identity)
        val placementName = when (placement.kind) {
            ExactPhysicalOwnerPlacement.Kind.PRESENT -> "present"
            ExactPhysicalOwnerPlacement.Kind.LEFT -> "left"
            ExactPhysicalOwnerPlacement.Kind.UNKNOWN -> "unknown"
        }
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.UNKNOWN || physicalOwnerIdentityMustFailClosed(
                physicalOwnerIdentityCheck(displayId, identity, placement.selectedId, placement.selectedUnique)))
            return PhysicalProjectionAdmissionRead(true, null,
                PhysicalProjectionAdmission("foreign_unknown", taskId, false, placementName))
        val generationMatches = generation > 0L
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.LEFT && !launcherDisabled && !controlled) {
            val cleared = clearStaleLegacyProjectionIfCurrent(context, taskId, packageName, displayId,
                identity, generation)
            if (!cleared) return PhysicalProjectionAdmissionRead(true, null,
                PhysicalProjectionAdmission("foreign_unknown", taskId, false, "changed_during_read"))
            return PhysicalProjectionAdmissionRead(false, null,
                PhysicalProjectionAdmission("stale_legacy_cleared", taskId, generationMatches, "left", generation))
        }
        val hasOwner = record.replacementPending || controlled || placement.kind == ExactPhysicalOwnerPlacement.Kind.PRESENT
        val packageTask = if (controlled || placement.kind == ExactPhysicalOwnerPlacement.Kind.PRESENT
            || (placement.kind == ExactPhysicalOwnerPlacement.Kind.LEFT && launcherDisabled)) "$packageName:$taskId" else null
        val disposition = when {
            controlled -> "exact_controlled"
            placement.kind == ExactPhysicalOwnerPlacement.Kind.PRESENT -> "exact_legacy"
            placement.kind == ExactPhysicalOwnerPlacement.Kind.LEFT && launcherDisabled -> "legacy_cleanup_pending"
            else -> "no_record"
        }
        return PhysicalProjectionAdmissionRead(hasOwner, packageTask,
            PhysicalProjectionAdmission(disposition, taskId, generationMatches, placementName, generation,
                record.replacementPending || record.recoveryPending))
    }

    /** Clear only the exact stale legacy row observed above; never erase a newer restore/recovery claim. */
    private fun clearStaleLegacyProjectionIfCurrent(context: Context, taskId: Int, packageName: String,
        displayId: Int, identity: String, generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!canClearStaleLegacyOwnerSnapshot(taskId, packageName, displayId, identity, generation,
                prefs.getInt(TASK, -1), prefs.getString(PACKAGE, null), prefs.getInt(DISPLAY, -1),
                prefs.getString(DISPLAY_IDENTITY, null), prefs.getLong(GENERATION, 0L),
                prefs.getBoolean(CONTROLLED, false), prefs.getBoolean(LAUNCHER_DISABLED, false),
                prefs.getString(REPLACEMENT_TOKEN, null) != null,
                prefs.getBoolean(KEEPER_START_PENDING, false) || prefs.getBoolean(KEEPER_START_CLAIMED, false)
                    || prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false))) return@synchronized false
        val committed = prefs.edit().remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
            .remove(DISPLAY).remove(DISPLAY_IDENTITY).remove(LAUNCHER_DISABLED)
            .remove(KEEPER_START_PENDING).remove(KEEPER_START_CLAIMED).remove(CONTROLLED_OWNER_CLOSING)
            .remove(GENERATION).commit()
        if (!committed) return@synchronized false
        top.zainzh.rearscreen.RearScreenKeeperService.clearControlledPhysicalRecoveryNotification(context, generation)
        ownershipEpoch.staleRecordPruned()
        true
    }

    internal fun canClearStaleLegacyOwnerSnapshot(expectedTaskId: Int, expectedPackage: String,
        expectedDisplayId: Int, expectedIdentity: String, expectedGeneration: Long,
        actualTaskId: Int, actualPackage: String?, actualDisplayId: Int, actualIdentity: String?,
        actualGeneration: Long, controlled: Boolean, launcherDisabled: Boolean,
        replacementPending: Boolean, recoveryPending: Boolean): Boolean =
        !controlled && !launcherDisabled && !replacementPending && !recoveryPending
            && matchesPhysicalOwnerSnapshot(expectedTaskId, expectedPackage, expectedDisplayId, expectedIdentity,
                expectedGeneration, actualTaskId, actualDisplayId, actualIdentity, actualGeneration, actualPackage)

    @JvmStatic fun hasControlledPhysicalProjectionRecord(context: Context): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(REPLACEMENT_TOKEN, null) != null) return@synchronized true
        prefs.getBoolean(CONTROLLED, false) && prefs.getInt(TASK, -1) > 0
                && !prefs.getString(PACKAGE, null).isNullOrBlank()
                && !prefs.getString(BASE_COMPONENT, null).isNullOrBlank()
                && prefs.getInt(DISPLAY, -1) > 0
                && !prefs.getString(DISPLAY_IDENTITY, null).isNullOrBlank()
                && prefs.getLong(GENERATION, 0L) > 0L
    }

    @JvmStatic fun controlledPhysicalProjectionGeneration(context: Context): Long? = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val generation = prefs.getLong(GENERATION, 0L)
        if (prefs.getBoolean(CONTROLLED, false) && generation > 0L) generation
        else prefs.getLong(REPLACEMENT_OLD_GENERATION, 0L).takeIf {
            prefs.getString(REPLACEMENT_TOKEN, null) != null && it > 0L
        }
    }

    /** User-triggered exact return can recover a controlled owner even when current task readback is unknown. */
    @JvmStatic fun controlledPhysicalProjectionForRecovery(context: Context,
            selectedRearDisplay: Int): ControlledPhysicalProjectionIdentity? = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val task = prefs.getInt(TASK, -1)
        val pkg = prefs.getString(PACKAGE, null)
        val base = prefs.getString(BASE_COMPONENT, null)
        val display = prefs.getInt(DISPLAY, -1)
        val identity = prefs.getString(DISPLAY_IDENTITY, null)
        val generation = prefs.getLong(GENERATION, 0L)
        if (!prefs.getBoolean(CONTROLLED, false) || task <= 0 || pkg.isNullOrBlank() || base.isNullOrBlank()
            || display != selectedRearDisplay || identity.isNullOrBlank() || generation <= 0L) {
            val oldTask = prefs.getInt(REPLACEMENT_OLD_TASK, -1)
            val oldPackage = prefs.getString(REPLACEMENT_OLD_PACKAGE, null)
            val oldBase = prefs.getString(REPLACEMENT_OLD_BASE, null)
            val oldDisplay = prefs.getInt(REPLACEMENT_OLD_DISPLAY, -1)
            val oldIdentity = prefs.getString(REPLACEMENT_OLD_IDENTITY, null)
            val oldGeneration = prefs.getLong(REPLACEMENT_OLD_GENERATION, 0L)
            if (prefs.getString(REPLACEMENT_TOKEN, null) == null || oldTask <= 0 || oldPackage.isNullOrBlank()
                || oldBase.isNullOrBlank() || oldDisplay != selectedRearDisplay
                || oldIdentity.isNullOrBlank() || oldGeneration <= 0L) return@synchronized null
            return@synchronized ControlledPhysicalProjectionIdentity(oldTask, oldPackage, oldBase,
                oldDisplay, oldIdentity, oldGeneration)
        }
        ControlledPhysicalProjectionIdentity(task, pkg, base, display, identity, generation)
    }

    /** Validates a delayed controlled Keeper start against the still-committed exact owner record. */
    @JvmStatic fun isCurrentPhysicalProjectionRecord(context: Context, packageTask: String,
            displayId: Int, expectedIdentity: String, generation: Long): Boolean {
        return synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val storedIdentity = prefs.getString(DISPLAY_IDENTITY, null) ?: return@synchronized false
            isCurrentPhysicalProjectionSnapshot(packageTask, displayId, expectedIdentity, generation,
                prefs.getInt(TASK, -1), prefs.getString(PACKAGE, null), prefs.getInt(DISPLAY, -1),
                storedIdentity, prefs.getLong(GENERATION, 0L), prefs.getBoolean(KEEPER_START_PENDING, false))
        }
    }

    internal fun isCurrentPhysicalProjectionSnapshot(packageTask: String, displayId: Int, expectedIdentity: String,
            expectedGeneration: Long, actualTaskId: Int, actualPackage: String?, actualDisplayId: Int,
            actualIdentity: String?, actualGeneration: Long, keeperStartPending: Boolean): Boolean {
        val separator = packageTask.lastIndexOf(':')
        if (separator <= 0 || separator == packageTask.lastIndex) return false
        val taskId = packageTask.substring(separator + 1).toIntOrNull() ?: return false
        val packageName = packageTask.substring(0, separator)
        return keeperStartPending && expectedIdentity.startsWith("$displayId:")
                && matchesPhysicalOwnerSnapshot(taskId, packageName, displayId, expectedIdentity,
                    expectedGeneration, actualTaskId, actualDisplayId, actualIdentity, actualGeneration, actualPackage)
    }

    /** Unlike a delayed start, the old Keeper must already have claimed A before P→V can proceed. */
    @JvmStatic fun isCurrentClaimedPhysicalReplacementOwner(context: Context,
            expected: PhysicalReplacementJournal): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hasClaimedReplacementOldOwner(expected, readPhysicalReplacementJournal(prefs), prefs)
    }

    private fun hasClaimedReplacementOldOwner(expected: PhysicalReplacementJournal,
        live: PhysicalReplacementJournal?, prefs: android.content.SharedPreferences): Boolean =
        PhysicalReplacementLifecycle.claimedOldOwnerAllowsP2v(expected, live,
            physicalKeeperOwnerMatches("${expected.oldPackage}:${expected.oldTask}", expected.displayId,
                expected.identity, expected.oldGeneration, prefs)
                && prefs.getString(BASE_COMPONENT, null) == expected.oldBase,
            prefs.getBoolean(KEEPER_START_PENDING, false), prefs.getBoolean(KEEPER_START_CLAIMED, false),
            prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false))

    /** Returns the exact committed owner; retains a launcher cleanup record after its task leaves. */
    @JvmStatic fun physicalProjectionTask(context: Context, service: ITaskService, selectedRearDisplay: Int): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val taskId = prefs.getInt(TASK, -1)
        if (taskId <= 0) return null
        val packageName = prefs.getString(PACKAGE, null) ?: return null
        val displayId = prefs.getInt(DISPLAY, -1)
        val physicalIdentity = prefs.getString(DISPLAY_IDENTITY, null)
        val observedGeneration = prefs.getLong(GENERATION, 0L)
        val controlledOwner = prefs.getBoolean(CONTROLLED, false)
        val placement = exactPhysicalOwnerPlacement(service, taskId, packageName, displayId, physicalIdentity)
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.UNKNOWN || displayId != selectedRearDisplay) return null
        val identityCheck = physicalOwnerIdentityCheck(displayId, physicalIdentity ?: "",
            placement.selectedId, placement.selectedUnique)
        if (physicalOwnerIdentityMustFailClosed(identityCheck)) return null
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.LEFT && !prefs.getBoolean(LAUNCHER_DISABLED, false)) {
            clearIfCurrent(context, taskId, packageName, displayId, physicalIdentity!!, observedGeneration)
            return null
        }
        val launcherCleanupPending = placement.kind == ExactPhysicalOwnerPlacement.Kind.LEFT
                && prefs.getBoolean(LAUNCHER_DISABLED, false)
        return if (controlledOwner || placement.kind == ExactPhysicalOwnerPlacement.Kind.PRESENT || launcherCleanupPending)
            "$packageName:$taskId" else null
    }

    @JvmStatic fun physicalProjectionLauncherDisabled(context: Context, taskId: Int, displayId: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return taskId > 0 && prefs.getInt(TASK, -1) == taskId && prefs.getInt(DISPLAY, -1) == displayId &&
            prefs.getBoolean(LAUNCHER_DISABLED, false)
    }

    @JvmStatic fun markPhysicalLauncherDisabled(context: Context, taskId: Int, displayId: Int): Boolean =
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (taskId <= 0 || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId)
                return@synchronized false
            prefs.edit().putBoolean(LAUNCHER_DISABLED, true).commit()
        }

    /** Controlled Cast variant: a stale generation must never mark a newer physical owner. */
    @JvmStatic fun markPhysicalLauncherDisabled(context: Context, taskId: Int, displayId: Int,
                                                generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (generation <= 0L || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId
            || prefs.getLong(GENERATION, 0L) != generation) return@synchronized false
        prefs.edit().putBoolean(LAUNCHER_DISABLED, true).commit()
    }

    @JvmStatic fun markPhysicalKeeperStartPending(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)) return@synchronized false
        if (prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false)) return@synchronized false
        prefs.edit().putBoolean(KEEPER_START_PENDING, true).commit()
    }

    @JvmStatic fun physicalKeeperStartPending(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long): Boolean {
        return synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
                    && prefs.getBoolean(KEEPER_START_PENDING, false)
        }
    }

    /** CAS cancellation: succeeds only for this exact owner while its start is still pending. */
    @JvmStatic fun clearPhysicalKeeperStartPending(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
            || !prefs.getBoolean(KEEPER_START_PENDING, false)) return@synchronized false
        val cleared = prefs.edit().putBoolean(KEEPER_START_PENDING, false).putBoolean(KEEPER_START_CLAIMED, false).commit()
        if (cleared) (lock as java.lang.Object).notifyAll()
        cleared
    }

    /** Atomically turns a delayed controlled start into a live in-memory Keeper owner before close can clear its lease. */
    @JvmStatic fun claimPhysicalKeeperStart(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long, claimOwner: Runnable): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
            || !keeperStartClaimAllowed(prefs.getBoolean(KEEPER_START_PENDING, false),
                prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false))) return@synchronized false
        // Persist the single-winner transition before touching in-memory service ownership.
        if (!prefs.edit().putBoolean(KEEPER_START_PENDING, false).putBoolean(KEEPER_START_CLAIMED, true).commit()) return@synchronized false
        try { claimOwner.run() } catch (_: Exception) {
            prefs.edit().putBoolean(KEEPER_START_CLAIMED, false).commit()
            (lock as java.lang.Object).notifyAll()
            return@synchronized false
        }
        (lock as java.lang.Object).notifyAll()
        true
    }

    /** Waits for the exact onStartCommand claim without polling or keeping the service awake. */
    @JvmStatic fun awaitPhysicalKeeperStartClaim(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long, timeoutMillis: Long): Boolean {
        val timeoutNanos = timeoutMillis.coerceIn(0L, 2000L) * 1_000_000L
        val deadline = System.nanoTime() + timeoutNanos
        synchronized(lock) {
            while (true) {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
                    || prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false)) return false
                if (prefs.getBoolean(KEEPER_START_CLAIMED, false)) return true
                if (!prefs.getBoolean(KEEPER_START_PENDING, false)) return false
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val millis = remaining / 1_000_000L
                val nanos = (remaining % 1_000_000L).toInt()
                try { (lock as java.lang.Object).wait(millis, nanos) }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
    }

    /** Close wins only by cancelling the exact pending start; a claimed Keeper must be stopped separately. */
    @JvmStatic fun beginControlledPhysicalProjectionClose(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long): Int = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)) return@synchronized -1
        if (prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false))
            return@synchronized if (prefs.getBoolean(KEEPER_START_CLAIMED, false)) 1 else 0
        val pending = prefs.getBoolean(KEEPER_START_PENDING, false)
        val claimed = prefs.getBoolean(KEEPER_START_CLAIMED, false)
        val edit = prefs.edit().putBoolean(CONTROLLED_OWNER_CLOSING, true)
        if (pending) edit.putBoolean(KEEPER_START_PENDING, false)
        if (!edit.commit()) return@synchronized -1
        (lock as java.lang.Object).notifyAll()
        // 0 means cancellation won (or no Keeper exists); 1 means an already-claimed Keeper must be stopped.
        keeperCloseOutcome(pending, claimed)
    }

    internal fun keeperStartClaimAllowed(pending: Boolean, closing: Boolean): Boolean = pending && !closing

    /** 0 = cancellation/no Keeper, 1 = exact Keeper stop required. */
    internal fun keeperCloseOutcome(pending: Boolean, claimed: Boolean): Int =
        if (!pending && claimed) 1 else 0

    internal fun canCommitPhysicalReplacementSnapshot(phase: String?, currentTask: Int,
            currentGeneration: Long, controlled: Boolean, oldTask: Int, oldGeneration: Long): Boolean =
        phase == "A_RETURNED" && controlled && currentTask == oldTask && currentGeneration == oldGeneration

    @JvmStatic fun physicalKeeperStartClaimed(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
                && prefs.getBoolean(KEEPER_START_CLAIMED, false)
    }

    /** A closed lease cannot be claimed later; clear a stale claimed bit only after exact service lookup missed. */
    @JvmStatic fun clearClaimedKeeperForClosedOwner(context: Context, packageTask: String, displayId: Int,
            identity: String, generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
            || !prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false)
            || !prefs.getBoolean(KEEPER_START_CLAIMED, false)) return@synchronized false
        prefs.edit().putBoolean(KEEPER_START_CLAIMED, false).commit()
    }

    /**
     * Reconcile only the exact stale Keeper claim left when natural onDestroy wins an explicit
     * close race. The old owner remains closing until the caller's normal exact owner CAS.
     */
    @JvmStatic fun reconcileNaturallyStoppedKeeperForClosedOwner(context: Context, packageTask: String,
            expectedBaseComponent: String, displayId: Int, identity: String, generation: Long,
            noLiveKeeper: java.util.function.BooleanSupplier): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val exactOwner = expectedBaseComponent.isNotBlank()
                && physicalKeeperOwnerMatches(packageTask, displayId, identity, generation, prefs)
                && prefs.getString(BASE_COMPONENT, null) == expectedBaseComponent
        if (!mayClearClaimedKeeperAfterClose(
                exactOwner,
                prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false),
                prefs.getBoolean(KEEPER_START_CLAIMED, false),
                prefs.getBoolean(KEEPER_START_PENDING, false),
                prefs.getString(REPLACEMENT_TOKEN, null) == null,
                noLiveKeeper.getAsBoolean())) return@synchronized false
        prefs.edit().putBoolean(KEEPER_START_CLAIMED, false).commit()
    }

    internal fun mayClearClaimedKeeperAfterClose(exactOwner: Boolean, closing: Boolean, claimed: Boolean,
            pending: Boolean, replacementJournalAbsent: Boolean, keeperAbsent: Boolean): Boolean =
        exactOwner && closing && claimed && !pending && replacementJournalAbsent && keeperAbsent

    private fun physicalKeeperOwnerMatches(packageTask: String, displayId: Int, identity: String,
            generation: Long, prefs: android.content.SharedPreferences): Boolean {
        val separator = packageTask.lastIndexOf(':')
        if (separator <= 0 || separator == packageTask.lastIndex) return false
        val taskId = packageTask.substring(separator + 1).toIntOrNull() ?: return false
        val packageName = packageTask.substring(0, separator)
        return generation > 0L && prefs.getInt(TASK, -1) == taskId
                && prefs.getString(PACKAGE, null) == packageName && prefs.getInt(DISPLAY, -1) == displayId
                && prefs.getString(DISPLAY_IDENTITY, null) == identity
                && prefs.getLong(GENERATION, 0L) == generation && prefs.getBoolean(CONTROLLED, false)
                && prefs.getString(BASE_COMPONENT, null)?.isNotBlank() == true
    }

    @JvmStatic fun clearPhysicalProjection(context: Context, taskId: Int, displayId: Int, generation: Long): Boolean =
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (generation <= 0L || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId ||
                prefs.getLong(GENERATION, 0L) != generation) return@synchronized false
            prefs.edit().remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
                .remove(DISPLAY).remove(DISPLAY_IDENTITY)
                .remove(LAUNCHER_DISABLED).remove(KEEPER_START_PENDING).remove(KEEPER_START_CLAIMED)
                .remove(CONTROLLED_OWNER_CLOSING).remove(GENERATION).apply()
            true
        }

    /** Exact controlled-cast release also compares the approved physical identity. */
    @JvmStatic fun clearPhysicalProjection(context: Context, taskId: Int, packageName: String, displayId: Int,
                                           expectedIdentity: String, generation: Long): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(CONTROLLED, false) && !prefs.getBoolean(CONTROLLED_OWNER_CLOSING, false))
            return@synchronized false
        if (!canClearPhysicalProjectionIfOwnerMatches(taskId, packageName, displayId, expectedIdentity, generation,
                prefs.getInt(TASK, -1), prefs.getInt(DISPLAY, -1), prefs.getString(DISPLAY_IDENTITY, null),
                prefs.getLong(GENERATION, 0L), prefs.getString(PACKAGE, null))) return@synchronized false
        prefs.edit().remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
            .remove(DISPLAY).remove(DISPLAY_IDENTITY)
            .remove(LAUNCHER_DISABLED).remove(KEEPER_START_PENDING).remove(KEEPER_START_CLAIMED)
            .remove(CONTROLLED_OWNER_CLOSING).remove(GENERATION).commit()
    }

    /** Atomically retire an exact controlled owner already at MAIN, without opening a move/restore path. */
    @JvmStatic @kotlin.jvm.JvmName("clearRetainedPhysicalOwnerAtHome")
    internal fun clearRetainedPhysicalOwnerAtHome(context: Context, taskId: Int, packageName: String,
        baseComponent: String, displayId: Int, expectedIdentity: String, generation: Long,
        noLiveKeeper: java.util.function.BooleanSupplier): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!top.zainzh.rearscreen.BuildConfig.DEBUG
            || !top.zainzh.rearscreen.BuildConfig.APPLICATION_ID.endsWith(".debug")
            || taskId <= 0 || packageName.isBlank() || baseComponent.isBlank() || displayId <= 0
            || expectedIdentity.isBlank() || generation <= 0L || !noLiveKeeper.getAsBoolean()
            || prefs.getString(REPLACEMENT_TOKEN, null) != null
            || prefs.getInt(TASK, -1) != taskId || prefs.getString(PACKAGE, null) != packageName
            || prefs.getString(BASE_COMPONENT, null) != baseComponent || prefs.getInt(DISPLAY, -1) != displayId
            || prefs.getString(DISPLAY_IDENTITY, null) != expectedIdentity
            || prefs.getLong(GENERATION, 0L) != generation || !prefs.getBoolean(CONTROLLED, false)
            || prefs.getBoolean(KEEPER_START_PENDING, false)) return@synchronized false
        // A claimed bit may be stale after process death. The caller has already proved there is
        // no in-memory Keeper; clear it in the same commit as the exact owner, never separately.
        val cleared = prefs.edit().remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
            .remove(DISPLAY).remove(DISPLAY_IDENTITY).remove(LAUNCHER_DISABLED)
            .remove(KEEPER_START_PENDING).remove(KEEPER_START_CLAIMED).remove(CONTROLLED_OWNER_CLOSING)
            .remove(GENERATION).commit()
        if (cleared) ownershipEpoch.staleRecordPruned()
        cleared
    }

    /** Fail closed for a known owner if the privileged identity query itself cannot complete. */
    @JvmStatic fun hasPhysicalProjection(context: Context, service: ITaskService, selectedRearDisplay: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(REPLACEMENT_TOKEN, null) != null) return true
        val taskId = prefs.getInt(TASK, -1)
        if (taskId <= 0) return false
        val packageName = prefs.getString(PACKAGE, null) ?: return true
        val displayId = prefs.getInt(DISPLAY, -1)
        val physicalIdentity = prefs.getString(DISPLAY_IDENTITY, null)
        val observedGeneration = prefs.getLong(GENERATION, 0L)
        if (prefs.getBoolean(CONTROLLED, false)) return true
        val placement = exactPhysicalOwnerPlacement(service, taskId, packageName, displayId, physicalIdentity)
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.UNKNOWN || displayId != selectedRearDisplay) return true
        val identityCheck = physicalOwnerIdentityCheck(displayId, physicalIdentity ?: "",
            placement.selectedId, placement.selectedUnique)
        if (physicalOwnerIdentityMustFailClosed(identityCheck)) return true
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.LEFT && !prefs.getBoolean(LAUNCHER_DISABLED, false))
            clearIfCurrent(context, taskId, packageName, displayId, physicalIdentity!!, observedGeneration)
        return placement.kind == ExactPhysicalOwnerPlacement.Kind.PRESENT
    }

    /** Read-only, privacy-safe mirror of the physical-owner branch used by Cast admission. */
    @JvmStatic fun diagnosticPhysicalProjectionBranch(context: Context, service: ITaskService,
                                                       selectedRearDisplay: Int): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val taskId = prefs.getInt(TASK, -1)
        if (taskId <= 0) return "none"
        val packageName = prefs.getString(PACKAGE, null) ?: return "known_owner_record_incomplete_fail_closed_branch"
        val displayId = prefs.getInt(DISPLAY, -1)
        val physicalIdentity = prefs.getString(DISPLAY_IDENTITY, null)
        if (physicalIdentity.isNullOrBlank()) return "known_owner_identity_unknown_fail_closed_branch"
        val placement = exactPhysicalOwnerPlacement(service, taskId, packageName, displayId, physicalIdentity)
        if (placement.kind == ExactPhysicalOwnerPlacement.Kind.UNKNOWN) return "authoritative_identity_unknown_fail_closed_branch"
        when (physicalOwnerIdentityCheck(displayId, physicalIdentity, placement.selectedId, placement.selectedUnique)) {
            PhysicalOwnerIdentityCheck.UNKNOWN -> return "authoritative_identity_unknown_fail_closed_branch"
            PhysicalOwnerIdentityCheck.MISMATCH -> return "authoritative_identity_mismatch_fail_closed_branch"
            PhysicalOwnerIdentityCheck.MATCH -> Unit
        }
        if (displayId != selectedRearDisplay) return "authoritative_identity_mismatch_fail_closed_branch"
        return if (placement.kind == ExactPhysicalOwnerPlacement.Kind.PRESENT) "active_owner_branch" else "inactive_record"
    }

    internal data class ExactPhysicalOwnerPlacement(val kind: Kind, val selectedId: Int,
                                                     val selectedUnique: String?) {
        internal enum class Kind { PRESENT, LEFT, UNKNOWN }
        companion object {
            val UNKNOWN = ExactPhysicalOwnerPlacement(Kind.UNKNOWN, -1, null)
            val LEFT = ExactPhysicalOwnerPlacement(Kind.LEFT, -1, null)
            val PRESENT = ExactPhysicalOwnerPlacement(Kind.PRESENT, -1, null)
        }
    }

    /** One authoritative all-display task snapshot binds exact owner placement and selected panel identity. */
    private fun exactPhysicalOwnerPlacement(service: ITaskService, taskId: Int, packageName: String,
                                             displayId: Int, physicalIdentity: String?): ExactPhysicalOwnerPlacement {
        if (physicalIdentity.isNullOrBlank()) return ExactPhysicalOwnerPlacement.UNKNOWN
        return try {
            parseExactPhysicalOwnerPlacement(service.queryExactTaskPlacement(taskId), taskId,
                packageName, displayId, physicalIdentity)
        } catch (_: Exception) { ExactPhysicalOwnerPlacement.UNKNOWN }
    }

    internal fun parseExactPhysicalOwnerPlacement(encoded: String, taskId: Int, packageName: String,
                                                    displayId: Int, physicalIdentity: String): ExactPhysicalOwnerPlacement {
        return try {
            val row = JSONObject(encoded)
            val selectedId = row.optInt("selectedId", -1)
            val unique = row.optString("selectedUnique", "").takeIf { it.isNotBlank() }
            if (selectedId <= 0 || unique == null || row.optInt("task", taskId) != taskId)
                return ExactPhysicalOwnerPlacement.UNKNOWN
            if ("$selectedId:$unique" != physicalIdentity || selectedId != displayId)
                return ExactPhysicalOwnerPlacement(ExactPhysicalOwnerPlacement.Kind.UNKNOWN, selectedId, unique)
            when (row.optString("status")) {
                "present" -> {
                    val exact = row.optInt("display", -1) == displayId && row.optString("package") == packageName
                    if (exact) ExactPhysicalOwnerPlacement(ExactPhysicalOwnerPlacement.Kind.PRESENT, selectedId, unique)
                    else ExactPhysicalOwnerPlacement(ExactPhysicalOwnerPlacement.Kind.LEFT, selectedId, unique)
                }
                else -> ExactPhysicalOwnerPlacement.UNKNOWN
            }
        } catch (_: Exception) { ExactPhysicalOwnerPlacement.UNKNOWN }
    }

    private fun clearIfCurrent(context: Context, observedTaskId: Int, observedPackage: String, observedDisplayId: Int,
                               observedIdentity: String, observedGeneration: Long): Boolean {
        return synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(CONTROLLED, false)) return@synchronized false
            if (!matchesPhysicalOwnerSnapshot(observedTaskId, observedPackage, observedDisplayId, observedIdentity,
                    observedGeneration, prefs.getInt(TASK, -1), prefs.getInt(DISPLAY, -1),
                    prefs.getString(DISPLAY_IDENTITY, null), prefs.getLong(GENERATION, 0L),
                    prefs.getString(PACKAGE, null))) return@synchronized false
            prefs.edit().remove(TASK).remove(PACKAGE).remove(BASE_COMPONENT).remove(CONTROLLED)
                .remove(DISPLAY).remove(DISPLAY_IDENTITY)
                .remove(LAUNCHER_DISABLED).remove(KEEPER_START_PENDING).remove(GENERATION).apply()
            top.zainzh.rearscreen.RearScreenKeeperService
                .clearControlledPhysicalRecoveryNotification(context, observedGeneration)
            ownershipEpoch.staleRecordPruned()
            true
        }
    }

    internal enum class PhysicalOwnerIdentityCheck { MATCH, MISMATCH, UNKNOWN }

    internal fun physicalOwnerIdentityCheck(storedDisplayId: Int, storedIdentity: String,
                                             selectedDisplayId: Int, selectedUnique: String?): PhysicalOwnerIdentityCheck {
        if (storedDisplayId <= 0 || storedIdentity.isBlank() || selectedDisplayId <= 0 || selectedUnique.isNullOrBlank())
            return PhysicalOwnerIdentityCheck.UNKNOWN
        if (storedDisplayId != selectedDisplayId || storedIdentity != "$selectedDisplayId:$selectedUnique")
            return PhysicalOwnerIdentityCheck.MISMATCH
        return PhysicalOwnerIdentityCheck.MATCH
    }

    internal fun canClaimPhysicalProjectionIfEmpty(existingTaskId: Int, displayId: Int, approvedIdentity: String,
                                                    authoritativeSelectedId: Int,
                                                    authoritativeSelectedUnique: String?): Boolean =
        existingTaskId <= 0 && physicalOwnerIdentityCheck(displayId, approvedIdentity,
            authoritativeSelectedId, authoritativeSelectedUnique) == PhysicalOwnerIdentityCheck.MATCH

    internal fun physicalOwnerIdentityMustFailClosed(check: PhysicalOwnerIdentityCheck): Boolean =
        check != PhysicalOwnerIdentityCheck.MATCH

    internal fun matchesPhysicalOwnerSnapshot(expectedTaskId: Int, expectedPackage: String, expectedDisplayId: Int,
                                                expectedIdentity: String, expectedGeneration: Long,
                                                actualTaskId: Int, actualDisplayId: Int,
                                                actualIdentity: String?, actualGeneration: Long,
                                                actualPackage: String?): Boolean =
        expectedTaskId > 0 && expectedPackage.isNotBlank() && expectedDisplayId > 0 && expectedIdentity.isNotBlank() &&
            expectedTaskId == actualTaskId && expectedPackage == actualPackage && expectedDisplayId == actualDisplayId &&
            expectedIdentity == actualIdentity && expectedGeneration == actualGeneration

    internal fun canClearPhysicalProjectionIfOwnerMatches(expectedTaskId: Int, expectedPackage: String,
                                                           expectedDisplayId: Int,
                                                           expectedIdentity: String, expectedGeneration: Long,
                                                           actualTaskId: Int, actualDisplayId: Int,
                                                           actualIdentity: String?, actualGeneration: Long,
                                                           actualPackage: String?): Boolean =
        expectedGeneration > 0L && matchesPhysicalOwnerSnapshot(expectedTaskId, expectedPackage, expectedDisplayId,
            expectedIdentity, expectedGeneration, actualTaskId, actualDisplayId, actualIdentity, actualGeneration,
            actualPackage)

    /** Pure identity predicate used to exercise task reuse/move-away behavior without an OEM display. */
    internal fun taskStillOwnsProjection(
        expectedTaskId: Int,
        expectedPackage: String,
        expectedDisplay: Int,
        expectedPhysicalIdentity: String,
        actualTaskId: Int,
        actualPackage: String?,
        actualDisplay: Int,
        actualPhysicalIdentity: String?
    ): Boolean = expectedTaskId > 0 && expectedPackage.isNotBlank() && expectedDisplay > 0 &&
        expectedTaskId == actualTaskId && expectedPackage == actualPackage && expectedDisplay == actualDisplay &&
        expectedPhysicalIdentity == actualPhysicalIdentity
}

/** Epoch tracks projection commits, not lazy removal of a cached owner that has already left. */
internal class LegacyProjectionOwnershipEpoch {
    private val sequence = java.util.concurrent.atomic.AtomicLong(0L)

    fun current(): Long = sequence.get()
    fun projectionCommitted(): Long = sequence.incrementAndGet()

    /** Stale metadata cleanup is not a new projection claim and must not invalidate Scene admission. */
    fun staleRecordPruned(): Long = sequence.get()
}
