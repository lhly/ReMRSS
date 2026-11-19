package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AiRearWallpaperLaunchPolicyTest {
    @Test public void lockedShortcutDoesNotDependOnUnlockedOptIn() {
        assertTrue(AiRearWallpaperSupport.allowsShortcut(true, false));
        assertTrue(AiRearWallpaperSupport.allowsShortcut(true, true));
    }

    @Test public void unlockedShortcutRequiresOptIn() {
        assertFalse(AiRearWallpaperSupport.allowsShortcut(false, false));
        assertTrue(AiRearWallpaperSupport.allowsShortcut(false, true));
    }
}
