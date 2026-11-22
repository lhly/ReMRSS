package top.zainzh.rearscreen

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.window.WindowDialog

/** Shared visual contract for every application settings page. */
internal object AppPalette {
    @Composable fun background() = colorResource(R.color.remrss_background)
    @Composable fun text() = colorResource(R.color.remrss_foreground)
    @Composable fun secondary() = colorResource(R.color.remrss_secondary)
    @Composable fun accent() = colorResource(R.color.remrss_primary)
    @Composable fun destructive() = colorResource(R.color.remrss_destructive)
    @Composable fun onDestructive() = colorResource(R.color.remrss_on_destructive)
    @Composable fun navigationSurface() = colorResource(R.color.remrss_navigation_surface)
}

@Composable internal fun AppTheme(content: @Composable () -> Unit) {
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme(primary = AppPalette.accent(), onPrimary = Color(0xFF0E213D))
        else lightColorScheme(primary = AppPalette.accent()), content = content)
}

internal fun ComponentActivity.miuixContent(content: @Composable () -> Unit) {
    prepareMiuixWindow()
    setContentView(miuixComposeView(content))
}

internal fun ComponentActivity.prepareMiuixWindow() {
    SharedActivityTransitions.apply(this)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
    }
}

/** Shared themed Compose host for native View containers that need their own measured bounds. */
internal fun ComponentActivity.miuixComposeView(content: @Composable () -> Unit): ComposeView = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AppTheme { content() } }
    }

@Composable internal fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(26.dp), clip = false),
        cornerRadius = 26.dp, insideMargin = PaddingValues(0.dp), content = content)
}

@Composable internal fun AppSection(title: String) {
    Text(title, fontSize = 13.sp, color = AppPalette.secondary(), fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp).semantics { heading() })
}

@Composable internal fun AppDivider() {
    Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(.5.dp)
        .background(AppPalette.secondary().copy(alpha = .12f)))
}

/** One action group: matching button geometry, with enough room for the actual labels. */
@Composable internal fun AppActionPair(
    secondary: String, onSecondary: () -> Unit,
    primary: String, onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryModifier: Modifier = Modifier, primaryModifier: Modifier = Modifier,
    secondaryEnabled: Boolean = true, primaryEnabled: Boolean = true,
    destructive: Boolean = false, emphasizePrimary: Boolean = true,
) {
    val primaryColors = when {
        destructive -> ButtonDefaults.textButtonColorsPrimary().copy(
            color = AppPalette.destructive(), textColor = AppPalette.onDestructive())
        emphasizePrimary -> ButtonDefaults.textButtonColorsPrimary()
        else -> ButtonDefaults.textButtonColors()
    }
    val buttons: @Composable (Boolean, Modifier) -> Unit = { main, layout ->
        TextButton(if (main) primary else secondary, if (main) onPrimary else onSecondary,
            layout.then(if (main) primaryModifier else secondaryModifier),
            enabled = if (main) primaryEnabled else secondaryEnabled, minHeight = 48.dp, cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = if (main) primaryColors else ButtonDefaults.textButtonColors())
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measure = rememberTextMeasurer()
        val style = MiuixTheme.textStyles.main.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
        val width = with(LocalDensity.current) {
            maxOf(measure.measure(AnnotatedString(secondary), style, softWrap = false).size.width,
                measure.measure(AnnotatedString(primary), style, softWrap = false).size.width).toDp()
        }
        if ((maxWidth - 8.dp) / 2 >= width + 32.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons(false, Modifier.weight(1f))
                buttons(true, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons(true, Modifier.fillMaxWidth())
                buttons(false, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Keep decisions in view on short windows and at large system font sizes. */
@Composable internal fun AppDialogDecisionContent(
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val maximum = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f }
    Column(modifier.fillMaxWidth().heightIn(max = maximum),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .testTag("dialog_decision_scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
        footer()
    }
}

/** A consent heading and its decision stay visible while the disclosures scroll. */
@Composable internal fun AppConsentPage(
    title: String, modifier: Modifier = Modifier, footer: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        AppDecisionLayout(Modifier.widthIn(max = 680.dp).fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp), bodyModifier = modifier,
            heading = { AppDecisionHeading(title) }, footer = footer, body = body)
    }
}

/** The body yields height to the heading and actions; the window owns the backdrop. */
@Composable internal fun AppDecisionLayout(
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    heading: (@Composable () -> Unit)? = null,
    spacing: Dp = 14.dp,
    footer: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        heading?.invoke()
        Column(bodyModifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(spacing), content = body)
        footer()
    }
}

@Composable internal fun AppDecisionHeading(title: String, fontSize: TextUnit = 22.sp) {
    Text(title, Modifier.semantics { heading() }, fontSize = fontSize,
        fontWeight = FontWeight.SemiBold, color = AppPalette.text())
}

// Match the existing drawer's ease-out curve. Compose applies the system duration scale.
internal val settingsRevealEasing = CubicBezierEasing(.23f, 1f, .32f, 1f)
internal const val SETTINGS_ENTER_MS = 200
internal const val SETTINGS_EXIT_MS = 150

@Composable internal fun AppSettingsReveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible = visible,
        enter = expandVertically(tween(SETTINGS_ENTER_MS, easing = settingsRevealEasing), expandFrom = Alignment.Top) +
                fadeIn(tween(SETTINGS_ENTER_MS, easing = settingsRevealEasing)),
        exit = shrinkVertically(tween(SETTINGS_EXIT_MS, easing = settingsRevealEasing), shrinkTowards = Alignment.Top) +
                fadeOut(tween(SETTINGS_EXIT_MS, easing = settingsRevealEasing))) {
        Column(Modifier.fillMaxWidth().inactiveInteractionIsolation(!visible), content = content)
    }
}

