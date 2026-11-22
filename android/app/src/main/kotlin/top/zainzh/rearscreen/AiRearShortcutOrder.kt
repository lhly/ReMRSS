package top.zainzh.rearscreen

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Icon

private val orderEaseOut = CubicBezierEasing(.23f, 1f, .32f, 1f)

/** Only the bounded, non-lazy selected group is movable. The app catalog remains lazy. */
internal class AiRearShortcutOrder(private val gap: Float) {
    private data class Drag(val key: Int, val initialGroupY: Float)
    private val heights = mutableStateMapOf<Int, Int>()
    private var gripY by mutableFloatStateOf(0f)
    private var delta by mutableFloatStateOf(0f)
    private var drag by mutableStateOf<Drag?>(null)
    var groupY by mutableFloatStateOf(0f)
    var viewportTop by mutableFloatStateOf(0f)
    var viewportBottom by mutableFloatStateOf(0f)
    val movingKey get() = drag?.key

    fun measure(key: Int, height: Int) { heights[key] = height }

    fun retain(keys: List<Int>) { heights.keys.filterNot(keys::contains).forEach { heights.remove(it) } }

    fun start(key: Int, rootY: Float, keys: List<Int>) {
        if (drag != null || keys.size < 2 || key !in keys || keys.any { heights[it] == null }) return
        gripY = rootY
        delta = 0f
        drag = Drag(key, groupY)
    }

    fun move(key: Int, y: Float) { if (movingKey == key) delta += y }

    fun stop(key: Int) { if (movingKey == key) drag = null }

    data class Placement(val from: Int, val to: Int, val topDelta: Float, val space: Float) {
        fun shift(index: Int): Float = when {
            index == from -> topDelta
            from < to && index in (from + 1)..to -> -space
            from > to && index in to until from -> space
            else -> 0f
        }
    }

    fun placement(keys: List<Int>): Placement? {
        val gesture = drag ?: return null
        val from = keys.indexOf(gesture.key)
        if (from == -1) return null
        val sizes = keys.map { heights[it]?.toFloat() ?: return null }
        val starts = sizes.runningFold(0f) { top, size -> top + size + gap }
        val height = sizes[from]
        val desiredTop = starts[from] + delta + gesture.initialGroupY - groupY
        val movingTop = desiredTop.coerceIn(0f, (starts.last() - gap - height).coerceAtLeast(0f))
        val center = desiredTop + height / 2
        val to = if (desiredTop < starts[from]) {
            // A row slides aside as the grabbed center enters it, rather than requiring
            // an extra touch-slop distance beyond the neighboring center.
            (0 until from).firstOrNull { center < starts[it] + sizes[it] } ?: from
        } else {
            (keys.lastIndex downTo from + 1).firstOrNull { center > starts[it] } ?: from
        }
        return Placement(from, to, movingTop - starts[from], height + gap)
    }

    fun autoscroll(keys: List<Int>, edge: Float, step: Float): Float {
        val finger = gripY + delta
        val last = groupY + keys.sumOf { heights[it] ?: 0 } + gap * (keys.size - 1)
        return when {
            finger < viewportTop + edge && groupY < viewportTop ->
                -minOf(viewportTop - groupY, step * ((viewportTop + edge - finger) / edge).coerceIn(0f, 1f))
            finger > viewportBottom - edge && last > viewportBottom ->
                minOf(last - viewportBottom, step * ((finger - viewportBottom + edge) / edge).coerceIn(0f, 1f))
            else -> 0f
        }
    }
}

@Composable internal fun KeepShortcutDragInView(order: AiRearShortcutOrder, keys: List<Int>, list: LazyListState) {
    val density = LocalDensity.current
    val edge = with(density) { 36.dp.toPx() }
    val nominalStep = with(density) { 10.dp.toPx() }
    LaunchedEffect(order.movingKey, list, keys) {
        if (order.movingKey == null) return@LaunchedEffect
        snapshotFlow { order.autoscroll(keys, edge, nominalStep) != 0f }.collectLatest { active ->
            if (!active) return@collectLatest
            var previousFrame = withFrameNanos { it }
            while (order.movingKey != null) {
                val now = withFrameNanos { it }
                val step = nominalStep * ((now - previousFrame) / 16_666_667f).coerceIn(0f, 2f)
                previousFrame = now
                val delta = order.autoscroll(keys, edge, step)
                if (delta == 0f || list.scrollBy(delta) == 0f) break
            }
        }
    }
}

@Composable internal fun shortcutRowPlacement(
    order: AiRearShortcutOrder, key: Int, index: Int, placement: AiRearShortcutOrder.Placement?,
): Modifier {
    val grabbed = order.movingKey == key
    val shift by animateFloatAsState(
        targetValue = if (grabbed) 0f else placement?.shift(index) ?: 0f,
        animationSpec = if (placement == null || grabbed) snap() else tween(150, easing = orderEaseOut),
        label = "rear_shortcut_order",
    )
    return Modifier.onSizeChanged { order.measure(key, it.height) }
        .zIndex(if (grabbed) 1f else 0f)
        .graphicsLayer {
            translationY = if (grabbed) placement?.topDelta ?: 0f else shift
        }
}

@Composable internal fun ShortcutDragHandle(
    order: AiRearShortcutOrder, key: Int, keys: List<Int>, index: Int, name: String,
    place: (key: Int, position: Int) -> Unit,
) {
    val label = stringResource(R.string.ai_rear_reorder_app, name)
    val up = stringResource(R.string.ai_rear_move_up)
    val down = stringResource(R.string.ai_rear_move_down)
    val onPlace = rememberUpdatedState(place)
    val latestKeys = rememberUpdatedState(keys)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(Modifier.size(48.dp).testTag("ai_rear_drag_$key")
        .onGloballyPositioned { coordinates = it }
        .semantics {
            contentDescription = label
            customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction(up) { onPlace.value(key, index - 1); true })
                if (index < keys.lastIndex) add(CustomAccessibilityAction(down) { onPlace.value(key, index + 1); true })
            }
        }
        .pointerInput(order, key) {
            detectDragGestures(onDragStart = { point ->
                coordinates?.takeIf { it.isAttached }?.localToRoot(point)?.y?.let { order.start(key, it, latestKeys.value) }
            }, onDragEnd = {
                if (order.movingKey == key) {
                    val target = order.placement(latestKeys.value)?.to
                    order.stop(key)
                    target?.let { onPlace.value(key, it) }
                }
            }, onDragCancel = { order.stop(key) }) { change, amount ->
                change.consume()
                order.move(key, amount.y)
            }
        }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_ai_rear_reorder), null,
            Modifier.size(20.dp), AppPalette.secondary())
    }
}

internal fun Modifier.shortcutOrderGroup(order: AiRearShortcutOrder) = onGloballyPositioned {
    order.groupY = it.positionInRoot().y
}

internal fun Modifier.shortcutOrderViewport(order: AiRearShortcutOrder, header: Float) = onGloballyPositioned {
    val y = it.positionInRoot().y
    order.viewportTop = y + header
    order.viewportBottom = y + it.size.height
}
