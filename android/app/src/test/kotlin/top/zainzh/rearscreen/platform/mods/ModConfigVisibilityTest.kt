package top.zainzh.rearscreen.platform.mods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.view.Display.DEFAULT_DISPLAY

class ModConfigVisibilityTest {
    @Test fun resumedVisibleSettingsWindowRemainsVisibleWithoutWindowFocus() {
        // A separate display may own global focus while this main-screen window stays visible.
        assertTrue(isModConfigSceneVisible(true, true, true, true, DEFAULT_DISPLAY, false))
    }

    @Test fun pausedDetachedHiddenOrInvisibleSettingsWindowCannotReceiveUserEvents() {
        assertFalse(isModConfigSceneVisible(resumed = false, attached = true, shown = true, windowVisible = true,
            displayId = DEFAULT_DISPLAY, closing = false))
        assertFalse(isModConfigSceneVisible(resumed = true, attached = false, shown = true, windowVisible = true,
            displayId = DEFAULT_DISPLAY, closing = false))
        assertFalse(isModConfigSceneVisible(resumed = true, attached = true, shown = false, windowVisible = true,
            displayId = DEFAULT_DISPLAY, closing = false))
        assertFalse(isModConfigSceneVisible(resumed = true, attached = true, shown = true, windowVisible = false,
            displayId = DEFAULT_DISPLAY, closing = false))
    }

    @Test fun rearUnknownOrClosingSettingsCannotReceiveUserEvents() {
        assertFalse(isModConfigSceneVisible(true, true, true, true, 1, false))
        assertFalse(isModConfigSceneVisible(true, true, true, true, null, false))
        assertFalse(isModConfigSceneVisible(true, true, true, true, DEFAULT_DISPLAY, true))
    }

    @Test fun settingsCastSourceIsOnlyIssuedFromTheMainDisplay() {
        assertTrue(isModConfigMainDisplay(DEFAULT_DISPLAY))
        assertFalse(isModConfigMainDisplay(1))
        assertFalse(isModConfigMainDisplay(null))
    }
}
