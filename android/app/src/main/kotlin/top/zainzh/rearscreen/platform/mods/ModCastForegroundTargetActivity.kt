package top.zainzh.rearscreen.platform.mods

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.AppRow
import top.zainzh.rearscreen.PageFrame
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.CastMode
import top.zainzh.rearscreen.platform.ControlledCastRuntimeProvider
import top.zainzh.rearscreen.platform.HostCastForegroundChoice
import top.zainzh.rearscreen.platform.RearCapabilityCore
import top.zainzh.rearscreen.platform.SessionOwner

/** Opaque first-stage target chooser. It starts the transparent confirmation task before focus. */
class ModCastForegroundTargetActivity : ComponentActivity() {
    private var choices by mutableStateOf(emptyList<HostCastForegroundChoice>())
    private var status by mutableStateOf("正在读取主屏任务…")
    private var working by mutableStateOf(false)
    @Volatile private var cancelled = false
    private var handedOff = false
    private var completed = false
    private var source: ModCastActivationSource? = null
    private var modId: String? = null
    private var owner: SessionOwner? = null
    private val runtime by lazy { ControlledCastRuntimeProvider.get(this) }
    private val core by lazy { RearCapabilityCore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = readSource(intent)
        modId = source?.modId
        val installed = modId?.let { ModPackageStore.installed(this, it) }
        val verified = modId?.let { ModPackageStore.find(this, it) }
        owner = modId?.let(core::modDataOwner)
        val mode = intent.getStringExtra(EXTRA_MODE)
        val capability = if (mode == CastMode.VIRTUAL.name) "rear.cast.virtual" else "rear.cast.direct_physical"
        if (source?.surface != ModCastActivationSource.Surface.MAIN_SETTINGS ||
            source?.let { ModCastActivationSources.isRegistered(it, modId.orEmpty()) } != true ||
            installed == null || !installed.enabled || verified?.authorFingerprint != installed.authorFingerprint ||
            !ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint) || capability !in installed.grantedCapabilities ||
            owner == null) {
            finishWithError("E_MOD_REVOKED"); return
        }
        runtime.owners.modAuthorizationChanged(owner!!, true)
        miuixContent {
            ForegroundCastTargetScreen(choices, status, working, ::openConfirmationForChoice,
                cancel = { finishWithError("E_USER_CANCELLED") })
        }
        loadChoices()
    }

    private fun loadChoices() {
        val activeOwner = owner ?: return
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runtime.coordinator.listForegroundChoices(activeOwner) }
            if (cancelled || isFinishing || isDestroyed) return@launch
            choices = loaded
            status = if (loaded.isEmpty()) "没有可核实的主屏应用" else "选择一个应用后，再单独确认投放。"
        }
    }

    private fun openConfirmationForChoice(choice: HostCastForegroundChoice) {
        if (working || cancelled || !sourceIsCurrent()) return finishWithError("E_CAST_SOURCE_STALE")
        working = true
        status = "正在打开透明确认层…"
        val pickerIntent = ModCastTargetPickerActivity.createIntent(this, readEffect(intent), requireNotNull(source))
            .putExtra(ModCastTargetPickerActivity.EXTRA_MAIN_SETTINGS_FLOW, true)
            .putExtra(ModCastTargetPickerActivity.EXTRA_FOREGROUND_CHOICE_TOKEN, choice.opaqueToken)
            .putExtra(ModCastTargetPickerActivity.EXTRA_FOREGROUND_CHOICE_LABEL, choice.label)
            .putExtra(ModCastTargetPickerActivity.EXTRA_REQUEST_ID,
                intent.getStringExtra(ModCastTargetPickerActivity.EXTRA_REQUEST_ID))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(pickerIntent, android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
            handedOff = true
            finish()
        } catch (_: Exception) { finishWithError("E_TARGET_PICKER_UNAVAILABLE") }
    }

    private fun readEffect(i: Intent) = ModCastStartEffect(i.getStringExtra(ModCastTargetPickerActivity.EXTRA_EFFECT_ID).orEmpty(), i.getStringExtra(EXTRA_MODE).orEmpty(),
        i.getStringExtra(EXTRA_REGION), i.getIntExtra(EXTRA_ROTATION, -1).takeIf { it >= 0 },
        i.getIntExtra(EXTRA_DENSITY, 0).takeIf { it > 0 })

    private fun sourceIsCurrent(): Boolean = !cancelled && source?.let {
        ModCastActivationSources.isRegistered(it, modId.orEmpty()) &&
            ModCastActivationSources.isRequestCurrent(it,
                intent.getStringExtra(ModCastTargetPickerActivity.EXTRA_REQUEST_ID).orEmpty()) &&
            !isFinishing && !isDestroyed
    } == true

    private fun finishWithError(code: String) {
        if (completed) return
        completed = true
        working = false
        if (!handedOff) source?.let { ModCastActivationSources.complete(it,
            intent.getStringExtra(ModCastTargetPickerActivity.EXTRA_REQUEST_ID).orEmpty(), false, code) }
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !handedOff && !completed) finishWithError("E_CAST_SOURCE_STALE")
        cancelled = true
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MOD_ID = "mod_id"
        private const val EXTRA_MODE = "cast_mode"
        private const val EXTRA_REGION = "cast_region"
        private const val EXTRA_ROTATION = "cast_rotation"
        private const val EXTRA_DENSITY = "cast_density"

        fun createIntent(context: Context, effect: ModCastStartEffect, source: ModCastActivationSource, requestId: String): Intent =
            ModCastTargetPickerActivity.createIntent(context, effect, source)
                .putExtra(ModCastTargetPickerActivity.EXTRA_REQUEST_ID, requestId)
                .setClass(context, ModCastForegroundTargetActivity::class.java)

        private fun readSource(i: Intent): ModCastActivationSource? = runCatching {
            val surface = ModCastActivationSource.Surface.valueOf(i.getStringExtra(ModCastTargetPickerActivity.EXTRA_SOURCE_SURFACE).orEmpty())
            val session = i.getStringExtra(ModCastTargetPickerActivity.EXTRA_SOURCE_SESSION)
            val generation = i.getLongExtra(ModCastTargetPickerActivity.EXTRA_SOURCE_GENERATION, 0L).takeIf { it > 0 }
            ModCastActivationSource(i.getStringExtra(EXTRA_MOD_ID).orEmpty(), surface,
                i.getStringExtra(ModCastTargetPickerActivity.EXTRA_SOURCE_INSTANCE).orEmpty(), session, generation).takeIf { it.isWellFormed() }
        }.getOrNull()
    }
}

@Composable
internal fun ForegroundCastTargetScreen(
    choices: List<HostCastForegroundChoice>, status: String, working: Boolean,
    choose: (HostCastForegroundChoice) -> Unit, cancel: () -> Unit,
) {
    PageFrame("选择主屏应用", cancel, "foreground_choice_back") { modifier, _ ->
        Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false).testTag("foreground_choice_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item("help") {
                    Text("选中应用后，还需单独确认投放。", fontSize = 14.sp,
                        color = AppPalette.secondary(), modifier = Modifier.padding(vertical = 6.dp))
                }
                if (choices.isEmpty() || working) item("status") { Text(status, color = AppPalette.secondary()) }
                items(choices, key = { it.opaqueToken }) { choice ->
                    AppCard {
                        AppRow(choice.label, choice.packageName.takeUnless { it == choice.label },
                            enabled = !working, action = { choose(choice) })
                    }
                }
            }
            TextButton("取消", cancel, Modifier.fillMaxWidth().testTag("foreground_choice_cancel"),
                minHeight = 48.dp, cornerRadius = 16.dp, enabled = !working)
        }
    }
}
