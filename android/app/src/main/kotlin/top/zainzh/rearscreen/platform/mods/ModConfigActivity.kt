package top.zainzh.rearscreen.platform.mods

import android.app.Activity
import android.app.ActivityOptions
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.AppPageContent
import top.zainzh.rearscreen.AppPageWindow
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.actions.RegisteredActionRef
import top.zainzh.rearscreen.platform.actions.RegisteredActionsActivity
import java.util.UUID

internal class ModConfigCastState : ViewModel() {
    var source: ModCastActivationSource? = null
    @Volatile var completion: ((Boolean, String?, JSONObject?) -> Unit)? = null
    @Volatile var requestId: String? = null

    fun sourceFor(modId: String): ModCastActivationSource {
        val current = source
        if (current?.modId == modId) return current
        return ModCastActivationSource(modId, ModCastActivationSource.Surface.MAIN_SETTINGS, UUID.randomUUID().toString())
            .also { source = it }
    }
}

internal data class RestoredModConfigPickerState(
    val pendingBinding: String?, val pendingModId: String?, val drafts: Map<String, RegisteredActionRef>
)

/** Bundle-only picker continuation: safe across Activity recreation without retaining lambdas. */
internal object ModConfigPickerState {
    fun write(bundle: Bundle, pendingBinding: String?, pendingModId: String?, drafts: Map<String, RegisteredActionRef>) {
        bundle.putString("registeredAction.pendingBinding", pendingBinding)
        bundle.putString("registeredAction.pendingModId", pendingModId)
        bundle.putString("registeredAction.drafts", JSONObject().apply {
            drafts.forEach { (binding, ref) -> put(binding, JSONObject().put("id", ref.id).put("version", ref.version)) }
        }.toString())
    }
    fun read(bundle: Bundle?): RestoredModConfigPickerState {
        val drafts = runCatching {
            val json = JSONObject(bundle?.getString("registeredAction.drafts") ?: "{}")
            buildMap { json.keys().forEach { key -> json.optJSONObject(key)?.let { put(key, RegisteredActionRef(it.getString("id"), it.getInt("version"))) } } }
        }.getOrDefault(emptyMap())
        return RestoredModConfigPickerState(bundle?.getString("registeredAction.pendingBinding"),
            bundle?.getString("registeredAction.pendingModId"), drafts)
    }
}

/** Window focus is global input state, not proof that a resumed settings window is hidden. */
internal fun isModConfigSceneVisible(
    resumed: Boolean,
    attached: Boolean,
    shown: Boolean,
    windowVisible: Boolean,
    displayId: Int?,
    closing: Boolean,
    routeActive: Boolean = true,
): Boolean = routeActive && resumed && attached && shown && windowVisible && isModConfigMainDisplay(displayId) && !closing

/** External entry point. Main uses the same ModConfigPage without opening another window. */
class ModConfigActivity : ComponentActivity() {
    private lateinit var page: ModConfigPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modId = intent.getStringExtra("mod_id").orEmpty()
        page = ModConfigPage(this, modId, savedInstanceState, ::finish)
        page.setRouteActive(true)
        miuixContent { AppPageWindow { page.Content() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        page.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        page.close()
        super.onDestroy()
    }
}

/** Owns one verified settings scene independently of how the host navigates to it. */
internal class ModConfigPage(
    private val activity: ComponentActivity,
    val modId: String,
    savedState: Bundle?,
    private val navigateBack: () -> Unit,
) : ContextWrapper(activity) {
    @Volatile private var closed = false
    @Volatile private var routeActive = false
    private var entered = false
    private var packageUnavailable = false
    private var packageInfo by mutableStateOf<VerifiedModPackage?>(null)
    var model by mutableStateOf<ModPersistedModel?>(null)
        private set
    var error by mutableStateOf("")
        private set
    var sceneVisible by mutableStateOf(false)
        private set
    private var visibilityDecor: View? = null
    private val windowVisibilityListener = ViewTreeObserver.OnWindowVisibilityChangeListener {
        updateSceneVisibility()
    }
    private val decorAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.viewTreeObserver.addOnWindowVisibilityChangeListener(windowVisibilityListener)
            updateSceneVisibility()
        }

