package top.zainzh.rearscreen.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDisplayOccupancyClosePolicyTest {
    @Test fun staleClaimRequiresExactClosingOwnerWithoutPendingJournalOrKeeper() {
        assertTrue(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = true, closing = true, claimed = true, pending = false,
            replacementJournalAbsent = true, keeperAbsent = true,
        ))
        assertFalse(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = false, closing = true, claimed = true, pending = false,
            replacementJournalAbsent = true, keeperAbsent = true,
        ))
        assertFalse(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = true, closing = false, claimed = true, pending = false,
            replacementJournalAbsent = true, keeperAbsent = true,
        ))
        assertFalse(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = true, closing = true, claimed = false, pending = false,
            replacementJournalAbsent = true, keeperAbsent = true,
        ))
        assertFalse(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = true, closing = true, claimed = true, pending = true,
            replacementJournalAbsent = true, keeperAbsent = true,
        ))
        assertFalse(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = true, closing = true, claimed = true, pending = false,
            replacementJournalAbsent = false, keeperAbsent = true,
        ))
        assertFalse(LegacyDisplayOccupancy.mayClearClaimedKeeperAfterClose(
            exactOwner = true, closing = true, claimed = true, pending = false,
            replacementJournalAbsent = true, keeperAbsent = false,
        ))
    }
}