@Composable internal fun AppRow(title: String, summary: String?, modifier: Modifier = Modifier,
    enabled: Boolean = true, action: (() -> Unit)? = null, value: String? = null, trailing: @Composable (() -> Unit)? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    AppRowContent(title, summary, modifier, enabled, interactionSource, action, action != null, value, trailing)
}

@Composable internal fun AppToggle(title: String, summary: String?, checked: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val toggleModifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch,
        interactionSource = interactionSource, indication = null, onValueChange = onChange)
    AppRowContent(title, summary, toggleModifier, enabled, interactionSource, action = null, observePress = true, value = null) {
        Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable private fun AppRowContent(title: String, summary: String?, modifier: Modifier,
    enabled: Boolean, interactionSource: MutableInteractionSource, action: (() -> Unit)?,
    observePress: Boolean, value: String?, trailing: @Composable (() -> Unit)?) {
    val pressed by interactionSource.collectIsPressedAsState()
    var pointerPressed by remember { mutableStateOf(false) }
    val pointerPressObserver = if (observePress) Modifier.pointerInput(enabled) {
        if (!enabled) {
            pointerPressed = false
        } else {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                pointerPressed = true
                var previousPosition = down.position
                try {
                    var stillDown = true
                    while (stillDown) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val tracked = event.changes.firstOrNull { it.id == down.id }
                        val moved = tracked != null && tracked.position != previousPosition
                        if (tracked == null || !tracked.pressed ||
                            tracked.position.x < 0f || tracked.position.y < 0f ||
                            tracked.position.x > size.width.toFloat() || tracked.position.y > size.height.toFloat() ||
                            (moved && tracked.isConsumed)) {
                            pointerPressed = false
                        }
                        if (tracked != null) previousPosition = tracked.position
                        stillDown = tracked?.pressed == true
                    }
                } finally {
                    pointerPressed = false
                }
            }
        }
    } else Modifier
    val pressProgress by animateFloatAsState(
        targetValue = if (enabled && (pressed || pointerPressed)) 1f else 0f,
        animationSpec = if (enabled && (pressed || pointerPressed)) androidx.compose.animation.core.snap()
            else tween(130, easing = settingsRevealEasing),
        label = "app_row_press",
    )
    Row(modifier.fillMaxWidth().then(pointerPressObserver).then(
        Modifier.background(AppPalette.text().copy(alpha = .055f * pressProgress), RoundedCornerShape(20.dp))
    ).then(if (action != null) Modifier.clickable(enabled = enabled, interactionSource = interactionSource,
        indication = null, onClick = action) else Modifier)
        .heightIn(min = 64.dp).padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppPalette.text())
            if (!value.isNullOrEmpty()) Text(value, fontSize = 14.sp, color = AppPalette.accent())
            if (!summary.isNullOrEmpty()) Text(summary, fontSize = 13.sp, color = AppPalette.secondary())
        }
        if (trailing != null) trailing()
        else if (action != null) AppNavigationChevron()
    }
}

internal fun Modifier.inactiveInteractionIsolation(inactive: Boolean): Modifier = if (!inactive) this else this
    .focusProperties { canFocus = false }
    .clearAndSetSemantics { }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false).consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

@Composable internal fun AppBackButton(back: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.ui_back)
    val feedback = remember { NavigationPress() }
    val press = animateFloatAsState(if (feedback.down) 1f else 0f,
        if (feedback.down) androidx.compose.animation.core.snap() else spring(dampingRatio = 1f, stiffness = 650f),
        label = "back_press")
    val ink = AppPalette.text()
    IconButton(onClick = back, modifier = modifier.size(48.dp).then(feedback.modifier)
        .background(ink.copy(alpha = .07f * press.value), CircleShape)
        .border(1.dp, ink.copy(alpha = .12f), CircleShape)
        .clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
        onClick { back(); true }
    }, minWidth = 48.dp, minHeight = 48.dp,
        backgroundColor = Color.Transparent) {
        Icon(painterResource(R.drawable.ic_navigation_back), contentDescription = null,
            modifier = Modifier.size(26.dp).graphicsLayer { scaleX = 1f - .04f * press.value; scaleY = scaleX },
            tint = ink)
    }
}

