package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class AcceptedVirtualCloseFlowTest {
    private static JSONObject status(String id, String state, boolean released) throws Exception {
        return new JSONObject().put("id", id).put("state", state)
                .put("readerLifecycle", released ? "RELEASED" : "READY")
                .put("outputAvailable", !released);
    }

    @Test public void acceptedSdkAndRecoveryCloseShareExactStopAndFreshTerminalReadback() throws Exception {
        final String exactId = "virtual-session-42";
        JSONObject[] live = {status(exactId, "running", false)};
        int[] stops = {0};
        AcceptedVirtualCloseFlow.StatusCheck exactSession = s -> exactId.equals(s.optString("id"));
        AcceptedVirtualCloseFlow.StatusCheck terminal = s -> "stopped".equals(s.optString("state"))
                && "RELEASED".equals(s.optString("readerLifecycle"))
                && !s.optBoolean("outputAvailable", true);
        JSONObject firstAttempt = AcceptedVirtualCloseFlow.stopAndReadTerminal(exactId, live[0],
                () -> live[0], id -> { assertEquals(exactId, id); stops[0]++; live[0] = status(id, "stopping", false); },
                exactSession, terminal);
        assertNull(firstAttempt);
        assertEquals(1, stops[0]);

        JSONObject retry = AcceptedVirtualCloseFlow.stopAndReadTerminal(exactId, live[0],
                () -> live[0], id -> { assertEquals(exactId, id); stops[0]++; live[0] = status(id, "stopped", true); },
                exactSession, terminal);
        assertNotNull(retry);
        assertEquals("stopped", retry.optString("state"));
        assertEquals(2, stops[0]);
    }

    @Test public void stoppedStatusWithUnknownReaderReceiptRetriesExactStopAndOldSessionNeverStops() throws Exception {
        final String exactId = "virtual-session-42";
        JSONObject unknownTerminal = status(exactId, "stopped", false);
        int[] stops = {0};
        JSONObject repaired = AcceptedVirtualCloseFlow.stopAndReadTerminal(exactId, unknownTerminal,
                () -> status(exactId, "stopped", true), id -> stops[0]++,
                s -> exactId.equals(s.optString("id")),
                s -> "stopped".equals(s.optString("state"))
                        && "RELEASED".equals(s.optString("readerLifecycle"))
                        && !s.optBoolean("outputAvailable", true));
        assertNotNull(repaired);
        assertEquals(1, stops[0]);

        JSONObject stale = AcceptedVirtualCloseFlow.stopAndReadTerminal(exactId,
                status("new-session", "running", false), () -> status(exactId, "stopped", true),
                id -> stops[0]++, s -> exactId.equals(s.optString("id")), s -> true);
        assertNull(stale);
        assertEquals(1, stops[0]);
    }
}
