package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.window.WindowDialog
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppActionPair
import top.zainzh.rearscreen.AppDialogDecisionContent
import top.zainzh.rearscreen.AppDivider
import top.zainzh.rearscreen.AiRearModWallpaperRecovery
import top.zainzh.rearscreen.AiRearWallpaperBinding
import top.zainzh.rearscreen.AiRearWallpaperEditorActivity
import top.zainzh.rearscreen.AiRearWallpaperInstallations
import top.zainzh.rearscreen.AppRow
import top.zainzh.rearscreen.AppToggle
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.ModWallpaperThemeKind
import top.zainzh.rearscreen.AppPageContent
import top.zainzh.rearscreen.AppRouteMotion
import top.zainzh.rearscreen.AppSection
import top.zainzh.rearscreen.AppStandaloneRoutes
import top.zainzh.rearscreen.MOD_DETAIL_ROUTE
import top.zainzh.rearscreen.MOD_MANAGEMENT_ROUTE
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.listState
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.saveList
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal fun modInstallSuccessNotice(name: String, replacing: Boolean, enabled: Boolean): String =
    if (replacing) "已更新 $name；原有配置与数据已保留，当前仍为${if (enabled) "启用" else "停用"}状态。"
    else "已安装 $name；默认保持停用，请先审核设置与能力。"

/** Retain only explicitly granted optional capabilities with an unchanged declaration. */
internal fun retainedOptionalGrants(old: InstalledMod?, candidate: VerifiedModPackage): Set<String> {
    if (old == null || old.authorFingerprint != candidate.authorFingerprint) return emptySet()
    return candidate.optionalCapabilities
        .filter { it in old.optionalCapabilities && it.name in old.grantedCapabilities }
        .mapTo(mutableSetOf()) { it.name }
}

private sealed interface ModDestructiveAction {
    data class RevokeAuthor(val mod: InstalledMod, val affectedMods: Int) : ModDestructiveAction
    data class Uninstall(val mod: InstalledMod) : ModDestructiveAction
}

/** Retained for callers that need a standalone Mod management window. */
class ModSettingsActivity : ComponentActivity() {
    private lateinit var page: ModSettingsPage
    private lateinit var motion: AppRouteMotion
    private var route by mutableIntStateOf(MOD_MANAGEMENT_ROUTE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("standalone_mod_detail") == true) route = MOD_DETAIL_ROUTE
        motion = AppRouteMotion(this, route) { if (it == MOD_DETAIL_ROUTE) MOD_MANAGEMENT_ROUTE else null }
        page = ModSettingsPage(this, savedInstanceState, intent.getStringExtra(EXTRA_FOCUS_MOD),
            navigateBack = ::finish, openDetail = { navigate(MOD_DETAIL_ROUTE) })
        if (route == MOD_DETAIL_ROUTE) page.reload()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (route == MOD_DETAIL_ROUTE && page.canPreviewBack)
                    motion.startBackPreview(route, MOD_MANAGEMENT_ROUTE, backEvent.progress)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                motion.updateBackPreview(backEvent.progress, android.os.SystemClock.uptimeMillis())
            }

            override fun handleOnBackCancelled() = motion.cancelBackPreview(route)

            override fun handleOnBackPressed() {
                if (page.consumeBack()) motion.cancelBackPreview(route)
                else if (route == MOD_DETAIL_ROUTE) navigate(MOD_MANAGEMENT_ROUTE)
                else finish()
            }
        })
        miuixContent {
            AppStandaloneRoutes(MOD_MANAGEMENT_ROUTE, route, motion) { current, active ->
                if (current == MOD_DETAIL_ROUTE) page.Detail(active) { navigate(MOD_MANAGEMENT_ROUTE) }
                else page.Content(active)
            }
        }
    }

    private fun navigate(target: Int) {
        val previous = route
        if (target == previous) return
        route = target
        motion.showPage(previous, target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        page.save(outState)
        outState.putBoolean("standalone_mod_detail", route == MOD_DETAIL_ROUTE)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        page.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FOCUS_MOD = "focus_mod"
        internal fun open(context: Context, modId: String? = null) =
            Intent(context, ModSettingsActivity::class.java).putExtra(EXTRA_FOCUS_MOD, modId)
    }
}

