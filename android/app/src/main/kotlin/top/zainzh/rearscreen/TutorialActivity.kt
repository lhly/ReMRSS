package top.zainzh.rearscreen

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Standalone entry shares the same body, actions and saved state as the settings route. */
class TutorialActivity : ComponentActivity() {
    internal lateinit var guide: TutorialGuide
        private set

    private lateinit var permissions: PermissionChecksPage
    private lateinit var motion: AppRouteMotion
    private var route by mutableIntStateOf(TUTORIAL_ROUTE)
    private val childBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            motion.startBackPreview(TUTORIAL_PERMISSION_ROUTE, TUTORIAL_ROUTE, backEvent.progress)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            motion.updateBackPreview(backEvent.progress, SystemClock.uptimeMillis())
        }

        override fun handleOnBackCancelled() = motion.cancelBackPreview(route)

        override fun handleOnBackPressed() = navigate(TUTORIAL_ROUTE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getInt("standalone_tutorial_page") == TUTORIAL_PERMISSION_ROUTE) {
            route = TUTORIAL_PERMISSION_ROUTE
        }
        guide = TutorialGuide(this, savedInstanceState)
        permissions = PermissionChecksPage(this, savedInstanceState, "tutorial_permissions_scroll")
        motion = AppRouteMotion(this, route) { NavigationMotion.parentRoute(it).takeIf { parent -> parent == TUTORIAL_ROUTE } }
        onBackPressedDispatcher.addCallback(this, childBack)
        childBack.isEnabled = route == TUTORIAL_PERMISSION_ROUTE
        miuixContent {
            AppStandaloneRoutes(TUTORIAL_ROUTE, route, motion) { page, active ->
                when (page) {
                    TUTORIAL_ROUTE -> guide.Content(::finish) { navigate(TUTORIAL_PERMISSION_ROUTE) }
                    TUTORIAL_PERMISSION_ROUTE -> permissions.Content({ navigate(TUTORIAL_ROUTE) }, active)
                }
            }
        }
    }

    private fun navigate(target: Int) {
        val previous = route
        if (target == previous) return
        route = target
        childBack.isEnabled = route == TUTORIAL_PERMISSION_ROUTE
        motion.showPage(previous, target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        guide.save(outState)
        permissions.save(outState)
        outState.putInt("standalone_tutorial_page", route)
        super.onSaveInstanceState(outState)
    }

    companion object { val sections get() = TutorialGuide.sections }
}

/** Collapsible chapters and attribution are bundled resources and work offline. */
internal class TutorialGuide(private val host: ComponentActivity, saved: Bundle?) {
    val list: LazyListState = saved.listState("tutorial_scroll")
    private var addingTile by mutableStateOf(false)
    private var expandedSection by mutableStateOf(saved?.getString("tutorial_expanded"))
    private fun getString(id: Int) = host.getString(id)
    private fun getString(id: Int, vararg args: Any) = host.getString(id, *args)

