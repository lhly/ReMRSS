package top.zainzh.rearscreen.platform.mods

import dev.remrss.scene.geometry.CutoutKnowledge
import dev.remrss.scene.geometry.SceneRegion
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
import top.zainzh.rearscreen.platform.rearDeviceInfoForMetrics
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

    @Test fun defaultDisplayResourcesAreRejectedEvenWhenDisplayObjectIdIsCorrect() {
        // Mirrors the device regression: selected rear ID and Display object ID are both 1,
        // but the Display object's metrics were sampled with D0 resources (1200x2608).
        val defaultDisplayMetrics = RearDisplayDimensions(1200, 2608, 1200, 2608, 419, 0)
        val info = rearDeviceInfoForMetrics(
            selectedDisplayId = 1,
            metricsDisplayObjectId = 1,
            metricsResourceDisplayId = 0,
            dimensions = defaultDisplayMetrics,
            state = "OFF",
        )

        assertEquals(1, info.displayId)
        assertNull(info.dimensions)
        assertNull(info.state)
        val encoded = ModRearInfoBindings.encode(ModRearInfoBindings.Read.Granted(info), false, null)
            .getJSONObject("info")
        assertEquals("INFO_UNAVAILABLE", encoded.getString("status"))
        assertFalse(encoded.has("logicalDisplayPx"))
        assertFalse(encoded.has("physicalModePx"))
    }

    @Test fun offRearDisplayKeepsItsOwnLogicalAndPhysicalDimensions() {
        val rearMetrics = RearDisplayDimensions(976, 596, 976, 596, 400, 0)
        val info = rearDeviceInfoForMetrics(
            selectedDisplayId = 1,
            metricsDisplayObjectId = 1,
            metricsResourceDisplayId = 1,
            dimensions = rearMetrics,
            state = "OFF",
        )
        val encoded = ModRearInfoBindings.encode(ModRearInfoBindings.Read.Granted(info), false, null)
            .getJSONObject("info")

        assertEquals("AVAILABLE", encoded.getString("status"))
        assertEquals("OFF", encoded.getString("state"))
        assertEquals(976, encoded.getJSONObject("logicalDisplayPx").getInt("width"))
        assertEquals(596, encoded.getJSONObject("logicalDisplayPx").getInt("height"))
        assertEquals(976, encoded.getJSONObject("physicalModePx").getInt("width"))
        assertEquals(596, encoded.getJSONObject("physicalModePx").getInt("height"))
    }
}
