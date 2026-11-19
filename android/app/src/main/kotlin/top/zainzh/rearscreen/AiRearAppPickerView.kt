package top.zainzh.rearscreen

import android.animation.ValueAnimator
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Predicate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Rear-only UI in the prepared task. The shelf owns no virtual display or input reader. */
internal object AiRearAppPickerView {
    private data class Content(val area: RearCastViewport, val apps: List<AiRearApp>)

    @JvmStatic
    fun create(host: ComponentActivity, remote: ITaskService, showApps: Boolean, launchFailed: Boolean,
        dismiss: Runnable, select: Predicate<ComponentName>): ComposeView = host.miuixComposeView {
        var content by remember { mutableStateOf<Content?>(null) }
        var loadFailed by remember { mutableStateOf(false) }
        var selectionFailed by remember { mutableStateOf(launchFailed) }
        LaunchedEffect(Unit) {
            if (!showApps) {
                // A launch failure needs a working back action even if the service is unavailable.
                content = withContext(Dispatchers.IO) {
                    runCatching { Content(RearCastGeometry.decode(remote.rearCastViewport), emptyList()) }.getOrNull()
                }
            } else {
                val result = withContext(Dispatchers.IO) {
                    runCatching { Content(RearCastGeometry.decode(remote.rearCastViewport), AiRearAppCatalog.list(host)) }
                }
                content = result.getOrNull()
                loadFailed = result.isFailure
            }
        }
        if (showApps) {
            AiRearAppShelf(content?.area, content?.apps, loadFailed, selectionFailed, dismiss::run) {
                val component = ComponentName.unflattenFromString(it.component)
                if (component == null || !select.test(component)) selectionFailed = true
            }
        } else {
            AiRearLaunchFeedback(content?.area, R.string.ai_rear_app_open_error,
                R.string.ai_rear_back_to_wallpaper, dismiss::run)
        }
    }

    @JvmStatic
    fun opening(host: ComponentActivity, area: RearCastViewport, cancel: Runnable): ComposeView =
        host.miuixComposeView { AiRearLaunchFeedback(area, R.string.ai_rear_cast_opening, android.R.string.cancel, cancel::run, false) }
}

private fun RearCastViewport?.safeInsets(): WindowInsets? = this?.let {
    WindowInsets(left, top, physicalWidth - right, physicalHeight - bottom)
}

@Composable internal fun AiRearAppShelf(
    area: RearCastViewport?, apps: List<AiRearApp>?, loadFailed: Boolean, selectionFailed: Boolean,
    dismiss: () -> Unit, select: (AiRearApp) -> Unit,
) {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    var expanded by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { expanded = true }
    val reveal = animateFloatAsState(if (expanded) 1f else 0f,
        spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), label = "rear_app_shelf")
    Box(Modifier.fillMaxSize().background(AppPalette.background())
        .windowInsetsPadding(area.safeInsets() ?: WindowInsets.displayCutout).padding(horizontal = 10.dp)) {
        Column(Modifier.fillMaxSize().testTag("ai_rear_shelf").graphicsLayer {
            transformOrigin = TransformOrigin(1f, 1f) // The bottom-right tile opens this surface.
            alpha = .82f + .18f * reveal.value
            scaleX = .96f + .04f * reveal.value
            scaleY = scaleX
        }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppBackButton(dismiss, Modifier.testTag("ai_rear_shelf_back"))
                Text(stringResource(R.string.ai_rear_all_apps), Modifier.weight(1f).semantics { heading() },
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppPalette.text())
            }
            if (selectionFailed) ShelfStatus(R.string.ai_rear_app_open_error)
            when {
                loadFailed -> if (!selectionFailed) ShelfStatus(R.string.ai_rear_app_open_error)
                apps == null -> ShelfStatus(R.string.ai_rear_list_loading)
                apps.isEmpty() -> ShelfStatus(R.string.ai_rear_list_empty)
                else -> {
                    val font = LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
                    LazyVerticalGrid(GridCells.Adaptive(68.dp * font), Modifier.weight(1f).testTag("ai_rear_shelf_grid"),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(apps, key = { it.component }) { app ->
                            val tile = RoundedCornerShape(16.dp)
                            Column(Modifier.heightIn(min = 72.dp)
                                .testTag("ai_rear_shelf_${app.component}")
                                .aiRearPressable(app.canCast) { select(app) }
                                .clip(tile).background(AppPalette.text().copy(alpha = if (app.canCast) .055f else .025f))
                                .padding(horizontal = 4.dp, vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                AiRearArtworkIcon(app.component, Modifier.size(32.dp).alpha(if (app.canCast) 1f else .48f))
                                Text(app.label, color = if (app.canCast) AppPalette.text() else AppPalette.secondary(),
                                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center)
                                if (!app.canCast) Text(stringResource(R.string.ai_rear_app_unavailable_short),
                                    fontSize = 11.sp, color = AppPalette.secondary(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun AiRearLaunchFeedback(
    area: RearCastViewport?, message: Int, actionLabel: Int, action: () -> Unit, failure: Boolean = true,
) {
    Box(Modifier.fillMaxSize().then(if (failure) Modifier.background(AppPalette.background()) else Modifier)
        .windowInsetsPadding(area.safeInsets() ?: WindowInsets.displayCutout).padding(10.dp),
        contentAlignment = if (failure) Alignment.Center else Alignment.BottomCenter) {
        AppCard(Modifier.widthIn(max = 270.dp).testTag("ai_rear_launch_feedback")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(message), fontSize = 16.sp, color = AppPalette.text(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
                TextButton(stringResource(actionLabel), action, Modifier.testTag("ai_rear_feedback_action"), minHeight = 48.dp)
            }
        }
    }
}

@Composable private fun ShelfStatus(id: Int) {
    Text(stringResource(id), Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 14.sp, color = AppPalette.secondary())
}