@Composable internal fun AppPageHeader(title: String, back: () -> Unit, backTag: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppBackButton(back, Modifier.testTag(backTag))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppPalette.text(),
            modifier = Modifier.weight(1f).semantics { heading() })
    }
}

@Composable internal fun PageFrame(title: String, back: () -> Unit, backTag: String,
    scrollState: LazyListState? = null,
    content: @Composable (Modifier, Dp) -> Unit) {
    AppPageWindow { AppPageContent(title, back, backTag, scrollState = scrollState, content = content) }
}

/** Standalone Activity adapter: the main settings shell already owns these insets. */
@Composable internal fun AppPageWindow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(AppPalette.background()).safeDrawingPadding().imePadding()) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun AppPageContent(title: String, back: () -> Unit, backTag: String,
    modifier: Modifier = Modifier, scrollState: LazyListState? = null, gridState: LazyGridState? = null,
    bodyScrollState: ScrollState? = null, fadeEnabled: Boolean = true,
    content: @Composable (Modifier, Dp) -> Unit) {
    val background = AppPalette.background()
    val density = LocalDensity.current
    val host = LocalView.current
    var insetsReady by remember(host) { mutableStateOf(host.isLaidOut && host.rootWindowInsets != null) }
    DisposableEffect(host) {
        val observer = host.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (host.rootWindowInsets != null) {
                    insetsReady = true
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                }
                return true
            }
        }
        if (!insetsReady) { observer.addOnPreDrawListener(listener); host.requestApplyInsets() }
        onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
    }
    // A short restored list must not be clamped against the pre-insets viewport.
    if (!insetsReady) return
    Column(modifier.fillMaxSize().background(background), horizontalAlignment = Alignment.CenterHorizontally) {
        AppPageHeader(title, back, backTag, Modifier.widthIn(max = 680.dp)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides PageBodyFocus) {
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.TopCenter) {
                val viewport = Modifier.widthIn(max = 680.dp).fillMaxSize()
                val fade = when {
                    !fadeEnabled -> viewport
                    scrollState != null -> viewport.pageBodyFade(background) {
                        scrollFadeOpacity(scrollState.firstVisibleItemIndex, scrollState.firstVisibleItemScrollOffset,
                            scrollState.layoutInfo.beforeContentPadding)
                    }
                    gridState != null -> viewport.pageBodyFade(background) {
                        scrollFadeOpacity(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
                            gridState.layoutInfo.beforeContentPadding)
                    }
                    bodyScrollState != null -> viewport.pageBodyFade(background) {
                        scrollFadeOpacity(0, bodyScrollState.value, with(density) { 12.dp.roundToPx() })
                    }
                    else -> viewport
                }
                content(fade, 0.dp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private object PageBodyFocus : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0f -> offset
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}

/** The body alone has a fade; enable it for reading lists, not focused forms. */
internal fun Modifier.pageBodyFade(
    background: Color,
    depth: Dp = 28.dp,
    trailing: Boolean = false,
    scrollOpacity: () -> Float,
) = drawWithCache {
    val height = minOf(depth.toPx(), size.height)
    val y = if (trailing) size.height - height else 0f
    val gradient = if (trailing) {
        Brush.verticalGradient(0f to background.copy(alpha = 0f), .6f to background.copy(alpha = .68f),
            1f to background, startY = y, endY = size.height)
    } else {
        Brush.verticalGradient(0f to background, .4f to background.copy(alpha = .68f),
            1f to background.copy(alpha = 0f), endY = height)
    }
    onDrawWithContent {
        drawContent()
        val opacity = scrollOpacity()
        if (height > 0f && opacity > 0f) {
            drawRect(gradient, topLeft = Offset(0f, y), size = Size(size.width, height), alpha = opacity)
        }
    }
}

private fun scrollFadeOpacity(index: Int, offset: Int, startPadding: Int): Float =
    if (index > 0) 1f else (offset.toFloat() / startPadding.coerceAtLeast(1)).coerceIn(0f, 1f)

// Scroll a keyboard-focused row clear of the rail without shortening the drawn viewport.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainTabFocus(tailPadding: Dp, content: @Composable () -> Unit) {
    val tailPx = with(LocalDensity.current) { tailPadding.toPx() }
    val spec = remember(tailPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val clearEnd = (containerSize - tailPx).coerceAtLeast(0f)
                return when {
                    offset < 0f -> offset
                    offset + size > clearEnd -> offset + size.coerceAtMost(clearEnd) - clearEnd
                    else -> 0f
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

internal fun Bundle?.listState(key: String) = LazyListState(this?.getInt(key + "_index", 0) ?: 0, this?.getInt(key + "_offset", 0) ?: 0)
internal fun Bundle.saveList(key: String, state: LazyListState) {
    putInt(key + "_index", state.firstVisibleItemIndex); putInt(key + "_offset", state.firstVisibleItemScrollOffset)
}
