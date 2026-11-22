package top.zainzh.rearscreen

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One interruptible push/pop presentation, shared by settings windows and hosted routes. */
internal class AppRouteMotion(
    private val activity: Context,
    initialRoute: Int,
    private val parentOf: (Int) -> Int? = { NavigationMotion.parentRoute(it) },
) {
    var transitionPair by mutableStateOf<NavigationRoutePair?>(null)
        private set
    private var backPreviewProgress by mutableStateOf<Float?>(null)
    private var progressOverride by mutableFloatStateOf(Float.NaN)
    private var lastDisplayedProgress by mutableFloatStateOf(if (parentOf(initialRoute) != null) 1f else 0f)
    private val displayedMotion = MotionVelocityTracker()
    private var motionDurationScale by mutableFloatStateOf(readMotionDurationScale())
    private var animationGeneration by mutableIntStateOf(0)
    private var animationRequest by mutableStateOf<MotionAnimationRequest?>(null)
    private val backMotion = NavigationMotion()

    val durationScale get() = motionDurationScale

    fun parentRoute(route: Int): Int? = parentOf(route)
    private val secondaryPageEasing = CubicBezierEasing(.23f, 1f, .32f, 1f)
    private data class MotionAnimationRequest(
        val generation: Int,
        val start: Float,
        val target: Float,
        val durationMillis: Int,
        val initialVelocity: Float = 0f,
        val springSettle: Boolean = false,
        val immediate: Boolean = false,
    )

    private fun readMotionDurationScale(): Float =
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    private fun requestMotion(
        start: Float,
        target: Float,
        durationMillis: Int,
        velocity: Float = 0f,
        springSettle: Boolean = false,
        immediate: Boolean = motionDurationScale == 0f,
    ) {
        val clampedStart = NavigationMotion.clampProgress(start)
        val clampedTarget = NavigationMotion.clampProgress(target)
        progressOverride = clampedStart
        animationRequest = MotionAnimationRequest(++animationGeneration, clampedStart, clampedTarget,
            durationMillis, velocity, springSettle, immediate)
    }
    @Composable fun Progress(route: Int, onPopSettled: () -> Unit = {}): Float {
        val settledCallback by rememberUpdatedState(onPopSettled)
        val motionProgress = remember { Animatable(lastDisplayedProgress) }
        val routeMotionProgress = backPreviewProgress ?: progressOverride.takeIf { it.isFinite() } ?: motionProgress.value
        LaunchedEffect(motionProgress) {
            snapshotFlow { motionProgress.value }.collect {
                lastDisplayedProgress = it
                displayedMotion.sample(it, android.os.SystemClock.uptimeMillis())
            }
        }
        LaunchedEffect(animationRequest?.generation) {
            val request = animationRequest ?: return@LaunchedEffect
            motionProgress.snapTo(request.start)
            lastDisplayedProgress = request.start
            progressOverride = Float.NaN
            if (request.immediate || motionDurationScale == 0f) {
                motionProgress.snapTo(request.target)
            } else if (request.springSettle) {
                motionProgress.animateTo(request.target,
                    spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = request.initialVelocity)
            } else {
                motionProgress.animateTo(request.target,
                    tween(request.durationMillis, easing = secondaryPageEasing))
            }
            if (animationRequest?.generation == request.generation) {
                lastDisplayedProgress = request.target
                progressOverride = Float.NaN
                transitionPair = null
                if (request.target == 0f) settledCallback()
                animationRequest = null
            }
        }
        LaunchedEffect(motionDurationScale) {
            if (motionDurationScale != 0f) return@LaunchedEffect
            if (backMotion.isPreviewing) {
                backMotion.cancelBackPreview()
                backPreviewProgress = null
            }
            val pair = transitionPair ?: return@LaunchedEffect
            val target = if (route == pair.childRoute) 1f else 0f
            requestMotion(lastDisplayedProgress, target, durationMillis = 0, immediate = true)
        }
        DisposableEffect(activity) {
            val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { motionDurationScale = readMotionDurationScale() }
                override fun onChange(selfChange: Boolean, changed: android.net.Uri?) { motionDurationScale = readMotionDurationScale() }
            }
            activity.contentResolver.registerContentObserver(uri, false, observer)
            onDispose { activity.contentResolver.unregisterContentObserver(observer) }
        }
        return routeMotionProgress
    }

    fun showPage(previous: Int, target: Int) {
        val commit = if (backMotion.isPreviewing && parentOf(previous) == target)
            backMotion.commitBackPreview() else null
        if (backMotion.isPreviewing && commit == null) {
            backMotion.cancelBackPreview()
            backPreviewProgress = null
        }
        val pair = transitionPair?.takeIf { (target == it.parentRoute && previous == it.childRoute) ||
                (target == it.childRoute && previous == it.parentRoute) }
            ?: NavigationMotion.pairBetween(previous, target, parentOf)
        val continuingTransition = pair != null && pair == transitionPair && animationRequest != null
        val start = backPreviewProgress ?: if (pair != null && pair == transitionPair)
            lastDisplayedProgress else if (pair != null && previous == pair.childRoute) 1f else 0f
        backPreviewProgress = null
        if (pair != null) {
            transitionPair = pair
            val targetProgress = if (target == pair.childRoute) 1f else 0f
            requestMotion(start, targetProgress, if (targetProgress == 1f) 260 else 220,
                velocity = commit?.velocity ?: if (continuingTransition) displayedMotion.velocity else 0f,
                springSettle = commit != null || continuingTransition)
        } else {
            transitionPair = null
            progressOverride = Float.NaN
            animationRequest = null
        }
    }
    fun startBackPreview(page: Int, target: Int, initialProgress: Float) {
        val pair = transitionPair?.takeIf { it.parentRoute == target && it.childRoute == page }
            ?: NavigationMotion.pairBetween(page, target, parentOf)
            ?: return
        val origin = if (pair == transitionPair) lastDisplayedProgress else if (page == pair.childRoute) 1f else 0f
        transitionPair = pair
        animationRequest = null
        progressOverride = Float.NaN
        val start = backMotion.beginBackPreview(origin)
        backPreviewProgress = start
        lastDisplayedProgress = start
        if (motionDurationScale != 0f && initialProgress > 0f) updateBackPreview(initialProgress, 0L)
    }
    fun updateBackPreview(progress: Float, frameTimeMillis: Long) {
        if (motionDurationScale == 0f) return
        backMotion.updateBackProgress(progress, frameTimeMillis)?.let {
            backPreviewProgress = it
            lastDisplayedProgress = it
        }
    }
    fun cancelBackPreview(page: Int) {
        val settle = backMotion.cancelBackPreview() ?: return
        backPreviewProgress = null
        val endpoint = transitionPair?.let { if (page == it.childRoute) 1f else 0f } ?: settle.progress
        requestMotion(lastDisplayedProgress, endpoint, 220, settle.velocity, springSettle = true)
    }
}

