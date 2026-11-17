package top.zainzh.rearscreen.platform

/** One authority for durable P→V phases and reader-resource facts. */
object PhysicalReplacementLifecycle {
    @JvmStatic fun unacceptedPhysicalCloseAllowed(phase: String, decision: String, isVirtual: Boolean,
        sessionUnbound: Boolean, exactOwnerA: Boolean, closeCasWon: Boolean, keeperAbsent: Boolean,
        resourcesStopped: Boolean, aPlacement: String, bPlacement: String, rearNoOwner: Boolean): Boolean =
        phase == "PREPARING" && decision == "NONE" && !isVirtual && sessionUnbound && exactOwnerA
            && closeCasWon && keeperAbsent && resourcesStopped && aPlacement == "MAIN"
            && bPlacement == "MAIN" && rearNoOwner

    /** Only an explicitly closed rollback that never reached the A move can retire in place. */
    @JvmStatic fun isPreparingVirtualRollback(phase: String, decision: String, newVirtual: Boolean): Boolean =
        newVirtual && phase == "PREPARING" && decision == "ROLLBACK"

    internal fun canCloseUnacceptedReplacement(journal: LegacyDisplayOccupancy.PhysicalReplacementJournal): Boolean =
        journal.phase == "PREPARING" && ((!journal.newVirtual && journal.decision == "NONE"
            && journal.newSessionId.isBlank()) || isPreparingVirtualRollback(journal.phase, journal.decision, journal.newVirtual))

    @JvmStatic fun replacementOwnerFact(phase: String, task: Int, pkg: String?, base: String?, display: Int,
        identity: String?, generation: Long, controlled: Boolean, oldTask: Int, oldPackage: String,
        oldBase: String, oldDisplay: Int, oldIdentity: String, oldGeneration: Long): String {
        if (controlled && task == oldTask && pkg == oldPackage && base == oldBase && display == oldDisplay
            && identity == oldIdentity && generation == oldGeneration && generation > 0L) return "SAME_A"
        val empty = task <= 0 && pkg.isNullOrBlank() && base.isNullOrBlank() && display < 0
            && identity.isNullOrBlank() && generation <= 0L && !controlled
        if (phase == "OWNER_VIRTUAL" && empty) return "ABSENT"
        if (!controlled || task <= 0 || pkg.isNullOrBlank() || base.isNullOrBlank() || display <= 0
            || identity.isNullOrBlank() || generation <= 0L) return "UNKNOWN"
        return "FOREIGN"
    }

    @JvmStatic fun p2vOwnerFactAllowed(phase: String, placement: String, ownerFact: String): Boolean = when {
        phase == "OWNER_VIRTUAL" && placement == "MAIN" -> ownerFact == "ABSENT"
        phase in setOf("PREPARING", "B_READY", "OWNER_A") && placement == "REAR" -> ownerFact == "SAME_A"
        phase in setOf("A_RETURNING", "A_RETURNED") && placement in setOf("REAR", "MAIN") -> ownerFact == "SAME_A"
        else -> false
    }

    internal fun claimedOldOwnerAllowsP2v(expected: LegacyDisplayOccupancy.PhysicalReplacementJournal,
        live: LegacyDisplayOccupancy.PhysicalReplacementJournal?, exactOwner: Boolean,
        pending: Boolean, claimed: Boolean, closing: Boolean): Boolean =
        expected == live && expected.newVirtual && expected.decision == "NONE"
            && (expected.phase == "PREPARING" || expected.phase == "B_READY" || expected.phase == "A_RETURNING")
            && exactOwner && claimed && !pending && !closing

    /** Compare-and-set accepts only the exact live durable snapshot, never a cached/pre-CAS journal. */
    @JvmStatic internal fun p2vPhaseCasAllowed(expected: LegacyDisplayOccupancy.PhysicalReplacementJournal,
        live: LegacyDisplayOccupancy.PhysicalReplacementJournal, from: String, to: String): Boolean {
        if (expected != live || expected.phase != from || expected.decision != "NONE" || !expected.newVirtual
            || expected.newSessionId.isBlank() || expected.newBackendGeneration <= 0L
            || expected.newCoordinatorGeneration <= 0L || expected.newOwnerIdentity.isBlank()
            || expected.oldTask <= 0 || expected.oldPackage.isBlank() || expected.oldBase.isBlank()
            || expected.oldGeneration <= 0L || expected.displayId <= 0 || expected.identity.isBlank()
            || expected.newTask <= 0 || expected.newPackage.isBlank() || expected.newBase.isBlank()
            || expected.newGeneration <= 0L) return false
        if (to in setOf("B_READY", "A_RETURNING")
            && (expected.readerState != "READY" || expected.readerId.isBlank()
                || expected.readerGeneration <= 0L)) return false
        return phaseTransitionAllowed(from, to, true, expected.readerState)
    }

