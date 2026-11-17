package top.zainzh.rearscreen

import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.*
import top.yukonga.miuix.kmp.blur.sensor.DeviceTilt
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun shouldObserveNavigationTilt(
    windowFocused: Boolean,
    lifecycleResumed: Boolean,
    interacting: Boolean,
): Boolean = windowFocused && lifecycleResumed && interacting

internal fun navigationLightAngle(gravityX: Float, gravityY: Float): Float =
    if (gravityX * gravityX + gravityY * gravityY > .01f)
        (atan2(gravityY, gravityX) / (PI / 60)).roundToInt() * (PI / 60).toFloat()
    else (-PI / 2).toFloat()

/** The content capture excludes navigation overlays, avoiding recursive glass sampling. */
@RequiresApi(33)
@Composable
internal fun BlurredNavigationFrame(
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
    navigation: @Composable BoxScope.() -> Unit,
) {
    val background = AppPalette.background()
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val tabsBackdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycleResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val press = remember(view, lifecycle) {
        NavigationPress { view.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }
    DisposableEffect(view, lifecycle, press) {
        // Invalidate the current gesture on the actual edge, even if focus
        // returns before Compose observes the intermediate state.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) press.cancel()
        }
        val lifeListener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) press.cancel()
        }
        val tree = view.viewTreeObserver
        tree.addOnWindowFocusChangeListener(focusListener)
        lifecycle.addObserver(lifeListener)
        onDispose {
            val liveTree = if (tree.isAlive) tree else view.viewTreeObserver
            if (liveTree.isAlive) liveTree.removeOnWindowFocusChangeListener(focusListener)
            lifecycle.removeObserver(lifeListener)
            press.cancel()
        }
    }
    // During idle, keep the last measured light direction without requesting sensor events.
    val angle = remember { mutableFloatStateOf((-PI / 2).toFloat()) }
    if (shouldObserveNavigationTilt(windowFocused, lifecycleResumed, press.down)) {
        val tilt = rememberDeviceTilt()
        LaunchedEffect(tilt) {
            snapshotFlow {
                tilt.value.takeUnless { it == DeviceTilt.Zero }?.let {
                    navigationLightAngle(it.gravityX, it.gravityY)
                }
            }.filterNotNull().collect { angle.floatValue = it }
        }
    }
    val material = remember(backdrop, tabsBackdrop, dark) { GlassNavigationMaterial(backdrop, tabsBackdrop, dark, angle) }
    CompositionLocalProvider(LocalNavigationMaterial provides material) {
        Box(modifier.then(press.modifier)) { content(Modifier.layerBackdrop(backdrop)); navigation() }
    }
}

@RequiresApi(33)
private class GlassNavigationMaterial(
    private val backdrop: Backdrop,
    private val tabs: LayerBackdrop,
    private val dark: Boolean,
    private val angle: State<Float>,
) : NavigationMaterial {
    override val samplesContent = true
    private val combined = NavigationCombinedBackdrop(backdrop, tabs)
    override fun Modifier.captureTabs() = this.alpha(0f).layerBackdrop(tabs)

    @Composable override fun Modifier.surface(shape: Shape, press: () -> Float, indicator: Boolean, compact: Boolean): Modifier {
        val surface = AppPalette.navigationSurface()
        val tint = surface.copy(alpha = if (dark) .44f else .38f)
        return this.drawBackdrop(
            backdrop = if (indicator) combined else backdrop,
            shape = { shape },
            effects = {
                padding = maxOf(padding, if (compact) 20.dp.toPx() else 40.dp.toPx())
                if (indicator) {
                    val p = .12f + .88f * press()
                    navigationLens(10.dp.toPx() * p, 14.dp.toPx() * p, depthEffect = true, chromaticAberration = .3f * press())
                } else {
                    colorControls(saturation = 1.5f)
                    blur(4.dp.toPx(), 4.dp.toPx())
                    val depth = if (compact) 8.dp.toPx() else 24.dp.toPx()
                    navigationLens(depth, depth * (1f + .15f * press()))
                }
            },
            highlight = {
                Highlight(width = .8.dp, alpha = if (indicator) .35f + .65f * press() else .75f,
                    style = BloomStroke(color = Color.White.copy(alpha = .12f), innerBlurRadius = 2.dp,
                        primaryLight = LightSource(LightPosition(.5f + cos(angle.value), .7f + sin(angle.value), -.05f), Color.White, 1f),
                        secondaryLight = LightSource(LightPosition(.5f, .8f, -.5f), Color.White, .4f), dualPeak = true))
            },
            onDrawSurface = {
                if (indicator) {
                    drawRect((if (dark) Color.White else Color.Black).copy(alpha = .08f * (1f - press())))
                } else drawRect(tint)
            },
        ).innerShadow(shape) {
            radius = (if (compact) 3.dp else 6.dp).toPx()
            color = Color.Black
            alpha = (if (dark) .12f else .06f) + .05f * press()
            offset = androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx())
        }
    }
}

/** Composite the emphasized tabs over the page before the indicator refracts them. */
@RequiresApi(33)
private class NavigationCombinedBackdrop(private val page: Backdrop, private val tabs: Backdrop) : Backdrop {
    override val isCoordinatesDependent = true
    override val offsetResidualX get() = page.offsetResidualX
    override val offsetResidualY get() = page.offsetResidualY
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Int) {
        with(page) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(tabs) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}
