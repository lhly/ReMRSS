package top.zainzh.rearscreen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import rikka.shizuku.Shizuku

class PermissionChecksActivity : ComponentActivity() {
    internal lateinit var page: PermissionChecksPage
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = PermissionChecksPage(this, savedInstanceState)
        miuixContent { AppPageWindow { page.Content(::finish) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        page.save(outState)
        super.onSaveInstanceState(outState)
    }
}

internal class PermissionChecksPage(
    private val host: ComponentActivity,
    saved: Bundle?,
    private val scrollKey: String = "permissions_scroll",
) {
    private val handler = Handler(Looper.getMainLooper())
    private var states by mutableStateOf(emptyMap<PermissionChecks.Item, PermissionChecks.State>())
    private val list = saved.listState(scrollKey)
    private fun getString(id: Int) = host.getString(id)
    private val controlConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) { refresh() }
        override fun onServiceDisconnected(name: android.content.ComponentName) { refresh() }
    }

    @Composable fun Content(back: () -> Unit, active: Boolean = true) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val life by lifecycle.currentStateFlow.collectAsState()
        if (active) {
            DisposableEffect(lifecycle) {
                val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
                    handler.post { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refresh() }
                }
                Shizuku.addRequestPermissionResultListener(permissionResult)
                onDispose {
                    Shizuku.removeRequestPermissionResultListener(permissionResult)
                    handler.removeCallbacksAndMessages(null)
                    TaskServiceManager.release(controlConnection)
                }
            }
            if (life.isAtLeast(Lifecycle.State.RESUMED)) DisposableEffect(lifecycle) {
                val tick = object : Runnable {
                    override fun run() {
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                        refresh()
                        handler.postDelayed(this, 2500)
                    }
                }
                handler.post(tick)
                onDispose { handler.removeCallbacks(tick) }
            }
        }
        AppPageContent(getString(R.string.ui_permissions_title), back, "permissions_back", scrollState = list) { pageModifier, headerInset ->
            LazyColumn(pageModifier.testTag("permissions_scroll"), state = list,
                contentPadding = PaddingValues(20.dp, headerInset + 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(getString(R.string.ui_permissions_help), fontSize = 13.sp, color = AppPalette.secondary()) }
                PermissionChecks.Item.entries.forEach { item ->
                    if (item == PermissionChecks.Item.BATTERY) item { AppSection(getString(R.string.ui_permissions_background_title)) }
                    item(item.name) {
                        val state = states[item] ?: PermissionChecks.State.UNKNOWN
                        AppCard {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(getString(PermissionChecks.title(host, item)), fontSize = 16.sp, color = AppPalette.text(), fontWeight = FontWeight.Medium,
                                    modifier = Modifier.semantics { heading() })
                                Text(getString(PermissionChecks.help(host, item)), fontSize = 13.sp, color = AppPalette.secondary())
                                val status: @Composable (Modifier) -> Unit = { modifier ->
                                    Text(getString(PermissionChecks.statusLabel(host, item, state)), fontSize = 13.sp, color = AppPalette.secondary(),
                                        modifier = modifier.testTag("permission_status_" + item.name).semantics { liveRegion = LiveRegionMode.Polite })
                                }
                                val button: @Composable (Modifier) -> Unit = { modifier ->
                                    TextButton(getString(PermissionChecks.actionLabel(host, item, state)), { openPermission(item) }, minHeight = 48.dp,
                                        cornerRadius = 16.dp, modifier = modifier.testTag("permission_action_" + item.name))
                                }
                                if (state == PermissionChecks.State.GRANTED || state == PermissionChecks.State.NOT_REQUIRED) {
                                    status(Modifier)
                                } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val available = maxWidth
                                    if (available / LocalDensity.current.fontScale >= 232.dp) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            status(Modifier.weight(1f))
                                            button(Modifier.widthIn(max = available * .45f))
                                        }
                                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        status(Modifier)
                                        button(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    val note = if (TaskServiceManager.isRoot(host))
                        R.string.ui_permissions_root_background_note else R.string.ui_permissions_background_note
                    Text(getString(note), fontSize = 13.sp, color = AppPalette.secondary())
                }
            }
        }
    }

    private fun refresh() { states = PermissionChecks.Item.entries.associateWith { PermissionChecks.read(host, it) } }
    private fun openPermission(item: PermissionChecks.Item) {
        if (item == PermissionChecks.Item.SHIZUKU && TaskServiceManager.isRoot(host)) {
            TaskServiceManager.acquire(host, controlConnection)
            TaskServiceManager.requestConnection(host)
            refresh()
            return
        }
        if (item == PermissionChecks.Item.SHIZUKU) try {
            if (PermissionChecks.read(host, item) == PermissionChecks.State.DENIED && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(701); return
            }
        } catch (_: RuntimeException) { }
        if (!PermissionChecks.openSettings(host, item)) Toast.makeText(host, R.string.ui_permission_settings_unavailable, Toast.LENGTH_LONG).show()
    }
    fun save(outState: Bundle) = outState.saveList(scrollKey, list)
}
