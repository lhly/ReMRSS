package top.zainzh.rearscreen;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RearImeStartupFocusTest {
    private static final String TARGET = "example.editor/.SearchActivity";
    private static final String TARGET_WINDOW =
            "Window #1 Window{123abc u0 example.editor/example.editor.SearchActivity}:\n"
            + "  mDisplayId=42 rootTaskId=10\n"
            + "  mHasSurface=true\n"
            + "  mDrawState=HAS_DRAWN\n"
            + "  isVisible=true\n";

    @Test public void requiresDrawnVisibleSurfaceOnOwnedDisplay() {
        assertTrue(RearImeStartupFocus.hasDrawnWindow(TARGET_WINDOW, 42, TARGET));
        assertFalse(RearImeStartupFocus.hasDrawnWindow(TARGET_WINDOW, 0, TARGET));
        assertFalse(RearImeStartupFocus.hasDrawnWindow(TARGET_WINDOW, 43, TARGET));
        assertFalse(RearImeStartupFocus.hasDrawnWindow(TARGET_WINDOW.replace("HAS_DRAWN", "READY_TO_SHOW"), 42, TARGET));
        assertFalse(RearImeStartupFocus.hasDrawnWindow(TARGET_WINDOW.replace("isVisible=true", "isVisible=false"), 42, TARGET));
        assertFalse(RearImeStartupFocus.hasDrawnWindow(TARGET_WINDOW.replace("mHasSurface=true", "mHasSurface=false"), 42, TARGET));
    }

    @Test public void lockScreenSeedCannotUsePrimaryOrAnotherDisplayAndDoesNotChangeOrdinaryHandoff() {
        String displayName = "ReMRSS-virtual-test";
        assertTrue(RearImeStartupFocus.canHostHelper(42, displayName, 42, displayName, true, true));
        assertFalse(RearImeStartupFocus.canHostHelper(0, displayName, 0, displayName, true, true));
        assertFalse(RearImeStartupFocus.canHostHelper(42, displayName, 43, displayName, true, true));
        assertFalse(RearImeStartupFocus.canHostHelper(42, displayName, 42, "Other display", true, true));
        assertFalse(RearImeStartupFocus.canHostHelper(42, displayName, 42, displayName, false, true));
        assertFalse(RearImeStartupFocus.canHostHelper(42, displayName, 42, displayName, true, false));
        assertTrue(RearImeStartupFocus.canHostHelper(42, displayName, 42, displayName, false, false));
    }

    @Test public void cannotBorrowDrawnStateFromAnotherWindow() {
        String other = TARGET_WINDOW.replace("SearchActivity", "LoginActivity");
        String waiting = TARGET_WINDOW.replace("HAS_DRAWN", "DRAW_PENDING");
        assertFalse(RearImeStartupFocus.hasDrawnWindow(waiting + other, 42, TARGET));
        assertTrue(RearImeStartupFocus.hasDrawnWindow(other + TARGET_WINDOW, 42, TARGET));
    }
}
