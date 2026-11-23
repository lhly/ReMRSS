package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import android.content.Intent
import android.app.Activity
import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.graphics.Point
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import top.zainzh.rearscreen.AppActionPair
import top.zainzh.rearscreen.AppDecisionHeading
import top.zainzh.rearscreen.AppDecisionLayout
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.pageBodyFade
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.miuixComposeView
import top.zainzh.rearscreen.prepareMiuixWindow
import dev.remrss.scene.geometry.CutoutKnowledge
import dev.remrss.scene.geometry.PixelInsets
import dev.remrss.scene.geometry.PixelRect as SharedPixelRect
import dev.remrss.scene.geometry.SceneRegion as SharedSceneRegion
import dev.remrss.scene.geometry.SceneViewportGeometry
import dev.remrss.scene.geometry.QuarterTurnSceneContainer
import top.zainzh.rearscreen.platform.*
import top.zainzh.rearscreen.platform.actions.ActionDispatchResult
import top.zainzh.rearscreen.platform.actions.ActionError
import top.zainzh.rearscreen.platform.actions.RegisteredActionRef
import kotlin.math.roundToInt

/** An omitted effect rotation inherits the current explicit placement; it never demotes a nonzero turn to legacy. */
internal fun sceneRequestForModEffect(
    current: SceneRequest,
    region: RearRegion,
    requestedRotation: Int?,
): SceneRequest {
    val explicitRotation = requestedRotation != null || current.hasExplicitContentRotation
    val requirements = current.requiredCapabilities.toMutableSet().apply {
        add(CapabilityRequirement("rear.scene", 1))
        if (current.keepVisible) add(CapabilityRequirement("rear.scene.keep_visible", 1))
        if (region == RearRegion.FULL) add(CapabilityRequirement("rear.region", 1))
        if (explicitRotation) add(CapabilityRequirement("rear.scene.orientation", 1))
    }
    return current.copy(
        region = region,
        keepVisible = current.keepVisible,
        requiredCapabilities = requirements,
        contentRotation = requestedRotation ?: current.contentRotation,
        hasExplicitContentRotation = explicitRotation,
    )
}

