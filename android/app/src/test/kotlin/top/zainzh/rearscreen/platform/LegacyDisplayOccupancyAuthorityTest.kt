package top.zainzh.rearscreen.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDisplayOccupancyAuthorityTest {
    @Test fun readerLifecycleReceiptsAreExactAndUnknownNeverLooksReleased() {
        assertTrue(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "NOT_REQUESTED", "", 0, "REQUESTED", "", 0))
        assertTrue(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "REQUESTED", "", 0, "UNKNOWN", "", 0))
        assertFalse(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "UNKNOWN", "", 0, "NO_READER", "", 0))
        assertTrue(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "REQUESTED", "", 0, "ALLOCATED", "reader-x", 8))
        assertTrue(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "ALLOCATED", "reader-x", 8, "READY", "reader-x", 8))
        assertTrue(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "READY", "reader-x", 8, "RELEASED", "reader-x", 8))
        assertFalse(LegacyDisplayOccupancy.physicalReaderTransitionAllowed(
            "READY", "reader-x", 8, "RELEASED", "reader-old", 7))
    }

    @Test fun replacementLifecycleSequenceAllowsRollbackOnlyBeforeAcceptance() {
        assertTrue(PhysicalReplacementLifecycle.readerTransitionAllowed(
            "NOT_REQUESTED", "", 0, "SESSION_CREATED", "", 0))
        assertTrue(PhysicalReplacementLifecycle.readerTransitionAllowed(
            "NOT_REQUESTED", "", 0, "NO_READER", "", 0))
        assertTrue(PhysicalReplacementLifecycle.mayRollbackToA("PREPARING", true, false,
            "NO_READER", true, true, "REAR"))
        assertTrue(PhysicalReplacementLifecycle.phaseTransitionAllowed("PREPARING", "B_READY", true, "READY"))
        assertTrue(PhysicalReplacementLifecycle.phaseTransitionAllowed("B_READY", "A_RETURNING", true, "READY"))
        assertTrue(PhysicalReplacementLifecycle.phaseTransitionAllowed("A_RETURNING", "A_RETURNED", true, "READY"))
        assertTrue(PhysicalReplacementLifecycle.phaseTransitionAllowed("A_RETURNED", "OWNER_VIRTUAL", true, "READY"))
        assertTrue(PhysicalReplacementLifecycle.phaseTransitionAllowed("OWNER_VIRTUAL", "ACCEPTED_VIRTUAL", true, "READY"))
        assertFalse(PhysicalReplacementLifecycle.phaseTransitionAllowed("PREPARING", "A_RETURNING", true, "READY"))
        assertFalse(PhysicalReplacementLifecycle.readerEventAllowed("OWNER_VIRTUAL", "READY"))
        assertTrue(PhysicalReplacementLifecycle.readerEventAllowed("OWNER_VIRTUAL", "RELEASED"))
        assertTrue(PhysicalReplacementLifecycle.mayRollbackToA("OWNER_VIRTUAL", true, false,
            "RELEASED", true, false, "MAIN"))
        assertTrue(PhysicalReplacementLifecycle.mayRollbackToA("OWNER_A", true, false,
            "RELEASED", true, true, "REAR"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA("ACCEPTED_VIRTUAL", true, true,
            "RELEASED", true, false, "MAIN"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA("OWNER_VIRTUAL", true, false,
            "RELEASED", true, false, "REAR"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA("OWNER_VIRTUAL", true, false,
            "READY", false, false, "MAIN"))
    }

    @Test fun explicitUnacceptedPhysicalCloseRequiresExactMainAndStoppedResources() {
        fun allowed(phase: String = "PREPARING", decision: String = "NONE", isVirtual: Boolean = false,
            sessionUnbound: Boolean = true, ownerA: Boolean = true, closeCas: Boolean = true,
            keeperAbsent: Boolean = true, resourcesStopped: Boolean = true, a: String = "MAIN",
            b: String = "MAIN", rearNoOwner: Boolean = true) =
            PhysicalReplacementLifecycle.unacceptedPhysicalCloseAllowed(phase, decision, isVirtual,
                sessionUnbound, ownerA, closeCas, keeperAbsent, resourcesStopped, a, b, rearNoOwner)

        assertTrue(allowed())
        assertFalse(allowed(phase = "ACCEPTED_B"))
        assertFalse(allowed(decision = "ACCEPT"))
        assertFalse(allowed(isVirtual = true))
        assertFalse(allowed(sessionUnbound = false))
        assertFalse(allowed(ownerA = false))
        assertFalse(allowed(closeCas = false))
        assertFalse(allowed(keeperAbsent = false))
        assertFalse(allowed(resourcesStopped = false))
        assertFalse(allowed(a = "UNKNOWN"))
        assertFalse(allowed(b = "UNKNOWN"))
        assertFalse(allowed(rearNoOwner = false))
    }

    @Test fun earlyNoAllocationRollbackRequiresStoppedBaselineAndExactRearA() {
        assertTrue(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "NOT_REQUESTED", true, true, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "REQUESTED", true, true, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "session-created", "NOT_REQUESTED", true, true, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "NOT_REQUESTED", false, true, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "NOT_REQUESTED", true, false, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "NOT_REQUESTED", true, true, "MAIN", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "NOT_REQUESTED", true, true, "REAR", false))
    }
    @Test fun typedExactPlacementDistinguishesPresentLeftAndUnknown() {
        val present = LegacyDisplayOccupancy.parseExactPhysicalOwnerPlacement(
            """{"status":"present","task":91,"display":4,"package":"reader.app","base":"reader.app/.Main","selectedId":4,"selectedUnique":"trusted-panel"}""",
            91, "reader.app", 4, "4:trusted-panel")
        assertEquals(LegacyDisplayOccupancy.ExactPhysicalOwnerPlacement.Kind.PRESENT, present.kind)
        val unprovenAbsence = LegacyDisplayOccupancy.parseExactPhysicalOwnerPlacement(
            """{"status":"absent","task":91,"display":-1,"selectedId":4,"selectedUnique":"trusted-panel"}""",
            91, "reader.app", 4, "4:trusted-panel")
        assertEquals(LegacyDisplayOccupancy.ExactPhysicalOwnerPlacement.Kind.UNKNOWN, unprovenAbsence.kind)
        val failed = LegacyDisplayOccupancy.parseExactPhysicalOwnerPlacement(
            """{"status":"unknown","task":91,"selectedId":4,"selectedUnique":"trusted-panel"}""",
            91, "reader.app", 4, "4:trusted-panel")
        assertEquals(LegacyDisplayOccupancy.ExactPhysicalOwnerPlacement.Kind.UNKNOWN, failed.kind)
        val drift = LegacyDisplayOccupancy.parseExactPhysicalOwnerPlacement(
            """{"status":"absent","task":91,"selectedId":4,"selectedUnique":"replacement-panel"}""",
            91, "reader.app", 4, "4:trusted-panel")
        assertEquals(LegacyDisplayOccupancy.ExactPhysicalOwnerPlacement.Kind.UNKNOWN, drift.kind)
    }

    @Test fun staleLegacyPruneCasCannotEraseConcurrentRestoreOrReplacement() {
        fun canClear(launcherDisabled: Boolean = false, replacement: Boolean = false,
            recovery: Boolean = false, controlled: Boolean = false, task: Int = 91,
            generation: Long = 12) = LegacyDisplayOccupancy.canClearStaleLegacyOwnerSnapshot(
                91, "reader.app", 4, "4:trusted-panel", 12,
                task, "reader.app", 4, "4:trusted-panel", generation,
                controlled, launcherDisabled, replacement, recovery)

        assertTrue(canClear())
        assertFalse(canClear(launcherDisabled = true))
        assertFalse(canClear(replacement = true))
        assertFalse(canClear(recovery = true))
        assertFalse(canClear(controlled = true))
        assertFalse(canClear(task = 92))
        assertFalse(canClear(generation = 13))
    }

    @Test fun cancellingPendingStartRejectsLateClaimButClaimedKeeperRequiresExactStop() {
        val pending = LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:trusted-panel", 12, true)
        assertTrue(pending)
        val cancelledBeforeDelivery = LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:trusted-panel", 12, false)
        assertFalse(cancelledBeforeDelivery)
        val lateClaim = LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:trusted-panel", 12, false)
        assertFalse(lateClaim)
        // cancel wins the shared lock: persisted pending=false makes delayed claim ineligible.
        assertFalse(LegacyDisplayOccupancy.keeperStartClaimAllowed(pending = false, closing = true))
        assertEquals(0, LegacyDisplayOccupancy.keeperCloseOutcome(pending = true, claimed = false))
        // claim wins first: close must require exact service stop; only success may precede owner CAS.
        assertEquals(1, LegacyDisplayOccupancy.keeperCloseOutcome(pending = false, claimed = true))
    }

    @Test fun delayedKeeperStartStillRequiresExactOwnerTaskIdentity() {
        assertTrue(LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:trusted-panel", 12, true))
        assertFalse(LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:trusted-panel", 13, true))
        assertFalse(LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 92, "reader.app", 4, "4:trusted-panel", 12, true))
        assertFalse(LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:replacement-panel", 12, true))
        assertFalse(LegacyDisplayOccupancy.isCurrentPhysicalProjectionSnapshot(
            "reader.app:91", 4, "4:trusted-panel", 12, 91, "reader.app", 4, "4:trusted-panel", 12, false))
    }

    @Test fun localIdentityUnavailableDoesNotPreventFreshAuthorityMatch() {
        // The admission value is the single privileged selected ID + unique identity; no local
        // RearDisplay.identity() value participates in the claim predicate.
        assertEquals(LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.MATCH,
            LegacyDisplayOccupancy.physicalOwnerIdentityCheck(4, "4:trusted-panel", 4, "trusted-panel"))
        assertTrue(LegacyDisplayOccupancy.canClaimPhysicalProjectionIfEmpty(
            -1, 4, "4:trusted-panel", 4, "trusted-panel"))
    }

    @Test fun authorityDriftOrUnknownFailsClosedAndCannotClaim() {
        assertEquals(LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.MISMATCH,
            LegacyDisplayOccupancy.physicalOwnerIdentityCheck(4, "4:trusted-panel", 4, "replacement-panel"))
        assertEquals(LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.MISMATCH,
            LegacyDisplayOccupancy.physicalOwnerIdentityCheck(4, "4:trusted-panel", 5, "trusted-panel"))
        assertEquals(LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.UNKNOWN,
            LegacyDisplayOccupancy.physicalOwnerIdentityCheck(4, "4:trusted-panel", -1, ""))
        assertEquals(LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.UNKNOWN,
            LegacyDisplayOccupancy.physicalOwnerIdentityCheck(4, "4:trusted-panel", 4, null))
        assertTrue(LegacyDisplayOccupancy.physicalOwnerIdentityMustFailClosed(
            LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.MISMATCH))
        assertTrue(LegacyDisplayOccupancy.physicalOwnerIdentityMustFailClosed(
            LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.UNKNOWN))
        assertFalse(LegacyDisplayOccupancy.physicalOwnerIdentityMustFailClosed(
            LegacyDisplayOccupancy.PhysicalOwnerIdentityCheck.MATCH))
        assertFalse(LegacyDisplayOccupancy.canClaimPhysicalProjectionIfEmpty(
            -1, 4, "4:trusted-panel", 4, "replacement-panel"))
        assertFalse(LegacyDisplayOccupancy.canClaimPhysicalProjectionIfEmpty(
            -1, 4, "4:trusted-panel", -1, ""))
    }

    @Test fun existingOwnerIsNeverOverwrittenEvenWithMatchingAuthority() {
        assertFalse(LegacyDisplayOccupancy.canClaimPhysicalProjectionIfEmpty(
            91, 4, "4:trusted-panel", 4, "trusted-panel"))
    }

    @Test fun replacementCannotCommitBeforeAExactReturnOrAfterOwnerDrift() {
        assertFalse(LegacyDisplayOccupancy.canCommitPhysicalReplacementSnapshot(
            "B_READY", 91, 12L, true, 91, 12L))
        assertFalse(LegacyDisplayOccupancy.canCommitPhysicalReplacementSnapshot(
            "A_RETURNED", 92, 12L, true, 91, 12L))
        assertFalse(LegacyDisplayOccupancy.canCommitPhysicalReplacementSnapshot(
            "A_RETURNED", 91, 13L, true, 91, 12L))
        assertFalse(LegacyDisplayOccupancy.canCommitPhysicalReplacementSnapshot(
            "A_RETURNED", 91, 12L, false, 91, 12L))
        assertTrue(LegacyDisplayOccupancy.canCommitPhysicalReplacementSnapshot(
            "A_RETURNED", 91, 12L, true, 91, 12L))
    }

    @Test fun returnReleaseRequiresExactTaskDisplayIdentityAndGeneration() {
        assertTrue(LegacyDisplayOccupancy.canClearPhysicalProjectionIfOwnerMatches(
            91, "reader.app", 4, "4:trusted-panel", 12, 91, 4, "4:trusted-panel", 12, "reader.app"))
        assertFalse(LegacyDisplayOccupancy.canClearPhysicalProjectionIfOwnerMatches(
            91, "reader.app", 4, "4:trusted-panel", 12, 92, 4, "4:trusted-panel", 12, "reader.app"))
        assertFalse(LegacyDisplayOccupancy.canClearPhysicalProjectionIfOwnerMatches(
            91, "reader.app", 4, "4:trusted-panel", 12, 91, 4, "4:replacement-panel", 12, "reader.app"))
        assertFalse(LegacyDisplayOccupancy.canClearPhysicalProjectionIfOwnerMatches(
            91, "reader.app", 4, "4:trusted-panel", 12, 91, 4, "4:trusted-panel", 13, "reader.app"))
        assertFalse(LegacyDisplayOccupancy.canClearPhysicalProjectionIfOwnerMatches(
            91, "reader.app", 4, "4:trusted-panel", 12, 91, 4, "4:trusted-panel", 12, "other.app"))
    }
}
