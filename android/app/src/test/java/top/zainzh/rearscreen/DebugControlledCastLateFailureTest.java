package top.zainzh.rearscreen;

import org.junit.Test;
import static org.junit.Assert.*;

public class DebugControlledCastLateFailureTest {
    @Test public void onlyTheBoundOwnerGenerationOperationAndCandidateTokenConsumeOnce() {
        final long[] now = { 100L };
        DebugControlledCastLateFailure.Gate gate = new DebugControlledCastLateFailure.Gate(() -> now[0]);
        assertTrue(gate.arm("session-A", "transportPackageName=dev.remrss.sample.rc5consumer", 7, 41, 90,
                "reader-A", "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity"));
        assertFalse(gate.bind("session-A", "wrong-owner", 7, 41, 90, "reader-A", 42,
                "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", 18,
                "transportPackageName=dev.remrss.sample.rc5candidate", 8, "op-A", "token-A"));
        assertFalse(gate.bind("session-other", "transportPackageName=dev.remrss.sample.rc5consumer", 7, 41, 90,
                "reader-A", 42, "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", 18,
                "transportPackageName=dev.remrss.sample.rc5candidate", 8, "op-A", "token-A"));
        assertTrue(gate.bind("session-A", "transportPackageName=dev.remrss.sample.rc5consumer", 7, 41, 90,
                "reader-A", 42, "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", 18,
                "transportPackageName=dev.remrss.sample.rc5candidate", 8, "op-A", "token-A"));
        assertFalse(gate.bind("session-A", "transportPackageName=dev.remrss.sample.rc5consumer", 7, 41, 90,
                "reader-A", 42, "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", 18,
                "transportPackageName=dev.remrss.sample.rc5candidate", 8, "op-B", "token-B"));
        assertFalse(gate.consume("op-A", "token-B"));
        assertFalse(gate.consume("op-B", "token-A"));
        assertTrue(gate.consume("op-A", "token-A"));
        assertFalse(gate.consume("op-A", "token-A"));
    }

    @Test public void unboundArmExpiresAndCannotInjectIntoALaterOperation() {
        final long[] now = { 100L };
        DebugControlledCastLateFailure.Gate gate = new DebugControlledCastLateFailure.Gate(() -> now[0]);
        assertTrue(gate.arm("session-A", "transportPackageName=dev.remrss.sample.rc5consumer", 7, 41, 90,
                "reader-A", "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity"));
        now[0] += 150_001L;
        assertFalse(gate.bind("session-A", "transportPackageName=dev.remrss.sample.rc5consumer", 7, 41, 90,
                "reader-A", 42, "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", 18,
                "transportPackageName=dev.remrss.sample.rc5candidate", 8, "op-A", "token-A"));
        assertTrue(gate.arm("session-B", "transportPackageName=dev.remrss.sample.rc5consumer", 8, 43, 91,
                "reader-B", "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity"));
        assertTrue(gate.bind("session-B", "transportPackageName=dev.remrss.sample.rc5consumer", 8, 43, 91,
                "reader-B", 44, "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", 19,
                "transportPackageName=dev.remrss.sample.rc5candidate", 9, "op-B", "token-B"));
        assertTrue(gate.consume("op-B", "token-B"));
    }
}