/** Ticket-routed, package-independent host shell for a verified Mod rear scene. */
class ModRearSceneActivity : ComponentActivity() {
    private var pkg by mutableStateOf<VerifiedModPackage?>(null)
    private var model by mutableStateOf<ModPersistedModel?>(null)
    private var message by mutableStateOf("正在加载 Mod…")
    private var error by mutableStateOf("")
    private var hostData by mutableStateOf(JSONObject())
    private var rearViewport by mutableStateOf<RearViewportSnapshot?>(null)
    private var cameraSafeInsets by mutableStateOf(CameraSafeInsets.Zero)
    private var cutoutReported by mutableStateOf<Boolean?>(null)
    private var launch: SceneLaunchContext? = null
    private var launchTicket: String? = null
    private var readyController: ModSceneReadyController? = null
    private var sceneReady by mutableStateOf(false)
    private var effectRequest by mutableStateOf<ModRearOpenEffect?>(null)
    private var castEffectRequest by mutableStateOf<ModCastStartEffect?>(null)
    private var effectRequestCompletion: ((Boolean, String?) -> Unit)? = null
    private var castEffectCompletion: ((Boolean, String?, org.json.JSONObject?) -> Unit)? = null
    private var effectInFlightCompletion: ((Boolean, String?) -> Unit)? = null
    private var sceneViewportTransition by mutableStateOf<ActiveSceneViewportTransition?>(null)
    private var viewportPresentationRefreshPending by mutableStateOf(false)
    private var sceneViewportTransitionTimeout: Runnable? = null
    private val sceneViewportTransitionHandler = Handler(Looper.getMainLooper())
    private var moduleSlotActive = false
    private var sessionId: String? = null
    private var generation: Long = 0
    private var viewportRevision: Long = 0
    private var windowReported = false
    private val windowReportRequests = ModSceneWindowReportRequests()
    private var viewportGeometryEpoch = 0L
    private var contentLayoutReady = false
    private var observedWindowGeometry: ModSceneWindowGeometry? = null
    private var sceneRoot: FrameLayout? = null
    private var sceneContainer: QuarterTurnSceneContainer? = null
    private var sceneContainerLayoutListener: View.OnLayoutChangeListener? = null
    private var coreClosed = false
    private var instanceToken = 0L
    private var effectConsumer: ModHostEffectRouter.Consumer? = null
    private var castSource: ModCastActivationSource? = null
    private var castSourceRegistration: Closeable? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var dataSubscription: Closeable? = null
    private val hostDataEpoch = ModDataSubscriptionEpoch()
    private var windowActuallyVisible by mutableStateOf(false)
    private var coordinatorVisible by mutableStateOf(false)
    private lateinit var windowLifecycleGate: ModSceneWindowLifecycleGate
    private val windowPolicyTraceGate = ModSceneWindowPolicyTraceGate()
    private val decorAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { reportActualWindowVisibility() }
        override fun onViewDetachedFromWindow(view: View) { reportActualWindowVisibility() }
    }
    private val windowVisibilityListener = ViewTreeObserver.OnWindowVisibilityChangeListener {
        // Re-read DecorView state after the window transition; the callback value alone
        // does not establish attachment, visibility, or this Activity's current binding.
        window.decorView.post { reportActualWindowVisibility() }
    }
    private val core by lazy { RearCapabilityCore.get(this) }
    private val broker by lazy { ModBrokerProvider.get(this) }
    private val observer = object : SceneObserver {
        override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
            runOnUiThread {
                if (!acceptSceneEvent(sessionId, generation)) return@runOnUiThread
                when (event) {
                    is SceneEvent.Ready -> {
                        // READY proves the measured window, not that Core has made
                        // the rear display visible. Visibility arrives separately.
                        logSceneLifecycle("core_ready_event", "eventGeneration=$generation")
                        refreshRearViewportBinding()
                        sceneReady = windowReported
                        message = ""
                        reconcileScenePresentation()
                    }
                    is SceneEvent.ViewportChanged -> {
                        logSceneLifecycle("core_viewport_event", "revision=${event.revision}")
                        refreshRearViewportBinding()
                    }
                    is SceneEvent.VisibilityChanged,
                    is SceneEvent.Suspended,
                    SceneEvent.Restored -> reconcileScenePresentation()
                    SceneEvent.BackendLost -> {
                        logSceneLifecycle("core_backend_lost")
                        reconcileScenePresentation()
                    }
                    is SceneEvent.Closed -> {
                        logSceneLifecycle("core_closed_event", "reason=${event.reason.name}")
                        ModSceneReadyController.markClosed(launchTicket)
                        coreClosed = true
                        sceneReady = false
                        finishAndRemoveTask()
                    }
                }
            }
        }
    }
    private val brokerListener = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) {
            if (!isCurrentSceneInstance() || !moduleSlotActive) return
            this@ModRearSceneActivity.model = model; message = ""
        }
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
            if (!isCurrentSceneInstance() || !moduleSlotActive) return
            this@ModRearSceneActivity.model = model
        }
        override fun onError(modId: String, code: String, message: String) {
            if (!isCurrentSceneInstance() || !moduleSlotActive) return
            if (code in setOf("E_PACKAGE_REPLACED", "E_MOD_REVOKED", "E_MOD_UNINSTALLED")) finishAndRemoveTask()
            else error = "$code：$message"
        }
        override fun onEffectRequested(modId: String, eventId: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        val ticket = intent.getStringExtra("remrss_scene_ticket")
        val route = ticket?.let { core.resolveSceneLaunchForHostActivity(it, componentName) }
        if (route == null) {
            Log.i("ModRearSceneLifecycle", "stage=route_rejected task=$taskId display=${window.decorView.display?.displayId ?: -1} ticketPresent=${!ticket.isNullOrBlank()}")
            // A ticket which no longer resolves cannot own a live READY controller.
            // Drop only this opaque ticket entry; do not infer or close another owner.
            ModSceneReadyController.markClosed(ticket)
            finishAndRemoveTask()
            return
        }
        launch = route
        launchTicket = ticket
        logSceneLifecycle("route_resolved", "region=${route.request.region.name} rotation=${route.request.contentRotation} keepVisible=${route.request.keepVisible}")
        val restoredSession = savedInstanceState?.getString("scene_session")
        val restoredGeneration = savedInstanceState?.getLong("scene_generation")
        // The verified launch ticket is authoritative. Saved state may carry viewport data,
        // but cannot override the current Core owner/session/generation identity.
        sessionId = route.sessionId
        generation = route.generation
        viewportRevision = savedInstanceState?.getLong("viewport_revision") ?: 0L
        windowLifecycleGate = ModSceneWindowLifecycleGate(sessionId!!, generation, ::onWindowLifecycleStateChanged)
        val owner = route.owner
        val principal = owner.principal as? SessionPrincipal.SignedMod ?: run {
            core.displaySessions.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
            ModSceneReadyController.markClosed(ticket)
            finishAndRemoveTask()
            return
        }
        val installed = ModPackageStore.installed(this, principal.modId)
        val verified = ModPackageStore.find(this, principal.modId)
        if (installed == null || !installed.enabled || !ModPackageStore.isAuthorTrusted(this, principal.authorFingerprint) ||
            verified == null || verified.authorFingerprint != principal.authorFingerprint ||
            verified.version != installed.version || verified.versionCode != installed.versionCode ||
            installed.logicalSessionId != principal.logicalSessionId ||
            !route.request.requiredCapabilities.all { it.name in installed.grantedCapabilities }) {
            runCatching { top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, owner) }
            core.displaySessions.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
            ModSceneReadyController.markClosed(ticket)
            finishAndRemoveTask()
            return
        }
        // Do not publish a process-scoped READY controller until this exact signed
        // Mod route has passed the current installation, enabled, and trust checks.
        readyController = ModSceneReadyController.forLaunch(route, requireNotNull(ticket), core.displaySessions)
        if (readyController == null) {
            logSceneLifecycle("ready_controller_unavailable")
            finishAndRemoveTask()
            return
        }
        registerSceneInstance()
        logSceneLifecycle("binding_registered")
        pkg = verified
        model = ModPackageStore.model(this, principal.modId)
        if (restoredSession == route.sessionId && restoredGeneration == route.generation) {
            core.displaySessions.attachSceneObserver(owner, route.sessionId, route.generation, observer)
        }
        val container = QuarterTurnSceneContainer(this).apply {
            contentRotation = route.request.contentRotation
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        prepareMiuixWindow()
        val compose = miuixComposeView {
            val activePackage = pkg
            val current = model
            ModSceneVisibilitySurface(visible = isSceneContentVisible() && sceneViewportTransition == null && !viewportPresentationRefreshPending) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    if (cutoutReported == false) Text("ROM 未提供可核实的屏幕开孔安全区", color = AppPalette.secondary())
                    if (error.isNotEmpty()) Text(error, color = AppPalette.destructive())
                    else if (activePackage == null || current == null) Text(message, color = AppPalette.secondary())
                    else if(activePackage.rearScene==null) Text("此 Mod 未声明背屏场景",color=AppPalette.secondary())
                    else {
                        val bindings = withModRearInfo(this@ModRearSceneActivity, activePackage, hostData, true,
                            rear = true, owner = route.owner, viewport = rearViewport)
                        ModNativeScene(activePackage, activePackage.rearScene, current.state, current.config, bindings, true,
                            onBack = { finish() },
                            onEvent = { type ->
                                if (!broker.submitUserEvent(activePackage.id, type, castSource = castSource)) {
                                    Toast.makeText(this@ModRearSceneActivity, "操作暂未提交：输入队列已满或场景正在切换，请稍后重试", Toast.LENGTH_SHORT).show()
                                }
                            },
                            visible = isSceneContentVisible(),
                            onRegisteredAction = ::invokeRegisteredAction,
                            onSaveConfig = { config, complete -> broker.saveConfig(activePackage.id, config, mainExecutor, complete) })
                    }
                }
            }
            val canShowRequest = isSceneContentVisible() && sceneViewportTransition == null &&
                !viewportPresentationRefreshPending
            val pendingEffect = effectRequest
            ModRearDecisionDialog(show = canShowRequest && pendingEffect != null, onCancel = ::cancelPendingEffect) {
                pendingEffect?.let { effect ->
                    val targetRegion = if (effect.region == "FULL") "全屏" else "相机安全区"
                    val targetRotation = effect.contentRotation ?: launch?.request?.contentRotation ?: 0
                    val requestedKeepVisible = if (effect.keepVisible) "保持亮屏" else "不要求保持亮屏"
                    ModRearSceneRequestContent(targetRegion, targetRotation * 90, requestedKeepVisible,
                        ::cancelPendingEffect) {
                        effectRequest = null
                        val completion = effectRequestCompletion
                        effectInFlightCompletion = completion
                        effectRequestCompletion = null
                        requestEffect(effect) { accepted, code ->
                            effectInFlightCompletion = null
                            completion?.invoke(accepted, code)
                        }
                    }
                }
            }
            val pendingCastEffect = castEffectRequest
            ModRearDecisionDialog(show = canShowRequest && pendingCastEffect != null, onCancel = ::cancelPendingCastEffect) {
                pendingCastEffect?.let { effect ->
                    ModRearCastRequestContent(
                        if (effect.mode == "VIRTUAL") "虚拟投放" else "物理直投",
                        effect.region ?: "沿用应用偏好",
                        effect.contentRotation?.let { "${it * 90}°" } ?: "沿用应用偏好",
                        ::cancelPendingCastEffect,
                    ) {
                        castEffectRequest = null
                        castSource?.let { launchModCastPicker(effect, it) }
                            ?: cancelPendingCastEffect()
                    }
                }
            }
        }
        sceneRoot = FrameLayout(this).apply { addView(container, FrameLayout.LayoutParams(-1, -1)) }
        sceneContainer = container
        container.addView(compose)
        setContentView(sceneRoot)
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            view.post { configureSceneViewport() }
            insets
        }
        window.decorView.requestApplyInsets()
        window.decorView.post { configureSceneViewport() }
        layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.width > 0 && view.height > 0) {
                // Insets can be dispatched before DecorView has its final bounds.
                // Recompute the native host geometry on the real layout edge; the
                // resulting container layout, not gated Mod content, gates READY.
                configureSceneViewport()
                if (sceneViewportTransition != null || !windowReported) submitMeasuredViewportIfNeeded(ticket)
            }
        }.also { window.decorView.addOnLayoutChangeListener(it) }
        sceneContainerLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view === sceneContainer) markSceneContainerLayoutReady(ticket)
        }.also { container.addOnLayoutChangeListener(it) }
        window.decorView.addOnAttachStateChangeListener(decorAttachListener)
        if (Build.VERSION.SDK_INT >= 34) {
            window.decorView.viewTreeObserver.addOnWindowVisibilityChangeListener(windowVisibilityListener)
        }
        reportActualWindowVisibility()
        window.decorView.post {
            if ((sceneViewportTransition != null || !windowReported) && window.decorView.width > 0 && window.decorView.height > 0)
                submitMeasuredViewportIfNeeded(ticket)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scene_session", sessionId)
        outState.putLong("scene_generation", generation)
        outState.putLong("viewport_revision", viewportRevision)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val route = launch ?: return
        if (!isCurrentSceneInstance()) {
            logSceneLifecycle("activity_resumed_stale_instance")
            return
        }
        val attached = runCatching { core.displaySessions.attachSceneObserver(route.owner, route.sessionId, generation, observer) }
        logSceneLifecycle("activity_resumed", "attachSuccess=${attached.getOrNull()?.success == true}")
        reportActualWindowVisibility()
    }

    override fun onStart() {
        super.onStart()
        if (!::windowLifecycleGate.isInitialized) return
        windowLifecycleGate.onActivityStarted(true)
        logSceneLifecycle("activity_started")
        if (window.decorView.width > 0 && window.decorView.height > 0) {
            applyMeasuredCameraInsets()
            configureSceneViewport()
            markSceneContainerLayoutReady(launchTicket)
        }
        reportActualWindowVisibility()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        // Stop this Activity's observer/data work promptly. The Core lease stays
        // open and is re-authorized only after fresh Core visibility on return.
        windowReportRequests.invalidate()
        readyController?.setActive(instanceToken, false)
        logSceneLifecycle("activity_stopped")
        if (::windowLifecycleGate.isInitialized) {
            windowLifecycleGate.onActivityStarted(false)
            reportActualWindowVisibility()
        }
        super.onStop()
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Input focus can move to the main display while this rear window remains
        // visible; recheck its real window visibility, but do not treat focus loss
        // as a cover or lease end.
        reportActualWindowVisibility()
    }

    override fun onDestroy() {
        val id = (launch?.owner?.principal as? SessionPrincipal.SignedMod)?.modId
        val isCurrentInstance = isCurrentSceneInstance()
        logSceneLifecycle("activity_destroyed", "isCurrentInstance=$isCurrentInstance finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
        sceneViewportTransition?.let { transition ->
            core.sceneViewportTransitions?.abortSceneViewportTransition(
                transition.oldRoute.owner, transition.oldRoute.sessionId, transition.oldRoute.generation, transition.id)
            sceneViewportTransitionTimeout?.let(sceneViewportTransitionHandler::removeCallbacks)
            sceneViewportTransitionTimeout = null
            if (!transition.completionSent) {
                transition.completionSent = true
                transition.complete(false, "E_SCENE_ACTIVITY_CLOSED")
            }
            sceneViewportTransition = null
        }
        windowReportRequests.invalidate()
        readyController?.detach(instanceToken)
        layoutListener?.let(window.decorView::removeOnLayoutChangeListener)
        sceneContainerLayoutListener?.let { sceneContainer?.removeOnLayoutChangeListener(it) }
        window.decorView.removeOnAttachStateChangeListener(decorAttachListener)
        if (Build.VERSION.SDK_INT >= 34) {
            val observer = window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnWindowVisibilityChangeListener(windowVisibilityListener)
        }
        stopHostData()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (id != null) effectConsumer?.let { ModHostEffectRouter.consumers.remove(id, it) }
        castSourceRegistration?.close()
        castSourceRegistration = null
        castSource = null
        castEffectRequest = null
        castEffectCompletion?.invoke(false, "E_CAST_SOURCE_STALE", null)
        castEffectCompletion = null
        effectRequestCompletion?.invoke(false, "E_SCENE_ACTIVITY_CLOSED")
        effectInFlightCompletion?.invoke(false, "E_SCENE_ACTIVITY_CLOSED")
        effectRequestCompletion = null
        effectInFlightCompletion = null
        if (shouldCloseModSceneOnDestroy(isCurrentInstance, isFinishing, isChangingConfigurations) && launch != null && !coreClosed) {
            val route = launch!!
            val closed = runCatching { core.displaySessions.closeScene(route.owner, route.sessionId) }.getOrNull()
            Log.i("ModRearSceneLifecycle", "stage=destroy_close session=${route.sessionId} generation=${route.generation} task=$taskId result=${closed?.success == true} error=${closed?.error?.name ?: "none"}")
            ModSceneReadyController.markClosed(launchTicket)
        }
        sessionId?.let { activeSceneInstances.remove(it, instanceToken) }
        if (moduleSlotActive && id != null) broker.closeObserver(id, brokerListener)
        moduleSlotActive = false
        super.onDestroy()
    }

    private fun startHostData() {
        if (dataSubscription != null) return
        if (!isCurrentSceneInstance() || !windowPolicy().showSceneContent || isFinishing || isDestroyed) return
        val route = launch ?: return
        if (route.owner.principal !is SessionPrincipal.SignedMod) return
        val active = pkg ?: return
        val fields = ModHostDataSnapshot.fieldsFor(active)
        if (fields.isEmpty()) return
        val generation = hostDataEpoch.start()
        val subscription = ModHostDataSnapshot.observe(core, route.owner, fields) { snapshot ->
            runOnUiThread {
                if (hostDataEpoch.isCurrent(generation) && isCurrentSceneInstance() && windowPolicy().showSceneContent && !isFinishing && !isDestroyed) hostData = snapshot
            }
        }
        if (subscription == null) hostDataEpoch.stop() else dataSubscription = subscription
    }

    private fun stopHostData() {
        hostDataEpoch.stop()
        dataSubscription?.close()
        dataSubscription = null
        hostData = JSONObject()
    }

    private fun requestEffect(effect: ModRearOpenEffect, complete: (Boolean, String?) -> Unit = { _, _ -> }) {
        val route = launch ?: run { complete(false, "E_STALE_SESSION"); return }
        val principal = route.owner.principal as? SessionPrincipal.SignedMod
            ?: run { complete(false, "E_CAPABILITY_DENIED"); return }
        val modId = principal.modId
        if (effect.scene != "rear" || effect.region !in setOf("FULL", "CAMERA_SAFE")) {
            complete(false, "E_INVALID_STATE"); return
        }
        if (core.modDataOwner(modId) != route.owner) {
            complete(false, "E_STALE_SESSION"); return
        }
        if (effect.keepVisible != route.request.keepVisible) {
            complete(false, "E_CAPABILITY_DENIED"); return
        }
        val region = if (effect.region == "FULL") RearRegion.FULL else RearRegion.CAMERA_SAFE
        val request = sceneRequestForModEffect(route.request, region, effect.contentRotation)
        if (request.region == route.request.region && request.contentRotation == route.request.contentRotation &&
            request.hasExplicitContentRotation == route.request.hasExplicitContentRotation) {
            complete(true, null)
            return
        }
        val transitions = core.sceneViewportTransitions
            ?: run { complete(false, "E_SCENE_OPEN"); return }
        val oldWindow = runCatching { windowReport() }.getOrElse {
            complete(false, "E_INVALID_STATE"); return
        }
        if (oldWindow.viewport.region != (if (route.request.region == RearRegion.FULL) SharedSceneRegion.FULL else SharedSceneRegion.SAFE) ||
            oldWindow.viewport.contentRotation != route.request.contentRotation ||
            !isSceneContentVisible() || !window.decorView.isAttachedToWindow || !window.decorView.isShown) {
            complete(false, "E_INVALID_STATE"); return
        }
        val token = runCatching { core.issueSceneActivationAfterUserConfirmation(route.owner, request) }
            .getOrElse { complete(false, "E_ACTIVATION_REQUIRED"); return }
        val transitionId = UUID.randomUUID().toString().replace("-", "")
        val deadline = SystemClock.elapsedRealtime() + RearSceneCoordinator.MAX_VIEWPORT_TRANSITION_MS
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                transitions.beginSceneViewportTransition(route.owner, route.sessionId, route.generation,
                    transitionId, route.request, request, token, deadline)
            }.getOrElse { SceneOperationResult(false, SceneError.BACKEND_UNAVAILABLE, retryable = true) }
            runOnUiThread {
                if (!result.success) {
                    val code = result.error?.let { "E_${it.name}" } ?: "E_SCENE_OPEN"
                    error = "无法切换背屏场景：${result.error ?: "服务暂不可用"}"
                    complete(false, code)
                    return@runOnUiThread
                }
                if (!isCurrentSceneInstance() || isFinishing || isDestroyed || launch != route ||
                    core.modDataOwner(modId) != route.owner) {
                    transitions.abortSceneViewportTransition(route.owner, route.sessionId, route.generation, transitionId)
                    complete(false, "E_STALE_SESSION")
                    return@runOnUiThread
                }
                val targetRoute = route.copy(request = request)
                val transition = ActiveSceneViewportTransition(transitionId, route, targetRoute, oldWindow, deadline, complete)
                sceneViewportTransition = transition
                scheduleSceneViewportTransitionTimeout(transition)
                launch = targetRoute
                contentLayoutReady = false
                viewportGeometryEpoch++
                configureSceneViewport()
                if (hasCurrentContainerLayout()) markSceneContainerLayoutReady(launchTicket)
            }
        }
    }

    private fun cancelPendingEffect() {
        effectRequest = null
        effectRequestCompletion?.invoke(false, "E_USER_CANCELLED")
        effectRequestCompletion = null
    }

    private fun cancelPendingCastEffect() {
        castEffectRequest = null
        castEffectCompletion?.invoke(false, "E_USER_CANCELLED", null)
        castEffectCompletion = null
    }

    private fun launchModCastPicker(effect: ModCastStartEffect, source: ModCastActivationSource) {
        val principal = launch?.owner?.principal as? SessionPrincipal.SignedMod
            ?: run { castEffectCompletion?.invoke(false, "E_MOD_REVOKED", null); castEffectCompletion = null; return }
        if (source != castSource || !ModCastActivationSources.isRegistered(source, principal.modId) ||
            !isSceneContentVisible() || !isCurrentSceneInstance() || isFinishing || isDestroyed) {
            castEffectCompletion?.invoke(false, "E_CAST_SOURCE_STALE", null)
            castEffectCompletion = null
            return
        }
        val intent = ModCastTargetPickerActivity.createIntent(this, effect, source)
        runCatching {
            startActivityForResult(intent, MOD_CAST_PICKER_REQUEST, ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        }.onFailure {
            castEffectCompletion?.invoke(false, "E_TARGET_PICKER_UNAVAILABLE", null)
            castEffectCompletion = null
        }
    }

    @Deprecated("Host target picker returns an opaque status only")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MOD_CAST_PICKER_REQUEST) return
        val completion = castEffectCompletion ?: return
        castEffectCompletion = null
        val source = castSource
        if (source == null || !ModCastActivationSources.isRegistered(source, source.modId) ||
            !isCurrentSceneInstance() || isFinishing || isDestroyed) {
            completion(false, "E_CAST_SOURCE_STALE", null)
            return
        }
        val result = if (resultCode == Activity.RESULT_OK && data != null) org.json.JSONObject()
            .put("sessionId", data.getStringExtra(ModCastTargetPickerActivity.EXTRA_RESULT_SESSION_ID))
            .put("generation", data.getLongExtra(ModCastTargetPickerActivity.EXTRA_RESULT_GENERATION, 0L))
            .put("state", "READY")
            .put("mode", data.getStringExtra(ModCastTargetPickerActivity.EXTRA_RESULT_MODE))
            .put("region", data.getStringExtra(ModCastTargetPickerActivity.EXTRA_RESULT_REGION))
            .put("contentRotation", data.getIntExtra(ModCastTargetPickerActivity.EXTRA_RESULT_ROTATION, 0))
            .put("presentationKind", data.getStringExtra(ModCastTargetPickerActivity.EXTRA_RESULT_PRESENTATION_KIND)) else null
        completion(resultCode == Activity.RESULT_OK, data?.getStringExtra(ModCastTargetPickerActivity.EXTRA_RESULT_CODE), result)
    }

    /** Called only from the renderer's native button click callback; Mod JSON supplies an opaque ref only. */
    private fun invokeRegisteredAction(ref: RegisteredActionRef) {
        val route = launch ?: return
        val active = pkg ?: return
        if (!isSceneContentVisible() || isFinishing || isDestroyed) return
        val declared = active.requiredCapabilities.any { it.name == "action.invoke" } ||
            active.optionalCapabilities.any { it.name == "action.invoke" }
        if (!declared) {
            error = "此 Mod 未声明已登记动作能力"
            return
        }
        val installed = ModPackageStore.installed(this, active.id)
        if (installed == null || !installed.enabled || installed.authorFingerprint != active.authorFingerprint ||
            "action.invoke" !in installed.grantedCapabilities) {
            error = "此 Mod 未获已登记动作授权"
            return
        }
        val currentOwner = core.modDataOwner(active.id)
        if (currentOwner == null || currentOwner.principal !is SessionPrincipal.SignedMod ||
            currentOwner.principal.modId != active.id || currentOwner.principal.authorFingerprint != active.authorFingerprint) {
            error = "此 Mod 已停用或作者信任已撤销"
            return
        }
        val activation = core.issueRegisteredActionActivationAfterHostClick(currentOwner, ref)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = core.invokeRegisteredAction(currentOwner, ref, activation)
            runOnUiThread {
                if (!isCurrentSceneInstance() || !isSceneContentVisible() || isFinishing || isDestroyed) return@runOnUiThread
                error = when (result) {
                    ActionDispatchResult.Accepted -> ""
                    is ActionDispatchResult.Rejected -> when (result.error) {
                        ActionError.REVOKED -> "此快捷操作已撤销"
                        ActionError.TARGET_UNAVAILABLE -> "快捷操作目标已不可用或签名已变化"
                        ActionError.BACKGROUND_RESTRICTED -> "操作已过期，请重新点击"
                        else -> "无法执行快捷操作：${result.error}"
                    }
                }
            }
        }
    }

    private fun submitMeasuredViewportIfNeeded(ticket: String) {
        val transition = sceneViewportTransition
        if (transition != null) {
            when (transition.phase) {
                SceneViewportTransitionPhase.APPLYING -> submitSceneViewportTransition(transition)
                SceneViewportTransitionPhase.ROLLING_BACK -> finishViewportRollbackIfMeasured(transition)
            }
            return
        }
        reportMeasuredWindow(ticket)
    }

    private fun scheduleSceneViewportTransitionTimeout(transition: ActiveSceneViewportTransition) {
        sceneViewportTransitionTimeout?.let(sceneViewportTransitionHandler::removeCallbacks)
        val timeout = Runnable {
            if (sceneViewportTransition !== transition) return@Runnable
            when (transition.phase) {
                SceneViewportTransitionPhase.APPLYING -> failSceneViewportTransition(transition, "E_DISPLAY_BUSY")
                SceneViewportTransitionPhase.ROLLING_BACK -> {
                    if (!transition.rollbackFallbackAttempted) {
                        transition.rollbackFallbackAttempted = true
                        submitSafeViewportRollbackFallback(transition)
                        if (sceneViewportTransition === transition) scheduleSceneViewportTransitionTimeout(transition)
                    } else {
                        terminateUnrecoverableSceneTransition(transition)
                    }
                }
            }
        }
        sceneViewportTransitionTimeout = timeout
        val remaining = if (transition.phase == SceneViewportTransitionPhase.APPLYING)
            (transition.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        else VIEWPORT_ROLLBACK_TIMEOUT_MS
        sceneViewportTransitionHandler.postDelayed(timeout, remaining)
    }

    private fun submitSceneViewportTransition(transition: ActiveSceneViewportTransition) {
        if (sceneViewportTransition !== transition || transition.phase != SceneViewportTransitionPhase.APPLYING ||
            transition.reportInFlight || !isCurrentSceneInstance() || isFinishing || isDestroyed) return
        if (SystemClock.elapsedRealtime() >= transition.deadlineElapsedRealtimeMs) {
            failSceneViewportTransition(transition, "E_DISPLAY_BUSY"); return
        }
        if (!contentLayoutReady || !hasCurrentContainerLayout()) return
        if (!applyMeasuredCameraInsets()) return
        val route = launch ?: run { failSceneViewportTransition(transition, "E_STALE_SESSION"); return }
        if (route != transition.targetRoute || core.modDataOwner(
                (route.owner.principal as? SessionPrincipal.SignedMod)?.modId ?: "") != route.owner) {
            failSceneViewportTransition(transition, "E_STALE_SESSION"); return
        }
        val report = runCatching { windowReport() }.getOrElse {
            failSceneViewportTransition(transition, "E_INVALID_STATE"); return
        }
        if (report.viewport.region != (if (route.request.region == RearRegion.FULL) SharedSceneRegion.FULL else SharedSceneRegion.SAFE) ||
            report.viewport.contentRotation != route.request.contentRotation ||
            report.viewport.displayId != window.decorView.display?.displayId) return
        val transitions = core.sceneViewportTransitions ?: run {
            failSceneViewportTransition(transition, "E_SCENE_OPEN"); return
        }
        val geometryEpoch = viewportGeometryEpoch
        transition.reportInFlight = true
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                transitions.reportSceneViewportTransition(route.owner, route.sessionId, route.generation, transition.id, report)
            }.getOrElse { SceneOperationResult(false, SceneError.BACKEND_UNAVAILABLE, retryable = true) }
            runOnUiThread {
                if (sceneViewportTransition !== transition) return@runOnUiThread
                transition.reportInFlight = false
                if (transition.phase != SceneViewportTransitionPhase.APPLYING) return@runOnUiThread
                if (!result.success) {
                    failSceneViewportTransition(transition, result.error?.let { "E_${it.name}" } ?: "E_SCENE_OPEN")
                    return@runOnUiThread
                }
                if (geometryEpoch != viewportGeometryEpoch || !hasCurrentContainerLayout()) {
                    if (hasCurrentContainerLayout()) submitSceneViewportTransition(transition)
                    return@runOnUiThread
                }
                val latestReport = runCatching { windowReport() }.getOrNull()
                if (latestReport == null || !sameMeasuredViewport(report, latestReport)) {
                    configureSceneViewport()
                    if (hasCurrentContainerLayout()) submitSceneViewportTransition(transition)
                    return@runOnUiThread
                }
                val principal = route.owner.principal as? SessionPrincipal.SignedMod
                if (principal == null || core.modDataOwner(principal.modId) != route.owner ||
                    !window.decorView.isAttachedToWindow || !window.decorView.isShown ||
                    isFinishing || isDestroyed || !isCurrentSceneInstance()) {
                    failSceneViewportTransition(transition, "E_STALE_SESSION"); return@runOnUiThread
                }
                val committed = transitions.commitSceneViewportTransition(
                    route.owner, route.sessionId, route.generation, transition.id)
                if (!committed.success) {
                    failSceneViewportTransition(transition, committed.error?.let { "E_${it.name}" } ?: "E_SCENE_OPEN")
                    return@runOnUiThread
                }
                sceneViewportTransitionTimeout?.let(sceneViewportTransitionHandler::removeCallbacks)
                sceneViewportTransitionTimeout = null
                viewportPresentationRefreshPending = true
                sceneViewportTransition = null
                error = ""
                reconcileScenePresentation()
                if (!transition.completionSent) {
                    transition.completionSent = true
                    transition.complete(true, null)
                }
            }
        }
    }

    private fun sameMeasuredViewport(expected: SceneWindowReport, actual: SceneWindowReport): Boolean =
        expected.component == actual.component && expected.taskId == actual.taskId &&
            expected.viewport.copy(revision = 0L) == actual.viewport.copy(revision = 0L)

    private fun failSceneViewportTransition(transition: ActiveSceneViewportTransition, code: String) {
        if (sceneViewportTransition !== transition) return
        val transitions = core.sceneViewportTransitions
        transitions?.abortSceneViewportTransition(transition.oldRoute.owner, transition.oldRoute.sessionId,
            transition.oldRoute.generation, transition.id)
        if (transition.phase == SceneViewportTransitionPhase.ROLLING_BACK) {
            if (!transition.completionSent) {
                transition.completionSent = true
                transition.complete(false, transition.failureCode ?: code)
            }
            return
        }
        transition.phase = SceneViewportTransitionPhase.ROLLING_BACK
        transition.reportInFlight = false
        transition.failureCode = code
        launch = transition.oldRoute
        contentLayoutReady = false
        viewportGeometryEpoch++
        configureSceneViewport()
        scheduleSceneViewportTransitionTimeout(transition)
        if (hasCurrentContainerLayout()) markSceneContainerLayoutReady(launchTicket)
    }

    private fun finishViewportRollbackIfMeasured(transition: ActiveSceneViewportTransition) {
        if (sceneViewportTransition !== transition || transition.phase != SceneViewportTransitionPhase.ROLLING_BACK ||
            transition.rollbackFallbackInFlight || !contentLayoutReady || !hasCurrentContainerLayout()) return
        val current = runCatching { windowReport() }.getOrNull() ?: return
        if (!sameMeasuredViewport(transition.oldWindow, current)) {
            if (transition.rollbackFallbackAttempted) submitSafeViewportRollbackFallback(transition)
            return
        }
        val principal = transition.oldRoute.owner.principal as? SessionPrincipal.SignedMod ?: return
        if (core.modDataOwner(principal.modId) != transition.oldRoute.owner || isFinishing || isDestroyed) return
        sceneViewportTransitionTimeout?.let(sceneViewportTransitionHandler::removeCallbacks)
        sceneViewportTransitionTimeout = null
        sceneViewportTransition = null
        error = "无法切换背屏场景：${transition.failureCode ?: "E_SCENE_OPEN"}"
        if (!transition.completionSent) {
            transition.completionSent = true
            transition.complete(false, transition.failureCode ?: "E_SCENE_OPEN")
        }
    }

    /** Re-measure the original request on this exact lease before closing an unrecoverable task. */
    private fun submitSafeViewportRollbackFallback(transition: ActiveSceneViewportTransition) {
        if (sceneViewportTransition !== transition || transition.phase != SceneViewportTransitionPhase.ROLLING_BACK ||
            transition.rollbackFallbackInFlight || !contentLayoutReady || !hasCurrentContainerLayout() ||
            isFinishing || isDestroyed || !isCurrentSceneInstance()) return
        val route = transition.oldRoute
        val principal = route.owner.principal as? SessionPrincipal.SignedMod ?: return
        if (launch != route || core.modDataOwner(principal.modId) != route.owner) {
            terminateUnrecoverableSceneTransition(transition); return
        }
        if (!applyMeasuredCameraInsets()) return
        val report = runCatching { windowReport() }.getOrElse { return }
        val expectedRegion = if (route.request.region == RearRegion.FULL) SharedSceneRegion.FULL else SharedSceneRegion.SAFE
        if (report.viewport.region != expectedRegion || report.viewport.contentRotation != route.request.contentRotation ||
            report.viewport.displayId != window.decorView.display?.displayId) return
        val geometryEpoch = viewportGeometryEpoch
        transition.rollbackFallbackInFlight = true
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                core.displaySessions.reportSceneViewport(route.owner, route.sessionId, route.generation, report)
            }.getOrElse { SceneOperationResult(false, SceneError.BACKEND_UNAVAILABLE, retryable = true) }
            runOnUiThread {
                if (sceneViewportTransition !== transition) return@runOnUiThread
                transition.rollbackFallbackInFlight = false
                if (!result.success) {
                    terminateUnrecoverableSceneTransition(transition); return@runOnUiThread
                }
                if (geometryEpoch != viewportGeometryEpoch || !hasCurrentContainerLayout()) {
                    if (hasCurrentContainerLayout()) submitSafeViewportRollbackFallback(transition)
                    return@runOnUiThread
                }
                val latest = runCatching { windowReport() }.getOrNull()
                if (latest == null || !sameMeasuredViewport(report, latest) || !window.decorView.isAttachedToWindow ||
                    !window.decorView.isShown || core.modDataOwner(principal.modId) != route.owner) {
                    if (latest != null && hasCurrentContainerLayout()) submitSafeViewportRollbackFallback(transition)
                    return@runOnUiThread
                }
                sceneViewportTransitionTimeout?.let(sceneViewportTransitionHandler::removeCallbacks)
                sceneViewportTransitionTimeout = null
                viewportPresentationRefreshPending = true
                sceneViewportTransition = null
                error = "无法切换背屏场景：${transition.failureCode ?: "E_SCENE_OPEN"}"
                reconcileScenePresentation()
                if (!transition.completionSent) {
                    transition.completionSent = true
                    transition.complete(false, transition.failureCode ?: "E_SCENE_OPEN")
                }
            }
        }
    }

    /** If neither the old geometry nor a verified same-lease re-measure is usable, close only this session. */
    private fun terminateUnrecoverableSceneTransition(transition: ActiveSceneViewportTransition) {
        if (sceneViewportTransition !== transition) return
        core.sceneViewportTransitions?.abortSceneViewportTransition(
            transition.oldRoute.owner, transition.oldRoute.sessionId, transition.oldRoute.generation, transition.id)
        sceneViewportTransitionTimeout?.let(sceneViewportTransitionHandler::removeCallbacks)
        sceneViewportTransitionTimeout = null
        sceneViewportTransition = null
        viewportPresentationRefreshPending = true
        if (!transition.completionSent) {
            transition.completionSent = true
            transition.complete(false, transition.failureCode ?: "E_SCENE_OPEN")
        }
        coreClosed = runCatching {
            core.displaySessions.closeScene(transition.oldRoute.owner, transition.oldRoute.sessionId).success
        }.getOrDefault(false)
        ModSceneReadyController.markClosed(launchTicket)
        finishAndRemoveTask()
    }

    private fun reportMeasuredWindow(ticket: String) {
        if (!isCurrentSceneInstance() || windowReported || windowReportRequests.isInFlight) return
        if (!contentLayoutReady) return
        if (!applyMeasuredCameraInsets()) return
        val route = launch ?: return
        val controller = readyController ?: return
        val report = runCatching { windowReport() }.getOrElse {
            error = it.message ?: "无法读取背屏窗口几何"
            return
        }
        observeWindowGeometry(report.viewport)
        val epoch = windowLifecycleGate.currentWindowRequestEpoch()
        val geometryEpoch = viewportGeometryEpoch
        val reportToken = windowReportRequests.begin(
            activityStarted = windowLifecycleGate.currentState().activityStarted,
            layoutApplied = contentLayoutReady && hasCurrentContainerLayout(),
        ) ?: return
        controller.submit(
            instance = instanceToken,
            report = report,
            windowEpoch = epoch,
            active = windowLifecycleGate.currentState().activityStarted,
        ) { completion ->
            if (!windowReportRequests.finish(reportToken)) return@submit
            if (completion.instance != instanceToken || !isCurrentSceneInstance() || isFinishing || isDestroyed) return@submit
            if (geometryEpoch != viewportGeometryEpoch) {
                windowReported = false
                // The report in flight may describe an obsolete rectangle. Re-read
                // current insets and container bounds after it drains; submit the
                // latest viewport now if it is already applied, otherwise the exact
                // native layout listener will submit it when that layout completes.
                applyMeasuredCameraInsets()
                configureSceneViewport()
                when (modSceneStaleReportAction(geometryEpoch, viewportGeometryEpoch, hasCurrentContainerLayout())) {
                    ModSceneStaleReportAction.RESUBMIT_LATEST -> markSceneContainerLayoutReady(ticket)
                    ModSceneStaleReportAction.WAIT_FOR_LAYOUT -> contentLayoutReady = false
                    null -> markSceneContainerLayoutReady(ticket)
                }
                return@submit
            }
            if (!completion.result.success) {
                logSceneLifecycle("ready_rejected", "error=${completion.result.error?.name ?: "none"} retryable=${completion.result.retryable} epoch=${completion.windowEpoch}")
                error = "背屏窗口验证失败：${completion.result.error}"
                finishAndRemoveTask()
                return@submit
            }
            if (completion.windowEpoch != windowLifecycleGate.currentWindowRequestEpoch()) {
                submitMeasuredViewportIfNeeded(ticket)
                return@submit
            }
            completeWindowReady(route)
        }
    }

    private fun refreshRearViewportBinding() {
        if (pkg?.let(ModRearInfoBindings::declared) != true) return
        // Queued events are invalidation signals, never the source of geometry.
        rearViewport = launch?.let(core::queryModRearViewport)
    }

    private fun completeWindowReady(route: SceneLaunchContext) {
        logSceneLifecycle("window_ready", "region=${route.request.region.name} rotation=${route.request.contentRotation}")
        windowReported = true
        refreshRearViewportBinding()
        sceneReady = true
        message = ""
        core.displaySessions.attachSceneObserver(route.owner, route.sessionId, generation, observer)
        synchronizeVisibleConsumers()
        reportActualWindowVisibility()
    }

    private fun reportActualWindowVisibility() {
        if (!::windowLifecycleGate.isInitialized) return
        val decor = window.decorView
        windowLifecycleGate.onWindowStateChanged(
            attached = decor.isAttachedToWindow,
            viewShown = decor.isShown && decor.visibility == View.VISIBLE,
            windowVisible = decor.windowVisibility == View.VISIBLE,
        )
        if (!isCurrentSceneInstance() || !sceneReady || !windowReported || isFinishing || isDestroyed) {
            applyWindowPolicy()
            return
        }
        val route = launch ?: return
        val visible = windowLifecycleGate.currentState().windowVisible
        val reported = runCatching { core.displaySessions.reportSceneVisibility(route.owner, route.sessionId, generation, visible) }
            .getOrNull()
        Log.i("ModRearSceneVisibility", "stage=window_report session=${route.sessionId} generation=$generation visible=$visible attached=${decor.isAttachedToWindow} shown=${decor.isShown} windowVisible=${decor.windowVisibility == View.VISIBLE} decorPx=${decor.width}x${decor.height} rootPx=${sceneRoot?.width ?: 0}x${sceneRoot?.height ?: 0} containerPx=${sceneContainer?.width ?: 0}x${sceneContainer?.height ?: 0} containerLayout=${sceneContainer?.hasAppliedContentLayout == true} success=${reported?.success == true} error=${reported?.error?.name ?: "none"} epoch=${windowLifecycleGate.currentWindowRequestEpoch()}")
        if (reported?.success == true) reconcileScenePresentation()
        else if (reported != null) {
            windowLifecycleGate.applyPresentationSnapshot(null, windowLifecycleGate.currentWindowRequestEpoch())
        }
    }

    /** Called on the UI thread: queued Scene events only trigger a fresh owner/session snapshot read. */
    private fun reconcileScenePresentation() {
        if (!isCurrentSceneInstance() || !sceneReady || !windowReported || isFinishing || isDestroyed) {
            synchronizeVisibleConsumers()
            return
        }
        val route = launch ?: return
        val requestEpoch = windowLifecycleGate.currentWindowRequestEpoch()
        val snapshotResult = runCatching {
            core.displaySessions.queryScenePresentation(route.owner, route.sessionId, generation)
        }
        if (snapshotResult.isFailure) {
            windowLifecycleGate.applyPresentationSnapshot(null, requestEpoch)
            Log.w("ModRearSceneVisibility", "stage=snapshot_query_failed session=${route.sessionId} generation=$generation epoch=$requestEpoch type=${snapshotResult.exceptionOrNull()?.javaClass?.simpleName}")
            error = "无法读取背屏显示状态"
            return
        }
        val snapshot = snapshotResult.getOrNull()
        val rejected = windowLifecycleGate.rejectionReason(snapshot, requestEpoch)
        val applied = windowLifecycleGate.applyPresentationSnapshot(snapshot, requestEpoch)
        when {
            snapshot == null && applied -> Log.i("ModRearSceneVisibility", "stage=snapshot_missing session=${route.sessionId} generation=$generation epoch=$requestEpoch result=fail_closed")
            rejected != null -> Log.i("ModRearSceneVisibility", "stage=snapshot_discarded reason=$rejected session=${route.sessionId} generation=$generation epoch=$requestEpoch revision=${snapshot?.revision ?: -1L}")
            else -> Log.i("ModRearSceneVisibility", "stage=snapshot_applied session=${route.sessionId} generation=$generation epoch=$requestEpoch revision=${snapshot?.revision ?: -1L} visible=${snapshot?.visible ?: false} accepted=$applied")
        }
        if (applied && viewportPresentationRefreshPending && snapshot?.visible == true &&
            windowLifecycleGate.currentState().windowVisible && isCurrentSceneInstance() && !isFinishing && !isDestroyed) {
            viewportPresentationRefreshPending = false
            synchronizeVisibleConsumers()
        }
        if (snapshot == null) {
            coreClosed = true
            sceneReady = false
            finishAndRemoveTask()
        }
    }

    private fun onWindowLifecycleStateChanged(state: ModSceneWindowLifecycleGate.State) {
        windowActuallyVisible = state.windowVisible
        coordinatorVisible = state.coordinatorVisible
        if (!state.windowVisible) {
            sceneViewportTransition?.takeIf { it.phase == SceneViewportTransitionPhase.APPLYING }
                ?.let { failSceneViewportTransition(it, "E_DISPLAY_BUSY") }
        }
        synchronizeVisibleConsumers()
    }

    private fun synchronizeVisibleConsumers() {
        if (isSceneContentVisible()) resumeVisibleConsumers() else suspendVisibleConsumers()
        applyWindowPolicy()
    }

    /** Only visible rear scenes own a worker observer; hidden Scenes keep their Core lease and task. */
    private fun resumeVisibleConsumers() {
        if (!isCurrentSceneInstance() || !windowPolicy().showSceneContent || isFinishing || isDestroyed) return
        val route = launch ?: return
        val modId = (route.owner.principal as? SessionPrincipal.SignedMod)?.modId ?: return
        if (!moduleSlotActive) {
            moduleSlotActive = true
            broker.open(modId, mainExecutor, brokerListener, visible = true)
            if (effectConsumer == null) {
                effectConsumer = object : ModHostEffectRouter.Consumer {
                    override fun request(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect) {
                        runOnUiThread { if (isCurrentSceneInstance() && moduleSlotActive && isSceneContentVisible() && !isFinishing && !isDestroyed) effectRequest = effect }
                    }
                    override fun requestWithResult(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect, complete: (Boolean, String?) -> Unit) {
                        runOnUiThread {
                            if (!isCurrentSceneInstance() || !moduleSlotActive || !isSceneContentVisible() || isFinishing || isDestroyed) {
                                complete(false, "E_SCENE_NOT_VISIBLE")
                                return@runOnUiThread
                            }
                            effectRequestCompletion?.invoke(false, "E_EFFECT_REPLACED")
                            effectRequest = effect
                            effectRequestCompletion = complete
                        }
                    }
                    override fun requestCastWithResult(mod: InstalledMod, eventId: String, effect: ModCastStartEffect,
                        source: ModCastActivationSource, complete: (Boolean, String?, org.json.JSONObject?) -> Unit) {
                        runOnUiThread {
                            if (source != castSource || !ModCastActivationSources.isRegistered(source, mod.id) ||
                                !isCurrentSceneInstance() || !moduleSlotActive || !isSceneContentVisible() || isFinishing || isDestroyed) {
                                complete(false, "E_CAST_SOURCE_STALE", null)
                                return@runOnUiThread
                            }
                            castEffectCompletion?.invoke(false, "E_EFFECT_REPLACED", null)
                            castEffectRequest = effect
                            castEffectCompletion = complete
                        }
                    }
                }
            }
        }
        if (castSource == null) {
            val source = ModCastActivationSource(modId, ModCastActivationSource.Surface.REAR_SCENE,
                UUID.randomUUID().toString(), route.sessionId, route.generation)
            castSource = source
            castSourceRegistration = ModCastActivationSources.register(source, requireNotNull(effectConsumer))
        }
        ModHostEffectRouter.consumers[modId] = requireNotNull(effectConsumer)
        broker.setObserverVisible(modId, brokerListener, true)
        startHostData()
    }

    private fun suspendVisibleConsumers() {
        stopHostData()
        val modId = (launch?.owner?.principal as? SessionPrincipal.SignedMod)?.modId
        if (moduleSlotActive && modId != null) broker.setObserverVisible(modId, brokerListener, false)
        if (modId != null) effectConsumer?.let { ModHostEffectRouter.consumers.remove(modId, it) }
        castSourceRegistration?.close()
        castSourceRegistration = null
        castSource = null
        castEffectRequest = null
        castEffectCompletion?.invoke(false, "E_CAST_SOURCE_STALE", null)
        castEffectCompletion = null
    }

    private fun isWindowActuallyVisible(): Boolean = windowActuallyVisible && windowReported && !isFinishing && !isDestroyed

    private fun isSceneContentVisible(): Boolean = windowPolicy().showSceneContent

    private fun applyWindowPolicy() {
        val policy = windowPolicy()
        val requested = launch?.request?.keepVisible == true
        val expected = policy.keepScreenOn
        val keepFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val before = (window.attributes.flags and keepFlag) != 0
        if (expected) window.addFlags(keepFlag) else window.clearFlags(keepFlag)
        val after = (window.attributes.flags and keepFlag) != 0
        val gate = if (::windowLifecycleGate.isInitialized) windowLifecycleGate.currentState() else null
        val displayId = window.decorView.display?.displayId ?: -1
        val decor = window.decorView
        val stateKey = "task=$taskId display=$displayId session=${sessionId ?: "none"} generation=$generation " +
            "sceneReady=$sceneReady windowReported=$windowReported contentLayoutReady=$contentLayoutReady " +
            "attached=${decor.isAttachedToWindow} shown=${decor.isShown} windowVisible=${isWindowActuallyVisible()} " +
            "decorPx=${decor.width}x${decor.height} rootPx=${sceneRoot?.width ?: 0}x${sceneRoot?.height ?: 0} " +
            "containerPx=${sceneContainer?.width ?: 0}x${sceneContainer?.height ?: 0} containerLayout=${sceneContainer?.hasAppliedContentLayout == true} " +
            "coreVisible=$coordinatorVisible contentVisible=${policy.showSceneContent} requested=$requested expected=$expected " +
            "transition=${sceneViewportTransition?.phase ?: "none"} refreshPending=$viewportPresentationRefreshPending " +
            "epoch=${gate?.windowRequestEpoch ?: -1L} revision=${gate?.presentationRevision ?: -1L}"
        // Log only policy-state changes, observed Window flag transitions, or an actual
        // before/after application. Repeated frame/layout callbacks with stable state stay quiet.
        if (windowPolicyTraceGate.shouldLog(stateKey, before, after)) {
            Log.i("ModSceneKeepPolicy", "apply $stateKey windowAttrKeepBefore=$before windowAttrKeepAfter=$after")
        }
    }


    private fun logSceneLifecycle(stage: String, details: String = "") {
        val gate = if (::windowLifecycleGate.isInitialized) windowLifecycleGate.currentState() else null
        val displayId = window.decorView.display?.displayId ?: -1
        Log.i("ModRearSceneLifecycle", "stage=$stage session=${sessionId ?: "none"} generation=$generation instance=$instanceToken task=$taskId display=$displayId activityStarted=${gate?.activityStarted ?: false} windowVisible=${gate?.windowVisible ?: false} coreVisible=${gate?.coordinatorVisible ?: false} epoch=${gate?.windowRequestEpoch ?: -1L} finishing=$isFinishing changingConfigurations=$isChangingConfigurations $details")
    }

    private fun windowPolicy() = ModSceneWindowPolicy(
        windowReady = sceneReady,
        windowAttachedAndVisible = isWindowActuallyVisible(),
        coordinatorVisible = coordinatorVisible,
        keepVisibleRequested = launch?.request?.keepVisible == true,
    )

    private fun registerSceneInstance() {
        instanceToken = nextSceneInstance.incrementAndGet()
        sessionId?.let { activeSceneInstances[it] = instanceToken }
    }

    private fun isCurrentSceneInstance(): Boolean = sessionId?.let { activeSceneInstances[it] == instanceToken && instanceToken != 0L } == true

    private fun acceptSceneEvent(eventSessionId: String, eventGeneration: Long): Boolean {
        val reason = when {
            !isCurrentSceneInstance() -> "STALE_ACTIVITY_INSTANCE"
            sessionId != eventSessionId -> "SESSION_MISMATCH"
            eventGeneration < generation -> "GENERATION_OLDER"
            else -> null
        }
        if (reason != null) {
            Log.i("ModRearSceneLifecycle", "stage=event_discarded reason=$reason session=${sessionId ?: "none"} generation=$generation eventSession=$eventSessionId eventGeneration=$eventGeneration instance=$instanceToken")
            return false
        }
        generation = eventGeneration
        return true
    }

    private fun windowReport(): SceneWindowReport {
        val component = componentName
        val root = window.decorView
        val width = root.width.coerceAtLeast(1)
        val height = root.height.coerceAtLeast(1)
        val display = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(metrics)
        val point = IntArray(2); root.getLocationOnScreen(point)
        val density = metrics.densityDpi.coerceAtLeast(1)
        val rotation = display.rotation
        val cutout = if (Build.VERSION.SDK_INT >= 28) root.rootWindowInsets?.displayCutout else null
        val safeLeft = cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0
        val safeTop = cutout?.safeInsetTop?.coerceIn(0, height) ?: 0
        val safeRight = cutout?.safeInsetRight?.coerceIn(0, width - safeLeft) ?: 0
        val safeBottom = cutout?.safeInsetBottom?.coerceIn(0, height - safeTop) ?: 0
        val insetsKnown = Build.VERSION.SDK_INT >= 28 && root.rootWindowInsets != null
        val cutoutKnown = cutout != null || insetsKnown
        cutoutReported = cutoutKnown
        if (!cutoutKnown) Log.w("ModRearScene", "ROM did not report a verifiable display-cutout safe area; camera-safe bounds are uncertain")
        val logicalDisplaySize = Point().also(display::getRealSize)
        val modeSize = runCatching { display.mode.physicalWidth to display.mode.physicalHeight }.getOrNull()
        val cutoutInsets = if (Build.VERSION.SDK_INT < 28 || root.rootWindowInsets == null) null else PixelInsets(safeLeft, safeTop, safeRight, safeBottom)
        val route = launch
        val region = if (route?.request?.region == RearRegion.FULL) SharedSceneRegion.FULL else SharedSceneRegion.SAFE
        val geometry = SceneViewportGeometry.fromWindow(
            displayBounds = SharedPixelRect(0, 0, logicalDisplaySize.x, logicalDisplaySize.y),
            windowBounds = SharedPixelRect(point[0], point[1], point[0] + width, point[1] + height),
            cutoutInsets = cutoutInsets,
            cutoutBoundsInWindow = cutout?.boundingRects.orEmpty().map { SharedPixelRect(it.left - point[0], it.top - point[1], it.right - point[0], it.bottom - point[1]) },
            cutoutKnowledge = when { cutoutInsets == null -> CutoutKnowledge.UNKNOWN; cutout != null -> CutoutKnowledge.KNOWN_PRESENT; else -> CutoutKnowledge.KNOWN_NONE },
            displayRotation = rotation,
            // The current Mod Compose tree uses the platform's measured orientation; an additional
            // view rotation is deliberately zero until a Scene request carries that separate intent.
            contentRotation = route?.request?.contentRotation ?: 0,
            effectiveDensityDpi = density,
            physicalModeWidthPx = modeSize?.first,
            physicalModeHeightPx = modeSize?.second,
        )
        val contentRect = geometry.contentBounds(region)
        require(!contentRect.isEmpty) { "E_SCENE_CONTENT_REGION_EMPTY" }
        val container = requireNotNull(sceneContainer) { "E_SCENE_CONTENT_NOT_APPLIED" }
        require(container.hasAppliedContentLayout && container.contentRotation == geometry.contentRotation) { "E_SCENE_CONTENT_NOT_APPLIED" }
        val containerPoint = IntArray(2).also(container::getLocationOnScreen)
        require(containerPoint[0] == contentRect.left && containerPoint[1] == contentRect.top &&
            container.width == contentRect.width && container.height == contentRect.height) { "E_SCENE_CONTENT_VIEWPORT_MISMATCH" }
        val viewport = RearViewportSnapshot(
            revision = ++viewportRevision,
            displayId = display.displayId,
            physicalDisplayPx = modeSize?.let { PixelSize(it.first, it.second) },
            contentBoundsPx = PixelRect(contentRect.left, contentRect.top, contentRect.right, contentRect.bottom),
            windowPx = PixelSize(width, height),
            windowDp = DpSize(width * 160f / density, height * 160f / density),
            densityDpi = density,
            renderBufferPx = null,
            displayRotation = rotation,
            contentRotation = geometry.contentRotation,
            inputTransform = geometry.windowInputMatrix(region).let { CoordinateTransform(it[0], it[1], it[2], it[3], it[4], it[5]) },
            region = region,
            cutoutKnowledge = geometry.cutoutKnowledge,
            cutoutBoundsPx = geometry.cutoutBoundsInWindow
        )
        return SceneWindowReport(component, taskId, viewport)
    }

    /** Update safe-area padding first; report only after Compose has laid out that exact inset state. */
    private fun applyMeasuredCameraInsets(): Boolean {
        val root = window.decorView
        val width = root.width.coerceAtLeast(1)
        val height = root.height.coerceAtLeast(1)
        val rootInsets = root.rootWindowInsets
        val cutout = if (Build.VERSION.SDK_INT >= 28) rootInsets?.displayCutout else null
        val left = cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0
        val top = cutout?.safeInsetTop?.coerceIn(0, height) ?: 0
        val right = cutout?.safeInsetRight?.coerceIn(0, width - left) ?: 0
        val bottom = cutout?.safeInsetBottom?.coerceIn(0, height - top) ?: 0
        val density = resources.displayMetrics.densityDpi.coerceAtLeast(1)
        val insets = CameraSafeInsets(
            (left * 160f / density).dp, (top * 160f / density).dp,
            (right * 160f / density).dp, (bottom * 160f / density).dp
        )
        val known = Build.VERSION.SDK_INT >= 28 && rootInsets != null
        val reported = cutout != null || known
        val layoutChanged = insets != cameraSafeInsets || cutoutReported != reported
        cameraSafeInsets = insets
        cutoutReported = reported
        if (!layoutChanged) {
            val container = sceneContainer ?: return false
            val root = sceneRoot ?: return false
            val params = container.layoutParams as? FrameLayout.LayoutParams ?: return false
            return container.hasAppliedContentLayout && container.width == params.width && container.height == params.height &&
                container.width > 0 && container.height > 0 && root.width > 0 && root.height > 0
        }
        viewportGeometryEpoch++
        contentLayoutReady = false
        invalidateGeometryPresentation()
        configureSceneViewport()
        return false
    }

    /** Place the native quarter-turn container on exactly the measured FULL/SAFE display rectangle. */
    private fun configureSceneViewport() {
        val decor = window.decorView
        val container = sceneContainer ?: return
        val root = sceneRoot ?: return
        if (decor.width <= 0 || decor.height <= 0) return
        val display = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val size = Point().also(display::getRealSize)
        val rootPoint = IntArray(2).also(decor::getLocationOnScreen)
        val cutout = if (Build.VERSION.SDK_INT >= 28) decor.rootWindowInsets?.displayCutout else null
        val insets = if (Build.VERSION.SDK_INT < 28 || decor.rootWindowInsets == null) null else PixelInsets(
            cutout?.safeInsetLeft?.coerceIn(0, decor.width) ?: 0,
            cutout?.safeInsetTop?.coerceIn(0, decor.height) ?: 0,
            cutout?.safeInsetRight?.coerceIn(0, decor.width) ?: 0,
            cutout?.safeInsetBottom?.coerceIn(0, decor.height) ?: 0
        )
        val region = if (launch?.request?.region == RearRegion.FULL) SharedSceneRegion.FULL else SharedSceneRegion.SAFE
        val turn = launch?.request?.contentRotation ?: 0
        container.contentRotation = turn
        val geometry = SceneViewportGeometry.fromWindow(
            SharedPixelRect(0, 0, size.x, size.y),
            SharedPixelRect(rootPoint[0], rootPoint[1], rootPoint[0] + decor.width, rootPoint[1] + decor.height),
            insets,
            cutout?.boundingRects.orEmpty().map { SharedPixelRect(it.left - rootPoint[0], it.top - rootPoint[1], it.right - rootPoint[0], it.bottom - rootPoint[1]) },
            when { insets == null -> CutoutKnowledge.UNKNOWN; cutout != null -> CutoutKnowledge.KNOWN_PRESENT; else -> CutoutKnowledge.KNOWN_NONE },
            display.rotation, turn, resources.displayMetrics.densityDpi.coerceAtLeast(1)
        )
        val bounds = geometry.contentBounds(region)
        if (bounds.isEmpty) return
        val params = (container.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(-1, -1)
        val left = bounds.left - rootPoint[0]
        val top = bounds.top - rootPoint[1]
        if (params.width != bounds.width || params.height != bounds.height || params.leftMargin != left || params.topMargin != top) {
            params.width = bounds.width; params.height = bounds.height
            params.leftMargin = left; params.topMargin = top
            container.layoutParams = params
            viewportGeometryEpoch++
            contentLayoutReady = false
            invalidateGeometryPresentation()
        }
        // If this exact geometry was already laid out, use it now. If requestLayout
        // is still pending, only the container's actual layout callback may open the
        // readiness path.
        markSceneContainerLayoutReady(launchTicket)
    }

    private fun markSceneContainerLayoutReady(ticket: String?) {
        if (ticket == null || isFinishing || isDestroyed) return
        val root = sceneRoot ?: return
        if (!hasCurrentContainerLayout() || root.width <= 0 || root.height <= 0) {
            contentLayoutReady = false
            return
        }
        contentLayoutReady = true
        runCatching { observeWindowGeometry(windowReport().viewport) }
        if ((sceneViewportTransition != null || !windowReported) && !windowReportRequests.isInFlight)
            submitMeasuredViewportIfNeeded(ticket)
    }

    /** Compare every submitted viewport field plus raw cutout facts lost by FULL-region bounds. */
    private fun observeWindowGeometry(viewport: RearViewportSnapshot): Boolean {
        val rootInsets = window.decorView.rootWindowInsets
        val cutout = if (Build.VERSION.SDK_INT >= 28) rootInsets?.displayCutout else null
        val width = window.decorView.width.coerceAtLeast(1)
        val height = window.decorView.height.coerceAtLeast(1)
        val safeLeft = cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0
        val safeTop = cutout?.safeInsetTop?.coerceIn(0, height) ?: 0
        val safeRight = cutout?.safeInsetRight?.coerceIn(0, width - safeLeft) ?: 0
        val safeBottom = cutout?.safeInsetBottom?.coerceIn(0, height - safeTop) ?: 0
        val insetsKnown = Build.VERSION.SDK_INT >= 28 && rootInsets != null
        val current = ModSceneWindowGeometry(
            viewport = viewport.copy(revision = 0L),
            safeLeftPx = safeLeft.takeIf { insetsKnown },
            safeTopPx = safeTop.takeIf { insetsKnown },
            safeRightPx = safeRight.takeIf { insetsKnown },
            safeBottomPx = safeBottom.takeIf { insetsKnown },
            insetsKnown = insetsKnown,
        )
        val previous = observedWindowGeometry
        if (!modSceneViewportGeometryChanged(previous, current)) return false
        observedWindowGeometry = current
        if (previous == null) return false
        viewportGeometryEpoch++
        // Keep the current, unchanged READY/visibility lease and keepVisible window policy
        // while the view is temporarily blacked out for an explicit same-task transaction.
        // No new viewport is published until its exact measured report commits.
        if (sceneViewportTransition == null) invalidateGeometryPresentation()
        return true
    }

    private fun invalidateGeometryPresentation() {
        // A same-lease region/rotation transition has not replaced the accepted Core
        // viewport yet. Keep its READY and keepVisible policy until the exact measured
        // target report commits; the transition surface itself stays black meanwhile.
        if (sceneViewportTransition != null) return
        windowReported = false
        sceneReady = false
        // The prior Core-visible snapshot belongs to the old geometry. Advancing
        // the lifecycle request epoch rejects any query already in flight; only a
        // fresh query after the replacement viewport is accepted can reopen content.
        if (::windowLifecycleGate.isInitialized) {
            windowLifecycleGate.invalidateForGeometryChange()
        } else {
            synchronizeVisibleConsumers()
        }
    }

    private fun hasCurrentContainerLayout(): Boolean {
        val container = sceneContainer ?: return false
        val params = container.layoutParams as? FrameLayout.LayoutParams ?: return false
        return ModSceneContainerLayout(
            actualWidth = container.width,
            actualHeight = container.height,
            actualLeft = container.left,
            actualTop = container.top,
            expectedWidth = params.width,
            expectedHeight = params.height,
            expectedLeft = params.leftMargin,
            expectedTop = params.topMargin,
            contentApplied = container.hasAppliedContentLayout,
        ).matchesCurrentGeometry()
    }

    private data class ActiveSceneViewportTransition(
        val id: String,
        val oldRoute: SceneLaunchContext,
        val targetRoute: SceneLaunchContext,
        val oldWindow: SceneWindowReport,
        val deadlineElapsedRealtimeMs: Long,
        val complete: (Boolean, String?) -> Unit,
        var phase: SceneViewportTransitionPhase = SceneViewportTransitionPhase.APPLYING,
        var reportInFlight: Boolean = false,
        var failureCode: String? = null,
        var completionSent: Boolean = false,
        var rollbackFallbackAttempted: Boolean = false,
        var rollbackFallbackInFlight: Boolean = false,
    )

    private enum class SceneViewportTransitionPhase { APPLYING, ROLLING_BACK }

    private data class CameraSafeInsets(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp) {
        companion object { val Zero = CameraSafeInsets(0.dp, 0.dp, 0.dp, 0.dp) }
    }

    companion object {
        private const val MOD_CAST_PICKER_REQUEST = 0x4D43
        private const val MAX_INITIAL_READY_RETRIES = 20
        private const val INITIAL_READY_RETRY_DELAY_MS = 150L
        private const val VIEWPORT_ROLLBACK_TIMEOUT_MS = MAX_INITIAL_READY_RETRIES * INITIAL_READY_RETRY_DELAY_MS
        private val nextSceneInstance = AtomicLong()
        private val activeSceneInstances = ConcurrentHashMap<String, Long>()
    }
}

