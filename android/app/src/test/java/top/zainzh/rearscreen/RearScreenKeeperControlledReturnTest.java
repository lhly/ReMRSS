package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RearScreenKeeperControlledReturnTest {
    @Test public void controlledNotificationRequiresBothExactOwnerFields() {
        assertTrue(RearScreenKeeperService.matchesControlledReturnRequest(true, true, 91, 12L, 91, 12L));
        assertFalse(RearScreenKeeperService.matchesControlledReturnRequest(false, false, -1, 0L, 91, 12L));
        assertFalse(RearScreenKeeperService.matchesControlledReturnRequest(true, false, 91, 0L, 91, 12L));
        assertFalse(RearScreenKeeperService.matchesControlledReturnRequest(true, true, 92, 12L, 91, 12L));
        assertFalse(RearScreenKeeperService.matchesControlledReturnRequest(true, true, 91, 13L, 91, 12L));
    }

    @Test public void delayedProximityReturnCannotAdoptANewStartOrOwnerGeneration() {
        assertTrue(RearScreenKeeperService.delayedProximityOwnerStillCurrent(
                7, 7, true, 91, 12L, true, 91, 12L));
        assertFalse(RearScreenKeeperService.delayedProximityOwnerStillCurrent(
                7, 8, true, 91, 12L, true, 91, 12L));
        assertFalse(RearScreenKeeperService.delayedProximityOwnerStillCurrent(
                7, 7, true, 91, 12L, true, 91, 13L));
        assertFalse(RearScreenKeeperService.delayedProximityOwnerStillCurrent(
                7, 7, false, -1, 0L, true, 91, 12L));
        assertTrue("legacy debounce behavior remains unchanged without a controlled owner",
                RearScreenKeeperService.delayedProximityOwnerStillCurrent(
                        7, 7, false, -1, 0L, false, -1, 0L));
        assertTrue("unrelated legacy service starts do not change the existing debounce path",
                RearScreenKeeperService.delayedProximityOwnerStillCurrent(
                        7, 8, false, -1, 0L, false, -1, 0L));
    }
}
