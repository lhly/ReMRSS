package top.zainzh.rearscreen

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private object DensityBulkOperation {
    var running by mutableStateOf(false)
}

class DensityCompatibilityActivity : ComponentActivity() {
    private lateinit var page: DensityCompatibilityPage
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) { finish(); return }
        page = DensityCompatibilityPage(this, state)
        miuixContent { AppPageWindow { page.Content(::finish) } }
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (::page.isInitialized) page.save(out)
        super.onSaveInstanceState(out)
    }
}

/** Per-App policy editor; never changes an already running cast. */
internal class DensityCompatibilityPage(private val host: ComponentActivity, saved: Bundle?) {
    private fun getString(id: Int) = host.getString(id)
    private fun getString(id: Int, vararg args: Any) = host.getString(id, *args)
    private data class App(val packageName: String, val label: String)
    private var bulkError by mutableStateOf(false)
    private var bulkResult by mutableStateOf<String?>(null)
    private var toggleFeedback: Toast? = null
    private var apps by mutableStateOf(emptyList<App>())
    private val search = TextFieldState(saved?.getString("density_search").orEmpty())
    private var loading by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var editingPackage by mutableStateOf(saved?.getString("density_editing_package"))
    private var draft by mutableStateOf(saved?.getString("density_dpi_draft").orEmpty())
    private var invalid by mutableStateOf(saved?.getBoolean("density_dpi_invalid", false) ?: false)
    private val list = saved.listState("density_list")
    private val preferences = RearDensityPreferences.preferences(host)
    private var session = 0
    private var active = false
    private val preferenceRefreshQueued = AtomicBoolean(false)
    private val refreshPreferences = Runnable {
        preferenceRefreshQueued.set(false)
        if (active && !host.isDestroyed && !host.isFinishing) revision++
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("enabled:") || key.startsWith("dpi:"))
            && preferenceRefreshQueued.compareAndSet(false, true)) mainHandler.post(refreshPreferences)
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "density-compat-worker").apply { isDaemon = true }
        }
        val mainHandler = Handler(Looper.getMainLooper())
    }


    @Composable fun Content(back: () -> Unit, interactive: Boolean = true) {
        if (interactive) {
            DisposableEffect(Unit) {
                active = true
                session++
                preferences.registerOnSharedPreferenceChangeListener(listener)
                revision++
                load()
                onDispose {
                    active = false
                    session++
                    preferences.unregisterOnSharedPreferenceChangeListener(listener)
                    mainHandler.removeCallbacks(refreshPreferences)
                    preferenceRefreshQueued.set(false)
                    toggleFeedback?.cancel()
                    toggleFeedback = null
                }
            }
            LaunchedEffect(list) {
                snapshotFlow { search.text.toString() }.drop(1).collect { list.requestScrollToItem(0) }
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        val compactEditor = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            && WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val query = search.text.toString().trim().lowercase(Locale.ROOT)
        val visible = remember(apps, query) {
            apps.filter { query.isEmpty() || it.label.lowercase(Locale.ROOT).contains(query) || it.packageName.lowercase(Locale.ROOT).contains(query) }
        }
        val selected = remember(apps, revision) { apps.associate { it.packageName to RearDensityPreferences.enabled(host, it.packageName) } }
        val dpiValues = remember(apps, revision) { apps.associate { it.packageName to RearDensityPreferences.layoutDensity(host, it.packageName) } }
        val groups = remember(visible, selected) {
            val (enabled, disabled) = visible.partition { selected[it.packageName] == true }
            listOf(true to enabled, false to disabled)
        }
        AppPageContent(getString(R.string.rear_density_title), back, "density_back") { pageModifier, headerInset ->
            LazyColumn(pageModifier.testTag("density_app_list"), state = list,
                contentPadding = PaddingValues(20.dp, headerInset + 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("help") { Text(getString(R.string.rear_density_help), fontSize = 13.sp, color = AppPalette.secondary()) }
                item("search") {
                    AppCatalogSearchField(search, getString(R.string.rear_density_search),
                        modifier = Modifier.testTag("density_app_search"), interactive = interactive)
                }
                if (!loading && !failed && apps.isNotEmpty()) item("bulk_controls") {
                    val canChangeAll = !loading && !failed && apps.isNotEmpty() && !DensityBulkOperation.running
                    BulkControlSection(
                        appCount = apps.size,
                        enabled = canChangeAll,
                        running = DensityBulkOperation.running,
                        error = bulkError,
                        result = bulkResult,
                        apply = ::applyBulk,
                    )
                }
                if (loading || failed || visible.isEmpty()) item("status") {
                    Text(getString(if (loading) R.string.rear_density_loading else if (failed) R.string.rear_density_failed else R.string.rear_density_empty),
                        fontSize = 13.sp, color = AppPalette.secondary())
                    if (failed) TextButton(getString(R.string.rear_density_retry), ::load, minHeight = 48.dp)
                }
                groups.forEach { (enabled, groupApps) ->
                    if (groupApps.isNotEmpty()) {
                        item(if (enabled) "enabled_group" else "disabled_group") {
                            Box(Modifier.testTag(if (enabled) "density_enabled_group" else "density_disabled_group")) {
                                AppSection(getString(if (enabled) R.string.rear_density_group_enabled else R.string.rear_density_group_disabled,
                                    groupApps.size))
                            }
                        }
                        items(groupApps, key = { it.packageName }) { app ->
                            AppCard(Modifier.testTag("density_app_" + app.packageName)) {
                                AppToggle(app.label, app.packageName, selected[app.packageName] == true,
                                    onChange = { enabled ->
                                        RearDensityPreferences.setEnabled(host, app.packageName, enabled)
                                        toggleFeedback?.cancel()
                                        val feedback = if (enabled) R.string.rear_density_enabled_next_cast
                                            else R.string.rear_density_disabled_next_cast
                                        toggleFeedback = Toast.makeText(host, getString(feedback, app.label), Toast.LENGTH_SHORT)
                                            .also { it.show() }
                                    })
                                if (selected[app.packageName] == true) {
                                    AppDivider()
                                    val dpi = dpiValues[app.packageName] ?: 0
                                    AppRow(getString(R.string.rear_density_layout_title),
                                        getString(if (dpi == 0) R.string.rear_density_layout_default else R.string.rear_density_layout_value,
                                            if (dpi == 0) VirtualCastRequest.DEFAULT_DENSITY_DPI else dpi),
                                        Modifier.testTag("density_dpi_" + app.packageName), action = {
                                            editingPackage = app.packageName
                                            draft = (if (dpi == 0) VirtualCastRequest.DEFAULT_DENSITY_DPI else dpi).toString()
                                            invalid = false
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
        WindowDialog(show = interactive && editingPackage != null, title = if (compactEditor) null else getString(R.string.rear_density_layout_title),
            onDismissRequest = { editingPackage = null; keyboard?.hide() }) {
            Column(Modifier.fillMaxWidth()
                .heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * if (compactEditor) 1f else .72f }),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!compactEditor) {
                        Text(apps.find { it.packageName == editingPackage }?.label ?: editingPackage.orEmpty(), color = AppPalette.text())
                    }
                    TextField(draft, { draft = it; invalid = false }, label = getString(R.string.rear_density_layout_title),
                        textStyle = MiuixTheme.textStyles.main.copy(color = AppPalette.text()),
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (saveDraft()) keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().testTag("density_dpi_input"))
                    if (invalid) Text(getString(R.string.rear_density_layout_error), color = AppPalette.destructive())
                    if (!compactEditor) {
                        Text(getString(R.string.rear_density_layout_help), fontSize = 13.sp, color = AppPalette.secondary())
                    }
                    TextButton(getString(R.string.rear_density_layout_reset), {
                        editingPackage?.let { RearDensityPreferences.setLayoutDensity(host, it, 0) }
                        editingPackage = null; keyboard?.hide()
                    }, Modifier.fillMaxWidth().testTag("density_dpi_reset"), minHeight = 48.dp,
                        colors = ButtonDefaults.textButtonColors().copy(color = Color.Transparent, textColor = AppPalette.accent()))
                }
                AppActionPair(getString(R.string.rear_density_layout_cancel), { editingPackage = null; keyboard?.hide() },
                    getString(R.string.rear_density_layout_save), { if (saveDraft()) keyboard?.hide() },
                    primaryModifier = Modifier.testTag("density_dpi_save"))
            }
        }
    }

    @Composable
    private fun BulkControlSection(
        appCount: Int,
        enabled: Boolean,
        running: Boolean,
        error: Boolean,
        result: String?,
        apply: (Boolean) -> Unit,
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("density_bulk_controls"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                getString(R.string.rear_density_bulk_summary, appCount),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AppPalette.text(),
            )
            AppActionPair(getString(R.string.rear_density_bulk_disable), { apply(false) },
                getString(R.string.rear_density_bulk_enable), { apply(true) },
                secondaryModifier = Modifier.testTag("density_bulk_disable"),
                primaryModifier = Modifier.testTag("density_bulk_enable"),
                secondaryEnabled = enabled, primaryEnabled = enabled, emphasizePrimary = false)

            when {
                running -> Text(
                    getString(R.string.rear_density_bulk_running, appCount),
                    fontSize = 12.sp,
                    color = AppPalette.secondary(),
                    modifier = Modifier.testTag("density_bulk_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
                error -> Text(
                    getString(R.string.rear_density_bulk_error),
                    fontSize = 12.sp,
                    color = AppPalette.destructive(),
                    modifier = Modifier.testTag("density_bulk_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
                result != null -> Text(
                    result,
                    fontSize = 12.sp,
                    color = AppPalette.secondary(),
                    modifier = Modifier.testTag("density_bulk_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    private fun applyBulk(enabled: Boolean) {
        if (loading || failed || apps.isEmpty() || DensityBulkOperation.running) return
        val targets = apps.map { it.packageName }.distinct()
        if (targets.isEmpty()) return
        DensityBulkOperation.running = true
        bulkError = false
        bulkResult = null
        val token = session
        val application = host.applicationContext
        worker.execute {
            val result = runCatching { RearDensityPreferences.setAllEnabled(application, targets, enabled) }
            mainHandler.post {
                DensityBulkOperation.running = false
                if (!active || token != session || host.isDestroyed || host.isFinishing) return@post
                if (result.isSuccess) {
                    bulkResult = getString(R.string.rear_density_bulk_done, targets.size)
                } else {
                    bulkError = true
                }
            }
        }
    }
    private fun saveDraft(): Boolean {
        val value = draft.trim().toIntOrNull()
        if (value == null || value !in 72..640) { invalid = true; return false }
        val pkg = editingPackage ?: return false
        RearDensityPreferences.setLayoutDensity(host, pkg, value)
        editingPackage = null
        return true
    }
    private fun load() {
        val token = session
        loading = true; failed = false
        worker.execute {
            val result = runCatching {
                val collator = Collator.getInstance(host.resources.configuration.locales[0])
                host.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != host.packageName }
                    .map { App(it.activityInfo.packageName, host.packageManager.getApplicationLabel(it.activityInfo.applicationInfo).toString()) }
                    .sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
            host.runOnUiThread { if (active && token == session && !host.isDestroyed && !host.isFinishing) {
                apps = result.getOrDefault(emptyList()); failed = result.isFailure; loading = false
            } }
        }
    }
    fun save(out: Bundle) {
        out.putString("density_search", search.text.toString())
        out.putString("density_editing_package", editingPackage)
        out.putString("density_dpi_draft", draft)
        out.putBoolean("density_dpi_invalid", invalid)
        out.saveList("density_list", list)
    }
}