/** No second title slot: the heading travels with the explanation on very short rear displays. */
@Composable internal fun ModRearDecisionDialog(show: Boolean, onCancel: () -> Unit, body: @Composable () -> Unit) {
    WindowDialog(show = show, onDismissRequest = onCancel,
        outsideMargin = DpSize(8.dp, 8.dp), insideMargin = DpSize(12.dp, 12.dp)) { body() }
}

@Composable internal fun ModRearSceneRequestContent(
    region: String, degrees: Int, keepVisible: String, onCancel: () -> Unit, onConfirm: () -> Unit,
) {
    ModRearRequestBody("Mod 请求打开背屏场景",
        listOf("$region · 内容旋转 $degrees°", "亮屏请求：$keepVisible", "仅应用到当前任务", "现有策略不变"),
        "确认打开", onCancel, onConfirm)
}

@Composable internal fun ModRearCastRequestContent(
    mode: String, region: String, direction: String, onCancel: () -> Unit, onConfirm: () -> Unit,
) {
    ModRearRequestBody("Mod 请求开始背屏投放",
        listOf("在主屏选择应用", "方式：$mode", "区域：$region", "方向：$direction", "投放与替换另行确认"),
        "选择应用", onCancel, onConfirm)
}

@Composable private fun ModRearRequestBody(
    title: String, disclosures: List<String>, confirmLabel: String,
    onCancel: () -> Unit, onConfirm: () -> Unit,
) {
    val scroll = rememberScrollState()
    val surface = DialogDefaults.backgroundColor()
    val fadePx = with(LocalDensity.current) { 8.dp.toPx() }
    val bodyViewport = Modifier.testTag("rear_mod_request_body")
        .pageBodyFade(surface, depth = 8.dp) { (scroll.value / fadePx).coerceIn(0f, 1f) }
        .pageBodyFade(surface, depth = 8.dp, trailing = true) {
            if (scroll.maxValue == Int.MAX_VALUE) 0f else ((scroll.maxValue - scroll.value) / fadePx).coerceIn(0f, 1f)
        }
    AppDecisionLayout(bodyModifier = bodyViewport, scroll = scroll, spacing = 8.dp, footer = {
        AppActionPair("取消", onCancel, confirmLabel, onConfirm,
            secondaryModifier = Modifier.testTag("rear_mod_request_cancel"),
            primaryModifier = Modifier.testTag("rear_mod_request_confirm"))
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppDecisionHeading(title, fontSize = 17.sp)
            disclosures.forEachIndexed { index, detail ->
                Text(detail, fontSize = 14.sp, color = if (index == 0) AppPalette.text() else AppPalette.secondary())
            }
        }
    }
}
