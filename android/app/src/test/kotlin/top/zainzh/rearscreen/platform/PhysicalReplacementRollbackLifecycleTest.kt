package top.zainzh.rearscreen.platform

import org.junit.Assert.*
import org.junit.Test

class PhysicalReplacementRollbackLifecycleTest {
    @Test fun exactUnallocatedProofSurvivesOwnerARestartForKeeperRetry() {
        assertTrue(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "PREPARING", "", "NOT_REQUESTED", true, true, "REAR", true))
        assertTrue(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "OWNER_A", "", "NOT_REQUESTED", true, true, "REAR", true))
        assertTrue(PhysicalReplacementLifecycle.mayRollbackToA(
            "OWNER_A", true, false, "NOT_REQUESTED", true, true, "REAR"))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "OWNER_A", "", "REQUESTED", true, true, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.unallocatedRollbackCandidate(
            "OWNER_A", "", "UNKNOWN", true, true, "REAR", true))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA(
            "OWNER_A", true, false, "NOT_REQUESTED", false, true, "REAR"))
    }

    @Test fun noReaderAndReleasedRemainDistinctTerminalReceipts() {
        assertTrue(PhysicalReplacementLifecycle.mayRollbackToA(
            "A_RETURNED", true, false, "NO_READER", true, true, "MAIN"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA(
            "A_RETURNED", true, false, "NO_READER", false, true, "MAIN"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA(
            "OWNER_A", true, false, "RELEASED", false, true, "REAR"))
        assertTrue(PhysicalReplacementLifecycle.mayRollbackToA(
            "OWNER_A", true, false, "NO_READER", true, true, "REAR"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA(
            "OWNER_A", true, false, "NO_READER", false, true, "REAR"))
        assertFalse(PhysicalReplacementLifecycle.mayRollbackToA(
            "OWNER_A", true, false, "UNKNOWN", true, true, "REAR"))
    }

    @Test fun ownerVirtualRollbackUsesAbsentOnlyAfterDurableOwnerClear() {
        assertEquals("ABSENT", PhysicalReplacementLifecycle.replacementOwnerFact(
            "OWNER_VIRTUAL", -1, null, null, -1, null, 0L, false,
            11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L))
        assertEquals("SAME_A", PhysicalReplacementLifecycle.replacementOwnerFact(
            "A_RETURNED", 11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L, true,
            11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L))
        assertEquals("FOREIGN", PhysicalReplacementLifecycle.replacementOwnerFact(
            "OWNER_VIRTUAL", 12, "other.pkg", "other.pkg/.Main", 4, "4:rear", 3L, true,
            11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L))
        assertEquals("UNKNOWN", PhysicalReplacementLifecycle.replacementOwnerFact(
            "OWNER_VIRTUAL", -1, "partial.pkg", null, -1, null, 0L, false,
            11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L))
        assertTrue(PhysicalReplacementLifecycle.p2vOwnerFactAllowed("OWNER_VIRTUAL", "MAIN", "ABSENT"))
        assertFalse(PhysicalReplacementLifecycle.p2vOwnerFactAllowed("OWNER_VIRTUAL", "MAIN", "UNKNOWN"))
        assertFalse(PhysicalReplacementLifecycle.p2vOwnerFactAllowed("A_RETURNED", "MAIN", "ABSENT"))
    }
}
