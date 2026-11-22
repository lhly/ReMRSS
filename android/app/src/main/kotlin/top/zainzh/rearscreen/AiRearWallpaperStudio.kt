package top.zainzh.rearscreen

import android.animation.ValueAnimator
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

internal enum class AiRearStudioAction { APPEARANCE, PICK_WORK, REPLACE, PREPARE, DISARM, RESTORE }
internal data class AiRearStudioWork(val title: String, val detail: String)

internal data class AiRearStudioModel(
    val grid: AiRearGrid,
    val apps: Map<String, AiRearApp>,
    val viewport: AiRearWallpaperArchive.Viewport,
    val background: Bitmap?,
    val work: AiRearStudioWork?,
    val connected: Boolean,
    val installed: Boolean,
    val ready: Boolean,
    val applying: Boolean,
    val preparing: Boolean,
    val editingImage: Boolean,
    val allowUnlocked: Boolean,
    val message: String,
    val isModTheme: Boolean = false,
    val modTheme: AiRearModWallpaperTheme? = null,
    val loadingTheme: Boolean = false,
    val checkingWork: Boolean = false,
) {
    val busy get() = applying || preparing || editingImage || loadingTheme || checkingWork
    val usable get() = !isModTheme || modTheme != null
    val customControls get() = modTheme?.art?.isNotEmpty() == true
    val appCapacity get() = if (customControls) checkNotNull(modTheme).buttons else grid.appCapacity
    val assigned get() = grid.apps.take(appCapacity).count { apps[it]?.canCast == true }
    val bindingsReady get() = if (isModTheme) modTheme?.validGrid(grid, ::canCast) == true else grid.isReady(::canCast)

    private fun canCast(component: String) = apps[component]?.canCast == true
}

@Composable internal fun AiRearWallpaperStudio(
    model: AiRearStudioModel,
    scroll: LazyListState,
    back: () -> Unit,
    editShortcuts: () -> Unit,
    setUnlock: (Boolean) -> Unit,
    action: (AiRearStudioAction) -> Unit,
    themeSelector: (@Composable () -> Unit)? = null,
    thumbnail: @Composable (Modifier) -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 640
    PageFrame(stringResource(R.string.ai_rear_title), back, "ai_rear_back") { modifier, inset ->
        LazyColumn(modifier.testTag("ai_rear_editor"), state = scroll,
            contentPadding = PaddingValues(20.dp, inset + 10.dp, 20.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (themeSelector != null) item("theme-selector") { themeSelector() }
            if (wide) {
                item("preview") {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) { StudioPreview(model, editShortcuts, action) }
                        Box(Modifier.weight(1f)) { StudioWorkCard(model, action, thumbnail) }
                    }
                }
            } else {
                item("preview") { StudioPreview(model, editShortcuts, action) }
                item("wallpaper") { StudioWorkCard(model, action, thumbnail) }
            }
            item("launch-options") {
                AppCard {
                    AppSection(stringResource(R.string.ai_rear_button_settings))
                    AppToggle(stringResource(R.string.ai_rear_allow_unlocked),
                        stringResource(R.string.ai_rear_allow_unlocked_hint), model.allowUnlocked,
                        modifier = Modifier.testTag("ai_rear_allow_unlocked"), onChange = setUnlock)
                }
            }
        }
    }
}

@Composable private fun StudioPreview(
    model: AiRearStudioModel, editShortcuts: () -> Unit, action: (AiRearStudioAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxActionWidth = maxWidth * .62f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ai_rear_preview), Modifier.weight(1f).semantics { heading() },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppPalette.text())
                if (!model.isModTheme) PreviewAppearance(model.background, !model.busy, Modifier.widthIn(max = maxActionWidth)) {
                    action(AiRearStudioAction.APPEARANCE)
                }
            }
        }
        if (!model.usable) return@Column
        AiRearWallpaperCanvas(model, editShortcuts)
        if (model.isModTheme || model.installed || model.grid.occupiedSlots.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(stringResource(if (model.installed) R.string.ai_rear_edit_draft else R.string.ai_rear_tap_to_edit),
                    modifier = Modifier.weight(1f), fontSize = 13.sp, color = AppPalette.secondary())
                val count = if (model.isModTheme)
                    stringResource(R.string.ai_rear_bound_count, model.assigned, model.appCapacity)
                    else stringResource(R.string.ai_rear_visible_buttons, model.grid.occupiedSlots.size)
                Text(count, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 13.sp, color = AppPalette.secondary())
            }
        }
    }
}

