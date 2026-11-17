package top.zainzh.rearscreen;

import org.json.JSONObject;

/** Shared exact stop→readback gate for SDK close and explicit recovery. */
final class AcceptedVirtualCloseFlow {
    interface StatusReader { JSONObject read() throws Exception; }
    interface ExactStop { void stop(String sessionId) throws Exception; }
    interface StatusCheck { boolean matches(JSONObject status); }
    private AcceptedVirtualCloseFlow() { }

    static JSONObject stopAndReadTerminal(String expectedSessionId, JSONObject initial,
            StatusReader reader, ExactStop stopper, StatusCheck exactSession, StatusCheck terminal) throws Exception {
        if (expectedSessionId == null || expectedSessionId.isBlank() || initial == null
                || !exactSession.matches(initial)) return null;
        if (terminal.matches(initial)) return initial;
        stopper.stop(expectedSessionId);
        JSONObject afterStop = reader.read();
        if (afterStop == null || !exactSession.matches(afterStop) || !terminal.matches(afterStop)) return null;
        return afterStop;
    }
}
