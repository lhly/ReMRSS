package top.zainzh.rearscreen.platform.mods

import dev.remrss.scene.geometry.CutoutKnowledge
import dev.remrss.scene.geometry.SceneRegion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.platform.CoordinateTransform
import top.zainzh.rearscreen.platform.DpSize
import top.zainzh.rearscreen.platform.PixelRect
import top.zainzh.rearscreen.platform.PixelSize
import top.zainzh.rearscreen.platform.RearDeviceInfo
import top.zainzh.rearscreen.platform.RearViewportSnapshot
import top.zainzh.rearscreen.platform.rearDeviceInfoFromInventory
import top.zainzh.rearscreen.platform.ambiguousPublicSize
import top.zainzh.rearscreen.platform.ipc.RearDisplayDimensions

class ModRearInfoBindingsTest {
    private val device = RearDeviceInfo(5, RearDisplayDimensions(400, 900, 800, 380, 320, 1), "ON")
    private val viewport = RearViewportSnapshot(
        revision = 3, displayId = 5, physicalDisplayPx = null, contentBoundsPx = PixelRect(20, 0, 700, 350),
        windowPx = PixelSize(760, 360), windowDp = DpSize(380f, 180f), densityDpi = 320, renderBufferPx = null,
        displayRotation = 1, contentRotation = 3, inputTransform = CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f),
        region = SceneRegion.SAFE, cutoutKnowledge = CutoutKnowledge.KNOWN_PRESENT,
    )

    @Test fun deviceRasterLogicalSizeAndCommittedSceneAreIndependent() {
        val result = ModRearInfoBindings.encode(ModRearInfoBindings.Read.Granted(device), true, viewport)
        val info = result.getJSONObject("info")
        assertEquals("AVAILABLE", info.getString("status"))
        assertEquals(400, info.getJSONObject("physicalModePx").getInt("width"))
        assertEquals(800, info.getJSONObject("logicalDisplayPx").getInt("width"))
        val measured = result.getJSONObject("viewport")
        assertEquals("READY", measured.getString("status"))
        assertEquals(760, measured.getJSONObject("windowPx").getInt("width"))
        assertEquals(700, measured.getJSONObject("contentBoundsPx").getInt("right"))
        assertEquals(680, measured.getJSONObject("contentBoundsPx").getInt("width"))
        assertEquals(3L, measured.getLong("revision"))
        assertEquals(3, measured.getInt("contentRotation"))
        assertFalse(measured.has("inputTransform"))
        assertFalse(info.has("displayId"))
    }

    @Test fun deniedPendingAndSettingsNeverExposeSceneGeometry() {
        for ((read, status) in listOf(ModRearInfoBindings.Read.Denied to "NOT_GRANTED", ModRearInfoBindings.Read.Pending to "PENDING")) {
            val bindings = ModRearInfoBindings.encode(read, true, viewport)
            for (key in listOf("info", "viewport")) {
                assertEquals(status, bindings.getJSONObject(key).getString("status"))
                assertEquals(1, bindings.getJSONObject(key).length())
            }
        }
        val granted = ModRearInfoBindings.Read.Granted(device)
        val settings = ModRearInfoBindings.encode(granted, false, viewport).getJSONObject("viewport")
        assertEquals("NOT_APPLICABLE", settings.getString("status"))
        assertEquals(1, settings.length())
        val noReport = ModRearInfoBindings.encode(granted, true, null).getJSONObject("viewport")
        assertEquals("NOT_READY", noReport.getString("status"))
        assertEquals(1, noReport.length())
    }

    @Test fun missingPanelAndUnreadableMetricsAreDifferentAndDoNotFakeDimensions() {
        for ((id, status) in listOf(-1 to "DISPLAY_UNAVAILABLE", 5 to "INFO_UNAVAILABLE")) {
            val bindings = ModRearInfoBindings.encode(ModRearInfoBindings.Read.Granted(RearDeviceInfo(id)), false, null)
            val info = bindings.getJSONObject("info")
            assertEquals(status, info.getString("status"))
            assertFalse(info.has("logicalDisplayPx"))
        }
        val unavailableRaster = device.copy(dimensions = RearDisplayDimensions(null, null, 800, 380, 320, 1))
        val info = ModRearInfoBindings.encode(ModRearInfoBindings.Read.Granted(unavailableRaster), false, null).getJSONObject("info")
        assertEquals("AVAILABLE", info.getString("status"))
        assertTrue(info.isNull("physicalModePx"))
        assertEquals(800, info.getJSONObject("logicalDisplayPx").getInt("width"))
    }

    @Test fun selectedRearInventoryPreservesLogicalOverrideEvenWhenThePanelIsOff() {
        val snapshot = JSONObject("""{"selectedId":1,"selectedUnique":"rear","displays":[
            {"id":0,"unique":"main","width":1200,"height":2608,"density":480,"rotation":0,"state":2},
            {"id":1,"unique":"rear","width":820,"height":510,"density":320,"rotation":1,"state":1}]}""")
        val info = rearDeviceInfoFromInventory(snapshot, 976 to 596)
        assertEquals(RearDisplayDimensions(976, 596, 820, 510, 320, 1), info.dimensions)
        assertEquals("OFF", info.state)
        snapshot.put("selectedUnique", "replaced")
        assertNull(rearDeviceInfoFromInventory(snapshot, null).dimensions)
    }

    @Test fun onlyUncorroboratedLocalBoundsAreSuppressed() {
        assertTrue(ambiguousPublicSize(1200 to 2608, 1200 to 2608, 976 to 596, 0))
        assertFalse(ambiguousPublicSize(800 to 480, 1200 to 2608, 976 to 596, 0))
        assertFalse(ambiguousPublicSize(596 to 976, 596 to 976, 976 to 596, 1))
    }
}