    /** Durable move intent is reserved for one exact physical A_RETURNING snapshot. */
    @JvmStatic internal fun physicalAReturnMoveAuthorizationAllowed(
        expected: LegacyDisplayOccupancy.PhysicalReplacementJournal,
        live: LegacyDisplayOccupancy.PhysicalReplacementJournal, exactOldOwner: Boolean,
        authorizationAlreadyRecorded: Boolean): Boolean =
        expected == live && !expected.newVirtual && expected.phase == "A_RETURNING"
            && expected.decision == "NONE" && !authorizationAlreadyRecorded
            && expected.newSessionId.isBlank() && expected.newBackendGeneration == 0L
            && expected.oldTask > 0 && expected.oldPackage.isNotBlank() && expected.oldBase.isNotBlank()
            && expected.oldGeneration > 0L && expected.displayId > 0 && expected.identity.isNotBlank()
            && expected.newTask > 0 && expected.newPackage.isNotBlank() && expected.newBase.isNotBlank()
            && expected.newGeneration > 0L && exactOldOwner

    /** PENDING is durable intent only; only a post-invocation receipt may authorize rollback from MAIN. */
    @JvmStatic fun physicalAReturnMoveMarkerUsable(newVirtual: Boolean, phase: String, decision: String,
        dispatched: Boolean, pending: Boolean): Boolean = !newVirtual
            && phase in setOf("A_RETURNING", "A_RETURNED", "OWNER_B") && decision == "NONE"
            && dispatched && !pending


    /** Session/backend identity may be first-bound once; a READY callback's receipt is idempotent, never overwritten. */
    @JvmStatic fun physicalReplacementSessionBindingAllowed(currentSession: String, currentGeneration: Long,
        requestedSession: String, requestedGeneration: Long): Boolean {
        if (requestedSession.isBlank() || requestedGeneration <= 0L) return false
        if (currentSession.isBlank() && currentGeneration == 0L) return true
        return currentSession == requestedSession && currentGeneration == requestedGeneration
    }

    @JvmStatic fun acceptedCloseAction(phase: String, decision: String,
        exactSession: Boolean, statusStopped: Boolean, exactTerminal: Boolean): String {
        if (phase != "ACCEPTED_VIRTUAL" || decision != "ACCEPT" || !exactSession) return "REJECT"
        return if (statusStopped && exactTerminal) "FINALIZE" else "STOP_EXACT"
    }

    @JvmStatic fun nextDecision(current: String, requested: String): String? = when {
        requested == "ACCEPT" && current == "NONE" -> "ACCEPT"
        requested == "ROLLBACK" && current in setOf("NONE", "ROLLBACK") -> "ROLLBACK"
        else -> null
    }

    @JvmStatic fun exactRollbackTicket(
        requestedToken: String, savedToken: String, requestedSession: String, savedSession: String,
        requestedBackendGeneration: Long, savedBackendGeneration: Long,
        requestedCoordinatorGeneration: Long, savedCoordinatorGeneration: Long,
        requestedOwner: String, savedOwner: String, requestedTask: Int, savedTask: Int,
        requestedBase: String, savedBase: String, requestedDisplay: Int, savedDisplay: Int,
        requestedRear: String, savedRear: String
    ): Boolean = requestedToken == savedToken && requestedSession == savedSession
        && requestedBackendGeneration == savedBackendGeneration
        && requestedCoordinatorGeneration == savedCoordinatorGeneration
        && requestedOwner == savedOwner && requestedTask == savedTask
        && requestedBase == savedBase && requestedDisplay == savedDisplay
        && requestedRear == savedRear

    @JvmStatic fun unallocatedRollbackCandidate(phase: String, sessionId: String,
        readerState: String, stoppedBaselineExact: Boolean, oldOwnerExact: Boolean,
        oldPlacement: String, singletonOldTask: Boolean): Boolean =
        phase in setOf("PREPARING", "OWNER_A") && sessionId.isBlank() && readerState == "NOT_REQUESTED"
            && stoppedBaselineExact && oldOwnerExact && oldPlacement == "REAR" && singletonOldTask

