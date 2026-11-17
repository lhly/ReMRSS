package dev.remrss.scene.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SceneViewportGeometryTest {
    private fun viewport(turn: Int) = SceneViewportGeometry.fromWindow(
        displayBounds = PixelRect(0, 0, 976, 596),
        windowBounds = PixelRect(0, 0, 976, 596),
        cutoutInsets = PixelInsets(296, 0, 0, 0),
        cutoutBoundsInWindow = listOf(PixelRect(0, 0, 296, 596)),
        displayRotation = 0,
        contentRotation = turn,
        effectiveDensityDpi = 440,
        physicalModeWidthPx = 976,
        physicalModeHeightPx = 596,
    )

    @Test fun fullAndSafeAreDifferentRectanglesAndCutoutIsAppliedExactlyOnce() {
        val g = viewport(1)
        assertEquals(PixelRect(0, 0, 976, 596), g.contentBounds(SceneRegion.FULL))
        assertEquals(PixelRect(296, 0, 976, 596), g.contentBounds(SceneRegion.SAFE))
        assertEquals(CutoutKnowledge.KNOWN_PRESENT, g.cutoutKnowledge)
        assertEquals(596, g.contentWidth(SceneRegion.FULL))
        assertEquals(976, g.contentHeight(SceneRegion.FULL))
        assertEquals(596, g.contentWidth(SceneRegion.SAFE))
        assertEquals(680, g.contentHeight(SceneRegion.SAFE))
        assertEquals(440, g.effectiveDensityDpi)
        assertEquals(976, g.physicalModeWidthPx)
    }

    @Test fun allQuarterTurnsRoundTripAsymmetricInteriorPointsAndEmitWindowLocalMatrices() {
        for (turn in 0..3) {
            val g = SceneViewportGeometry.fromWindow(
                PixelRect(0, 0, 976, 596), PixelRect(0, 0, 976, 596), PixelInsets.NONE,
                displayRotation = 2, contentRotation = turn, effectiveDensityDpi = 440
            )
            val (wx, wy) = g.contentToWindow(73f, 41f, SceneRegion.FULL)
            val (cx, cy) = g.windowToContent(wx, wy, SceneRegion.FULL)
            assertEquals("turn=$turn x", 73f, cx, 0.001f)
            assertEquals("turn=$turn y", 41f, cy, 0.001f)
            val m = g.windowInputMatrix(SceneRegion.FULL)
            assertEquals(6, m.size)
            assertEquals(if (turn % 2 == 0) 976 else 596, g.contentWidth(SceneRegion.FULL))
            assertEquals(if (turn % 2 == 0) 596 else 976, g.contentHeight(SceneRegion.FULL))
        }
    }

    @Test fun distinguishesUnknownCutoutFromKnownNoCutoutAndIntersectsWindowBounds() {
        val unknown = SceneViewportGeometry.fromWindow(
            PixelRect(0, 0, 300, 200), PixelRect(-12, 10, 288, 210), null,
            displayRotation = 0, effectiveDensityDpi = 320
        )
        assertEquals(CutoutKnowledge.UNKNOWN, unknown.cutoutKnowledge)
        assertEquals(PixelRect(0, 10, 288, 200), unknown.contentBounds(SceneRegion.FULL))
        assertEquals(unknown.contentBounds(SceneRegion.FULL), unknown.contentBounds(SceneRegion.SAFE))

        val noCutout = SceneViewportGeometry.fromWindow(
            PixelRect(0, 0, 300, 200), PixelRect(0, 0, 300, 200), PixelInsets.NONE,
            displayRotation = 0, effectiveDensityDpi = 320
        )
        assertEquals(CutoutKnowledge.KNOWN_NONE, noCutout.cutoutKnowledge)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f), noCutout.windowInputMatrix(SceneRegion.SAFE), 0f)
    }

    @Test fun asymmetricSafeRectUsesActualWindowOffsetWithoutApplyingInsetsTwice() {
        val g = SceneViewportGeometry.fromWindow(
            PixelRect(0, 0, 1200, 900), PixelRect(30, 40, 1030, 740), PixelInsets(11, 23, 37, 19),
            displayRotation = 3, contentRotation = 3, effectiveDensityDpi = 320
        )
        assertEquals(PixelRect(41, 63, 993, 721), g.contentBounds(SceneRegion.SAFE))
        val matrix = g.windowInputMatrix(SceneRegion.SAFE)
        assertArrayEquals(floatArrayOf(0f, -1f, 681f, 1f, 0f, -11f), matrix, 0f)
        val windowPoint = g.contentToWindow(73f, 41f, SceneRegion.SAFE)
        assertEquals(52f, windowPoint.first, 0.001f)
        assertEquals(608f, windowPoint.second, 0.001f)
        val contentPoint = g.windowToContent(windowPoint.first, windowPoint.second, SceneRegion.SAFE)
        assertEquals(73f, contentPoint.first, 0.001f)
        assertEquals(41f, contentPoint.second, 0.001f)
    }
}
