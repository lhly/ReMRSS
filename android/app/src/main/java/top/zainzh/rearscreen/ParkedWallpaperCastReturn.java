package top.zainzh.rearscreen;

import org.json.JSONObject;

/** Both a menu exit and an already-ended guest must release this round, not the standby host. */
final class ParkedWallpaperCastReturn {
    private ParkedWallpaperCastReturn() { }

    static JSONObject release(String id, JSONObject owned, AcceptedVirtualCloseFlow.StatusReader read,
                              AcceptedVirtualCloseFlow.ExactStop stop) throws Exception {
        if (id == null || id.isEmpty() || owned == null || !id.equals(owned.optString("id"))) return null;
        JSONObject live = read.read();
        if (live == null || !id.equals(live.optString("id"))) return null;
        if (VirtualCastTerminalStatus.matchesReleasedParkedCast(owned, live)) return live;
        try {
            stop.stop(id);
        } catch (Exception stopRejected) {
            // A guest's Back can finish the session between the first read and our exact stop.
            JSONObject terminal = read.read();
            if (VirtualCastTerminalStatus.matchesReleasedParkedCast(owned, terminal)) return terminal;
            throw stopRejected;
        }
        JSONObject terminal = read.read();
        return VirtualCastTerminalStatus.matchesReleasedParkedCast(owned, terminal) ? terminal : null;
    }
}
