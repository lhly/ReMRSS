package top.zainzh.rearscreen;

import org.json.JSONException;
import org.json.JSONObject;

/** Shared producer/consumer meaning for shutdown status; stopped is emitted only after resource exit. */
public final class VirtualCastTerminalStatus {
    private VirtualCastTerminalStatus() { }

    static JSONObject write(JSONObject status, boolean closing, boolean resourcesStopped,
            boolean previousOutputAvailable) throws JSONException {
        status.put("state", closing ? (resourcesStopped ? "stopped" : "stopping") : status.optString("state", "running"));
        status.put("outputAvailable", closing && resourcesStopped ? false : previousOutputAvailable);
        return status;
    }

    /** Live manager snapshot for no current session; old/unknown terminal status is never promoted to stopped. */
    static JSONObject noSessionManagerStatus(JSONObject previous, JSONObject input,
            boolean noCurrent, boolean noReplacement, boolean noStart) throws JSONException {
        JSONObject status = previous == null ? new JSONObject() : new JSONObject(previous.toString());
        JSONObject actualInput = input == null ? new JSONObject() : new JSONObject(input.toString());
        boolean stopped = noCurrent && noReplacement && noStart && "stopped".equals(status.optString("state"))
                && "stopped".equals(actualInput.optString("state"))
                && actualInput.has("resourcesStopped") && actualInput.optBoolean("resourcesStopped")
                && actualInput.has("outputAvailable") && !actualInput.optBoolean("outputAvailable");
        status.put("managerInput", actualInput).put("managerResourcesStopped", stopped);
        return status;
    }

    /** Both the live cast and its actual active input reader must match the approved P→V operation. */
    static boolean matchesRunningPhysicalReplacement(JSONObject state, int rearDisplay, String rearIdentity,
            String operation, long ownerGeneration, String ownerIdentity, String session, long generation,
            int task, String base, String reader, long readerGeneration) {
        if (state == null || rearDisplay <= 0 || ownerGeneration <= 0L || generation <= 0L || readerGeneration <= 0L
                || task <= 0 || !exact(state, "rearIdentity", rearIdentity)
                || !exact(state, "coordinatorOperationId", operation) || !exact(state, "ownerIdentity", ownerIdentity)
                || !exact(state, "id", session) || !exact(state, "baseComponent", base)
                || !exact(state, "intendedBaseComponent", base) || !exact(state, "readerId", reader)
                || !exact(state, "state", "running") || !exact(state, "readerLifecycle", "READY")
                || rearDisplay != state.optInt("rearDisplayId", -1) || task != state.optInt("task", -1)
                || ownerGeneration != state.optLong("ownerGeneration", -1L)
                || generation != state.optLong("generation", -1L)
                || readerGeneration != state.optLong("readerGeneration", -1L)
                || !Boolean.TRUE.equals(state.opt("outputAvailable"))) return false;
        int virtualDisplay = state.optInt("display", -1);
        JSONObject input = state.optJSONObject("input");
        return virtualDisplay > 0 && virtualDisplay != rearDisplay && input != null
                && exact(input, "id", reader) && exact(input, "state", "running")
                && exact(input, "readerLifecycle", "READY") && readerGeneration == input.optLong("generation", -1L)
                && task == input.optInt("task", -1) && virtualDisplay == input.optInt("display", -1)
                && Boolean.TRUE.equals(input.opt("outputAvailable"));
    }

    private static boolean exact(JSONObject object, String key, String expected) {
        return expected != null && !expected.isBlank() && expected.equals(object.opt(key));
    }

    /** Session-bound input evidence; never substitutes a process-global historical reader for no-reader. */
    public static JSONObject physicalReaderStatus(String lifecycle, String readerId, long readerGeneration,
            int taskId, int displayId, JSONObject observed) throws JSONException {
        if (readerId == null || readerId.isEmpty())
            return new JSONObject().put("state", "NO_READER".equals(lifecycle) ? "stopped" : "unknown")
                    .put("readerLifecycle", lifecycle == null ? "UNKNOWN" : lifecycle)
                    .put("task", taskId).put("display", displayId).put("outputAvailable", false);
        JSONObject exact = observed == null ? new JSONObject() : new JSONObject(observed.toString());
        if ("READY".equals(lifecycle) && (!"running".equals(exact.optString("state"))
                || !readerId.equals(exact.optString("id"))
                || readerGeneration <= 0L || readerGeneration != exact.optLong("generation", -1L)
                || taskId <= 0 || taskId != exact.optInt("task", -1)
                || displayId <= 0 || displayId != exact.optInt("display", -1)
                || !(exact.opt("outputAvailable") instanceof Boolean)))
            return new JSONObject().put("state", "unknown").put("readerLifecycle", "UNKNOWN")
                    .put("task", taskId).put("display", displayId).put("outputAvailable", false);
        exact.put("readerLifecycle", lifecycle == null ? "UNKNOWN" : lifecycle);
        exact.put("id", readerId).put("generation", readerGeneration).put("task", taskId).put("display", displayId);
        return exact;
    }
}
