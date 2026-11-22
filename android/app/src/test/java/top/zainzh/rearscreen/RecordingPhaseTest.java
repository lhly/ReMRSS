package top.zainzh.rearscreen;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingPhaseTest {
    @Test public void pendingOperationsAcceptNeitherStartNorStop() {
        for (RecordingPhase phase : new RecordingPhase[]{RecordingPhase.PREPARING, RecordingPhase.SAVING}) {
            assertFalse(phase.canStart());
            assertFalse(phase.canStop());
            assertTrue(phase.isBusy());
        }
    }

    @Test public void stopFailureKeepsStopAvailableWhileStartAndSaveFailuresPermitStartingAgain() {
        assertTrue(RecordingPhase.STOP_FAILED.canStop());
        assertFalse(RecordingPhase.STOP_FAILED.canStart());
        for (RecordingPhase phase : new RecordingPhase[]{RecordingPhase.START_FAILED, RecordingPhase.SAVE_FAILED, RecordingPhase.SAVED}) {
            assertTrue(phase.canStart());
            assertFalse(phase.canStop());
        }
    }

    @Test public void statusProbeAndSignalCheckTheSameExactRecorder() {
        String output = "/storage/emulated/0/Movies/ReMRSS_20261119_210000_123abcde.mp4";
        String probe = PrivilegedShellArguments.recordingStatus(123, output);
        String stop = PrivilegedShellArguments.stopRecording(123, output, 2);
        assertFalse(probe.contains("kill"));
        assertEquals(stop.substring(0, stop.indexOf("; then")), probe.substring(0, probe.indexOf("; then")));
        assertTrue(probe.contains("/proc/123/cmdline"));
        assertTrue(probe.contains(PrivilegedShellArguments.quote(output)));
    }
}
