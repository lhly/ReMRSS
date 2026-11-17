package top.zainzh.rearscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class BlurredNavigationFrameTiltTest {
    @Test fun idleFocusHandoffsCannotRestartTilt() {
        for (focused in listOf(false, true, false, true)) {
            assertFalse(shouldObserveNavigationTilt(focused, lifecycleResumed = true, interacting = false))
        }
    }

    @Test fun tracksTiltOnlyDuringInputInFocusedResumedWindow() {
        assertTrue(shouldObserveNavigationTilt(windowFocused = true, lifecycleResumed = true, interacting = true))
        assertFalse(shouldObserveNavigationTilt(windowFocused = false, lifecycleResumed = true, interacting = true))
        assertFalse(shouldObserveNavigationTilt(windowFocused = true, lifecycleResumed = false, interacting = true))
        assertFalse(shouldObserveNavigationTilt(windowFocused = true, lifecycleResumed = true, interacting = false))
    }

    @Test fun activationNeedsAFreshPressAfterCancellation() {
        var mayBegin = true
        val press = NavigationPress { mayBegin }
        press.begin()
        assertTrue(press.down)
        press.cancel()
        mayBegin = false
        press.begin()
        assertFalse(press.down)
        mayBegin = true
        assertFalse(press.down)
        press.begin()
        assertTrue(press.down)
        press.cancel()
        assertFalse(press.down)
    }

    @Test fun preservesTheExistingQuantizedLightAngleAndFallback() {
        assertEquals(0f, navigationLightAngle(1f, 0f), 0f)
        assertEquals((PI / 2).toFloat(), navigationLightAngle(0f, 1f), 0f)
        assertEquals((-PI / 2).toFloat(), navigationLightAngle(0f, 0f), 0f)
    }
}
