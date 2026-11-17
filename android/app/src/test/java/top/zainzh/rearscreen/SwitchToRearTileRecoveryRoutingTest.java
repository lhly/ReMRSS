package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SwitchToRearTileRecoveryRoutingTest {
    @Test public void noControlledOwnerKeepsExistingTileRoute() {
        assertEquals(SwitchToRearTileService.ClickRoute.LEGACY_SWITCH,
                SwitchToRearTileService.routeForControlledOwner(false));
    }

    @Test public void controlledOwnerUsesOnlyExactReturnRoute() {
        assertEquals(SwitchToRearTileService.ClickRoute.CONTROLLED_RETURN,
                SwitchToRearTileService.routeForControlledOwner(true));
    }

    @Test public void journalWithoutPhysicalOwnerStillUsesStrictRecoveryRoute() {
        assertEquals(SwitchToRearTileService.ClickRoute.CONTROLLED_RETURN,
                SwitchToRearTileService.routeForControlledState(false, true));
        assertEquals(SwitchToRearTileService.ClickRoute.LEGACY_SWITCH,
                SwitchToRearTileService.routeForControlledState(false, false));
    }
}