internal fun Modifier.retreatingRoute(progress: Float, direction: Float): Modifier = graphicsLayer {
    val retreat = .018f * progress
    scaleX = 1f - retreat
    scaleY = 1f - retreat
    translationX = -size.width * .012f * progress * direction
    alpha = 1f - .07f * progress
}

internal fun Modifier.enteringRoute(progress: Float, direction: Float): Modifier = graphicsLayer {
    translationX = size.width * (1f - progress) * direction
}

internal fun inactiveRouteModifier(inactive: Boolean): Modifier = if (!inactive) Modifier else Modifier
        .focusProperties { canFocus = false }
        .semantics { hideFromAccessibility() }
        .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false).consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }


/** A standalone workflow has the same pinned panes and motion, without a Tab rail. */
@Composable
internal fun AppStandaloneRoutes(
    root: Int,
    route: Int,
    motion: AppRouteMotion,
    onPopSettled: () -> Unit = {},
    content: @Composable (route: Int, active: Boolean) -> Unit,
) {
    AppPageWindow {
        val progress = motion.Progress(route, onPopSettled)
        val pair = motion.transitionPair
        val child = pair?.childRoute ?: route.takeIf { it != root }
        val position = if (pair == null) 1f else NavigationMotion.clampProgress(progress)
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        Box(Modifier.fillMaxSize().clipToBounds()) {
            val parent = pair?.parentRoute ?: (child?.let(motion::parentRoute) ?: root)
            val parentMotion = if (child == null) Modifier else Modifier.retreatingRoute(position, direction)
                .testTag("navigation_motion_parent")
            AppRoutePane(route == parent, parentMotion) { content(parent, route == parent) }
            if (child != null) {
                AppRoutePane(route == child, Modifier.enteringRoute(position, direction)
                    .testTag("navigation_motion_child")) { content(child, route == child) }
            }
        }
    }
}

@Composable
internal fun AppRoutePane(
    active: Boolean,
    motion: Modifier,
    contentInsets: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    // The moving page is opaque to its edge, including the safe-area band.
    // Only its contents are inset, so the rail cannot peek under the page.
    Box(Modifier.fillMaxSize().then(motion).background(AppPalette.background())
        .then(inactiveRouteModifier(!active)), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(contentInsets).widthIn(max = 680.dp).fillMaxSize()
            .then(if (active) Modifier else Modifier.clearAndSetSemantics {})) {
            content()
        }
    }
}
