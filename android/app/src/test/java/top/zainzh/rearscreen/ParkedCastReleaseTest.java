package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ParkedCastReleaseTest {
    private static JSONObject running() throws Exception {
        return new JSONObject().put("id", "cast-2").put("host", 31).put("task", 45)
                .put("display", 6).put("readerId", "reader-2").put("state", "running");
    }

    private static JSONObject released() throws Exception {
        JSONObject stoppedReader = new JSONObject().put("id", "reader-2")
                .put("task", 45).put("display", 6).put("state", "stopped")
                .put("resourcesStopped", true).put("outputAvailable", false);
        return VirtualCastTerminalStatus.noSessionManagerStatus(
                VirtualCastTerminalStatus.write(running(), true, true, true),
                stoppedReader, true, true, true);
    }

    @Test public void canReparkOnlyAfterExactVirtualDisplayAndReaderRelease() throws Exception {
        assertTrue(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(), released()));
        assertFalse(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(),
                released().put("managerResourcesStopped", false)));
        assertFalse(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(),
                released().put("outputAvailable", true)));
        assertFalse(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(),
                released().put("id", "previous-cast")));
        assertFalse(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(),
                released().put("host", 32)));
        assertFalse(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(),
                released().put("display", 7)));
        JSONObject otherReader = released();
        otherReader.getJSONObject("managerInput").put("id", "previous-reader");
        assertFalse(VirtualCastTerminalStatus.matchesReleasedParkedCast(running(), otherReader));
    }
}