    @Composable fun Content(back: () -> Unit, permissions: () -> Unit) {
        val scope = rememberCoroutineScope()
        AppPageContent(NativeStrings.get(host, "tutorial"), back, "tutorial_back", scrollState = list) { pageModifier, headerInset ->
            LazyColumn(pageModifier.testTag("tutorial_scroll"),
                state = list, contentPadding = PaddingValues(20.dp, headerInset + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("intro") { Text(getString(R.string.tutorial_intro), fontSize = 14.sp, color = AppPalette.secondary()) }
                sections.forEachIndexed { index, section ->
                    item(section) {
                        Chapter(section, getString(titles[index])) {
                            if (section == "casting") {
                                castingFaq.forEach { (question, answer) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(getString(question), Modifier.semantics { heading() }, fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium, color = AppPalette.text())
                                        Text(getString(answer), fontSize = 14.sp, color = AppPalette.secondary())
                                    }
                                }
                            } else {
                                getString(steps.getValue(section)).replace("{scheme}", BuildConfig.URI_SCHEME).split('\n').forEachIndexed { number, step ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("${number + 1}.", fontSize = 14.sp, color = AppPalette.accent())
                                        Text(step, fontSize = 14.sp, color = AppPalette.secondary(), modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            if (index == 0) Text(getString(R.string.privilege_tutorial_root), fontSize = 14.sp, color = AppPalette.secondary())
                            if (index == 0 && !TaskServiceManager.isRoot(host)) TextButton(NativeStrings.get(host, "open_shizuku"), {
                                if (!PermissionChecks.openSettings(host, PermissionChecks.Item.SHIZUKU)) openError()
                            }, Modifier.testTag("tutorial_shizuku"), minHeight = 48.dp, enabled = expandedSection == section)
                            if (index == 1) TextButton(getString(R.string.tutorial_open_permissions), permissions, Modifier.testTag("tutorial_permissions"), minHeight = 48.dp, enabled = expandedSection == section)
                            if (section == "tiles") {
                                tileShortcuts.forEach { tile ->
                                    TextButton(getString(R.string.tutorial_add_tile, getString(tile.label)), { addTile(tile, scope) },
                                        Modifier.fillMaxWidth().testTag("tutorial_add_tile_${tile.tag}"), minHeight = 48.dp,
                                        enabled = !addingTile && expandedSection == section)
                                }
                            }
                        }
                    }
                }
                item("source") {
                    Chapter("source", getString(R.string.tutorial_source_title)) {
                        Text(getString(R.string.tutorial_source_help), fontSize = 13.sp, color = AppPalette.secondary())
                        TextButton(getString(R.string.tutorial_view_original), ::openOriginal,
                            Modifier.testTag("tutorial_original"), minHeight = 48.dp, enabled = expandedSection == "source")
                    }
                }
            }
        }
    }
    @Composable
    private fun Chapter(section: String, title: String, content: @Composable ColumnScope.() -> Unit) {
        val expanded = expandedSection == section
        val arrow by animateFloatAsState(if (expanded) 90f else 0f,
            tween(if (expanded) SETTINGS_ENTER_MS else SETTINGS_EXIT_MS, easing = settingsRevealEasing), label = "tutorial_arrow")
        val description = getString(if (expanded) R.string.tutorial_expanded else R.string.tutorial_collapsed)
        AppCard(Modifier.testTag(if (section == "source") "tutorial_source" else "tutorial_section_$section")) {
            AppRow(title, null, Modifier.testTag("tutorial_header_$section").semantics {
                heading()
                role = Role.Button
                stateDescription = description
            }, action = { expandedSection = if (expanded) null else section }, trailing = {
                AppNavigationChevron(Modifier.rotate(arrow))
            })
            AppSettingsReveal(expanded) {
                AppDivider()
                Column(Modifier.padding(18.dp).testTag("tutorial_body_$section"),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
    private fun addTile(tile: TileShortcut, scope: CoroutineScope) {
        if (addingTile) return
        if (PermissionChecks.read(host, PermissionChecks.Item.SHIZUKU) != PermissionChecks.State.GRANTED) {
            Toast.makeText(host, R.string.privilege_service_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        addingTile = true
        scope.launch {
            var connection: ServiceConnection? = null
            val message = try {
                val service = withTimeout(6000) {
                    suspendCancellableCoroutine<ITaskService> { continuation ->
                        val consumer = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                if (continuation.isActive) continuation.resume(ITaskService.Stub.asInterface(binder))
                            }
                            override fun onServiceDisconnected(name: ComponentName) { }
                        }
                        connection = consumer
                        TaskServiceManager.acquire(host, consumer)
                    }
                }
                withContext(Dispatchers.IO) { addTileWithService(tile, service) }
            } catch (_: TimeoutCancellationException) {
                R.string.tutorial_tile_add_failed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                R.string.tutorial_tile_add_failed
            } finally {
                connection?.let(TaskServiceManager::release)
                addingTile = false
            }
            Toast.makeText(host, getString(message, getString(tile.label)), Toast.LENGTH_LONG).show()
        }
    }
    private suspend fun addTileWithService(tile: TileShortcut, service: ITaskService): Int {
        // Components come only from the three bundled services; never rewrite the user's tile list.
        val component = ComponentName(host, tile.service).flattenToString()
        val spec = "custom($component)"
        fun listed() = service.executeShellCommandWithResult("settings --user current get secure sysui_qs_tiles")
            .trim().split(',').any { it == spec || it == "custom(${ComponentName(host, tile.service).flattenToShortString()})" }
        if (listed()) return R.string.tutorial_tile_already_added
        if (!service.executeShellCommand("cmd statusbar add-tile '$component'")) return R.string.tutorial_tile_add_failed
        // SystemUI updates asynchronously. A successful shell exit alone does not prove addition.
        repeat(20) {
            if (listed()) return R.string.tutorial_tile_added
            delay(100)
        }
        return R.string.tutorial_tile_add_failed
    }
    private fun openOriginal() {
        try { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProjectInfo.TUTORIAL_SOURCE))) }
        catch (_: android.content.ActivityNotFoundException) { openError() }
        catch (_: SecurityException) { openError() }
    }
    private fun openError() { Toast.makeText(host, NativeStrings.get(host, "open_failed"), Toast.LENGTH_LONG).show() }
    fun save(outState: Bundle) {
        outState.saveList("tutorial_scroll", list)
        outState.putString("tutorial_expanded", expandedSection)
    }
    private data class TileShortcut(val service: Class<*>, val label: Int, val tag: String)
    companion object {
        private val tileShortcuts = listOf(
            TileShortcut(SwitchToRearTileService::class.java, R.string.tile_label, "switch"),
            TileShortcut(RearScreenshotTileService::class.java, R.string.tile_screenshot_label, "screenshot"),
            TileShortcut(RearScreenRecordTileService::class.java, R.string.tile_record_label, "record"))
        val sections = listOf("setup", "permissions", "tiles", "casting", "capture", "display", "notifications", "background", "troubleshooting")
        private val titles = listOf(R.string.tutorial_setup_title, R.string.tutorial_permissions_title, R.string.tutorial_tiles_title, R.string.tutorial_casting_title,
            R.string.tutorial_capture_title, R.string.tutorial_display_title, R.string.tutorial_notifications_title, R.string.tutorial_background_title, R.string.tutorial_troubleshooting_title)
        private val steps = mapOf(
            "setup" to R.string.tutorial_setup_steps,
            "permissions" to R.string.tutorial_permissions_steps,
            "tiles" to R.string.tutorial_tiles_steps,
            "capture" to R.string.tutorial_capture_steps,
            "display" to R.string.tutorial_display_steps,
            "notifications" to R.string.tutorial_notifications_steps,
            "background" to R.string.tutorial_background_steps,
            "troubleshooting" to R.string.tutorial_troubleshooting_steps,
        )
        private val castingFaq = listOf(
            R.string.tutorial_cast_layout_question to R.string.tutorial_cast_layout_answer,
            R.string.tutorial_cast_size_question to R.string.tutorial_cast_size_answer,
            R.string.tutorial_cast_menu_question to R.string.tutorial_cast_menu_answer,
            R.string.tutorial_cast_mode_question to R.string.tutorial_cast_mode_answer,
            R.string.tutorial_cast_lock_question to R.string.tutorial_cast_lock_answer,
        )
    }
}
