package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public class ControlledCastHostComponentTest {
    private static final String HOST_CLASS = "top.zainzh.rearscreen.VirtualRearHostActivity";

    @Test public void debugApplicationIdDoesNotRewriteTheActualClassNamespace() {
        String expected = "top.zainzh.rearscreen.debug/top.zainzh.rearscreen.VirtualRearHostActivity";
        assertEquals(expected, ControlledCastHostComponent.expected("top.zainzh.rearscreen.debug", HOST_CLASS));
        assertTrue(ControlledCastHostComponent.matches(expected, "top.zainzh.rearscreen.debug", HOST_CLASS));
        assertFalse(ControlledCastHostComponent.matches(
                "top.zainzh.rearscreen.debug/top.zainzh.rearscreen.debug.VirtualRearHostActivity",
                "top.zainzh.rearscreen.debug", HOST_CLASS));
    }

    @Test public void releaseApplicationIdUsesItsNormalHostComponent() {
        String expected = "top.zainzh.rearscreen/top.zainzh.rearscreen.VirtualRearHostActivity";
        assertEquals(expected, ControlledCastHostComponent.expected("top.zainzh.rearscreen", HOST_CLASS));
        assertTrue(ControlledCastHostComponent.matches(expected, "top.zainzh.rearscreen", HOST_CLASS));
        assertTrue("Android's short form canonicalizes to the exact release component",
                ControlledCastHostComponent.matches("top.zainzh.rearscreen/.VirtualRearHostActivity",
                        "top.zainzh.rearscreen", HOST_CLASS));
    }

    @Test public void shortClassAndWrongPackageAreRejected() {
        assertNull(ControlledCastHostComponent.expected("top.zainzh.rearscreen.debug", "VirtualRearHostActivity"));
        assertNull(ControlledCastHostComponent.expected("top.zainzh.rearscreen.debug", ".VirtualRearHostActivity"));
        assertFalse(ControlledCastHostComponent.matches(
                "other.package/top.zainzh.rearscreen.VirtualRearHostActivity",
                "top.zainzh.rearscreen.debug", HOST_CLASS));
    }
}