@Composable private fun PreviewAppearance(
    background: Bitmap?, enabled: Boolean, modifier: Modifier, open: () -> Unit,
) {
    val accent = if (enabled) AppPalette.accent() else AppPalette.secondary()
    Box(modifier.heightIn(min = 48.dp).testTag("ai_rear_appearance")
        .aiRearPressable(enabled = enabled, pressScale = .97f, onClick = open), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha = .10f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (background != null) {
                Image(background.asImageBitmap(), null, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Icon(painterResource(R.drawable.ic_ai_rear_appearance), null, Modifier.size(18.dp), accent)
            }
            Text(stringResource(R.string.ai_rear_appearance), fontSize = 13.sp, color = accent,
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable private fun StudioWorkCard(
    model: AiRearStudioModel, action: (AiRearStudioAction) -> Unit, thumbnail: @Composable (Modifier) -> Unit,
) {
    AppCard {
        AppSection(stringResource(R.string.ai_rear_grid_cast_title))
        val work = model.work
        AppRow(work?.title ?: stringResource(if (model.installed) R.string.ai_rear_backed_up
            else R.string.ai_rear_pick_wallpaper), work?.detail,
            modifier = Modifier.testTag("ai_rear_pick_work"), enabled = !model.busy && model.connected,
            action = if (model.installed && !model.isModTheme) null else ({ action(AiRearStudioAction.PICK_WORK) }),
            trailing = if (work == null) null else ({
                thumbnail(Modifier.size(width = 62.dp, height = 42.dp).clip(RoundedCornerShape(8.dp)))
            }))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioStatus(model)
            val primary = when {
                model.installed && !model.ready -> AiRearStudioAction.PREPARE
                !model.installed && work != null -> AiRearStudioAction.REPLACE
                else -> null
            }
            if (primary != null) {
                val id = if (primary == AiRearStudioAction.PREPARE) R.string.ai_rear_prepare
                    else R.string.ai_rear_backup_replace
                TextButton(stringResource(id), { action(primary) },
                    Modifier.fillMaxWidth().testTag("ai_rear_primary_action"),
                    enabled = !model.busy && model.connected && model.usable &&
                        (model.installed || model.bindingsReady),
                    minHeight = 48.dp, cornerRadius = 16.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        if (model.installed) {
            AppDivider()
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(stringResource(R.string.ai_rear_restore), { action(AiRearStudioAction.RESTORE) },
                    Modifier.testTag("ai_rear_restore"), enabled = !model.busy && model.connected, minHeight = 48.dp)
                if (model.ready) TextButton(stringResource(R.string.ai_rear_disarm),
                    { action(AiRearStudioAction.DISARM) }, enabled = !model.busy, minHeight = 48.dp)
            }
        }
    }
}

@Composable private fun StudioStatus(model: AiRearStudioModel) {
    val id = when {
        model.checkingWork -> R.string.ai_rear_checking_work
        model.applying -> if (model.installed) R.string.ai_rear_restoring else R.string.ai_rear_replacing
        model.preparing -> R.string.ai_rear_preparing
        model.loadingTheme -> R.string.ai_rear_loading_theme
        !model.usable -> R.string.ai_rear_theme_paused
        !model.connected -> R.string.ai_rear_connect
        model.ready && model.installed -> R.string.ai_rear_prepared
        model.installed -> R.string.ai_rear_prepare_hint
        model.work == null -> R.string.ai_rear_pick_hint
        !model.bindingsReady -> when {
            model.customControls -> R.string.ai_rear_theme_bindings
            model.isModTheme -> R.string.ai_rear_complete_grid
            model.grid.occupiedSlots.isEmpty() -> R.string.ai_rear_minimum_shortcut
            else -> R.string.ai_rear_unavailable_shortcuts
        }
        else -> R.string.ai_rear_replace_ready
    }
    val label = model.message.takeIf { !model.applying && !model.preparing && it.isNotBlank() }
        ?: stringResource(id)
    Text(label, Modifier.testTag("ai_rear_status").semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 14.sp, lineHeight = 20.sp,
        color = if (model.ready && model.message.isEmpty()) AppPalette.accent() else AppPalette.secondary())
}

@Composable private fun AiRearWallpaperCanvas(model: AiRearStudioModel, editShortcuts: () -> Unit) {
    val viewport = model.viewport
    val shape = RoundedCornerShape(24.dp)
    val editLabel = stringResource(R.string.ai_rear_shortcuts_title)
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(viewport.width.toFloat() / viewport.height)
        .clip(shape).background(Color(AiRearWallpaperArt.WALL)).testTag("ai_rear_canvas")) {
        val unit = maxWidth / viewport.width.toFloat()
        val density = LocalDensity.current
        val artwork = model.modTheme?.takeIf { it.art.isNotEmpty() }
        val backdrop = remember(artwork) { artwork?.backdrop?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        if (backdrop != null) Image(backdrop.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        model.background?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(AiRearWallpaperArt.PHOTO_SHADE)))
        }
        // The preview represents the hardware, not additional art written into the wallpaper.
        val camera = remember { RearCameraGapGeometry.candidate("popsicle") }
        if (viewport.width == camera.width && viewport.height == camera.height &&
            viewport.left >= camera.x + camera.radius + camera.margin) {
            listOf(camera.upperY, camera.lowerY).forEach { y ->
                Box(Modifier.absoluteOffset(unit * (camera.x - camera.radius), unit * (y - camera.radius))
                    .size(unit * (2 * camera.radius)).background(Color(0xFF101720), CircleShape)
                    .border(.8.dp, Color(0x2CBCD4E7), CircleShape).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(.77f).aspectRatio(1f).background(Color(0xFF0B1118), CircleShape)
                        .border(.6.dp, Color(0x3A57718A), CircleShape))
                    Box(Modifier.fillMaxWidth(.36f).aspectRatio(1f).background(Color(0xFF121D2C), CircleShape)
                        .border(1.dp, Color(0x3A617A96), CircleShape))
                }
            }
        }
        val cells = remember(viewport, artwork, model.isModTheme, model.grid) {
            when {
                artwork != null -> artwork.art.map { AiRearWallpaperArchive.TileCell(it.index, it.cell(viewport)) }
                model.isModTheme -> AiRearWallpaperArchive.cells(viewport).mapIndexed { index, cell ->
                    AiRearWallpaperArchive.TileCell(index, cell)
                }
                else -> AiRearWallpaperArchive.occupiedCells(viewport, model.grid)
            }
        }
        if (cells.isEmpty()) {
            Box(Modifier.absoluteOffset(unit * viewport.left, unit * viewport.top)
                .size(unit * (viewport.width - viewport.left - viewport.right),
                    unit * (viewport.height - viewport.top - viewport.bottom))
                .testTag("ai_rear_add_shortcuts").aiRearPressable(!model.busy, onClick = editShortcuts),
                contentAlignment = Alignment.Center) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_ai_rear_add), null, Modifier.size(22.dp),
                        tint = Color(AiRearWallpaperArt.TEXT))
                    Text(stringResource(R.string.ai_rear_add_shortcuts), color = Color(AiRearWallpaperArt.TEXT),
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                }
            }
        }
        cells.forEachIndexed { position, (index, cell) ->
            val tile = model.grid.tile(index)
            val library = tile == AiRearGrid.Tile.AllApps
            val app = (tile as? AiRearGrid.Tile.App)?.let { model.apps[it.component] }
            val label = when {
                library -> stringResource(R.string.ai_rear_all_apps)
                app != null -> app.label
                tile is AiRearGrid.Tile.App -> stringResource(R.string.ai_rear_unavailable)
                else -> stringResource(R.string.ai_rear_empty_slot)
            }
            val spoken = stringResource(R.string.ai_rear_slot_summary, position + 1, label)
            val face = AiRearWallpaperArt.face(cell.width, cell.height, icon = true, library = library)
            val frame = RoundedCornerShape(unit * face.radius)
            val labelHeight = unit * (face.textSize + 6)
            val faceModifier = Modifier.absoluteOffset(unit * cell.x, unit * cell.y)
                .size(unit * cell.width, unit * cell.height).testTag("ai_rear_tile_$index")
                .semantics { contentDescription = spoken }
            if (artwork != null) {
                val image = remember(artwork, index) {
                    artwork.art[index].png.let { BitmapFactory.decodeByteArray(it, 0, it.size) }.asImageBitmap()
                }
                val textSize = unit * AiRearWallpaperArchive.artLabelSize(cell)
                val labelHeight = textSize * 1.2f
                Box(faceModifier.aiRearPressable(!model.busy, onClickLabel = editLabel, onClick = editShortcuts)) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Text(label, Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        .absoluteOffset(y = unit * AiRearWallpaperArchive.artLabelY(cell) - labelHeight / 2).clearAndSetSemantics {},
                        fontSize = with(density) { textSize.toSp() }, lineHeight = with(density) { labelHeight.toSp() },
                        color = Color.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                return@forEachIndexed
            }
            Box(faceModifier.aiRearPressable(!model.busy, onClickLabel = editLabel, onClick = editShortcuts)
                .clip(frame).background(Color(face.surface)).border(.75.dp, Color(AiRearWallpaperArt.OUTLINE), frame)) {
                val icon = Modifier.align(Alignment.TopCenter).absoluteOffset(y = unit * face.iconTop).size(unit * face.icon)
                when {
                    library -> Image(painterResource(R.drawable.ic_ai_rear_all_apps), null, icon)
                    app != null -> AiRearArtworkIcon(app.component, icon)
                    else -> Image(painterResource(R.drawable.ic_ai_rear_add), null, icon.padding(3.dp))
                }
                Text(label, Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    .absoluteOffset(y = unit * face.labelY - labelHeight / 2).clearAndSetSemantics {},
                    fontSize = with(density) { (unit * face.textSize).toSp() },
                    lineHeight = with(density) { labelHeight.toSp() },
                    fontWeight = FontWeight.Medium, color = Color(AiRearWallpaperArt.TEXT),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Observe the press without consuming it; scrolling still cancels the ordinary click. */
internal fun Modifier.aiRearPressable(
    enabled: Boolean = true, pressScale: Float = .96f, onClickLabel: String? = null, onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val focusPress by interactions.collectIsPressedAsState()
    var isTouchDown by remember { mutableStateOf(false) }
    val pressed = enabled && (isTouchDown || focusPress)
    val motion = ValueAnimator.areAnimatorsEnabled()
    val scale = animateFloatAsState(if (pressed && motion) pressScale else 1f,
        animationSpec = if (pressed) snap() else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
        label = "wallpaper_press")
    val watch = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            isTouchDown = true
            try {
                var up = false
                while (!up) {
                    val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                    up = change?.pressed != true || change.isConsumed ||
                        change.position.x !in 0f..size.width.toFloat() || change.position.y !in 0f..size.height.toFloat()
                }
            } finally {
                isTouchDown = false
            }
        }
    }
    then(watch).clickable(enabled = enabled, interactionSource = interactions,
        indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
        .graphicsLayer {
            scaleX = scale.value; scaleY = scale.value
            alpha = if (pressed && !motion) .82f else 1f
        }
}

private val LocalAiRearArtwork = staticCompositionLocalOf<LruCache<String, ImageBitmap>?> { null }

@Composable internal fun AiRearArtworkScope(content: @Composable () -> Unit) {
    val icons = remember { LruCache<String, ImageBitmap>(96) }
    CompositionLocalProvider(LocalAiRearArtwork provides icons, content = content)
}

@Composable internal fun AiRearArtworkIcon(component: String, modifier: Modifier = Modifier, placeholder: Boolean = true) {
    val context = LocalContext.current
    val cache = LocalAiRearArtwork.current
    var image by remember(cache, component) { mutableStateOf(cache?.get(component)) }
    var loaded by remember(cache, component) { mutableStateOf(image != null) }
    LaunchedEffect(context, cache, component) {
        if (loaded) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            val target = ComponentName.unflattenFromString(component) ?: return@withContext null
            runCatching { context.packageManager.getActivityIcon(target).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
        image?.let { cache?.put(component, it) }
        loaded = true
    }
    val icon = image
    when {
        icon != null -> Image(icon, null, modifier.testTag("ai_rear_artwork_$component"))
        placeholder && (loaded || cache == null) -> Image(painterResource(R.drawable.ic_ai_rear_all_apps), null, modifier)
        else -> Spacer(modifier)
    }
}
