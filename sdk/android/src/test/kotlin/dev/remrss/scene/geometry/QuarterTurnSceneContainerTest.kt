package dev.remrss.scene.geometry

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuarterTurnSceneContainerTest {
    private class TouchProbe(context: android.content.Context) : View(context) {
        var down: Pair<Float, Float>? = null
        data class Pointer(val id: Int, val x: Float, val y: Float)
        data class SeenEvent(val action: Int, val pointers: List<Pointer>)
        val seen = mutableListOf<SeenEvent>()
        init { isClickable = true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) down = event.x to event.y
            seen += SeenEvent(event.actionMasked, (0 until event.pointerCount).map { i -> Pointer(event.getPointerId(i), event.getX(i), event.getY(i)) })
            return true
        }
    }

    @Test fun nativeMeasureLayoutAndHitTransformMatchAllFourQuarterTurns() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (turn in 0..3) {
            val host = QuarterTurnSceneContainer(activity).apply { contentRotation = turn }
            val child = TouchProbe(activity)
            host.addView(child)
            val width = 120; val height = 80
            host.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, width, height)
            assertEquals(if (turn % 2 == 0) width else height, child.measuredWidth)
            assertEquals(if (turn % 2 == 0) height else width, child.measuredHeight)
            assertEquals(turn * 90f, child.rotation)

            val logicalX = 19f; val logicalY = 27f
            val (screenX, screenY) = SceneViewportGeometry.fromWindow(
                PixelRect(0, 0, width, height), PixelRect(0, 0, width, height), PixelInsets.NONE,
                displayRotation = 0, contentRotation = turn, effectiveDensityDpi = 320
            ).contentToWindow(logicalX, logicalY, SceneRegion.FULL)
            val now = android.os.SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, screenX, screenY, 0)
            val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, screenX, screenY, 0)
            assertTrue("turn=$turn should native-hit the transformed child", host.dispatchTouchEvent(down))
            host.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
            val actual = requireNotNull(child.down)
            assertEquals("turn=$turn child x", logicalX, actual.first, 0.01f)
            assertEquals("turn=$turn child y", logicalY, actual.second, 0.01f)
            host.removeView(child)
        }
    }

    @Test fun nativeMultiPointerSequencePreservesPointerIdsAndTransformedCoordinatesAtEveryTurn() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (turn in 0..3) {
            val width = 120; val height = 80
            val host = QuarterTurnSceneContainer(activity).apply { contentRotation = turn }
            val child = TouchProbe(activity)
            host.addView(child)
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            val geometry = SceneViewportGeometry.fromWindow(
                PixelRect(0, 0, width, height), PixelRect(0, 0, width, height), PixelInsets.NONE,
                displayRotation = 0, contentRotation = turn, effectiveDensityDpi = 320
            )
            val now = android.os.SystemClock.uptimeMillis()
            fun event(action: Int, ids: IntArray, logicalPoints: List<Pair<Float, Float>>, time: Long): MotionEvent {
                val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(ids.size) { i ->
                    val (x, y) = geometry.contentToWindow(logicalPoints[i].first, logicalPoints[i].second, SceneRegion.FULL)
                    MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                }
                return MotionEvent.obtain(now, time, action, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            }
            val first = event(MotionEvent.ACTION_DOWN, intArrayOf(2), listOf(19f to 27f), now)
            assertTrue("turn=$turn first pointer dispatch", host.dispatchTouchEvent(first)); first.recycle()
            val secondDown = event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(2, 7), listOf(19f to 27f, 64f to 41f), now + 1)
            assertTrue("turn=$turn second pointer dispatch", host.dispatchTouchEvent(secondDown)); secondDown.recycle()
            val move = event(MotionEvent.ACTION_MOVE, intArrayOf(2, 7), listOf(23f to 29f, 69f to 45f), now + 2)
            assertTrue("turn=$turn multi-pointer move dispatch", host.dispatchTouchEvent(move)); move.recycle()
            val received = child.seen.last()
            assertEquals(MotionEvent.ACTION_MOVE, received.action)
            assertEquals(listOf(2, 7), received.pointers.map { it.id })
            assertEquals(23f, received.pointers[0].x, 0.01f)
            assertEquals(29f, received.pointers[0].y, 0.01f)
            assertEquals(69f, received.pointers[1].x, 0.01f)
            assertEquals(45f, received.pointers[1].y, 0.01f)
            host.removeView(child)
        }
    }
}