    @JvmStatic
    fun readerTransitionAllowed(oldState: String, oldReader: String, oldGeneration: Long,
        nextState: String, nextReader: String, nextGeneration: Long): Boolean {
        if (nextState == "SESSION_CREATED")
            return oldState == "NOT_REQUESTED" && oldReader.isEmpty() && oldGeneration == 0L
                && nextReader.isEmpty() && nextGeneration == 0L
        if (oldState == nextState) return oldReader == nextReader && oldGeneration == nextGeneration
        return when (nextState) {
            "REQUESTED" -> oldState == "NOT_REQUESTED" && oldReader.isEmpty() && oldGeneration == 0L
                && nextReader.isEmpty() && nextGeneration == 0L
            "NO_READER" -> oldState in setOf("NOT_REQUESTED", "REQUESTED")
                && oldReader.isEmpty() && oldGeneration == 0L && nextReader.isEmpty() && nextGeneration == 0L
            "UNKNOWN" -> oldState == "REQUESTED" && oldReader.isEmpty() && oldGeneration == 0L
                && nextReader.isEmpty() && nextGeneration == 0L
            "ALLOCATED" -> oldState == "REQUESTED" && oldReader.isEmpty() && oldGeneration == 0L
                && nextReader.isNotBlank() && nextGeneration > 0L
            "READY" -> oldState == "ALLOCATED" && oldReader == nextReader
                && oldGeneration == nextGeneration && nextReader.isNotBlank() && nextGeneration > 0L
            "RELEASED" -> oldState in setOf("ALLOCATED", "READY") && oldReader == nextReader
                && oldGeneration == nextGeneration && nextReader.isNotBlank() && nextGeneration > 0L
            else -> false
        }
    }

    @JvmStatic fun phaseTransitionAllowed(from: String, to: String, isVirtual: Boolean, readerState: String): Boolean =
        when (from to to) {
            "PREPARING" to "B_READY" -> !isVirtual || readerState == "READY"
            "B_READY" to "A_RETURNING" -> !isVirtual || readerState == "READY"
            "A_RETURNING" to "A_RETURNED" -> !isVirtual || readerState == "READY"
            "A_RETURNED" to "OWNER_VIRTUAL" -> isVirtual && readerState == "READY"
            "OWNER_VIRTUAL" to "ACCEPTED_VIRTUAL" -> isVirtual && readerState == "READY"
            "OWNER_B" to "ACCEPTED_B" -> !isVirtual
            else -> false
        }

    @JvmStatic fun readerEventAllowed(phase: String, event: String): Boolean = when (phase) {
        "PREPARING", "B_READY", "A_RETURNING", "A_RETURNED" ->
            event in setOf("SESSION_CREATED", "REQUESTED", "NO_READER", "UNKNOWN", "ALLOCATED", "READY", "RELEASED")
        "OWNER_VIRTUAL" -> event == "RELEASED"
        "ACCEPTED_VIRTUAL" -> event == "RELEASED"
        else -> false
    }

    @JvmStatic fun mayRollbackToA(phase: String, isVirtual: Boolean, accepted: Boolean,
        readerState: String, exactReaderTerminal: Boolean, oldOwnerExact: Boolean,
        oldPlacement: String): Boolean {
        if (accepted || phase.startsWith("ACCEPTED") || oldPlacement !in setOf("MAIN", "REAR"))
            return false
        if (isVirtual && readerState !in setOf("NO_READER", "RELEASED", "NOT_REQUESTED")) return false
        if (isVirtual && readerState in setOf("NO_READER", "RELEASED") && !exactReaderTerminal) return false
        if (isVirtual && readerState == "NOT_REQUESTED"
            && (phase !in setOf("PREPARING", "OWNER_A") || !exactReaderTerminal)) return false
        return when (phase) {
            "PREPARING", "B_READY" -> oldPlacement == "REAR" && oldOwnerExact
            "A_RETURNING", "A_RETURNED" -> oldOwnerExact
            "OWNER_VIRTUAL" -> oldPlacement == "MAIN" && readerState == "RELEASED" && exactReaderTerminal
            "OWNER_A" -> oldPlacement == "REAR" && oldOwnerExact
                && readerState in setOf("NOT_REQUESTED", "NO_READER", "RELEASED") && exactReaderTerminal
            else -> false
        }
    }
}