/** The list and signed-package flow are the same in either host. */
internal class ModSettingsPage(
    private val activity: ComponentActivity,
    savedState: Bundle?,
    initialFocus: String?,
    private val navigateBack: () -> Unit,
    private val openDetail: () -> Unit,
    private val canConfigureWallpaper: () -> Boolean = { AiRearWallpaperEditorActivity.supportsThemes() },
    private val openConfig: (String) -> Unit = {
        activity.startActivity(Intent(activity, ModConfigActivity::class.java).putExtra("mod_id", it))
    },
    private val openPreview: (String) -> Unit = {
        activity.startActivity(Intent(activity, ModPreviewActivity::class.java).putExtra("mod_id", it))
    },
    private val openWallpaper: (String) -> Unit = {
        activity.startActivity(AiRearWallpaperEditorActivity.open(activity, it))
    },
) : ContextWrapper(activity) {
    private var mods by mutableStateOf<List<InstalledMod>>(emptyList())
    private var loaded by mutableStateOf(false)
    private var pending by mutableStateOf<VerifiedModPackage?>(null)
    private var working by mutableStateOf(false)
    private var installing by mutableStateOf(false)
    private var installError by mutableStateOf("")
    private var error by mutableStateOf(savedState?.getString("error").orEmpty())
    private var notice by mutableStateOf(savedState?.getString("notice").orEmpty())
    private var feedbackModId by mutableStateOf(savedState?.getString("feedback_mod_id"))
    private var destructiveAction by mutableStateOf<ModDestructiveAction?>(null)
    private var focusMod by mutableStateOf(savedState?.getString("focus_mod") ?: initialFocus.takeIf { savedState == null })
    private var wallpaperWorks by mutableStateOf(emptyMap<String, List<String>>())
    private var closed = false
    private val list = savedState.listState("mod_list")
    private val detail = savedState.listState("mod_detail")
    private var detailId by mutableStateOf(savedState?.getString("detail_id"))

    fun selectDetail(id: String) {
        if (detailId == id) return
        detailId = id
        detail.requestScrollToItem(0)
    }
    private val scope get() = activity.lifecycleScope
    private val picker = activity.activityResultRegistry.register(
        "mod_management_zip", ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) inspectArchive(uri) }
    private val visibility = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = reload()
    }

    fun focus(modId: String) {
        focusMod = modId
    }

    fun save(state: Bundle) {
        state.saveList("mod_list", list)
        state.saveList("mod_detail", detail)
        state.putString("detail_id", detailId)
        state.putString("focus_mod", focusMod)
        state.putString("error", error)
        state.putString("notice", notice)
        state.putString("feedback_mod_id", feedbackModId)
    }

    fun close() {
        closed = true
        picker.unregister()
        activity.lifecycle.removeObserver(visibility)
    }

    val canPreviewBack get() = !installing && pending == null && destructiveAction == null

    fun consumeBack(): Boolean = when {
        installing -> true
        pending != null -> { dismissInstall(); true }
        destructiveAction != null -> { destructiveAction = null; true }
        else -> false
    }

    fun back() {
        if (!consumeBack()) navigateBack()
    }

    @Composable
    fun Content(active: Boolean) {
        LaunchedEffect(active, focusMod, loaded, mods) {
            if (!active || !loaded) return@LaunchedEffect
            val mod = focusMod ?: return@LaunchedEffect
            val index = mods.indexOfFirst { it.id == mod }
            if (index >= 0) {
                list.scrollToItem(index + 1 + (if (error.isNotEmpty()) 1 else 0) +
                    (if (notice.isNotEmpty()) 1 else 0))
            }
            focusMod = null
        }
        AppPageContent("Mod 管理", ::back, "mod_settings_back", scrollState = list) { modifier, inset ->
            ModList(modifier.testTag("mod_management_scroll"), list,
                PaddingValues(20.dp, inset + 12.dp, 20.dp, 24.dp))
        }
        Decisions(active)
    }

    @Composable
    fun TabContent(
        modifier: Modifier, state: LazyListState, tailPadding: Dp, active: Boolean, heading: @Composable () -> Unit,
    ) {
        ModList(modifier.testTag("modules_tab"), state, PaddingValues(20.dp, 22.dp, 20.dp, tailPadding),
            heading = heading)
        Decisions(active)
    }

    @Composable
    private fun ModList(
        modifier: Modifier, state: LazyListState, padding: PaddingValues, heading: (@Composable () -> Unit)? = null,
    ) {
        LazyColumn(modifier, state = state, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (heading != null) item("mod_tab_heading") { heading() }
            item("import") {
                AppCard(Modifier.testTag("mod_import_card")) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("导入 Mod", fontSize = 18.sp, color = AppPalette.text())
                        Text("安装前会显示作者和所需能力。", color = AppPalette.secondary(), fontSize = 13.sp)
                        TextButton(
                            if (working) "正在验证…" else "选择 ZIP 文件",
                            { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            Modifier.fillMaxWidth().testTag("mod_import_zip"),
                            enabled = !working, minHeight = 48.dp,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
            if (error.isNotEmpty()) item("error") { AppCard { Column(Modifier.padding(18.dp)) { Text(error, color = AppPalette.destructive()) } } }
            if (notice.isNotEmpty()) item("notice") { AppCard { Column(Modifier.padding(18.dp)) { Text(notice, color = AppPalette.secondary()) } } }
            if (loaded && mods.isEmpty()) item("empty") { AppCard { AppRow("尚未安装 Mod", null) } }
            items(mods, key = { it.id }) { mod ->
                AppCard(Modifier.testTag("mod_row_${mod.id}")) {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppRow(mod.name, "${mod.authorName} · ${mod.version}",
                            modifier = Modifier.weight(1f).testTag("mod_detail_entry_${mod.id}"),
                            action = { selectDetail(mod.id); openDetail() })
                        val enabledLabel = getString(R.string.mod_list_enable, mod.name)
                        Box(Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp)
                            .testTag("mod_enabled_${mod.id}")
                            .toggleable(mod.enabled, enabled = !working, role = Role.Switch) { changeEnabled(mod, it) }
                            .semantics { contentDescription = enabledLabel }, contentAlignment = Alignment.Center) {
                            Switch(mod.enabled, onCheckedChange = null, enabled = !working,
                                modifier = Modifier.clearAndSetSemantics { })
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Detail(active: Boolean, back: () -> Unit) {
        val mod = mods.firstOrNull { it.id == detailId }
        LaunchedEffect(active, loaded, mod, working) {
            if (active && loaded && mod == null && !working) back()
        }
        AppPageContent(mod?.name ?: getString(R.string.mod_detail_title), back, "mod_detail_back", scrollState = detail) { modifier, inset ->
            LazyColumn(modifier.testTag("mod_detail_scroll"), state = detail,
                contentPadding = PaddingValues(20.dp, inset + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mod != null) {
                    item("identity") {
                        AppCard { AppToggle("启用", "${mod.authorName} · ${mod.version}", mod.enabled, enabled = !working) {
                            changeEnabled(mod, it, fromDetail = true)
                        } }
                    }
                    if (feedbackModId == mod.id && error.isNotEmpty()) item("error") {
                        Text(error, Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = AppPalette.destructive())
                    }
                    if (feedbackModId == mod.id && notice.isNotEmpty()) item("notice") {
                        Text(notice, Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = AppPalette.secondary())
                    }
                    item("actions") { ModActions(mod) }
                    item("capabilities") { ModPermissions(mod) }
                    item("management") {
                        AppSection(getString(R.string.mod_detail_management))
                        AppCard {
                            AppRow("撤销作者信任", null, enabled = !working, action = {
                                destructiveAction = ModDestructiveAction.RevokeAuthor(mod,
                                    mods.count { it.authorFingerprint == mod.authorFingerprint })
                            })
                            AppDivider()
                            AppRow("卸载…", null, enabled = !working,
                                action = { destructiveAction = ModDestructiveAction.Uninstall(mod) })
                        }
                    }
                } else if (error.isNotEmpty()) item("error") { Text(error, color = AppPalette.destructive()) }
            }
        }
        Decisions(active)
    }

    @Composable
    private fun ModActions(mod: InstalledMod) {
        AppSection(getString(R.string.mod_detail_actions))
        AppCard {
            AppRow("本机预览", "同源原生渲染；不连接背屏", Modifier.testTag("mod_preview_${mod.id}"),
                action = { openPreview(mod.id) })
            AppDivider()
            AppRow("设置", if (mod.enabled) null else "启用此 Mod 后可配置",
                modifier = Modifier.testTag("mod_config_${mod.id}"), enabled = mod.enabled && !working,
                action = { openConfig(mod.id) })
            if (mod.wallpaperTheme != null && canConfigureWallpaper()) {
                val needsEnable = mod.wallpaperTheme == ModWallpaperThemeKind.DRAWN_CONTROLS && !mod.enabled
                AppDivider()
                AppRow(getString(if (needsEnable) R.string.ai_rear_enable_configure_theme else R.string.ai_rear_configure_theme),
                    wallpaperWorks[mod.id]?.takeIf { it.isNotEmpty() }
                        ?.joinToString(" · ") ?: getString(R.string.ai_rear_theme_no_work),
                    modifier = Modifier.testTag("mod_wallpaper_${mod.id}"), enabled = !working,
                    action = { configureWallpaper(mod, needsEnable) })
            }
            val optionalScene = mod.optionalCapabilities.any { it.name == "rear.scene" }
            if (optionalScene || mod.requiredCapabilities.any { it.name == "rear.scene" }) {
                val sceneAllowed = "rear.scene" in mod.grantedCapabilities
                val ready = sceneAllowed && mod.enabled
                val guidance = when {
                    !sceneAllowed && !optionalScene -> "更新此 Mod 时，请在安装审核中授权「背屏显示」。"
                    !sceneAllowed && !mod.enabled -> "在「能力与权限」开启「背屏显示」，再启用 Mod。"
                    !sceneAllowed -> "在「能力与权限」开启「背屏显示」。"
                    !mod.enabled -> "打开上方「启用」开关。"
                    else -> null
                }
                AppDivider()
                AppRow("显示到背屏", guidance, Modifier.testTag("mod_show_rear_${mod.id}"),
                    enabled = !working, action = if (ready) ({ openRear(mod.id) }) else null)
                if (ready) {
                    Text(getString(R.string.mod_scene_cover_behavior_note),
                        Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                        color = AppPalette.secondary(), fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun ModPermissions(mod: InstalledMod) {
        AppSection(getString(R.string.mod_detail_permissions))
        AppCard {
            Text("已授权 ${mod.grantedCapabilities.sorted().joinToString { capabilityLabel(it) }.ifEmpty { "无" }}",
                Modifier.padding(18.dp), fontSize = 13.sp, color = AppPalette.secondary())
            mod.optionalCapabilities.forEach { capability ->
                AppDivider()
                AppToggle(capabilityLabel(capability.name), "可单独授权或撤销",
                    capability.name in mod.grantedCapabilities, enabled = !working) { enabled ->
                    changeGrant(mod, capability, enabled)
                }
            }
        }
    }

    @Composable
    private fun Decisions(active: Boolean) {
        if (!active) return
        DisposableEffect(activity.lifecycle) {
            activity.lifecycle.addObserver(visibility)
            onDispose { activity.lifecycle.removeObserver(visibility) }
        }
        val candidate = pending
        WindowDialog(show = candidate != null, title = "审核 Mod 作者与能力",
            onDismissRequest = { if (!installing) dismissInstall() }) {
            candidate?.let { pkg ->
                val old = mods.firstOrNull { it.id == pkg.id }
                val retained = remember(pkg, old) { retainedOptionalGrants(old, pkg) }
                var selected by remember(pkg, old) { mutableStateOf(retained) }
                val declared = pkg.requiredCapabilities.map { it.name }.toSet()
                val willRevoke = old?.grantedCapabilities.orEmpty() - declared - selected
                val verb = when {
                    installing -> "正在处理…"
                    old != null -> "确认更新并保留数据"
                    else -> "信任此作者并安装"
                }
                AppDialogDecisionContent(
                    footer = {
                        AppActionPair("取消", ::dismissInstall, verb, { install(pkg, selected) },
                            secondaryEnabled = !installing, primaryEnabled = !working)
                    },
                ) {
                    Text("${pkg.name} · ${pkg.version} (${pkg.versionCode})", color = AppPalette.text())
                    Text("作者：${pkg.authorName}\nRSA 指纹：${pkg.authorFingerprint}", fontSize = 12.sp, color = AppPalette.secondary())
                    Text("必需能力：${pkg.requiredCapabilities.joinToString { "${capabilityLabel(it.name)}（${it.name}）v${it.minimumVersion}" }.ifEmpty { "无" }}", color = AppPalette.text())
                    Text("权限声明：必需 ${pkg.requiredPermissions.joinToString().ifEmpty { "无" }}；可选 ${pkg.optionalPermissions.joinToString().ifEmpty { "无" }}。声明不是系统权限授权。", fontSize = 12.sp, color = AppPalette.secondary())
                    if (pkg.optionalCapabilities.isNotEmpty()) {
                        Text(if (old == null) "可选能力（默认关闭）" else "可选能力（新增或变化的能力需要确认）", fontSize = 14.sp, color = AppPalette.text())
                        pkg.optionalCapabilities.forEach { capability ->
                            val name = capability.name
                            val change = when {
                                name in retained -> "此前已授权；关闭将撤销"
                                name in old?.grantedCapabilities.orEmpty() -> "授权声明已变化，请重新选择"
                                old != null -> "此前未授权"
                                else -> null
                            }
                            AppToggle("${capabilityLabel(name)}（$name）v${capability.minimumVersion}", change,
                                name in selected, enabled = !installing) { enabled ->
                                selected = if (enabled) selected + name else selected - name
                            }
                        }
                    }
                    if (willRevoke.isNotEmpty()) Text(
                        "更新后将移除：${willRevoke.sorted().joinToString("、") { capabilityLabel(it) }}",
                        fontSize = 13.sp, color = AppPalette.secondary())
                    Text("信任作者不等于授予全部能力。", fontSize = 12.sp, color = AppPalette.secondary())
                    if (installError.isNotBlank()) Text(installError, color = AppPalette.destructive(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        val action = destructiveAction
        WindowDialog(show = action != null, title = when (action) {
            is ModDestructiveAction.RevokeAuthor -> "撤销作者信任？"
            is ModDestructiveAction.Uninstall -> "卸载 Mod？"
            null -> "确认操作"
        }, onDismissRequest = { destructiveAction = null }) {
            when (action) {
                is ModDestructiveAction.RevokeAuthor -> ModDangerActions("撤销信任", { destructiveAction = null },
                    { destructiveAction = null; revokeAuthor(action.mod) }) {
                    Text("将停用该作者的 ${action.affectedMods} 个 Mod，已保存的数据会保留。作者再次被信任前，这些 Mod 都不能运行。",
                        color = AppPalette.text())
                }
                is ModDestructiveAction.Uninstall -> ModDangerActions("卸载并删除数据", { destructiveAction = null },
                    { destructiveAction = null; uninstall(action.mod) }, enabled = !working) {
                    Text("将移除 ${action.mod.name} 的安装包、配置和本地数据；此操作不能撤销。", color = AppPalette.text())
                    wallpaperWorks[action.mod.id]?.takeIf { it.isNotEmpty() }?.let {
                        Text(getString(R.string.ai_rear_uninstall_work_list, it.joinToString("、")), color = AppPalette.secondary())
                    }
                }
                null -> Unit
            }
        }
    }

    @Composable
    private fun ModDangerActions(label: String, cancel: () -> Unit, confirm: () -> Unit,
        enabled: Boolean = true, details: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        AppDialogDecisionContent(
            footer = { AppActionPair("取消", cancel, label, confirm, primaryEnabled = enabled, destructive = true) },
            body = details,
        )
    }

    private fun reportFeedback(message: String, failed: Boolean = false, modId: String? = null) {
        feedbackModId = modId
        error = if (failed) message else ""
        notice = if (failed) "" else message
    }

    fun reload() {
        val installed = runCatching { ModPackageStore.list(this) }.getOrElse {
            reportFeedback(it.message ?: "无法读取 Mod 列表", failed = true)
            return
        }
        mods = installed
        val installations = AiRearWallpaperInstallations.forApp(this)
        val prefs = getSharedPreferences(AiRearWallpaperBinding.PREFS, MODE_PRIVATE)
        wallpaperWorks = mods.associate { mod -> mod.id to runCatching {
            installations.forMod(mod.id).map {
                val suffix = it.id.takeLast(6)
                prefs.getString(it.titleKey, null)?.takeIf(String::isNotBlank)?.let { title -> "$title · $suffix" } ?: suffix
            }
        }.getOrDefault(emptyList()) }
        loaded = true
    }

    private fun configureWallpaper(mod: InstalledMod, enable: Boolean) {
        if (working || closed) return
        if (!enable) {
            openWallpaper(mod.id)
            return
        }
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ModPackageStore.setEnabled(activity, mod.id, true) }
            }
            working = false
            result.onSuccess {
                reload()
                if (!closed) openWallpaper(mod.id)
            }.onFailure { reportFeedback(it.message ?: "启用 Mod 失败", failed = true, modId = mod.id) }
        }
    }

    private fun inspectArchive(uri: Uri) {
        working = true
        reportFeedback("")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "无法打开 ZIP" }; readBounded(input, ModPackageReader.MAX_ZIP_BYTES) }
                    .let { bytes -> bytes to ModPackageReader.read(bytes.inputStream()) }
                }
            }
            working = false
            result.onSuccess { (bytes, pkg) -> stagedArchive = bytes; pending = pkg }
                .onFailure { error = it.message ?: "Mod 包无效" }
        }
    }

    private var stagedArchive by mutableStateOf<ByteArray?>(null)

    private fun dismissInstall() {
        pending = null
        stagedArchive = null
        installError = ""
    }

    private fun install(pkg: VerifiedModPackage, optional: Set<String>) {
        if (working) return
        val archive = stagedArchive ?: run { pending = null; return }
        val selected = optional + pkg.requiredCapabilities.map { it.name }
        working = true
        installing = true
        installError = ""
        val old = ModPackageStore.installed(this, pkg.id)
        if (old == null) commitInstall(pkg, archive, selected, null)
        else ModBrokerProvider.get(this).beginPackageReplacement(old.id, mainExecutor) { drained, reason ->
            if (!drained) {
                working = false
                installing = false
                installError = reason ?: "无法安全更新 Mod"
            } else commitInstall(pkg, archive, selected, old)
        }
    }

    private fun commitInstall(pkg: VerifiedModPackage, archive: ByteArray, selected: Set<String>, old: InstalledMod?) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModPackageStore.install(activity, archive, ModInstallDecision(pkg.authorFingerprint, selected), IsolatedModMigrationRunner(activity))
                }
            }
            var oldCastCleanupRequested = old == null
            if (old != null && result.isSuccess) {
                oldCastCleanupRequested = withContext(Dispatchers.IO) {
                    runCatching { closeModSessions(old)?.let { it.authorizationRevoked && it.cleanupRequested } == true }.getOrDefault(false)
                }
                withContext(Dispatchers.IO) {
                    runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(activity).displaySessions.closeOwnerSessions(sessionOwner(old), top.zainzh.rearscreen.platform.SceneCloseReason.OWNER_REVOKED) }
                }
            }
            if (old != null) {
                val closeUnconfirmed = isRuntimeCloseUnconfirmed(result.exceptionOrNull())
                // Never rebind the old worker merely because migration teardown
                // could not confirm slot release. Keep the Mod paused until an
                // explicit close completion path can safely resume it.
                if (!closeUnconfirmed) ModBrokerProvider.get(activity).finishPackageReplacement(old.id, result.isSuccess)
            }
            working = false
            installing = false
            result.onSuccess {
                dismissInstall()
                reload()
                if (old == null && it.wallpaperTheme != null) focusMod = it.id
                reportFeedback(when {
                    old == null -> modInstallSuccessNotice(it.name, false, it.enabled)
                    oldCastCleanupRequested -> "已更新 ${it.name}；旧 Cast owner 已失权并提交异步收尾，实际结束状态待核验。"
                    else -> "已更新 ${it.name}；旧 Cast owner 清理请求未确认，需继续核实。"
                })
            }.onFailure { installError = it.message ?: "未能安装 Mod；请检查原因后重试。" }
        }
    }

    private fun changeEnabled(mod: InstalledMod, enabled: Boolean, fromDetail: Boolean = false) {
        val feedbackTarget = mod.id.takeIf { fromDetail }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val updated = ModPackageStore.setEnabled(activity, mod.id, enabled)
                val cleanupRequested = enabled || runCatching { closeModSessions(updated)?.let { it.authorizationRevoked && it.cleanupRequested } == true }.getOrDefault(false)
                cleanupRequested
            }
                .onSuccess { cleanupRequested -> withContext(Dispatchers.Main) {
                    reload()
                    val message = if (enabled) "Mod 已启用" else if (cleanupRequested)
                        "Mod 已停用；Cast owner 已失权并提交异步收尾，结束状态待核验。"
                    else "Mod 已停用；Cast owner 收尾请求未确认，授权保持撤销。"
                    reportFeedback(message, modId = feedbackTarget)
                } }
                .onFailure { withContext(Dispatchers.Main) {
                    reload()
                    reportFeedback(it.message ?: "状态更新失败", failed = true, modId = feedbackTarget)
                } }
        }
    }

    private fun changeGrant(mod: InstalledMod, capability: ModCapability, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val updated = ModPackageStore.setCapabilityGrant(activity, mod.id, capability.name, capability.minimumVersion, enabled)
                val cleanupRequested = enabled || runCatching { closeModSessions(updated)?.let { it.authorizationRevoked && it.cleanupRequested } == true }.getOrDefault(false)
                cleanupRequested
            }
                .onSuccess { cleanupRequested -> withContext(Dispatchers.Main) {
                    reload()
                    val message = if (enabled) "能力已授权" else if (cleanupRequested)
                        "能力已撤销；Cast owner 已失权并提交异步收尾，结束状态待核验。"
                    else "能力已撤销；Cast owner 收尾请求未确认，授权保持撤销。"
                    reportFeedback(message, modId = mod.id)
                } }
                .onFailure { withContext(Dispatchers.Main) {
                    reload()
                    reportFeedback(it.message ?: "授权更新失败", failed = true, modId = mod.id)
                } }
        }
    }

    private fun revokeAuthor(mod: InstalledMod) {
        scope.launch(Dispatchers.IO) {
            val ids = runCatching { ModPackageStore.revokeAuthorTrust(activity, mod.authorFingerprint) }
                .getOrElse {
                    withContext(Dispatchers.Main) { reportFeedback(it.message ?: "作者信任撤销失败", failed = true, modId = mod.id) }
                    return@launch
                }
            var allCleanupRequested = true
            ids.mapNotNull { id -> ModPackageStore.installed(activity, id) }
                .forEach { installed ->
                    val requested = runCatching { closeModSessions(installed)?.let { it.authorizationRevoked && it.cleanupRequested } == true }.getOrDefault(false)
                    if (!requested) allCleanupRequested = false
                }
            withContext(Dispatchers.Main) {
                reload()
                val message = if (allCleanupRequested)
                    "已撤销作者信任并停用 ${ids.size} 个 Mod；Cast owner 收尾已提交，结束状态待核验。"
                else "作者信任已撤销并停用 ${ids.size} 个 Mod；至少一个 Cast owner 收尾请求未确认，授权保持撤销。"
                reportFeedback(message, modId = mod.id)
            }
        }
    }

    private fun closeModSessions(mod: InstalledMod): top.zainzh.rearscreen.platform.ModCastRevocationSubmission? {
        val castCleanup = runCatching {
            top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, sessionOwner(mod))
        }.getOrNull()
        runCatching { ModBrokerProvider.get(this).invalidateAndWait(mod.id) }
        runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).displaySessions.closeOwnerSessions(sessionOwner(mod), top.zainzh.rearscreen.platform.SceneCloseReason.OWNER_REVOKED) }
        return castCleanup
    }

    private fun uninstall(mod: InstalledMod) {
        if (working) return
        working = true
        scope.launch(Dispatchers.IO) {
          try {
            val restored = try {
                AiRearModWallpaperRecovery.restoreBeforeUninstall(activity, mod.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    reload()
                    val message = if (failure.message == getString(R.string.ai_rear_uninstall_connect))
                        getString(R.string.ai_rear_uninstall_connect)
                    else getString(R.string.ai_rear_uninstall_restore_first)
                    reportFeedback(message, failed = true, modId = mod.id)
                }
                return@launch
            }
            val disabled = runCatching { ModPackageStore.setEnabled(activity, mod.id, false) }
            if (disabled.isFailure) {
                withContext(Dispatchers.Main) {
                    reportFeedback(disabled.exceptionOrNull()?.message ?: "无法停用 Mod", failed = true, modId = mod.id)
                }
                return@launch
            }
            val workerDrained = runCatching {
                ModBrokerProvider.get(activity).invalidateAndWait(mod.id, "E_MOD_UNINSTALLED", "Mod 已卸载")
            }.isSuccess
            val castCleanup = runCatching {
                top.zainzh.rearscreen.platform.revokeModControlledCastOwner(activity, sessionOwner(mod))
            }.getOrNull()
            if (!workerDrained || castCleanup?.let { it.authorizationRevoked && it.cleanupRequested } != true) {
                withContext(Dispatchers.Main) {
                    reportFeedback("Mod 已停用且授权保持撤销，但清理请求未确认；为保留恢复依据，暂不删除安装数据。",
                        failed = true, modId = mod.id)
                }
                return@launch
            }
            runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(activity).displaySessions.closeOwnerSessions(sessionOwner(mod), top.zainzh.rearscreen.platform.SceneCloseReason.OWNER_REVOKED) }
            val removal = runCatching { ModPackageStore.uninstall(activity, mod.id) }
            if (removal.isSuccess) withContext(Dispatchers.Main) {
                reload()
                val message = if (restored > 0) getString(R.string.ai_rear_mod_uninstalled, mod.name)
                    else "已卸载 ${mod.name} 并删除本地数据；Cast owner 收尾已提交，实际结束状态待核验。"
                reportFeedback(message)
            } else withContext(Dispatchers.Main) {
                reload()
                reportFeedback(removal.exceptionOrNull()?.message ?: "卸载失败；Mod 保持停用。", failed = true, modId = mod.id)
            }
          } finally {
            withContext(Dispatchers.Main) { working = false }
          }
        }
    }

    private fun openRear(id: String) {
        val mod = ModPackageStore.installed(this, id) ?: return
        if (!mod.enabled || "rear.scene" !in mod.grantedCapabilities) {
            reportFeedback("作者未声明或用户未授权 rear.scene", failed = true, modId = id)
            return
        }
        val verified = ModPackageStore.find(this, id) ?: return
        if (verified.authorFingerprint != mod.authorFingerprint ||
            !ModPackageStore.isAuthorTrusted(this, mod.authorFingerprint)) {
            reportFeedback("无法验证此 Mod 的作者信任状态", failed = true, modId = id)
            return
        }
        val request = initialModSceneRequest(ComponentName(this, ModRearSceneActivity::class.java), mod.grantedCapabilities)
        val owner = sessionOwner(mod)
        val core = top.zainzh.rearscreen.platform.RearCapabilityCore.get(this)
        val installedOwnerIsCurrent = {
            val current = ModPackageStore.installed(this, id)
            current != null && current.enabled && current.id == mod.id &&
                current.version == mod.version && current.versionCode == mod.versionCode &&
                current.authorFingerprint == mod.authorFingerprint &&
                current.logicalSessionId == mod.logicalSessionId &&
                current.grantedCapabilities == mod.grantedCapabilities &&
                ModPackageStore.isAuthorTrusted(this, mod.authorFingerprint) &&
                ModPackageStore.find(this, id)?.let { it.authorFingerprint == mod.authorFingerprint &&
                    it.version == mod.version && it.versionCode == mod.versionCode } == true
        }
        reportFeedback("正在连接背屏…", modId = id)
        scope.launch(Dispatchers.IO) {
            val reopenCoordinator = core.displaySessions as? ModSceneReopenCoordinator
            if (reopenCoordinator == null) {
                android.util.Log.w("ModSceneOpen", "stage=reopen_route_unavailable")
                withContext(Dispatchers.Main) {
                    reportFeedback("背屏服务暂不可用；Mod 设置仍可在本机使用。", failed = true, modId = id)
                }
                return@launch
            }
            val reopen = reopenCoordinator.reopenOwnedModScene(
                owner = owner,
                component = request.component,
                grantedCapabilities = mod.grantedCapabilities,
                isInstalledOwnerCurrent = installedOwnerIsCurrent,
            )
            when (reopen) {
                is ModSceneReopenResult.Resumed -> {
                    android.util.Log.i("ModSceneOpen", "stage=existing_session_resumed session=${reopen.sessionId} generation=${reopen.generation} region=${reopen.region.name}")
                    withContext(Dispatchers.Main) { reportFeedback("已恢复原有背屏场景。", modId = id) }
                    return@launch
                }
                ModSceneReopenResult.Busy -> {
                    android.util.Log.i("ModSceneOpen", "stage=reopen_busy region=${request.region.name} keepVisible=${request.keepVisible}")
                    withContext(Dispatchers.Main) { reportFeedback("背屏正在使用中；请稍后再试。", failed = true, modId = id) }
                    return@launch
                }
                is ModSceneReopenResult.Rejected -> {
                    android.util.Log.i("ModSceneOpen", "stage=reopen_rejected error=${reopen.error.name}")
                    withContext(Dispatchers.Main) {
                        reportFeedback(sceneErrorMessage(reopen.error), failed = true, modId = id)
                    }
                    return@launch
                }
                is ModSceneReopenResult.Pending -> {
                    android.util.Log.i("ModSceneOpen", "stage=reopen_pending session=${reopen.sessionId} generation=${reopen.generation} reason=${reopen.reason.name}")
                    val confirmed = withTimeoutOrNull(5_000L) {
                        while (true) {
                            val snapshot = runCatching {
                                core.displaySessions.queryScenePresentation(owner, reopen.sessionId, reopen.generation)
                            }.getOrNull()
                            if (snapshot?.sessionId == reopen.sessionId && snapshot.generation == reopen.generation && snapshot.visible) {
                                return@withTimeoutOrNull true
                            }
                            delay(250L)
                        }
                        false
                    } ?: false
                    withContext(Dispatchers.Main) {
                        if (confirmed) reportFeedback("已恢复原有背屏场景。", modId = id)
                        else reportFeedback("原有背屏场景尚未确认显示，请检查背屏后重试。", failed = true, modId = id)
                    }
                    return@launch
                }
                ModSceneReopenResult.NoExistingSession -> {
                    android.util.Log.i("ModSceneOpen", "stage=no_owned_session region=${request.region.name} keepVisible=${request.keepVisible}")
                }
            }

            if (!installedOwnerIsCurrent()) {
                withContext(Dispatchers.Main) {
                    reportFeedback("此 Mod 的安装或背屏授权已变化，请重新打开。", failed = true, modId = id)
                }
                return@launch
            }
            val token = runCatching {
                core.issueSceneActivationAfterUserConfirmation(owner, request)
            }.getOrElse { exception ->
                withContext(Dispatchers.Main) {
                    reportFeedback("无法打开背屏：${exception.message ?: "授权暂不可用"}；Mod 设置仍可在本机使用。",
                        failed = true, modId = id)
                }
                return@launch
            }
            if (!installedOwnerIsCurrent()) {
                withContext(Dispatchers.Main) {
                    reportFeedback("此 Mod 的安装或背屏授权已变化，请重新打开。", failed = true, modId = id)
                }
                return@launch
            }
            val result = runCatching {
                core.displaySessions.openScene(owner, request, token, object : top.zainzh.rearscreen.platform.SceneObserver {
                    override fun onSceneEvent(sessionId: String, generation: Long, event: top.zainzh.rearscreen.platform.SceneEvent) {
                        when (event) {
                            top.zainzh.rearscreen.platform.SceneEvent.BackendLost -> scope.launch(Dispatchers.Main) {
                                reportFeedback("背屏服务已断开；Mod 设置仍可在本机使用。", failed = true, modId = id)
                            }
                            is top.zainzh.rearscreen.platform.SceneEvent.Closed -> scope.launch(Dispatchers.Main) {
                                reportFeedback("背屏场景已关闭：${event.reason}", failed = true, modId = id)
                            }
                            else -> Unit
                        }
                    }
                })
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { opened ->
                    when (opened) {
                        is top.zainzh.rearscreen.platform.SceneOpenResult.Rejected -> {
                            android.util.Log.i("ModSceneOpen", "stage=busy_or_rejected error=${opened.code.name} retryable=${opened.retryable} region=${request.region.name} keepVisible=${request.keepVisible}")
                            reportFeedback(sceneErrorMessage(opened.code), failed = true, modId = id)
                        }
                        is top.zainzh.rearscreen.platform.SceneOpenResult.Accepted -> {
                            android.util.Log.i("ModSceneOpen", "stage=new_session_accepted session=${opened.handle.sessionId} generation=${opened.handle.generation} region=${request.region.name} rotation=${request.contentRotation} keepVisible=${request.keepVisible}")
                            reportFeedback("已请求显示到背屏。", modId = id)
                        }
                    }
                }.onFailure { exception ->
                    android.util.Log.i("ModSceneOpen", "stage=open_failed type=${exception.javaClass.simpleName}")
                    reportFeedback("无法打开背屏：${exception.message ?: "服务暂不可用"}；Mod 设置仍可在本机使用。",
                        failed = true, modId = id)
                }
            }
        }
    }

    private fun sceneErrorMessage(code: top.zainzh.rearscreen.platform.SceneError): String = when (code) {
        top.zainzh.rearscreen.platform.SceneError.BACKEND_UNAVAILABLE -> "背屏服务不可用；Mod 设置仍可在本机使用。"
        top.zainzh.rearscreen.platform.SceneError.UNSUPPORTED -> "当前设备不支持背屏显示；Mod 设置仍可在本机使用。"
        top.zainzh.rearscreen.platform.SceneError.DISPLAY_UNAVAILABLE -> "背屏显示区域暂不可用；Mod 设置仍可在本机使用。"
        top.zainzh.rearscreen.platform.SceneError.DISPLAY_BUSY -> "背屏正在使用中；请稍后再试。"
        top.zainzh.rearscreen.platform.SceneError.CAPABILITY_DENIED -> "此 Mod 未获背屏显示授权。"
        top.zainzh.rearscreen.platform.SceneError.STALE_SESSION -> "背屏请求已过期，请重新打开。"
        top.zainzh.rearscreen.platform.SceneError.INVALID_COMPONENT,
        top.zainzh.rearscreen.platform.SceneError.INVALID_STATE,
        top.zainzh.rearscreen.platform.SceneError.LIMIT_EXCEEDED,
        top.zainzh.rearscreen.platform.SceneError.STORAGE_FAILED -> "背屏请求未完成：$code"
    }

    private fun sessionOwner(mod: InstalledMod): top.zainzh.rearscreen.platform.SessionOwner {
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        val cert = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        val digest = cert?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } } ?: error("无法确定宿主签名")
        return top.zainzh.rearscreen.platform.SessionOwner(android.os.Process.myUid(), packageName, digest,
            top.zainzh.rearscreen.platform.SessionPrincipal.SignedMod(mod.authorFingerprint, mod.id, mod.logicalSessionId))
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= limit) { "ZIP 超过 2 MiB" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    private fun capabilityLabel(name: String): String = when (name) {
        ModRearInfoBindings.CAPABILITY -> "背屏信息与当前场景视口"
        "data.battery" -> "电池信息"
        "data.systemMetrics" -> "设备运行指标"
        "rear.scene" -> "背屏显示"
        "rear.scene.keep_visible" -> "保持显示"
        "rear.notification" -> "发布自有背屏通知"
        "rear.info" -> "背屏信息"
        else -> name
    }

    private fun brokerListener(done: (Boolean, String?) -> Unit) = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) = Unit
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) = Unit
        override fun onError(modId: String, code: String, message: String) { done(false, message) }
        override fun onEffectRequested(modId: String, eventId: String) = Unit
    }
}
