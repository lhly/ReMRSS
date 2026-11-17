package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VirtualRearHostKeeperDeferralTest {
    @Test public void newHostDefersSharedKeeperOnlyForControlledReplacement() {
        assertTrue(VirtualRearHostActivity.shouldStartManagedKeeper(true, false));
        assertFalse(VirtualRearHostActivity.shouldStartManagedKeeper(true, true));
        assertFalse(VirtualRearHostActivity.shouldStartManagedKeeper(false, false));
        assertFalse(VirtualRearHostActivity.shouldStartManagedKeeper(false, true));
    }

    @Test public void onlyPhysicalReplacementTransfersLauncherRestoreToBExit() {
        assertTrue(VirtualRearHostActivity.shouldDeferReplacementLauncherRestore(true, true));
        assertFalse(VirtualRearHostActivity.shouldDeferReplacementLauncherRestore(true, false));
        assertFalse(VirtualRearHostActivity.shouldDeferReplacementLauncherRestore(false, true));
    }
}
