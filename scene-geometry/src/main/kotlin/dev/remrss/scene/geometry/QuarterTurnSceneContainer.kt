package dev.remrss.scene.geometry

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Single-child native host for a quarter-turned scene. Android owns drawing, clipping and MotionEvent
 * coordinate inversion for the transformed child; callers must not add a second input inverse.
 * Size this container to the already-selected FULL or SAFE rectangle (do not apply cutout insets here).
 */
class QuarterTurnSceneContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    var contentRotation: Int = 0
        set(value) {
            require(value in 0..3) { "contentRotation must be a quarter-turn in 0..3" }
            if (field == value) return
            field = value
            requestLayout()
        }
    var hasAppliedContentLayout: Boolean = false
        private set

    init {
        clipChildren = true
        clipToPadding = true
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        require(childCount == 1) { "QuarterTurnSceneContainer hosts exactly one content view" }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        hasAppliedContentLayout = false
        val width = measuredSize(widthMeasureSpec, suggestedMinimumWidth)
        val height = measuredSize(heightMeasureSpec, suggestedMinimumHeight)
        setMeasuredDimension(width, height)
        if (childCount == 0) return
        val child = getChildAt(0)
        val rotated = contentRotation % 2 == 1
        val childWidth = if (rotated) height else width
        val childHeight = if (rotated) width else height
        child.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val child = getChildAt(0)
        val width = right - left
        val height = bottom - top
        val childLeft = (width - child.measuredWidth) / 2
        val childTop = (height - child.measuredHeight) / 2
        child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        child.pivotX = child.measuredWidth / 2f
        child.pivotY = child.measuredHeight / 2f
        child.rotation = contentRotation * 90f
        hasAppliedContentLayout = child.measuredWidth > 0 && child.measuredHeight > 0 && child.rotation == contentRotation * 90f
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
    override fun generateLayoutParams(params: LayoutParams?) = LayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams?) = params != null

    private fun measuredSize(spec: Int, minimum: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec).coerceAtLeast(minimum)
        else -> minimum
    }
}
