package top.zainzh.rearscreen

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

/** Compact drawer inside the physical safe area; all input is dispatched locally. */
class RearCastMenuUi(activity: ComponentActivity, exit: Runnable, left: Runnable, right: Runnable, viewport: Runnable, dismiss: Runnable) {
    private var animationGeneration = 0
    private var operationLabel by mutableIntStateOf(0)
    private val controlsBusy get() = operationLabel != 0
    private var gestureY by mutableFloatStateOf(Float.NaN)
    private var fullViewport by mutableStateOf(false)
    private val content = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppTheme {
                BoxWithConstraints(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = !controlsBusy, onClick = dismiss::run
                )) {
                    val density = LocalDensity.current
                    val inset = with(density) { 8.dp.roundToPx() }
                    val availableHeight = with(density) { maxHeight.roundToPx() }
                    val usableWidth = (maxWidth - 16.dp).coerceAtLeast(0.dp)
                    val columns = when {
                        usableWidth >= 212.dp -> 4
                        usableWidth >= 108.dp -> 2
                        else -> 1
                    }
                    val drawerWidth = if (controlsBusy) minOf(212.dp, usableWidth) else minOf(usableWidth, (4 + columns * 52).dp)
                    val heightLimit = (maxHeight - 16.dp).coerceAtLeast(0.dp)
                    var measuredHeight by remember { mutableIntStateOf(0) }
                    val approximateHeight = minOf(heightLimit, (4 + (4 / columns) * 52).dp)
                    val drawerHeight = measuredHeight.takeIf { it > 0 } ?: with(density) { approximateHeight.roundToPx() }
                    AppCard(Modifier.align(Alignment.TopEnd).padding(end = 8.dp).width(drawerWidth)
                        .offset {
                            val center = gestureY.takeIf { it.isFinite() } ?: (availableHeight / 2f)
                            IntOffset(0, (center - drawerHeight / 2f).toInt()
                                .coerceIn(inset, (availableHeight - drawerHeight - inset).coerceAtLeast(inset)))
                        }.onSizeChanged { measuredHeight = it.height }.clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                        )) {
                        if (controlsBusy) {
                            Text(stringResource(operationLabel),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                color = AppPalette.text(), fontSize = 13.sp)
                        } else {
                            val actions = listOf(
                                Triple(R.drawable.ic_rear_cast_exit, R.string.rear_cast_menu_exit, exit),
                                Triple(R.drawable.ic_rear_cast_rotate_left, R.string.rear_cast_menu_left, left),
                                Triple(R.drawable.ic_rear_cast_rotate_right, R.string.rear_cast_menu_right, right),
                                Triple(if (fullViewport) R.drawable.ic_rear_cast_avoid_camera else R.drawable.ic_rear_cast_full,
                                    if (fullViewport) R.string.rear_cast_menu_avoid_camera else R.string.rear_cast_menu_full, viewport))
                            Column(Modifier.heightIn(max = heightLimit).verticalScroll(rememberScrollState()).padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                actions.chunked(columns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        row.forEach { (icon, label, action) -> DrawerAction(icon, label, false, action) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val view: View = android.widget.FrameLayout(activity).apply {
        visibility = View.INVISIBLE
        clipChildren = true
        addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    @Composable private fun DrawerAction(icon: Int, label: Int, busy: Boolean, action: Runnable) {
        IconButton(onClick = action::run, modifier = Modifier.size(48.dp), enabled = !busy,
            minWidth = 48.dp, minHeight = 48.dp, cornerRadius = 16.dp,
            backgroundColor = colorResource(R.color.remrss_control)) {
            Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(26.dp),
                tint = AppPalette.text().copy(alpha = if (busy) .4f else 1f))
        }
    }
    fun anchor(y: Float) { gestureY = y }
    fun showOperation(label: Int) { operationLabel = label }
    fun updateViewportMode(value: Boolean) { fullViewport = value }
    fun show(open: Boolean) {
        val generation = ++animationGeneration
        val wasVisible = view.visibility == View.VISIBLE
        content.animate().cancel()
        val distance = minOf(view.width.toFloat(), 228 * view.resources.displayMetrics.density)
        val animate = (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) && view.isAttachedToWindow
        if (!open) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { view.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            if (animate && wasVisible && view.isShown) {
                content.animate().alpha(0f).translationX(distance).setDuration(125)
                    .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f))
                    .withEndAction { if (generation == animationGeneration) view.visibility = View.INVISIBLE }.start()
            } else { view.visibility = View.INVISIBLE; content.alpha = 0f }
            return
        }
        operationLabel = 0
        view.visibility = View.VISIBLE
        if (animate) {
            if (!wasVisible) { content.alpha = 0f; content.translationX = distance }
            content.animate().alpha(1f).translationX(0f).setDuration(180)
                .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f)).start()
        } else { content.alpha = 1f; content.translationX = 0f }
    }
}
