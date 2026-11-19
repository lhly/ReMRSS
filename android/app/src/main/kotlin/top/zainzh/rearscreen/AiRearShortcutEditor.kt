package top.zainzh.rearscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text

@Composable internal fun AiRearShortcutEditor(
    draft: AiRearShortcutDraft,
    apps: List<AiRearApp>,
    loading: Boolean,
    back: () -> Unit,
    limit: Int = AiRearShortcutDraft.LIMIT,
    allowAllApps: Boolean = true,
    update: (AiRearShortcutDraft) -> Unit,
) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx().toFloat() }
    val order = remember(gap) { AiRearShortcutOrder(gap) }
    val keys = remember(draft.entries) { draft.entries.map { it.key } }
    LaunchedEffect(keys) { order.retain(keys) }
    KeepShortcutDragInView(order, keys, list)

    val keyboard = LocalSoftwareKeyboardController.current
    var search by rememberSaveable { mutableStateOf("") }
    val query = search.trim()
    val catalog = remember(apps) { apps.associateBy { it.component } }
    val duplicateLabels = remember(apps) { apps.groupingBy { it.label }.eachCount() }
    val visible = remember(apps, draft.entries, query) {
        val configured = draft.entries.mapTo(HashSet()) { it.component }
        apps.filter { it.canCast && it.component !in configured &&
            (query.isEmpty() || it.label.contains(query, ignoreCase = true) || it.component.contains(query, ignoreCase = true)) }
    }
    val dragging = order.movingKey != null
    val full = draft.entries.size >= limit

    PageFrame(stringResource(R.string.ai_rear_shortcuts_title), {
        keyboard?.hide()
        back()
    }, "ai_rear_shortcuts_back") { modifier, inset ->
        LazyColumn(modifier.shortcutOrderViewport(order, with(density) { inset.toPx() }).testTag("ai_rear_shortcuts"),
            state = list, contentPadding = PaddingValues(20.dp, inset + 12.dp, 20.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (allowAllApps) item("all_apps") {
                AppCard {
                    AppToggle(stringResource(R.string.ai_rear_all_apps), stringResource(R.string.ai_rear_all_apps_hint),
                        checked = draft.allApps, enabled = !dragging,
                        modifier = Modifier.testTag("ai_rear_library"), onChange = { update(draft.withAllApps(it)) })
                }
            }
            item("selected_heading") {
                AppSection(stringResource(R.string.ai_rear_configured_shortcuts, draft.entries.size, limit))
            }
            item("ordered") {
                if (draft.entries.isEmpty()) {
                    ShortcutListStatus(R.string.ai_rear_shortcuts_empty)
                } else {
                    // Keeping at most six rows in one measured group also keeps a grabbed handle alive while scrolling.
                    Column(Modifier.fillMaxWidth().shortcutOrderGroup(order), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val placement = order.placement(keys)
                        draft.entries.forEachIndexed { index, entry ->
                            key(entry.key) {
                                val app = catalog[entry.component]
                                val parked = draft.parked(index)
                                val packageName = entry.component.substringBefore('/')
                                val name = app?.label ?: packageName
                                val detail = when {
                                    parked -> stringResource(R.string.ai_rear_shortcut_parked)
                                    !loading && app?.canCast != true -> stringResource(R.string.ai_rear_app_unavailable_short)
                                    (duplicateLabels[name] ?: 0) > 1 -> packageName
                                    else -> null
                                }
                                val removeLabel = stringResource(R.string.ai_rear_remove_app, name)
                                AppCatalogCard(name, detail, muted = parked, verticalPadding = 8.dp,
                                    modifier = shortcutRowPlacement(order, entry.key, index, placement)
                                        .testTag(if (order.movingKey == entry.key) "ai_rear_dragging_${entry.key}"
                                            else "ai_rear_selected_${entry.key}"),
                                    icon = {
                                        AiRearArtworkIcon(entry.component, Modifier.size(40.dp).alpha(if (parked) .55f else 1f),
                                            placeholder = false)
                                    }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (keys.size > 1) ShortcutDragHandle(order, entry.key, keys, index, name) { key, to ->
                                            update(draft.move(key, to))
                                        }
                                        Box(Modifier.size(48.dp).testTag("ai_rear_remove_${entry.key}")
                                            .semantics { contentDescription = removeLabel }
                                            .toggleable(value = true, enabled = !dragging, role = Role.Checkbox) {
                                                update(draft.remove(entry.key))
                                            }, contentAlignment = Alignment.Center) {
                                            CatalogCheck(checked = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item("available_search") {
                Column {
                    AppSection(stringResource(R.string.ai_rear_available_shortcuts))
                    if (full) ShortcutListStatus(if (allowAllApps) R.string.ai_rear_shortcuts_full else R.string.ai_rear_theme_shortcuts_full)
                    AppCatalogSearchField(search, { search = it }, stringResource(R.string.ai_rear_search_apps),
                        modifier = Modifier.testTag("ai_rear_search"), interactive = !dragging)
                }
            }
            if (visible.isEmpty()) item("empty_results") {
                ShortcutListStatus(if (loading) R.string.ai_rear_list_loading else R.string.ai_rear_shortcuts_no_results)
            }
            items(visible, key = { "available:${it.component}" }) { app ->
                val detail = app.component.substringBefore('/').takeIf {
                    (duplicateLabels[app.label] ?: 0) > 1 || query.isNotEmpty() && !app.label.contains(query, ignoreCase = true)
                }
                AppCatalogCard(app.label, detail, muted = full, verticalPadding = 10.dp,
                    interaction = Modifier.testTag("ai_rear_available_${app.component}").toggleable(value = false, enabled = !full && !dragging, role = Role.Checkbox) {
                        update(draft.add(app.component))
                    }, icon = {
                        AiRearArtworkIcon(app.component, Modifier.size(40.dp).alpha(if (full) .6f else 1f), placeholder = false)
                    }) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CatalogCheck(checked = false)
                    }
                }
            }
        }
    }
}

@Composable private fun CatalogCheck(checked: Boolean) {
    Checkbox(if (checked) ToggleableState.On else ToggleableState.Off,
        onClick = null, modifier = Modifier.clearAndSetSemantics { })
}

@Composable private fun ShortcutListStatus(label: Int) {
    Text(stringResource(label), Modifier.padding(horizontal = 14.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 13.sp, color = AppPalette.secondary())
}
