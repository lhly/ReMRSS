package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.List;
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

    @Test public void shortBackThatAlreadyClosedTheGuestReusesTheReleaseWithoutStoppingAgain() throws Exception {
        JSONObject terminal = released();
        assertSame(terminal, ParkedWallpaperCastReturn.release("cast-2", running(), () -> terminal,
                id -> fail("The guest's Back already released this session")));
    }

    @Test public void menuExitStillStopsExactlyTheOwnedSession() throws Exception {
        JSONObject terminal = released();
        ArrayDeque<JSONObject> reads = new ArrayDeque<>(List.of(running(), terminal));
        ArrayDeque<String> stops = new ArrayDeque<>();
        assertSame(terminal, ParkedWallpaperCastReturn.release("cast-2", running(), reads::remove, stops::add));
        assertEquals("cast-2", stops.remove());
        assertTrue(stops.isEmpty());
    }

    @Test public void exactTerminalReadbackWinsWhenGuestExitsDuringStop() throws Exception {
        JSONObject terminal = released();
        ArrayDeque<JSONObject> reads = new ArrayDeque<>(List.of(running(), terminal));
        assertSame(terminal, ParkedWallpaperCastReturn.release("cast-2", running(), reads::remove,
                id -> { throw new IllegalStateException("Session already removed"); }));
    }

    @Test public void aNewSessionOrAnUnreleasedReaderCannotReparkTheHost() throws Exception {
        assertNull(ParkedWallpaperCastReturn.release("cast-2", running(),
                () -> running().put("id", "newer-cast"), id -> fail("Do not stop the newer cast")));
        JSONObject incomplete = released().put("managerResourcesStopped", false);
        ArrayDeque<JSONObject> reads = new ArrayDeque<>(List.of(running(), incomplete));
        assertThrows(IllegalStateException.class, () -> ParkedWallpaperCastReturn.release("cast-2", running(),
                reads::remove, id -> { throw new IllegalStateException("Unverified release"); }));
    }
}
