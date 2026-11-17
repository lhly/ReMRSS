package dev.remrss.scene.geometry

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/** Coordinate values are integer physical pixels in the current display orientation. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right >= left && bottom >= top) }
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
    fun intersect(other: PixelRect): PixelRect {
        val l = max(left, other.left); val t = max(top, other.top)
        val r = min(right, other.right); val b = min(bottom, other.bottom)
        return if (r <= l || b <= t) PixelRect(l, t, l, t) else PixelRect(l, t, r, b)
    }
    fun inset(insets: PixelInsets): PixelRect {
        val l = (left + insets.left).coerceAtMost(right)
        val t = (top + insets.top).coerceAtMost(bottom)
        val r = (right - insets.right).coerceAtLeast(l)
        val b = (bottom - insets.bottom).coerceAtLeast(t)
        return PixelRect(l, t, r, b)
    }
    fun toAndroidRect() = Rect(left, top, right, bottom)
}

data class PixelInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) }
    companion object { val NONE = PixelInsets(0, 0, 0, 0) }
}

enum class CutoutKnowledge { UNKNOWN, KNOWN_NONE, KNOWN_PRESENT }
enum class SceneRegion { FULL, SAFE }

/**
 * Geometry for one measured host window. `displayBounds` is logical display space, not mode pixels;
 * `windowBounds` is the actual root-view screen rectangle. Cutout insets are window-local and are
 * applied once after intersection. Physical mode size and effective density remain independent.
 */
data class SceneViewportGeometry(
    val displayBounds: PixelRect,
    val windowBounds: PixelRect,
    val cutoutInsets: PixelInsets,
    val cutoutKnowledge: CutoutKnowledge,
    val cutoutBoundsInWindow: List<PixelRect>,
    val displayRotation: Int,
    val contentRotation: Int,
    val effectiveDensityDpi: Int,
    val physicalModeWidthPx: Int? = null,
    val physicalModeHeightPx: Int? = null,
) {
    init {
        require(displayRotation in 0..3 && contentRotation in 0..3)
        require(effectiveDensityDpi > 0)
        require((physicalModeWidthPx == null) == (physicalModeHeightPx == null))
        require(physicalModeWidthPx == null || physicalModeWidthPx > 0 && physicalModeHeightPx!! > 0)
    }

    val measuredWindow get() = windowBounds.intersect(displayBounds)
    val safeBounds get() = windowBounds.inset(cutoutInsets).intersect(displayBounds)
    fun contentBounds(region: SceneRegion) = if (region == SceneRegion.FULL) measuredWindow else safeBounds
    fun contentWidth(region: SceneRegion) = if (contentRotation % 2 == 0) contentBounds(region).width else contentBounds(region).height
    fun contentHeight(region: SceneRegion) = if (contentRotation % 2 == 0) contentBounds(region).height else contentBounds(region).width

    /** Affine matrix for raw window-local coordinates. Native transformed children should consume their own local events instead. */
    fun windowInputMatrix(region: SceneRegion): FloatArray {
        val r = contentBounds(region)
        val l = (r.left - windowBounds.left).toFloat()
        val t = (r.top - windowBounds.top).toFloat()
        val w = r.width.toFloat(); val h = r.height.toFloat()
        return when (contentRotation) {
            1 -> floatArrayOf(0f, 1f, -t, -1f, 0f, l + w)
            2 -> floatArrayOf(-1f, 0f, l + w, 0f, -1f, t + h)
            3 -> floatArrayOf(0f, -1f, t + h, 1f, 0f, -l)
            else -> floatArrayOf(1f, 0f, -l, 0f, 1f, -t)
        }
    }

    /** Window-local pixels -> content-local pixels; native View dispatch performs this transform itself. */
    fun windowToContent(x: Float, y: Float, region: SceneRegion): Pair<Float, Float> {
        val r = contentBounds(region)
        val u = x - (r.left - windowBounds.left); val v = y - (r.top - windowBounds.top)
        return when (contentRotation) {
            1 -> v to (r.width - u)
            2 -> (r.width - u) to (r.height - v)
            3 -> (r.height - v) to u
            else -> u to v
        }
    }

    fun contentToWindow(x: Float, y: Float, region: SceneRegion): Pair<Float, Float> {
        val r = contentBounds(region)
        val (u, v) = when (contentRotation) {
            1 -> (r.width - y) to x
            2 -> (r.width - x) to (r.height - y)
            3 -> y to (r.height - x)
            else -> x to y
        }
        return (r.left - windowBounds.left + u) to (r.top - windowBounds.top + v)
    }

    companion object {
        fun fromWindow(
            displayBounds: PixelRect,
            windowBounds: PixelRect,
            cutoutInsets: PixelInsets?,
            cutoutBoundsInWindow: List<PixelRect> = emptyList(),
            cutoutKnowledge: CutoutKnowledge? = null,
            displayRotation: Int,
            contentRotation: Int = 0,
            effectiveDensityDpi: Int,
            physicalModeWidthPx: Int? = null,
            physicalModeHeightPx: Int? = null,
        ) = SceneViewportGeometry(
            displayBounds, windowBounds, cutoutInsets ?: PixelInsets.NONE,
            cutoutKnowledge ?: when { cutoutInsets == null -> CutoutKnowledge.UNKNOWN; cutoutBoundsInWindow.isEmpty() && cutoutInsets == PixelInsets.NONE -> CutoutKnowledge.KNOWN_NONE; else -> CutoutKnowledge.KNOWN_PRESENT },
            cutoutBoundsInWindow, displayRotation, contentRotation, effectiveDensityDpi,
            physicalModeWidthPx, physicalModeHeightPx
        )
    }
}