        override fun onViewDetachedFromWindow(view: View) {
            view.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnWindowVisibilityChangeListener(windowVisibilityListener)
            publishSceneVisibility(false)
        }
    }
    private val visibilityLifecycle = object : DefaultLifecycleObserver {
        // Activity.onResume runs before AndroidX reaches RESUMED. When the other
        // display owns focus, a window focus callback need not follow.
        override fun onResume(owner: LifecycleOwner) = updateSceneVisibility()
        override fun onPause(owner: LifecycleOwner) = publishSceneVisibility(false)
    }
    private var hostData by mutableStateOf(JSONObject())
    private var dataSubscription: Closeable? = null
    private val hostDataEpoch = ModDataSubscriptionEpoch()
    private val core by lazy { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this) }
    private val broker by lazy { ModBrokerProvider.get(this) }
    private val restoredPicker = ModConfigPickerState.read(savedState)
    private var pendingActionBinding = restoredPicker.pendingBinding
    private var pendingActionModId = restoredPicker.pendingModId
    private var registeredActionDrafts by mutableStateOf(restoredPicker.drafts)
    private var deferredPickerResult: ActivityResult? = null
    private var castSource: ModCastActivationSource? = null
    private var castSourceRegistration: Closeable? = null
    private val castState = ViewModelProvider(activity)[ModConfigCastState::class.java]
    private val castConsumer = object : ModHostEffectRouter.Consumer {
        override fun request(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect) {
            throw IllegalStateException("E_CAST_SOURCE_UNSUPPORTED_EFFECT")
        }
        override fun requestCastWithResult(mod: InstalledMod, eventId: String, effect: ModCastStartEffect,
            source: ModCastActivationSource, complete: (Boolean, String?, JSONObject?) -> Unit) {
            activity.runOnUiThread { launchCastPicker(mod, effect, source, complete) }
        }
        override fun completeCast(source: ModCastActivationSource, requestId: String, accepted: Boolean, code: String?, result: JSONObject?) {
            activity.runOnUiThread {
                if (closed || !routeActive || source != castSource || castState.requestId != requestId ||
                    !ModCastActivationSources.isRegistered(source, modId)) return@runOnUiThread
                val completion = castState.completion ?: return@runOnUiThread
                castState.completion = null
                castState.requestId = null
                completion(accepted, code, result)
            }
        }
        override fun isCastRequestCurrent(source: ModCastActivationSource, requestId: String): Boolean =
            !closed && routeActive && source == castSource && castState.requestId == requestId && castState.completion != null &&
                ModCastActivationSources.isRegistered(source, modId)
    }
    private val actionPicker = activity.activityResultRegistry.register(
        "mod_config_registered_action", ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // The registry may return a result before a recreated page has loaded the signed package.
        if (!closed && (!entered || routeActive)) {
            if (packageInfo == null || !routeActive) deferredPickerResult = result
            else acceptPickerResult(result)
        }
    }

    private fun acceptPickerResult(result: ActivityResult) {
        val data = result.data
        val id = data?.getStringExtra(RegisteredActionsActivity.EXTRA_SELECTED_REF_ID)
        val version = data?.getIntExtra(RegisteredActionsActivity.EXTRA_SELECTED_REF_VERSION, 0) ?: 0
        val ref = if (result.resultCode == Activity.RESULT_OK && !id.isNullOrBlank() && version == 1) RegisteredActionRef(id, version) else null
        val binding = pendingActionBinding
        val expectedModId = pendingActionModId
        pendingActionBinding = null
        pendingActionModId = null
        if (ref != null && binding != null && expectedModId == modId && packageInfo?.id == modId) {
            registeredActionDrafts = registeredActionDrafts + (binding to ref)
            error = "动作已选择；点击保存设置后生效"
        } else if (result.resultCode == Activity.RESULT_OK) error = "动作选择已取消或设置场景已变化"
    }
    private val brokerListener = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) { this@ModConfigPage.model = model }
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { this@ModConfigPage.model = model }
        override fun onError(modId: String, code: String, message: String) {
            if (code in setOf("E_PACKAGE_REPLACED", "E_MOD_REVOKED", "E_MOD_UNINSTALLED")) {
                packageUnavailable = true
                if (routeActive) navigateBack()
            } else error = "$code：$message"
        }
        override fun onEffectRequested(modId: String, eventId: String) = Unit
    }

    init {
        val decor = activity.window.decorView
        visibilityDecor = decor
        decor.addOnAttachStateChangeListener(decorAttachListener)
        if (decor.isAttachedToWindow) decorAttachListener.onViewAttachedToWindow(decor)
        activity.lifecycle.addObserver(visibilityLifecycle)
        activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val installed = ModPackageStore.installed(activity, modId)
                    if (installed == null || !installed.enabled ||
                        !ModPackageStore.isAuthorTrusted(activity, installed.authorFingerprint)) return@runCatching null
                    val verified = ModPackageStore.find(activity, modId)
                        ?.takeIf { it.id == modId && it.authorFingerprint == installed.authorFingerprint }
                        ?: return@runCatching null
                    verified to ModPackageStore.model(activity, modId)
                }.getOrNull()
            }
            if (closed) return@launch
            packageUnavailable = loaded == null
            if (loaded != null) {
                packageInfo = loaded.first
                model = loaded.second
            }
            activateScene()
        }
    }

    fun setRouteActive(active: Boolean) {
        if (closed || routeActive == active) return
        routeActive = active
        if (active) {
            entered = true
            activateScene()
        } else {
            invalidateCastRequest()
            deferredPickerResult = null
            pendingActionBinding = null
            pendingActionModId = null
            updateSceneVisibility()
        }
    }

    private fun activateScene() {
        if (!routeActive || closed) return
        if (packageUnavailable) {
            navigateBack()
            return
        }
        if (packageInfo == null) return
        if (castSourceRegistration == null) {
            val source = castState.sourceFor(modId)
            castSource = source
            castSourceRegistration = ModCastActivationSources.register(source, castConsumer)
            broker.open(modId, mainExecutor, brokerListener)
        }
        consumeDeferredPickerResult()
        updateSceneVisibility()
    }

    private fun consumeDeferredPickerResult() {
        if (!routeActive || packageInfo == null) return
        val result = deferredPickerResult ?: return
        deferredPickerResult = null
        acceptPickerResult(result)
    }

    @Composable
    fun Content() {
        val active = packageInfo
        val current = model
        if (active == null || current == null) {
            AppPageContent("Mod 设置", navigateBack, "mod_back_$modId") { modifier, _ ->
                Text(error.ifBlank { "正在加载设置…" }, modifier.padding(24.dp), color = AppPalette.secondary())
            }
        } else {
            val bindings = withModRearInfo(this, active, hostData, sceneVisible)
            ModNativeScene(active, active.settingsScene, current.state, current.config, bindings, false,
                onBack = navigateBack,
                onEvent = {
                    if (!sceneVisible || closing ||
                        !broker.submitUserEvent(active.id, it, castSource = castSource)
                    ) {
                        error = "操作暂未提交：输入队列已满、设置页不可见或场景正在切换，请稍后重试"
                    }
                },
                visible = sceneVisible,
                onRegisteredAction = ::invokeRegisteredAction,
                registeredActionDrafts = registeredActionDrafts,
                onSelectRegisteredAction = ::selectRegisteredAction,
                onClearRegisteredActionDrafts = { registeredActionDrafts = emptyMap() },
                onSaveConfig = { config, done ->
                    if (!sceneVisible || closing) done(false, "设置页不可见")
                    else broker.saveConfig(active.id, config, mainExecutor) { saved, reason ->
                        if (saved) model = ModPackageStore.model(activity, active.id)
                        done(saved, reason)
                    }
                },
                notice = error.ifBlank { null },
                standaloneWindow = false)
        }
    }

    private val closing get() = closed || activity.isFinishing || activity.isDestroyed

    private fun updateSceneVisibility() {
        val decor = visibilityDecor ?: return
        publishSceneVisibility(
            isModConfigSceneVisible(
                resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                attached = decor.isAttachedToWindow,
                shown = decor.isShown,
                windowVisible = decor.windowVisibility == View.VISIBLE,
                displayId = decor.display?.displayId,
                closing = closing,
                routeActive = routeActive && packageInfo != null,
            ),
        )
    }

    private fun publishSceneVisibility(visible: Boolean) {
        if (sceneVisible == visible) return
        sceneVisible = visible
        broker.setObserverVisible(modId, brokerListener, visible)
        if (visible) startHostData() else stopHostData()
    }

    fun save(outState: Bundle) {
        ModConfigPickerState.write(outState, pendingActionBinding, pendingActionModId, registeredActionDrafts)
    }

    fun close() {
        closed = true
        activity.lifecycle.removeObserver(visibilityLifecycle)
        visibilityDecor?.let { decor ->
            decor.removeOnAttachStateChangeListener(decorAttachListener)
            decor.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnWindowVisibilityChangeListener(windowVisibilityListener)
        }
        visibilityDecor = null
        publishSceneVisibility(false)
        if (castSource != null) broker.closeObserver(modId, brokerListener)
        castSourceRegistration?.close()
        castSourceRegistration = null
        actionPicker.unregister()
        if (!activity.isChangingConfigurations && castState.source?.modId == modId &&
            (castSource == null || castSource == castState.source)) {
            invalidateCastRequest()
            castState.source = null
        }
    }

    private fun invalidateCastRequest() {
        val completion = castState.completion
        castState.completion = null
        castState.requestId = null
        completion?.invoke(false, "E_CAST_SOURCE_STALE", null)
    }

    private fun launchCastPicker(
        mod: InstalledMod,
        effect: ModCastStartEffect,
        source: ModCastActivationSource,
        complete: (Boolean, String?, JSONObject?) -> Unit,
    ) {
        val active = packageInfo
        val currentSource = castSource
        if (source != currentSource || !ModCastActivationSources.isRegistered(source, modId) ||
            !sceneVisible || !isModConfigMainDisplay(activity.display?.displayId) || closing ||
            active == null || active.id != mod.id || active.authorFingerprint != mod.authorFingerprint) {
            complete(false, "E_CAST_SOURCE_STALE", null)
            return
        }
        val installed = ModPackageStore.installed(this, mod.id)
        val verified = runCatching { ModPackageStore.find(this, mod.id) }.getOrNull()
        val capability = if (effect.mode == "VIRTUAL") "rear.cast.virtual" else "rear.cast.direct_physical"
        if (installed == null || !installed.enabled || installed.authorFingerprint != active.authorFingerprint ||
            verified?.authorFingerprint != active.authorFingerprint || !ModPackageStore.isAuthorTrusted(this, active.authorFingerprint) ||
            capability !in installed.grantedCapabilities) {
            complete(false, "E_MOD_REVOKED", null)
            return
        }
        castState.completion?.invoke(false, "E_EFFECT_REPLACED", null)
        val requestId = UUID.randomUUID().toString()
        castState.requestId = requestId
        castState.completion = complete
        val intent = ModCastForegroundTargetActivity.createIntent(this, effect, source, requestId)
        try {
            startActivity(intent, ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
        } catch (_: Exception) {
            castState.completion = null
            castState.requestId = null
            complete(false, "E_TARGET_PICKER_UNAVAILABLE", null)
        }
    }

    private fun startHostData() {
        dataSubscription?.close()
        dataSubscription = null
        val generation = hostDataEpoch.start()
        hostData = JSONObject()
        val active = packageInfo ?: return
        val fields = ModHostDataSnapshot.fieldsFor(active)
        if (fields.isEmpty()) return
        val owner = core.modDataOwner(modId) ?: return
        dataSubscription = ModHostDataSnapshot.observe(core, owner, fields) { snapshot ->
            activity.runOnUiThread {
                if (sceneVisible && hostDataEpoch.isCurrent(generation) && !closing) hostData = snapshot
            }
        }
    }

    private fun stopHostData() {
        hostDataEpoch.stop()
        dataSubscription?.close()
        dataSubscription = null
        hostData = JSONObject()
    }

    private fun selectRegisteredAction(binding: String, currentRef: RegisteredActionRef?) {
        val active = packageInfo ?: return
        if (!sceneVisible || closing) return
        val declared = active.requiredCapabilities.any { it.name == "action.invoke" } || active.optionalCapabilities.any { it.name == "action.invoke" }
        if (!declared) { error = "此 Mod 未声明已登记动作能力"; return }
        val installed = ModPackageStore.installed(this, modId)
        if (installed == null || !installed.enabled || installed.authorFingerprint != active.authorFingerprint) {
            error = "此 Mod 已停用或作者信任已撤销"; return
        }
        if ("action.invoke" !in installed.grantedCapabilities) { error = "此 Mod 未获已登记动作授权"; return }
        val owner = core.modDataOwner(modId) ?: run { error = "此 Mod 当前不可用"; return }
        pendingActionBinding = binding
        pendingActionModId = modId
        runCatching { actionPicker.launch(RegisteredActionsActivity.createIntent(this, owner, selectAction = true)) }
            .onFailure { pendingActionBinding = null; pendingActionModId = null; error = it.message ?: "无法打开已登记动作选择器" }
    }

    /** Settings-scene action buttons are real native clicks, dispatched locally through Core. */
    private fun invokeRegisteredAction(ref: RegisteredActionRef) {
        val active = packageInfo ?: return
        if (!sceneVisible || closing) return
        val installed = ModPackageStore.installed(this, active.id)
        val declared = active.requiredCapabilities.any { it.name == "action.invoke" } || active.optionalCapabilities.any { it.name == "action.invoke" }
        val owner = core.modDataOwner(active.id)
        if (!declared || installed == null || !installed.enabled || installed.authorFingerprint != active.authorFingerprint ||
            "action.invoke" !in installed.grantedCapabilities || owner == null) {
            error = "此 Mod 当前未获已登记动作授权"
            return
        }
        val activation = runCatching { core.issueRegisteredActionActivationAfterHostClick(owner, ref) }
            .getOrElse { error = "无法确认此快捷操作，请重新点击"; return }
        Thread {
            val result = core.invokeRegisteredAction(owner, ref, activation)
            activity.runOnUiThread {
                if (!sceneVisible || closing) return@runOnUiThread
                error = when (result) {
                    top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Accepted -> ""
                    is top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Rejected -> "无法执行快捷操作：${result.error}"
                }
            }
        }.start()
    }

}

internal fun isModConfigMainDisplay(displayId: Int?): Boolean = displayId == android.view.Display.DEFAULT_DISPLAY
