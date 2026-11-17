package top.zainzh.rearscreen;

import org.junit.Test;
import static org.junit.Assert.*;

public class DebugPhysicalReplacementLateFailureTest {
    @Test public void consumesOnlyTheExactKnownSampleAfterBIsVisible() {
        DebugPhysicalReplacementLateFailure.Gate gate = new DebugPhysicalReplacementLateFailure.Gate();
        assertTrue(gate.arm(81, 12L, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicala",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
        assertFalse(gate.consume(81, 12L, 82, 4, "4:trusted-panel", "other.package",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
        assertFalse(gate.consume(81, 12L, 82, 5, "5:replacement-panel", "dev.remrss.sample.rc5physicalb",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
        assertTrue(gate.consume(81, 12L, 82, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicalb",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
        assertFalse(gate.consume(81, 12L, 82, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicalb",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
    }

    @Test public void wrongBaseCannotArmOrConsumeTheFixture() {
        DebugPhysicalReplacementLateFailure.Gate gate = new DebugPhysicalReplacementLateFailure.Gate();
        assertFalse(gate.arm(81, 12L, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicala", "other/.Main"));
        assertFalse(gate.consume(81, 12L, 82, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicalb",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
    }

    @Test public void cooperativeArmConsumesOnlyItsExactBAndAcceptsOnlyItsExactRollbackReceipt()
            throws InterruptedException {
        DebugPhysicalReplacementLateFailure.Gate gate = new DebugPhysicalReplacementLateFailure.Gate();
        String identity = "4:trusted-panel";
        assertTrue(gate.arm(81, 12L, 4, identity, "dev.remrss.sample.rc5physicala",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity", 82));
        assertFalse(gate.consume(81, 12L, 83, 4, identity, "dev.remrss.sample.rc5physicalb",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));
        assertFalse(gate.awaitRollbackVerified(0L, 81, 12L, 82, 4, identity));
        assertTrue(gate.consume(81, 12L, 82, 4, identity, "dev.remrss.sample.rc5physicalb",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity"));

        gate.recordRollbackVerified(81, 12L, 83, 4, identity);
        assertFalse(gate.awaitRollbackVerified(0L, 81, 12L, 82, 4, identity));
        gate.recordRollbackVerified(81, 11L, 82, 4, identity);
        assertFalse(gate.awaitRollbackVerified(0L, 81, 12L, 82, 4, identity));
        gate.recordRollbackVerified(81, 12L, 82, 5, "5:other-panel");
        assertFalse(gate.awaitRollbackVerified(0L, 81, 12L, 82, 4, identity));

        gate.recordRollbackVerified(81, 12L, 82, 4, identity);
        assertTrue(gate.awaitRollbackVerified(0L, 81, 12L, 82, 4, identity));
        assertFalse(gate.awaitRollbackVerified(0L, 81, 12L, 83, 4, identity));
    }

    @Test public void cooperativeArmRejectsInvalidOrSameTaskCandidate() {
        DebugPhysicalReplacementLateFailure.Gate gate = new DebugPhysicalReplacementLateFailure.Gate();
        assertFalse(gate.arm(81, 12L, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicala",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity", -1));
        assertFalse(gate.arm(81, 12L, 4, "4:trusted-panel", "dev.remrss.sample.rc5physicala",
                "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity", 81));
    }
}
