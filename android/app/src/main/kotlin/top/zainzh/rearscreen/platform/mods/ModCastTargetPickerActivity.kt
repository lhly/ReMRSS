package top.zainzh.rearscreen.platform.mods

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.BuildConfig
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.CastApprovalFailure
import top.zainzh.rearscreen.CastApprovalPane
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.CastCommitResult
import top.zainzh.rearscreen.platform.CastEvent
import top.zainzh.rearscreen.platform.CastMode
import top.zainzh.rearscreen.platform.CastObserver
import top.zainzh.rearscreen.platform.CastPrepareResult
import top.zainzh.rearscreen.platform.CastRegion
import top.zainzh.rearscreen.platform.CastTargetRequest
import top.zainzh.rearscreen.platform.CastTargetSelector
import top.zainzh.rearscreen.platform.ControlledCastRuntimeProvider
import top.zainzh.rearscreen.platform.RearCapabilityCore
import top.zainzh.rearscreen.platform.SessionOwner
import java.util.concurrent.atomic.AtomicReference

/** Main-display picker and second approval for one Mod-owned Cast request. */
class ModCastTargetPickerActivity : ComponentActivity() {
    private var message by mutableStateOf("正在读取可投放的主屏应用…")
    private var error by mutableStateOf("")
    private var choices by mutableStateOf(emptyList<top.zainzh.rearscreen.platform.HostCastTargetChoice>())
    private var selectedChoice by mutableStateOf<top.zainzh.rearscreen.platform.HostCastTargetChoice?>(null)
    private var prepared by mutableStateOf<top.zainzh.rearscreen.platform.PreparedCastTicket?>(null)
    private var owner: SessionOwner? = null
    private var modId: String? = null
    private var mode: CastMode? = null
    private var region: CastRegion? = null
    private var rotation: Int? = null
    private var density: Int? = null
    private var source: ModCastActivationSource? = null
    private var deliveredHandle: top.zainzh.rearscreen.platform.CastSessionHandle? = null
    private var completed = false
    private var committing by mutableStateOf(false)
    private var uiReady = false
    private var terminalFailure by mutableStateOf<String?>(null)
    private var mainChoicePreparationStarted = false
    private val mainSettingsFlow by lazy { intent.getBooleanExtra(EXTRA_MAIN_SETTINGS_FLOW, false) }
    private val requestId by lazy { intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty() }
    private val core by lazy { RearCapabilityCore.get(this) }
    private val runtime by lazy { ControlledCastRuntimeProvider.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        traceFocus("picker_created")
        savedInstanceState?.getString("terminal_failure")?.let { code ->
            completed = true
            terminalFailure = code
            setResult(Activity.RESULT_CANCELED, intent.putExtra(EXTRA_RESULT_CODE, code))
            renderPicker()
            return
        }
        modId = intent.getStringExtra(EXTRA_MOD_ID)
        mode = runCatching { CastMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrNull()
        region = intent.getStringExtra(EXTRA_REGION)?.let { runCatching { CastRegion.valueOf(it) }.getOrNull() }
        rotation = intent.getIntExtra(EXTRA_ROTATION, -1).takeIf { it >= 0 }
        density = intent.getIntExtra(EXTRA_DENSITY, 0).takeIf { it > 0 }
        source = readSource(intent)
        val installed = modId?.let { ModPackageStore.installed(this, it) }
        val verified = modId?.let { ModPackageStore.find(this, it) }
        val currentOwner = modId?.let(core::modDataOwner)
        val castCapability = when (mode) {
            CastMode.VIRTUAL -> "rear.cast.virtual"
            CastMode.DIRECT_PHYSICAL -> "rear.cast.direct_physical"
            null -> ""
        }
        if (installed == null || verified == null || currentOwner == null || !installed.enabled ||
            installed.authorFingerprint != verified.authorFingerprint ||
            !ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint) ||
            castCapability !in installed.grantedCapabilities || mode == null ||
            source?.let { ModCastActivationSources.isRegistered(it, modId.orEmpty()) } != true ||
            mainSettingsFlow && requestId.isBlank() ||
            (region == CastRegion.FULL && "rear.region" !in installed.grantedCapabilities) ||
            (rotation != null && "rear.scene.orientation" !in installed.grantedCapabilities)) {
            currentOwner?.let { runCatching { top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, it) } }
            fail("E_MOD_REVOKED")
            return
        }
        owner = currentOwner
        runtime.owners.modAuthorizationChanged(currentOwner, true)
        if (mainSettingsFlow) {
            message = "正在核对所选主屏任务…"
        } else loadChoices()
        renderPicker()
    }

    private fun renderPicker() {
        uiReady = true
        miuixContent {
            val terminal = terminalFailure
            val ticket = prepared
            when {
                terminal != null -> CastApprovalFailure("未能启动背屏投放", castFailureMessage(terminal), ::finish)
                ticket != null -> CastApprovalPane(
                    application = selectedChoice?.label ?: "已选应用",
                    mode = if (ticket.target.mode == CastMode.VIRTUAL) "虚拟投放" else "物理直投",
                    region = if (ticket.target.region == CastRegion.FULL) "全域" else "安全区",
                    rotationDegrees = ticket.target.contentRotation * 90,
                    occupancy = replacementSummary(ticket),
                    secondaryLabel = if (mainSettingsFlow) "取消" else "返回选择",
                    submitting = committing, submittingLabel = "正在启动…",
                    secondaryEnabled = mainSettingsFlow || !committing,
                    onSecondary = if (mainSettingsFlow) ::finishCancelled else ::cancelPrepared,
                    onConfirm = ::confirmPrepared,
                    onCancelFlow = if (mainSettingsFlow) null else ::finishCancelled,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("intro") {
                        AppCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("选择要投放的主屏应用", fontSize = 22.sp, color = AppPalette.text())
                                Text("核对目标和占用后再确认；确认前不会切换任务。", color = AppPalette.secondary())
                            }
                        }
                    }
                    error.takeIf { it.isNotBlank() }?.let { detail ->
                        item("error") { Text(detail, color = AppPalette.destructive(),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    }
                    if (choices.isEmpty()) item("message") { Text(message, color = AppPalette.secondary()) }
                    items(choices, key = { it.opaqueToken }) { choice ->
                        AppCard(Modifier.fillMaxWidth().clickable { select(choice) }) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(choice.label)
                                Text(choice.packageName, color = AppPalette.secondary())
                            }
                        }
                    }
                    item("cancel") { TextButton("取消", ::finishCancelled, Modifier.fillMaxWidth(), minHeight = 48.dp) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        terminalFailure?.let { outState.putString("terminal_failure", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        traceFocus("picker_resumed")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        traceFocus("picker_window_focus", hasFocus)
        if (hasFocus && !completed && mainSettingsFlow && !mainChoicePreparationStarted) {
            mainChoicePreparationStarted = true
            prepareMainSettingsChoice()
        }
    }

    private fun prepareMainSettingsChoice() {
        val activeOwner = owner ?: return fail("E_MOD_REVOKED")
        val activeMod = modId ?: return fail("E_MOD_REVOKED")
        val activeSource = source ?: return fail("E_CAST_SOURCE_STALE")
        val token = intent.getStringExtra(EXTRA_FOREGROUND_CHOICE_TOKEN)?.takeIf(String::isNotBlank)
            ?: return fail("E_TARGET_CHANGED")
        traceFocus("target_token_accepted", true)
        lifecycleScope.launch {
            val coroutineJob = currentCoroutineContext().job
            val preparedRef = AtomicReference<top.zainzh.rearscreen.platform.PreparedCastTicket?>()
            var adopted = false
            try {
                val pickerComponent = ComponentName(this@ModCastTargetPickerActivity,
                    ModCastTargetPickerActivity::class.java).flattenToString()
                val request = CastTargetRequest(CastTargetSelector.ForegroundChoiceToken(
                    token, taskId, pickerComponent), mode ?: return@launch fail("E_INVALID_STATE"),
                    region, rotation, density)
                val result = withContext(Dispatchers.IO) {
                    runtime.coordinator.prepare(activeOwner, request).also { preparedResult ->
                        if (preparedResult is CastPrepareResult.Accepted) preparedRef.set(preparedResult.ticket)
                    }
                }
                if (!coroutineJob.isActive) {
                    preparedRef.get()?.let { ticket ->
                        runCatching { runtime.coordinator.abort(activeOwner, ticket.ticketId, ticket.generation) }
                    }
                    return@launch
                }
                traceFocus("target_choice_prepared", result is CastPrepareResult.Accepted)
                if (result is CastPrepareResult.Accepted) prepared = result.ticket
                if (isFinishing || isDestroyed || !mainAuthorizationCurrent(activeOwner, activeMod, activeSource))
                    return@launch fail("E_CAST_SOURCE_STALE")
                when (result) {
                    is CastPrepareResult.Accepted -> {
                        val target = result.ticket.target
                        val pkg = ComponentName.unflattenFromString(target.baseComponent)?.packageName
                            ?: return@launch fail("E_TARGET_CHANGED")
                        val label = intent.getStringExtra(EXTRA_FOREGROUND_CHOICE_LABEL)
                            ?.takeIf(String::isNotBlank) ?: pkg
                        val choice = top.zainzh.rearscreen.platform.HostCastTargetChoice(
                            token, label, pkg, target.taskId, target.baseComponent)
                        selectedChoice = choice
                        choices = listOf(choice)
                        error = ""
                        message = "已核实所选主屏任务；请查看投放预览后单独确认。"
                        adopted = true
                    }
                    is CastPrepareResult.Rejected -> fail("E_${result.code.name}")
                }
                traceFocus("confirmation_prepared", prepared != null)
            } catch (cancelled: CancellationException) {
                if (!adopted) preparedRef.get()?.let { ticket ->
                    runCatching { runtime.coordinator.abort(activeOwner, ticket.ticketId, ticket.generation) }
                }
                throw cancelled
            }
        }
    }

    private fun mainAuthorizationCurrent(activeOwner: SessionOwner, activeMod: String,
        activeSource: ModCastActivationSource): Boolean {
        if (isFinishing || isDestroyed || source != activeSource || !requestCurrent(activeSource, activeMod) ||
            core.modDataOwner(activeMod) != activeOwner) return false
        val installed = ModPackageStore.installed(this, activeMod) ?: return false
        val verified = runCatching { ModPackageStore.find(this, activeMod) }.getOrNull()
        val capability = if (mode == CastMode.VIRTUAL) "rear.cast.virtual" else "rear.cast.direct_physical"
        return installed.enabled && verified?.authorFingerprint == installed.authorFingerprint &&
            ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint) && capability in installed.grantedCapabilities
    }

    private fun requestCurrent(activeSource: ModCastActivationSource? = source,
        activeMod: String? = modId): Boolean {
        if (activeSource == null || activeMod == null ||
            !ModCastActivationSources.isRegistered(activeSource, activeMod)) return false
        return !mainSettingsFlow || ModCastActivationSources.isRequestCurrent(activeSource, requestId)
    }

    private fun loadChoices() {
        val activeOwner = owner ?: return
        val currentMod = modId ?: return
        if (!requestCurrent(source, currentMod)) return fail("E_CAST_SOURCE_STALE")
        val installed = ModPackageStore.installed(this, currentMod) ?: run {
            owner?.let { runCatching { top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, it) } }
            return fail("E_MOD_REVOKED")
        }
        if (!installed.enabled || !ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint)) {
            runCatching { top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, activeOwner) }
            return fail("E_MOD_REVOKED")
        }
        choices = runCatching { runtime.coordinator.listTargetChoices(activeOwner) }.getOrElse {
            error = "E_TARGETS_UNAVAILABLE"; emptyList()
        }
        message = if (choices.isEmpty()) "当前没有符合条件的主屏应用" else "选择一个应用后，先核对目标与背屏占用，再单独确认。"
    }

    private fun select(choice: top.zainzh.rearscreen.platform.HostCastTargetChoice) {
        if (completed || prepared != null) return
        if (!requestCurrent()) return fail("E_CAST_SOURCE_STALE")
        val activeOwner = owner ?: return fail("E_MOD_REVOKED")
        val activeMode = mode ?: return fail("E_INVALID_STATE")
        val request = CastTargetRequest(CastTargetSelector.ChoiceToken(choice.opaqueToken), activeMode, region, rotation, density)
        when (val result = runtime.coordinator.prepare(activeOwner, request)) {
            is CastPrepareResult.Accepted -> { selectedChoice = choice; prepared = result.ticket; error = "" }
            is CastPrepareResult.Rejected -> error = castFailureMessage("E_${result.code.name}")
        }
    }

    private fun confirmPrepared() {
        if (completed || committing) return
        if (!requestCurrent()) return fail("E_CAST_SOURCE_STALE")
        val activeOwner = owner ?: return fail("E_MOD_REVOKED")
        val ticket = prepared ?: return
        val activeMod = modId?.let { ModPackageStore.installed(this, it) } ?: return fail("E_MOD_REVOKED")
        val verified = ModPackageStore.find(this, activeMod.id)
        val castCapability = when (mode) {
            CastMode.VIRTUAL -> "rear.cast.virtual"
            CastMode.DIRECT_PHYSICAL -> "rear.cast.direct_physical"
            null -> ""
        }
        val stillAuthorized = activeMod.enabled && verified?.authorFingerprint == activeMod.authorFingerprint &&
            ModPackageStore.isAuthorTrusted(this, activeMod.authorFingerprint) && core.modDataOwner(activeMod.id) == activeOwner &&
            castCapability in activeMod.grantedCapabilities &&
            (region != CastRegion.FULL || "rear.region" in activeMod.grantedCapabilities) &&
            (rotation == null || "rear.scene.orientation" in activeMod.grantedCapabilities)
        if (!stillAuthorized) {
            top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, activeOwner)
            return fail("E_MOD_REVOKED")
        }
        val activeSource = source ?: return fail("E_CAST_SOURCE_STALE")
        val activeRequestId = requestId
        val isMainRequest = mainSettingsFlow
        val capturedModId = activeMod.id
        committing = true
        val activation = runtime.activationAuthority.issueFromConfirmedHostAction(activeOwner, ticket.ticketId, ticket.generation)
        when (val result = runtime.coordinator.commit(activeOwner, ticket.ticketId, ticket.generation, activation, object : CastObserver {
            override fun onCastEvent(event: CastEvent) = runOnUiThread {
                if (isFinishing || isDestroyed || completed) {
                    if (event is CastEvent.Ready && deliveredHandle != event.handle)
                        runCatching { runtime.coordinator.close(activeOwner, event.handle) }
                    return@runOnUiThread
                }
                if (!requestCurrent(activeSource, activeMod.id)) {
                    if (event is CastEvent.Ready)
                        runCatching { runtime.coordinator.close(activeOwner, event.handle) }
                    return@runOnUiThread fail("E_CAST_SOURCE_STALE")
                }
                when (event) {
                    is CastEvent.Ready -> finishReady(event)
                    is CastEvent.CommitFailed -> fail("E_${event.failure.code.name}")
                    is CastEvent.Expired -> fail("E_TICKET_STALE")
                    is CastEvent.Aborted -> fail("E_USER_CANCELLED")
                    is CastEvent.Closed -> Unit
                    is CastEvent.Failed -> fail("E_${event.failure.code.name}")
                }
            }
        }, requestIsCurrent = {
            ModCastActivationSources.isRegistered(activeSource, capturedModId) &&
                (!isMainRequest || ModCastActivationSources.isRequestCurrent(activeSource, activeRequestId))
        })) {
            is CastCommitResult.Accepted -> Unit
            is CastCommitResult.Rejected -> fail("E_${result.code.name}")
        }
    }

    private fun replacementSummary(ticket: top.zainzh.rearscreen.platform.PreparedCastTicket): String {
        val occupant = ticket.observedOccupant ?: return "背屏占用：当前没有受控投放"
        val pkg = android.content.ComponentName.unflattenFromString(occupant.baseComponent)?.packageName
        val label = pkg?.let { runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
        }.getOrNull() } ?: "现有投放"
        return "替换：$label 的现有背屏投放"
    }

    private fun cancelPrepared() {
        val ticket = prepared ?: return
        owner?.let { runtime.coordinator.abort(it, ticket.ticketId, ticket.generation) }
        prepared = null; selectedChoice = null; committing = false
    }

    private fun finishReady(event: CastEvent.Ready) {
        val activeOwner = owner
        val activeModId = modId
        val activeSource = source
        val installed = activeModId?.let { ModPackageStore.installed(this, it) }
        val verified = activeModId?.let { runCatching { ModPackageStore.find(this, it) }.getOrNull() }
        val capability = when (mode) {
            CastMode.VIRTUAL -> "rear.cast.virtual"
            CastMode.DIRECT_PHYSICAL -> "rear.cast.direct_physical"
            null -> ""
        }
        if (activeOwner == null || activeModId == null || activeSource == null ||
            !requestCurrent(activeSource, activeModId) ||
            installed == null || !installed.enabled || verified?.authorFingerprint != installed.authorFingerprint ||
            !ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint) || capability !in installed.grantedCapabilities ||
            (region == CastRegion.FULL && "rear.region" !in installed.grantedCapabilities) ||
            (rotation != null && "rear.scene.orientation" !in installed.grantedCapabilities) ||
            core.modDataOwner(activeModId) != activeOwner) {
            // The source or its trust was lost while commit was in flight. Close only this exact
            // newly-created owner handle rather than returning an orphaned live session.
            activeOwner?.let { runCatching { runtime.coordinator.close(it, event.handle) } }
            return fail("E_CAST_SOURCE_STALE")
        }
        completed = true
        deliveredHandle = event.handle
        val data = Intent().putExtra(EXTRA_RESULT_CODE, "READY")
            .putExtra(EXTRA_RESULT_SESSION_ID, event.handle.sessionId)
            .putExtra(EXTRA_RESULT_GENERATION, event.handle.generation)
            .putExtra(EXTRA_RESULT_MODE, event.target.mode.name)
            .putExtra(EXTRA_RESULT_REGION, event.target.region.name)
            .putExtra(EXTRA_RESULT_ROTATION, event.target.contentRotation)
            .putExtra(EXTRA_RESULT_PRESENTATION_KIND, event.presentationKind.name)
        setResult(Activity.RESULT_OK, data)
        if (mainSettingsFlow) source?.let { ModCastActivationSources.complete(it, requestId, true, "READY", org.json.JSONObject()
            .put("sessionId", event.handle.sessionId).put("generation", event.handle.generation)
            .put("state", "READY").put("mode", event.target.mode.name).put("region", event.target.region.name)
            .put("contentRotation", event.target.contentRotation).put("presentationKind", event.presentationKind.name)) }
        finish()
    }

    private fun finishCancelled() {
        if (completed) return
        prepared?.let { ticket -> owner?.let { runtime.coordinator.abort(it, ticket.ticketId, ticket.generation) } }
        fail("E_USER_CANCELLED")
    }

    private fun fail(code: String) {
        if (completed) return
        traceFocus("fail", code)
        completed = true
        prepared?.let { ticket -> owner?.let { runtime.coordinator.abort(it, ticket.ticketId, ticket.generation) } }
        if (mainSettingsFlow) source?.let { ModCastActivationSources.complete(it, requestId, false, code) }
        setResult(Activity.RESULT_CANCELED, intent.putExtra(EXTRA_RESULT_CODE, code))
        if (uiReady && !isFinishing && code != "E_USER_CANCELLED") {
            committing = false
            terminalFailure = code
        } else {
            if (!isFinishing && code != "E_USER_CANCELLED")
                android.widget.Toast.makeText(this, castFailureMessage(code), android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun castFailureMessage(code: String): String = when (code) {
        "E_TICKET_STALE", "E_EXPIRED" -> "本次确认已过期。返回后重新选择投放目标。"
        "E_TARGET_CHANGED", "E_TARGET_UNAVAILABLE" -> "主屏目标已变化。返回后重新打开要投放的应用。"
        "E_MOD_REVOKED", "E_CAST_SOURCE_STALE" -> "来源已关闭或未获投放授权。请返回模块重新发起。"
        "E_OCCUPANCY_CHANGED", "E_OCCUPIED" -> "背屏占用已变化。请检查背屏后重新发起。"
        else -> "请检查背屏和连接状态，再发起投放。"
    }

    private fun traceFocus(stage: String, value: Any? = null) {
        if (BuildConfig.DEBUG && mainSettingsFlow) {
            val suffix = value?.let { " value=$it" }.orEmpty()
            android.util.Log.i("ModCastFocusProbe", "stage=$stage$suffix")
        }
    }

    override fun onDestroy() {
        if (!completed && mainSettingsFlow && isFinishing) fail("E_USER_CANCELLED")
        if (!completed) prepared?.let { ticket -> owner?.let { runtime.coordinator.abort(it, ticket.ticketId, ticket.generation) } }
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context, effect: ModCastStartEffect, source: ModCastActivationSource): Intent =
            Intent(context, ModCastTargetPickerActivity::class.java)
                .putExtra(EXTRA_MOD_ID, source.modId)
                .putExtra(EXTRA_EFFECT_ID, effect.id)
                .putExtra(EXTRA_MODE, effect.mode)
                .putExtra(EXTRA_REGION, effect.region)
                .putExtra(EXTRA_ROTATION, effect.contentRotation ?: -1)
                .putExtra(EXTRA_DENSITY, effect.requestedDensityDpi ?: 0)
                .putExtra(EXTRA_SOURCE_SURFACE, source.surface.name)
                .putExtra(EXTRA_SOURCE_INSTANCE, source.instanceId)
                .putExtra(EXTRA_SOURCE_SESSION, source.sceneSessionId)
                .putExtra(EXTRA_SOURCE_GENERATION, source.sceneGeneration ?: 0L)

        private fun readSource(intent: Intent): ModCastActivationSource? = runCatching {
            val surface = ModCastActivationSource.Surface.valueOf(intent.getStringExtra(EXTRA_SOURCE_SURFACE).orEmpty())
            val session = intent.getStringExtra(EXTRA_SOURCE_SESSION)
            val generation = intent.getLongExtra(EXTRA_SOURCE_GENERATION, 0L).takeIf { it > 0L }
            ModCastActivationSource(intent.getStringExtra(EXTRA_MOD_ID).orEmpty(), surface,
                intent.getStringExtra(EXTRA_SOURCE_INSTANCE).orEmpty(), session, generation)
                .takeIf { it.isWellFormed() }
        }.getOrNull()

        const val EXTRA_MOD_ID = "mod_id"
        const val EXTRA_EFFECT_ID = "cast_effect_id"
        const val EXTRA_MODE = "cast_mode"
        const val EXTRA_REGION = "cast_region"
        const val EXTRA_ROTATION = "cast_rotation"
        const val EXTRA_DENSITY = "cast_density"
        const val EXTRA_RESULT_CODE = "cast_result_code"
        const val EXTRA_RESULT_SESSION_ID = "cast_result_session_id"
        const val EXTRA_RESULT_GENERATION = "cast_result_generation"
        const val EXTRA_RESULT_MODE = "cast_result_mode"
        const val EXTRA_RESULT_REGION = "cast_result_region"
        const val EXTRA_RESULT_ROTATION = "cast_result_rotation"
        const val EXTRA_RESULT_PRESENTATION_KIND = "cast_result_presentation_kind"
        const val EXTRA_SOURCE_SURFACE = "cast_source_surface"
        const val EXTRA_SOURCE_INSTANCE = "cast_source_instance"
        const val EXTRA_SOURCE_SESSION = "cast_source_session"
        const val EXTRA_SOURCE_GENERATION = "cast_source_generation"
        const val EXTRA_MAIN_SETTINGS_FLOW = "cast_main_settings_flow"
        const val EXTRA_FOREGROUND_CHOICE_TOKEN = "cast_foreground_choice_token"
        const val EXTRA_FOREGROUND_CHOICE_LABEL = "cast_foreground_choice_label"
        const val EXTRA_REQUEST_ID = "cast_request_id"
    }
}
