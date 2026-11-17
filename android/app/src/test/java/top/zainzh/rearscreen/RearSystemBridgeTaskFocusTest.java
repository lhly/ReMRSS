package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearSystemBridgeTaskFocusTest {
    @Test public void onlyExactSoleTaskRootOnMainDisplayCanBeFocused() {
        String stacks = "RootTask id=8 displayId=0\n taskId=71: sample.target/.Main\n"
                + "RootTask id=42 displayId=1\n taskId=2042: sample.rear/.Main\n";
        assertTrue(RearSystemBridge.isSoleTaskRoot(stacks, 71, 0));
        assertFalse(RearSystemBridge.isSoleTaskRoot(stacks, 2042, 0));
        assertFalse(RearSystemBridge.isSoleTaskRoot(stacks, 999, 0));
    }

    @Test public void sharedDuplicateMalformedOrUnknownRootsFailClosed() {
        assertFalse(RearSystemBridge.isSoleTaskRoot(
                "RootTask id=8 displayId=0\n taskId=71: sample.target/.Main\n taskId=72: sample.other/.Main\n", 71, 0));
        assertFalse(RearSystemBridge.isSoleTaskRoot(
                "RootTask id=8 displayId=0\n taskId=71: sample.target/.Main\n taskId=71: sample.target/.Main\n", 71, 0));
        assertFalse(RearSystemBridge.isSoleTaskRoot(
                "RootTask id=8 displayId=0\n taskId=bad\n taskId=71: sample.target/.Main\n", 71, 0));
        assertFalse(RearSystemBridge.isSoleTaskRoot("taskId=71: sample.target/.Main\n", 71, 0));
        assertFalse(RearSystemBridge.isSoleTaskRoot(
                "RootTask id=8\n taskId=71: sample.target/.Main\n", 71, 0));
    }
}
