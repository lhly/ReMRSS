package top.zainzh.rearscreen

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.util.function.Predicate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Native rear-only picker in the already prepared Activity; it owns no virtual display or reader. */
internal object AiRearAppPickerView {
    private data class Content(val area: RearCastViewport, val apps: List<AiRearApp>)

    @JvmStatic
    fun create(host: ComponentActivity, remote: ITaskService, showApps: Boolean, launchFailed: Boolean,
        dismiss: Runnable, select: Predicate<ComponentName>): ComposeView = host.miuixComposeView {
        var content by remember { mutableStateOf<Content?>(null) }
        var loadFailed by remember { mutableStateOf(false) }
        var selectionFailed by remember { mutableStateOf(launchFailed) }
        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Content(RearCastGeometry.decode(remote.rearCastViewport),
                        if (showApps) AiRearAppCatalog.list(host) else emptyList())
                }
            }
            content = result.getOrNull()
            loadFailed = result.isFailure
        }
        val area = content?.area
        val insets = area?.let { WindowInsets(it.left, it.top,
            it.physicalWidth - it.right, it.physicalHeight - it.bottom) } ?: WindowInsets.displayCutout
        Column(Modifier.fillMaxSize().background(AppPalette.background()).windowInsetsPadding(insets)
            .padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppBackButton(dismiss::run)
                Text(stringResource(if (showApps) R.string.ai_rear_all_apps else R.string.ai_rear_app_open_error),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppPalette.text(),
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            if (showApps && selectionFailed) Status(R.string.ai_rear_app_open_error)
            val rows = content?.apps
            if (loadFailed) {
                if (!launchFailed) Status(R.string.ai_rear_app_open_error)
            } else if (rows == null) {
                Status(R.string.ai_rear_list_loading)
            } else if (showApps && rows.isEmpty()) {
                Status(R.string.ai_rear_list_empty)
            } else if (showApps && rows.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows, key = { it.component }) { app ->
                        AppCard {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .clickable(enabled = app.canCast, role = Role.Button) {
                                    val component = ComponentName.unflattenFromString(app.component)
                                    if (component == null || !select.test(component)) selectionFailed = true
                                }.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppIcon(host, app)
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontSize = 16.sp,
                                        color = if (app.canCast) AppPalette.text() else AppPalette.secondary(),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!app.canCast)
                                        Text(stringResource(R.string.ai_rear_app_not_separate), fontSize = 12.sp,
                                            color = AppPalette.secondary())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun waiting(host: ComponentActivity, area: RearCastViewport, cancel: Runnable): ComposeView =
        host.miuixComposeView {
            val insets = WindowInsets(area.left, area.top, area.physicalWidth - area.right,
                area.physicalHeight - area.bottom)
            Box(Modifier.fillMaxSize().background(AppPalette.background()).windowInsetsPadding(insets)
                .padding(12.dp), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ai_rear_cast_preparing), fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = AppPalette.text(),
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.ai_rear_cast_release_touch), fontSize = 15.sp,
                        color = AppPalette.secondary(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(stringResource(android.R.string.cancel), cancel::run,
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }

    @Composable
    private fun Status(id: Int) {
        Text(stringResource(id), Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 14.sp, color = AppPalette.secondary())
    }

    @Composable
    private fun AppIcon(host: ComponentActivity, app: AiRearApp) {
        var bitmap by remember(app.component) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(app.component) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    host.packageManager.getActivityIcon(requireNotNull(ComponentName.unflattenFromString(app.component)))
                        .toBitmap(64, 64)
                }.getOrNull()
            }
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize()) }
        }
    }
}
