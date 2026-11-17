package dev.remrss.platform.sdk

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.graphics.Point
import dev.remrss.scene.geometry.CutoutKnowledge
import dev.remrss.scene.geometry.PixelInsets
import dev.remrss.scene.geometry.PixelRect
import dev.remrss.scene.geometry.SceneRegion
import dev.remrss.scene.geometry.SceneViewportGeometry
import dev.remrss.scene.geometry.QuarterTurnSceneContainer
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformCallback
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformService
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Stable error codes returned by the SDK's connection and foreground gates. */
object RearSdkErrorCode {
    const val CLIENT_CLOSED = "E_CLIENT_CLOSED"
    const val NOT_CONNECTED = "E_NOT_CONNECTED"
    const val SERVICE_UNAVAILABLE = "E_SERVICE_UNAVAILABLE"
    const val SERVICE_DISCONNECTED = "E_SERVICE_DISCONNECTED"
    const val CONTRACT_INCOMPATIBLE = "E_CONTRACT_INCOMPATIBLE"
    const val BINDING_DIED = "E_BINDING_DIED"
    const val FOREGROUND_REQUIRED = "E_FOREGROUND_REQUIRED"
}

/** SDK package metadata and the host wire contract accepted by this SDK release. */
object RearPlatformSdk {
    @JvmField val SDK_VERSION: String = BuildConfig.SDK_VERSION
    const val HOST_CONTRACT_VERSION = "remrss.platform/1"
}

internal object RearContractNegotiation {
    fun failureCode(contract: String?): String? = when {
        contract == null -> RearSdkErrorCode.SERVICE_UNAVAILABLE
        contract != RearPlatformSdk.HOST_CONTRACT_VERSION -> RearSdkErrorCode.CONTRACT_INCOMPATIBLE
        else -> null
    }
}

/** An app-owned binding. Keep one instance in Application scope while its sessions are active. */
class RearClient private constructor(
    context: Context,
    private val hostPackageName: String,
    private val eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val onEvent: (RearPlatformEvent) -> Unit
) : Closeable {
    constructor(
        context: Context,
        eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
        onEvent: (RearPlatformEvent) -> Unit
    ) : this(context, HOST_PACKAGE, eventExecutor, onEvent)

    private val appContext = context.applicationContext
    private val callerPackage = context.packageName
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rear-sdk-client").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val resultDispatcher = ClientResultDispatcher(eventExecutor) { closed.get() }
    private val epochIds = AtomicLong(0L)
    private val connectionStateVersion = AtomicLong(0L)
    private val currentEpoch = AtomicReference<ClientEpoch?>(null)
    private val eventDispatcher = ClientEventDispatcher(eventExecutor, { closed.get() }) { epoch -> currentEpoch.get() === epoch }
    private val operationQueue = ClientOperationQueue(
        worker,
        resultDispatcher,
        { closed.get() },
        { epoch -> currentEpoch.get() === epoch },
        { epoch -> releaseEpochAsync(epoch as ClientEpoch) }
    )
    @Volatile var contractVersion: String? = null
        private set
    private val connectionGate = ConnectionHandshakeGate<IBinder>(RearPlatformSdk.HOST_CONTRACT_VERSION)
    private val activeBinding = ActiveBindingSlot<ServiceBindingHandle<ServiceConnection>>()
    private val dataLock = Any()
    private val dataHandlers = ConcurrentHashMap<String, (RearDataSnapshot) -> Unit>()
    private val pendingCastResults = ConcurrentHashMap<String, (Result<RearCastSessionHandle>) -> Unit>()
    private val pendingCastProgress = ConcurrentHashMap<String, (RearCastProgress) -> Unit>()
    private val pendingCastGates = ConcurrentHashMap<String, CastRequestEpochTerminalGate>()
    private val closedDataIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingData = LinkedHashMap<String, RearDataSnapshot>()
    val isConnected: Boolean get() = currentEpoch.get() != null

    private inner class ClientEpoch(
        val id: Long,
        val bindAttemptId: Long,
        val binder: IBinder,
        val service: IRemoteRearPlatformService
    ) {
        val callback: IRemoteRearPlatformCallback = object : IRemoteRearPlatformCallback.Stub() {
            override fun onEvent(eventJson: String) = handleEvent(this@ClientEpoch, eventJson)
        }
    }

    private fun newBindingHandle(attemptId: Long): ServiceBindingHandle<ServiceConnection> =
        ServiceBindingHandle<ServiceConnection>(attemptId).also { binding ->
            binding.connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) = handleServiceConnected(binding, service)
                override fun onServiceDisconnected(name: ComponentName) = handleServiceDisconnected(binding, name)
                override fun onBindingDied(name: ComponentName) = invalidateBinding(binding, RearSdkErrorCode.BINDING_DIED)
                override fun onNullBinding(name: ComponentName) = invalidateBinding(binding, RearSdkErrorCode.SERVICE_UNAVAILABLE)
            }
        }

    private fun handleEvent(epoch: ClientEpoch, eventJson: String) {
        if (closed.get() || currentEpoch.get() !== epoch) return
        val event = runCatching { RearPlatformEvent.fromJson(eventJson) }.getOrElse {
            RearPlatformEvent("error", data = JSONObject().put("code", "E_INVALID_STATE").put("message", it.message))
        }
        eventDispatcher.dispatch(epoch) {
            deliverCastResult(epoch, event)
            if (event.type == "data") {
                val id = event.data.optString("subscriptionId")
                val snapshot = runCatching { RearDataSnapshot.fromJson(event.data.toString()) }.getOrNull()
                if (id.isNotEmpty() && snapshot != null && id !in closedDataIds) {
                    val handler = dataHandlers[id]
                    if (handler != null) runCatching { handler(snapshot) }
                    else synchronized(dataLock) {
                        if (!closed.get() && currentEpoch.get() === epoch && id !in closedDataIds) pendingData[id] = snapshot
                    }
                }
            }
            runCatching { onEvent(event) }
        }
    }

    private fun deliverCastResult(epoch: ClientEpoch, event: RearPlatformEvent) {
        if (event.type !in setOf("castStage", "castReady", "castFailed")) return
        val requestId = event.data.optString("requestId").takeIf(String::isNotEmpty) ?: return
        val gate = pendingCastGates[requestId] ?: return
        if (event.type == "castStage") {
            val stage = runCatching { RearCastProgressStage.valueOf(event.data.optString("stage")) }.getOrNull() ?: return
            gate.dispatch(epoch) {
                pendingCastProgress[requestId]?.invoke(RearCastProgress(stage, event.data.optString("phase").takeIf(String::isNotEmpty),
                    event.data.optString("code").takeIf(String::isNotEmpty)))
            }
            return
        }
        gate.terminate(epoch) {
            val progress = pendingCastProgress.remove(requestId)
            pendingCastGates.remove(requestId, gate)
            val completion = pendingCastResults.remove(requestId) ?: return@terminate
            when (event.type) {
                "castReady" -> {
                val sessionId = event.data.optString("sessionId")
                val generation = event.data.optLong("generation")
                if (sessionId.isBlank() || generation <= 0L) {
                    progress?.invoke(RearCastProgress(RearCastProgressStage.FAILED, "BACKEND", "E_INVALID_STATE"))
                    completion(Result.failure(IllegalStateException("E_INVALID_STATE")))
                } else {
                    progress?.invoke(RearCastProgress(RearCastProgressStage.READY))
                    completion(Result.success(RearCastSessionHandle(sessionId, generation)))
                }
                }
                "castFailed" -> {
                val code = event.data.optString("code", "E_CAST_FAILED")
                val phase = event.data.optString("phase", when (code) {
                    "E_USER_CANCELLED", "E_TICKET_STALE" -> "CONFIRMATION"
                    else -> "BACKEND"
                })
                progress?.invoke(RearCastProgress(RearCastProgressStage.FAILED, phase, code))
                completion(Result.failure(IllegalStateException(code)))
                }
            }
        }
    }

    private fun handleServiceConnected(binding: ServiceBindingHandle<ServiceConnection>, binder: IBinder) {
        val attemptId = binding.attemptId
        val candidate: IRemoteRearPlatformService = IRemoteRearPlatformService.Stub.asInterface(binder)
        var replacedEpoch: ClientEpoch? = null
        val acceptedBinding = connectionGate.serviceConnected(attemptId, binder) {
                currentEpoch.get()?.takeIf { it.bindAttemptId == attemptId }?.let { previous ->
                    replacedEpoch = previous
                    currentEpoch.compareAndSet(previous, null)
                    contractVersion = null
                    connectionStateVersion.incrementAndGet()
                }
            }
        if (!acceptedBinding) return
        replacedEpoch?.let(::releaseEpochAsync)
        replacedEpoch?.let { failPendingCastRequests(it, RearSdkErrorCode.SERVICE_DISCONNECTED) }
        try {
            worker.execute {
                val result: Result<String> = runCatching { candidate.getContractVersion() }
                val contract: String? = result.getOrNull()
                var publishedEpoch: ClientEpoch? = null
                val transition: ConnectionHandshakeGate.Transition = connectionGate.handshakeCompleted(
                    attemptId, binder, contract,
                    publish = {
                        val epoch = ClientEpoch(epochIds.incrementAndGet(), attemptId, binder, candidate)
                        publishedEpoch = epoch
                        currentEpoch.set(epoch)
                        contractVersion = contract
                        connectionStateVersion.incrementAndGet()
                    },
                    clear = { currentEpoch.set(null); contractVersion = null }
                ) ?: return@execute
                if (RearContractNegotiation.failureCode(contract) != null && transition.wasBound) {
                    unbindService(binding)
                }
                val expectedEpoch = publishedEpoch
                transition.completion?.let { completion ->
                    completeConnectionWaiters(completion) {
                        connectionWaiterCompletionIsCurrent(completion.result, expectedEpoch, currentEpoch.get())
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            unbindService(binding)
        }
    }

    private fun handleServiceDisconnected(binding: ServiceBindingHandle<ServiceConnection>, name: ComponentName) {
        val attemptId = binding.attemptId
        var retiredEpoch: ClientEpoch? = null
        val transition = connectionGate.disconnected(attemptId) {
            currentEpoch.get()?.takeIf { it.bindAttemptId == attemptId }?.let { current ->
                retiredEpoch = current
                currentEpoch.compareAndSet(current, null)
            }
            contractVersion = null
        }
        retiredEpoch?.let(::releaseEpochAsync)
        retiredEpoch?.let { failPendingCastRequests(it, RearSdkErrorCode.SERVICE_DISCONNECTED) }
        transition?.completion?.let(::completeConnectionWaiters)
        if (transition != null) {
            val stateVersion = connectionStateVersion.incrementAndGet()
            eventExecutor.execute {
                if (!closed.get() && connectionStateVersion.get() == stateVersion && currentEpoch.get() == null)
                    runCatching { onEvent(RearPlatformEvent("backendDisconnected")) }
            }
        }
    }

    /** A dead/null binding will not reconnect by itself; release it so a later connect() can retry. */
    private fun invalidateBinding(binding: ServiceBindingHandle<ServiceConnection>, code: String) {
        val attemptId = binding.attemptId
        var retiredEpoch: ClientEpoch? = null
        val transition = connectionGate.bindingFailed(attemptId, code) {
            retiredEpoch = currentEpoch.getAndSet(null)
            contractVersion = null
        } ?: return
        retiredEpoch?.let(::releaseEpochAsync)
        retiredEpoch?.let { failPendingCastRequests(it, code) }
        if (transition.wasBound) unbindService(binding)
        activeBinding.clearIfCurrent(binding)
        transition.completion?.let(::completeConnectionWaiters)
        val stateVersion = connectionStateVersion.incrementAndGet()
        eventExecutor.execute {
            if (!closed.get() && connectionStateVersion.get() == stateVersion && currentEpoch.get() == null)
                runCatching { onEvent(RearPlatformEvent("backendDisconnected", data = JSONObject().put("code", code))) }
        }
    }

    private fun unbindService(binding: ServiceBindingHandle<ServiceConnection>) {
        binding.unbound.request {
            Handler(Looper.getMainLooper()).post {
                try { appContext.unbindService(binding.connection) } catch (_: RuntimeException) { /* Already unbound or service process died. */ }
                activeBinding.clearIfCurrent(binding)
            }
        }
    }

    /** Uses the retired epoch's exact service/callback pair and never blocks the caller. */
    private fun releaseEpochAsync(epoch: ClientEpoch) {
        Thread({ runCatching { epoch.service.releaseClient(callerPackage, epoch.callback) } },
            "rear-sdk-release-${epoch.id}").apply { isDaemon = true }.start()
    }

    private fun failPendingCastRequests(epoch: ClientEpoch, code: String) {
        pendingCastGates.entries.toList().forEach { (id, gate) ->
            if (gate.isOpenFor(epoch)) failCastRequest(id, epoch, "CONNECTION", code)
        }
    }

    private fun failCastRequest(requestId: String, epoch: ClientEpoch, phase: String, code: String, cause: Throwable? = null) {
        val gate = pendingCastGates[requestId] ?: return
        gate.terminate(epoch) {
            pendingCastGates.remove(requestId, gate)
            pendingCastProgress.remove(requestId)?.invoke(RearCastProgress(RearCastProgressStage.FAILED, phase, code))
            pendingCastResults.remove(requestId)?.let {
                dispatchResult(it, Result.failure(IllegalStateException(code, cause)))
            }
        }
    }

    private fun completeConnectionWaiters(completion: ConnectionHandshakeGate.Completion, isCurrent: () -> Boolean = { true }) {
        completion.waiters.forEach { waiter -> resultDispatcher.dispatch(waiter, completion.result, isCurrent) }
    }

    fun connect(callback: (Result<Unit>) -> Unit) {
        when (val decision = connectionGate.connect({ currentEpoch.get() }, callback)) {
            is ConnectionHandshakeGate.ConnectDecision.AlreadyConnected ->
                dispatchResult(callback, Result.success(Unit)) { currentEpoch.get() === decision.identity }
            ConnectionHandshakeGate.ConnectDecision.AwaitingBinding -> Unit
            is ConnectionHandshakeGate.ConnectDecision.Rejected -> {
                val code = decision.code
                dispatchResult(callback, Result.failure(IllegalStateException(code)))
            }
            is ConnectionHandshakeGate.ConnectDecision.StartBinding -> {
                val attemptId = decision.attemptId
                val binding = newBindingHandle(attemptId)
                activeBinding.publish(binding)
                if (!connectionGate.shouldStartBinding(attemptId)) {
                    activeBinding.clearIfCurrent(binding)
                    return
                }
                val intent = Intent().setComponent(ComponentName(hostPackageName, HOST_SERVICE))
                val didBind = attemptBinding({ appContext.bindService(intent, binding.connection, Context.BIND_AUTO_CREATE) }) {
                    // Context may register a dispatcher before returning false or throwing; release only this attempt.
                    unbindService(binding)
                    val transition = connectionGate.bindingFailed(attemptId, RearSdkErrorCode.SERVICE_UNAVAILABLE) {
                        currentEpoch.set(null)
                        contractVersion = null
                    }
                    transition?.completion?.let(::completeConnectionWaiters)
                }
                val bindResult = connectionGate.bindingCompleted(attemptId, didBind)
                bindResult.completion?.let(::completeConnectionWaiters)
                if (bindResult.unbind) unbindService(binding)
                if (!didBind) activeBinding.clearIfCurrent(binding)
            }
        }
    }

    fun queryCapabilities(callback: (Result<RearCapabilities>) -> Unit) = onBinder(callback) { service ->
        RearCapabilities.fromJson(service.service.queryCapabilities(callerPackage))
    }

    /** One-shot callback completes only when the host verifies both output and input readiness. */
    fun openCastSession(activity: Activity, request: RearCastRequest, callback: (Result<RearCastSessionHandle>) -> Unit) =
        openCastSession(activity, request, {}, callback)

    /** Opens a host-confirmed Cast session and reports request/confirmation/backend stages separately. */
    fun openCastSession(
        activity: Activity,
        request: RearCastRequest,
        onProgress: (RearCastProgress) -> Unit,
        callback: (Result<RearCastSessionHandle>) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            onProgress(RearCastProgress(RearCastProgressStage.FAILED, "FOREGROUND", RearSdkErrorCode.FOREGROUND_REQUIRED))
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED))); return
        }
        val requestId = java.util.UUID.randomUUID().toString()
        val requestEpoch = currentEpoch.get()
        if (requestEpoch == null) {
            onProgress(RearCastProgress(RearCastProgressStage.FAILED, "PREPARATION", RearSdkErrorCode.NOT_CONNECTED))
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.NOT_CONNECTED)))
            return
        }
        val requestGate = CastRequestEpochTerminalGate(requestEpoch)
        pendingCastGates[requestId] = requestGate
        pendingCastResults[requestId] = callback
        pendingCastProgress[requestId] = onProgress
        onProgress(RearCastProgress(RearCastProgressStage.PREPARING))
        val payload = request.toJson().toString()
        onBinder<PendingIntent>({ result -> result.fold(onSuccess = { pending ->
            if (!requestGate.isOpenFor(requestEpoch)) return@fold
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                failCastRequest(requestId, requestEpoch, "FOREGROUND", RearSdkErrorCode.FOREGROUND_REQUIRED)
            } else try {
                pendingCastProgress[requestId]?.invoke(RearCastProgress(RearCastProgressStage.WAITING_CONFIRMATION))
                activity.startIntentSenderForResult(pending.intentSender, CAST_REQUEST_CODE, null, 0, 0, 0)
            } catch (error: IntentSender.SendIntentException) {
                val code = "E_CONFIRMATION_LAUNCH"
                failCastRequest(requestId, requestEpoch, "CONFIRMATION", code, error)
            }
        }, onFailure = { error ->
            val code = error.message?.substringBefore(':')?.takeIf { it.startsWith("E_") } ?: "E_PREPARE_FAILED"
            failCastRequest(requestId, requestEpoch, "PREPARATION", code, error)
        }) }) { service ->
            if (service !== requestEpoch || currentEpoch.get() !== requestEpoch) throw IllegalStateException(RearSdkErrorCode.SERVICE_DISCONNECTED)
            service.service.requestCastActivation(callerPackage, requestId, payload, service.callback)
        }
    }

    fun queryCastSession(handle: RearCastSessionHandle, callback: (Result<RearCastSessionSnapshot>) -> Unit) = onBinder(callback) { service ->
        RearCastSessionSnapshot.fromJson(service.service.queryCastSession(callerPackage, handle.sessionId, handle.generation, service.callback))
    }

    /** Close only the caller-owned opaque session; this never targets the current foreground task. */
    fun closeCastSession(handle: RearCastSessionHandle, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.closeCastSession(callerPackage, handle.sessionId, handle.generation, service.callback))
    }

    /**
     * Read the selected rear panel without requiring the privileged backend. The reported panel
     * size is physical pixels; an opened Scene's measured window is available in its Ready event.
     */
    fun queryRearInfo(callback: (Result<RearInfoSnapshot>) -> Unit) = onBinder(callback) { service ->
        RearInfoSnapshot.fromJson(service.service.queryRearInfo(callerPackage))
    }

    /** Returns a one-shot host picker PendingIntent. Launch it for result to receive the opaque ref. */
    fun requestRegisteredActionSelection(callback: (Result<PendingIntent>) -> Unit) = onBinder(callback) { service ->
        requireActionCapabilitySupported(service)
        service.service.requestRegisteredActionSelection(callerPackage, service.callback)
    }

    /** Returns a host confirmation PendingIntent; each run still requires the user's explicit tap. */
    fun requestRegisteredActionInvocation(ref: RearRegisteredActionRef, callback: (Result<PendingIntent>) -> Unit) = onBinder(callback) { service ->
        requireActionCapabilitySupported(service)
        val capability = registeredActionCapabilityState(service.service.queryCapabilities(callerPackage))
        if (!capability.granted) throw SecurityException("E_CAPABILITY_DENIED: no registered action has been authorized")
        service.service.requestRegisteredActionInvocation(callerPackage, ref.id, ref.version, service.callback)
    }

    private fun requireActionCapabilitySupported(service: ClientEpoch) {
        val capability = registeredActionCapabilityState(service.service.queryCapabilities(callerPackage))
        if (!capability.supported)
            throw UnsupportedOperationException("E_UNSUPPORTED: host does not implement registered actions")
    }

    /** Start this only from a focused Activity; the host asks the user to choose data categories. */
    fun requestDataAuthorization(activity: Activity, fields: Set<RearDataField>, callback: (Result<Unit>) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED))); return
        }
        require(fields.isNotEmpty()) { "At least one data field is required" }
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        onBinder<PendingIntent>({ result -> result.fold(onSuccess = { pending ->
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                callback(Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
            } else try {
                activity.startIntentSenderForResult(pending.intentSender, DATA_CONSENT_REQUEST_CODE, null, 0, 0, 0)
                callback(Result.success(Unit))
            } catch (error: IntentSender.SendIntentException) { callback(Result.failure(error)) }
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            service.service.requestDataAuthorization(callerPackage, payload, service.callback)
        }
    }

    /** Opens the host's independent per-publisher grant screen from a focused Activity. */
    fun requestNotificationAuthorization(activity: Activity, callback: (Result<Unit>) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED))); return
        }
        onBinder<PendingIntent>({ result -> result.fold(onSuccess = { pending ->
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                callback(Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
            } else try {
                activity.startIntentSenderForResult(pending.intentSender, NOTIFICATION_CONSENT_REQUEST_CODE, null, 0, 0, 0)
                callback(Result.success(Unit))
            } catch (error: IntentSender.SendIntentException) { callback(Result.failure(error)) }
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            service.service.requestNotificationAuthorization(callerPackage, service.callback)
        }
    }

    /** Publish a new notice or replace this publisher's notice with the same id. */
    fun publishNotification(request: RearNotificationRequest, callback: (Result<RearNotificationSubmitResult>) -> Unit) =
        onBinder(callback) { service ->
            RearNotificationSubmitResult.fromJson(service.service.publishNotification(callerPackage, request.toJson(), service.callback))
        }

    /** Cancel only this SDK connection's notice with the given id. */
    fun cancelNotification(id: String, callback: (Result<RearNotificationSubmitResult>) -> Unit) = onBinder(callback) { service ->
        RearNotificationSubmitResult.fromJson(service.service.cancelNotification(callerPackage, id, service.callback))
    }

    fun queryData(fields: Set<RearDataField>, callback: (Result<RearDataSnapshot>) -> Unit) = onBinder(callback) { service ->
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        RearDataSnapshot.fromJson(service.service.queryData(callerPackage, payload, service.callback))
    }

    /** Sampling is host-owned; callbacks arrive through this client's configured event executor. */
    fun observeData(fields: Set<RearDataField>, intervalMs: Long = 5_000L,
                    onSnapshot: (RearDataSnapshot) -> Unit,
                    callback: (Result<Closeable>) -> Unit) {
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        onBinder({ result -> result.fold(onSuccess = { id ->
            closedDataIds.remove(id)
            val lease = RearDataSubscription(this, id)
            dataHandlers[id] = onSnapshot
            val queued = synchronized(dataLock) { pendingData.remove(id) }
            queued?.let { runCatching { onSnapshot(it) } }
            callback(Result.success(lease))
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            val root = JSONObject(service.service.observeData(callerPackage, payload, intervalMs, service.callback))
            if (!root.optBoolean("success")) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            root.getString("subscriptionId")
        }
    }

    fun closeDataObservation(subscriptionId: String, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.closeDataObservation(callerPackage, subscriptionId, service.callback))
    }

    internal fun closeDataLease(subscriptionId: String) {
        closedDataIds.add(subscriptionId)
        dataHandlers.remove(subscriptionId)
        synchronized(dataLock) { pendingData.remove(subscriptionId) }
        closeDataObservation(subscriptionId)
    }

    /** Call from a focused foreground Activity after the app's own user action. The SDK never starts the host UI from a service. */
    fun requestSceneActivation(activity: Activity, request: RearSceneRequest, callback: (Result<Unit>) -> Unit = {}) =
        requestSceneActivationJson(activity, request.toJson().toString(), callback)

    /** New explicit quarter-turn overload; unlike the old method, orientation never follows display.rotation. */
    fun requestSceneActivation(
        activity: Activity,
        request: RearSceneRequest,
        contentRotation: Int,
        callback: (Result<Unit>) -> Unit = {}
    ) {
        require(contentRotation in 0..3) { "contentRotation must be a quarter-turn in 0..3" }
        requestSceneActivationJson(activity, request.toJson(contentRotation).toString(), callback)
    }

    private fun requestSceneActivationJson(activity: Activity, requestJson: String, callback: (Result<Unit>) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
            return
        }
        onBinder<PendingIntent>({ result ->
            result.fold(
                onSuccess = { pendingIntent ->
                    if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                        callback(Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
                    } else {
                        try {
                            activity.startIntentSenderForResult(pendingIntent.intentSender, ACTIVATION_REQUEST_CODE, null, 0, 0, 0)
                            callback(Result.success(Unit))
                        } catch (error: IntentSender.SendIntentException) {
                            callback(Result.failure(error))
                        }
                    }
                },
                onFailure = { callback(Result.failure(it)) }
            )
        }) { service -> service.service.requestSceneActivation(callerPackage, requestJson, service.callback) }
    }

    /** Call from the target Activity after its first real layout, not before setContent/onCreate. */
    fun reportWindowReady(activity: Activity, launchTicket: String, revision: Long = 1L, callback: (Result<RearOperationResult>) -> Unit) {
        val report = runCatching { RearViewportReporter.capture(activity, revision) }.getOrElse {
            dispatchResult(callback, Result.failure(it)); return
        }
        onBinder({ result ->
            val operation = result.getOrNull()
            // A timed-out/failed privileged launch can arrive after its reservation was discarded.
            // Only this ticket-bound Activity may remove its own task; never guess by component/package.
            if (operation?.error == "E_STALE_SESSION" && !activity.isFinishing && !activity.isDestroyed) {
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) activity.finishAndRemoveTask() }
            }
            callback(result)
        }) { service -> RearOperationResult.fromJson(
            service.service.reportSceneWindowReady(callerPackage, launchTicket, report.toJson().toString(), service.callback)
        ) }
    }

    /** Explicit-orientation Scene report. Call only after the matching QuarterTurnSceneContainer is laid out. */
    fun reportWindowReady(
        activity: Activity,
        launchTicket: String,
        region: RearRegion,
        container: QuarterTurnSceneContainer,
        revision: Long = 1L,
        callback: (Result<RearOperationResult>) -> Unit
    ) {
        val report = runCatching {
            RearViewportReporter.capture(activity, revision, region.toSceneRegion(), container)
        }.getOrElse { dispatchResult(callback, Result.failure(it)); return }
        submitWindowReady(activity, launchTicket, report, callback)
    }

    private fun submitWindowReady(activity: Activity, launchTicket: String, report: RearWindowReport, callback: (Result<RearOperationResult>) -> Unit) {
        onBinder({ result ->
            val operation = result.getOrNull()
            if (operation?.error == "E_STALE_SESSION" && !activity.isFinishing && !activity.isDestroyed)
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) activity.finishAndRemoveTask() }
            callback(result)
        }) { service -> RearOperationResult.fromJson(
            service.service.reportSceneWindowReady(callerPackage, launchTicket, report.toJson().toString(), service.callback)
        ) }
    }

    fun reportViewport(activity: Activity, sessionId: String, generation: Long, revision: Long, callback: (Result<RearOperationResult>) -> Unit) {
        val report = runCatching { RearViewportReporter.capture(activity, revision) }.getOrElse {
            dispatchResult(callback, Result.failure(it)); return
        }
        reportViewport(sessionId, generation, report, callback)
    }

    /** Explicit-orientation viewport report; no legacy display-rotation inference is used. */
    fun reportViewport(
        activity: Activity,
        sessionId: String,
        generation: Long,
        region: RearRegion,
        container: QuarterTurnSceneContainer,
        revision: Long,
        callback: (Result<RearOperationResult>) -> Unit
    ) {
        val report = runCatching { RearViewportReporter.capture(activity, revision, region.toSceneRegion(), container) }
            .getOrElse { dispatchResult(callback, Result.failure(it)); return }
        reportViewport(sessionId, generation, report, callback)
    }

    /** Submit an already captured window report so its revision and measured geometry stay one observation. */
    fun reportViewport(sessionId: String, generation: Long, report: RearWindowReport, callback: (Result<RearOperationResult>) -> Unit) {
        require(report.revision > 0) { "revision must be positive" }
        onBinder(callback) { service -> RearOperationResult.fromJson(
            service.service.reportSceneViewport(callerPackage, sessionId, generation, report.toJson().toString(), service.callback)
        ) }
    }

    fun attachObserver(sessionId: String, generation: Long, callback: (Result<RearOperationResult>) -> Unit) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.attachSceneObserver(callerPackage, sessionId, generation, service.callback))
    }

    fun reportVisibility(sessionId: String, generation: Long, visible: Boolean, callback: (Result<RearOperationResult>) -> Unit) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.reportSceneVisibility(callerPackage, sessionId, generation, visible, service.callback))
    }

    /** Reconcile delayed observer callbacks against the Core's current owner/session snapshot. */
    fun queryScenePresentation(
        sessionId: String,
        generation: Long,
        callback: (Result<RearScenePresentation>) -> Unit
    ) = onBinder(callback) { service ->
        RearScenePresentation.fromJson(service.service.queryScenePresentation(callerPackage, sessionId, generation, service.callback))
    }

    fun closeScene(sessionId: String, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.closeScene(callerPackage, sessionId, service.callback))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var epochAtClose: ClientEpoch? = null
        val transition = connectionGate.close {
            epochAtClose = currentEpoch.getAndSet(null)
            contractVersion = null
        }
        val epoch = epochAtClose
        transition.completion?.let(::completeConnectionWaiters)
        dataHandlers.clear()
        closedDataIds.clear()
        synchronized(dataLock) { pendingData.clear() }
        pendingCastResults.keys.toList().forEach { id ->
            val gate = pendingCastGates[id] ?: return@forEach
            val epochForRequest = epoch ?: return@forEach
            if (gate.isOpenFor(epochForRequest)) failCastRequest(id, epochForRequest, "SDK", RearSdkErrorCode.CLIENT_CLOSED)
        }
        val binding = transition.attemptId?.let { id -> activeBinding.current()?.takeIf { it.attemptId == id } }
        ClientCloseSequence(
            operations = operationQueue,
            unbind = { if (transition.wasBound && binding != null) unbindService(binding) },
            scheduleHostRelease = { epoch?.let(::releaseEpochAsync) },
            shutdownWorker = { worker.shutdown() }
        ).close()
    }

    private fun <T> onBinder(callback: (Result<T>) -> Unit, action: (ClientEpoch) -> T) {
        if (closed.get()) { dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED))); return }
        val epoch = currentEpoch.get()
        if (epoch == null) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.NOT_CONNECTED)))
            return
        }
        operationQueue.submit(epoch, { action(epoch) }, callback)
    }

    private fun <T> dispatchResult(callback: (Result<T>) -> Unit, result: Result<T>, isCurrent: () -> Boolean = { true }) {
        resultDispatcher.dispatch(callback, result, isCurrent)
    }

    companion object {
        const val HOST_PACKAGE = "top.zainzh.rearscreen"
        const val HOST_SERVICE = "top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService"
        const val EXTRA_LAUNCH_TICKET = "remrss_scene_ticket"
        const val ACTIVATION_REQUEST_CODE = 0x524D
        const val DATA_CONSENT_REQUEST_CODE = 0x524E
        const val NOTIFICATION_CONSENT_REQUEST_CODE = 0x524F
        const val CAST_REQUEST_CODE = 0x5250

        /** For local host-debug variants; production callers should use the default constructor. */
        @JvmStatic fun forHostPackage(
            context: Context,
            hostPackageName: String,
            eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
            onEvent: (RearPlatformEvent) -> Unit
        ) = RearClient(context, hostPackageName, eventExecutor, onEvent)
    }
}

enum class RearNotificationPriority { LOW, NORMAL, HIGH }
enum class RearNotificationPrivacy { STANDARD, HIDE_TITLE, HIDE_TEXT }
enum class RearNotificationSubmitStatus { ACCEPTED, SUPPRESSED, REJECTED }
enum class RearNotificationEventStatus { DISPLAYED, SUPPRESSED, ENDED, FAILED }
enum class RearNotificationEndReason {
    TIMEOUT, REPLACED, CANCELLED, OWNER_CLOSED, OWNER_DIED, AUTH_REVOKED, GLOBAL_DISABLED,
    PRIVACY_POLICY, DND, COVERED, DISPLAY_UNAVAILABLE, SCENE_TAKEOVER, SERVICE_STOPPED, RENDER_FAILED, HOST_ERROR
}

data class RearNotificationRequest(
    val id: String,
    val title: String,
    val text: String = "",
    val durationMs: Long = 8_000L,
    val priority: RearNotificationPriority = RearNotificationPriority.NORMAL,
    val privacy: RearNotificationPrivacy = RearNotificationPrivacy.STANDARD,
) {
    internal fun toJson(): String = JSONObject().put("id", id).put("title", title).put("text", text)
        .put("durationMs", durationMs).put("priority", priority.name).put("privacy", privacy.name).toString()
}

data class RearNotificationSubmitResult(
    val status: RearNotificationSubmitStatus,
    val id: String,
    val requestId: String?,
    val reason: String?,
) {
    companion object {
        fun fromJson(json: String): RearNotificationSubmitResult {
            val value = JSONObject(json)
            return RearNotificationSubmitResult(
                RearNotificationSubmitStatus.valueOf(value.getString("status")), value.getString("id"),
                value.optString("requestId").takeUnless { it.isBlank() || it == "null" },
                value.optString("reason").takeUnless { it.isBlank() || it == "null" }
            )
        }
    }
}

data class RearNotificationEvent(
    val requestId: String,
    val id: String,
    val status: RearNotificationEventStatus,
    val reason: String?,
    val endedBy: RearNotificationEndReason?,
) {
    companion object {
        internal fun fromJson(value: JSONObject): RearNotificationEvent = RearNotificationEvent(
            value.getString("requestId"), value.getString("id"), RearNotificationEventStatus.valueOf(value.getString("status")),
            value.optString("reason").takeUnless { it.isBlank() || it == "null" },
            value.optString("endedBy").takeUnless { it.isBlank() || it == "null" }?.let(RearNotificationEndReason::valueOf)
        )
    }
}

/** Opaque identity returned by the host picker. It contains no Intent or executable target data. */
data class RearRegisteredActionRef(val id: String, val version: Int = 1) {
    companion object {
        /** Read only a successful RegisteredActionsActivity result. */
        @JvmStatic fun fromActivityResult(data: Intent?): RearRegisteredActionRef? {
            val id = data?.getStringExtra("registered_actions_selected_ref_id") ?: return null
            val version = data.getIntExtra("registered_actions_selected_ref_version", 0)
            return RearRegisteredActionRef(id, version).takeIf { id.matches(Regex("[a-f0-9]{48}") ) && version == 1 }
        }
    }
}

class RearDataSubscription internal constructor(private val client: RearClient, private val id: String) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) client.closeDataLease(id) }
}

data class RearSceneRequest(
    val component: ComponentName,
    val region: RearRegion = RearRegion.CAMERA_SAFE,
    val keepVisible: Boolean = true,
    val requiredCapabilities: Set<CapabilityRequirement> = emptySet()
) {
    internal fun toJson(contentRotation: Int? = null) = JSONObject().put("component", component.flattenToString()).put("region", region.name)
        .put("keepVisible", keepVisible).put("requiredCapabilities", JSONArray().apply {
            (requiredCapabilities + if (contentRotation != null) setOf(CapabilityRequirement("rear.scene.orientation", 1)) else emptySet())
                .forEach { put(JSONObject().put("name", it.name).put("minimumVersion", it.minimumVersion)) }
        }).apply { if (contentRotation != null) {
            require(contentRotation in 0..3) { "contentRotation must be a quarter-turn in 0..3" }
            put("contentRotation", contentRotation)
        } }
}

/** Placement supplied only by the host for the exact approved Scene launch ticket. */
data class RearSceneLaunchPlacement(val region: RearRegion, val contentRotation: Int?)

object RearSceneLaunchMetadata {
    const val EXTRA_REGION = "remrss_scene_region"
    const val EXTRA_EXPLICIT_ROTATION = "remrss_scene_explicit_rotation"
    const val EXTRA_CONTENT_ROTATION = "remrss_scene_content_rotation"

    /** Legacy launches have no placement extras and retain the old SAFE/default viewport semantics. */
    @JvmStatic fun read(intent: Intent): RearSceneLaunchPlacement? {
        if (!intent.hasExtra(EXTRA_REGION) && !intent.hasExtra(EXTRA_EXPLICIT_ROTATION) && !intent.hasExtra(EXTRA_CONTENT_ROTATION)) return null
        val region = when (intent.getStringExtra(EXTRA_REGION)) {
            "CAMERA_SAFE" -> RearRegion.CAMERA_SAFE
            "FULL" -> RearRegion.FULL
            else -> throw IllegalArgumentException("E_INVALID_STATE: Scene launch region")
        }
        val explicit = intent.getBooleanExtra(EXTRA_EXPLICIT_ROTATION, false)
        val turn = intent.getIntExtra(EXTRA_CONTENT_ROTATION, 0)
        require(turn in 0..3 && (explicit || turn == 0)) { "E_INVALID_STATE: Scene launch content rotation" }
        return RearSceneLaunchPlacement(region, if (explicit) turn else null)
    }
}

enum class RearRegion { CAMERA_SAFE, FULL }

enum class RearCastMode { VIRTUAL, DIRECT_PHYSICAL }
enum class RearCastPresentationKind { MANAGED_VIEWPORT, SYSTEM_MANAGED }

enum class RearCastProgressStage { PREPARING, WAITING_CONFIRMATION, CONFIRMED, COMMIT_ACCEPTED, READY, FAILED }

/** Public Cast request progress; phase/code contain only stable stage diagnostics, never target identity. */
data class RearCastProgress(
    val stage: RearCastProgressStage,
    val phase: String? = null,
    val errorCode: String? = null
)

/** The Cast request addresses only the task containing its initiating foreground Activity. */
data class RearCastRequest(
    val mode: RearCastMode,
    /** Null preserves the target app's saved region. */
    val region: RearRegion? = null,
    /** Null preserves the app's saved presentation orientation; 0..3 explicitly sets quarter turns. */
    val contentRotation: Int? = null,
    val requestedDensityDpi: Int? = null
) {
    init {
        require(contentRotation == null || contentRotation in 0..3) { "contentRotation must be a quarter-turn in 0..3" }
        require(requestedDensityDpi == null || requestedDensityDpi in 72..1000) { "requestedDensityDpi must be 72..1000" }
        require(mode != RearCastMode.DIRECT_PHYSICAL || (region == null && contentRotation == null && requestedDensityDpi == null)) {
            "Direct physical projection does not control region, content rotation or density"
        }
    }

    internal fun toJson() = JSONObject()
        .put("selector", "CURRENT_FOREGROUND").put("mode", mode.name)
        .apply {
            region?.let { put("region", if (it == RearRegion.FULL) "FULL" else "SAFE") }
            contentRotation?.let { put("contentRotation", it) }
            requestedDensityDpi?.let { put("requestedDensityDpi", it) }
        }
}

/** Opaque owner-scoped handle created only after the host verifies real output and input readiness. */
data class RearCastSessionHandle(val sessionId: String, val generation: Long) {
    init { require(sessionId.isNotBlank() && generation > 0L) }
}

data class RearCastSessionSnapshot(
    val handle: RearCastSessionHandle,
    val state: String,
    val mode: RearCastMode,
    val region: RearRegion,
    val contentRotation: Int,
    val presentationKind: RearCastPresentationKind,
    val failureCode: String? = null,
    val appliedViewport: RearCastAppliedViewport? = null
) {
    companion object {
        fun fromJson(json: String): RearCastSessionSnapshot {
            val root = JSONObject(json)
            if (!root.optBoolean("success", false)) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            return RearCastSessionSnapshot(
                RearCastSessionHandle(root.getString("sessionId"), root.getLong("generation")),
                root.getString("state"), RearCastMode.valueOf(root.getString("mode")),
                if (root.getString("region") == "FULL") RearRegion.FULL else RearRegion.CAMERA_SAFE,
                root.getInt("contentRotation"), RearCastPresentationKind.valueOf(root.getString("presentationKind")),
                root.optString("failureCode").takeUnless(String::isEmpty),
                root.optJSONObject("viewport")?.let(RearCastAppliedViewport::fromJson)
            )
        }
    }
}

/** Coordinates are actual applied viewport pixels; the input matrix retains rotation cross-terms. */
data class RearCastAppliedViewport(
    val revision: Long,
    val rearDisplayId: Int,
    val region: RearRegion,
    val densityDpi: Int,
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val displayRotation: Int,
    val contentRotation: Int,
    val windowLeftPx: Int,
    val windowTopPx: Int,
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val inputScaleX: Float,
    val inputSkewX: Float,
    val inputTranslateX: Float,
    val inputSkewY: Float,
    val inputScaleY: Float,
    val inputTranslateY: Float
) {
    companion object {
        fun fromJson(json: JSONObject) = RearCastAppliedViewport(
            json.getLong("revision"), json.getInt("rearDisplayId"),
            if (json.getString("region") == "FULL") RearRegion.FULL else RearRegion.CAMERA_SAFE,
            json.getInt("densityDpi"), json.getInt("leftPx"), json.getInt("topPx"),
            json.getInt("widthPx"), json.getInt("heightPx"), json.getInt("bufferWidthPx"), json.getInt("bufferHeightPx"),
            json.getInt("displayRotation"), json.getInt("contentRotation"),
            json.getInt("windowLeftPx"), json.getInt("windowTopPx"),
            json.getInt("windowWidthPx"), json.getInt("windowHeightPx"), json.getDouble("inputScaleX").toFloat(),
            json.getDouble("inputSkewX").toFloat(), json.getDouble("inputTranslateX").toFloat(),
            json.getDouble("inputSkewY").toFloat(), json.getDouble("inputScaleY").toFloat(), json.getDouble("inputTranslateY").toFloat()
        )
    }
}

private fun RearRegion.toSceneRegion() = if (this == RearRegion.FULL) SceneRegion.FULL else SceneRegion.SAFE

/** Applies the window-level half of a requested Scene keep-visible lease. */
object RearSceneWindowPolicy {
    /** Call from the Scene Activity's visible lifecycle; clear it when the Activity is hidden. */
    @JvmStatic fun applyKeepVisible(activity: Activity, enabled: Boolean) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Focus/resumed state is not ownership; a live request follows Core visibility and the real window. */
    @JvmStatic fun shouldKeepScreenOn(
        requested: Boolean,
        leaseActive: Boolean,
        windowActuallyVisible: Boolean,
        sceneVisible: Boolean = true
    ): Boolean = requested && leaseActive && windowActuallyVisible && sceneVisible

    @JvmStatic fun applySceneLease(
        activity: Activity,
        requested: Boolean,
        leaseActive: Boolean,
        windowActuallyVisible: Boolean,
        sceneVisible: Boolean = true
    ) {
        applyKeepVisible(activity, shouldKeepScreenOn(requested, leaseActive, windowActuallyVisible, sceneVisible))
    }
}
data class CapabilityRequirement(val name: String, val minimumVersion: Int)
data class RearCapability(val name: String, val version: Int, val availability: String, val granted: Boolean, val reasonCode: String?)
data class RearCapabilities(val contract: String, val deviceAvailable: Boolean, val backendConnected: Boolean, val capabilities: List<RearCapability>) {
    companion object {
        fun fromJson(json: String): RearCapabilities {
            val root = JSONObject(json)
            val caps = root.getJSONArray("capabilities")
            return RearCapabilities(root.getString("contract"), root.getBoolean("deviceAvailable"), root.getBoolean("backendConnected"),
                (0 until caps.length()).map { i -> caps.getJSONObject(i).let { RearCapability(it.getString("name"), it.getInt("version"),
                    it.getString("availability"), it.getBoolean("granted"), it.optString("reasonCode").takeUnless(String::isEmpty)) } })
        }
    }
}

data class RearPanelInfo(
    val displayId: Int,
    /** Physical raster of the current Display.Mode, in that mode's native orientation; null if unavailable. */
    val physicalModeWidthPx: Int?,
    val physicalModeHeightPx: Int?,
    /** Current logical display size in px; can differ from physical mode under size overrides. */
    val logicalWidthPx: Int,
    val logicalHeightPx: Int,
    /** Current logical display density in DPI, not the final Scene window dp size. */
    val densityDpi: Int,
    /** Current Display.rotation quarter-turns (0..3), independent of the mode's native orientation. */
    val rotation: Int,
    /** ON, OFF, DOZE, DOZE_SUSPEND, VR, or UNKNOWN. */
    val state: String
)

data class RearInfoSnapshot(
    val contract: String,
    val deviceAvailable: Boolean,
    val backendConnected: Boolean,
    val panelInfoAvailable: Boolean,
    val panel: RearPanelInfo?,
    val capabilities: List<RearCapability>
) {
    companion object {
        fun fromJson(json: String): RearInfoSnapshot {
            val root = JSONObject(json)
            val panel = root.optJSONObject("display")?.let { display ->
                val dimensions = display.optJSONObject("dimensions")
                val modeSize = dimensions?.optJSONObject("physicalModePx")
                val logicalSize = dimensions?.optJSONObject("logicalDisplayPx")
                if (dimensions == null || logicalSize == null) null else RearPanelInfo(
                    display.getInt("displayId"),
                    modeSize?.getInt("width"), modeSize?.getInt("height"),
                    logicalSize.getInt("width"), logicalSize.getInt("height"),
                    dimensions.getInt("densityDpi"), dimensions.getInt("rotation"), display.getString("state")
                )
            }
            val caps = root.getJSONArray("capabilities")
            val capabilities = (0 until caps.length()).map { index -> caps.getJSONObject(index).let {
                RearCapability(it.getString("name"), it.getInt("version"), it.getString("availability"),
                    it.getBoolean("granted"), it.optString("reasonCode").takeUnless(String::isEmpty))
            } }
            val infoAvailable = root.getBoolean("displayInfoAvailable")
            require(infoAvailable == (panel != null)) { "E_INVALID_STATE: panel info" }
            return RearInfoSnapshot(root.getString("contract"), root.getBoolean("deviceAvailable"),
                root.getBoolean("backendConnected"), infoAvailable, panel, capabilities)
        }
    }
}

data class RearOperationResult(val success: Boolean, val error: String?, val retryable: Boolean) {
    companion object { fun fromJson(json: String) = JSONObject(json).let { RearOperationResult(it.optBoolean("success"), it.optString("error").takeUnless(String::isEmpty), it.optBoolean("retryable")) } }
}

enum class RearDataField { BATTERY_LEVEL_PERCENT, BATTERY_CHARGING, SYSTEM_CPU_UTILIZATION_PERCENT, SYSTEM_MEMORY_AVAILABLE_BYTES, SYSTEM_MEMORY_TOTAL_BYTES, SYSTEM_GPU_UTILIZATION_PERCENT }
sealed interface RearDataValue {
    val sampledAtElapsedRealtimeMs: Long
    data class Number(val value: Double, val unit: String, override val sampledAtElapsedRealtimeMs: Long) : RearDataValue
    data class Flag(val value: Boolean, override val sampledAtElapsedRealtimeMs: Long) : RearDataValue
    data class Unavailable(val reason: String, override val sampledAtElapsedRealtimeMs: Long) : RearDataValue
}

data class RearDataSnapshot(val fields: Map<RearDataField, RearDataValue>, val sampledAtElapsedRealtimeMs: Long) {
    companion object {
        fun fromJson(json: String): RearDataSnapshot {
            val root = JSONObject(json)
            if (!root.optBoolean("success", true)) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            val fields = root.optJSONObject("fields") ?: JSONObject()
            val values = buildMap {
                fields.keys().forEach { key ->
                    val field = RearDataField.valueOf(key)
                    val value = fields.getJSONObject(key)
                    val sampleTime = value.getLong("sampledAtElapsedRealtimeMs")
                    put(field, when (value.getString("type")) {
                        "number" -> RearDataValue.Number(value.getDouble("value"), value.getString("unit"), sampleTime)
                        "flag" -> RearDataValue.Flag(value.getBoolean("value"), sampleTime)
                        "unavailable" -> RearDataValue.Unavailable(value.getString("reason"), sampleTime)
                        else -> throw IllegalArgumentException("E_INVALID_STATE: data value")
                    })
                }
            }
            return RearDataSnapshot(values, root.getLong("sampledAtElapsedRealtimeMs"))
        }
    }
}

data class RearPlatformEvent(
    val type: String,
    val sessionId: String? = null,
    val generation: Long? = null,
    val data: JSONObject = JSONObject(),
    val notificationEvent: RearNotificationEvent? = null,
) {
    companion object {
        fun fromJson(json: String): RearPlatformEvent {
            val root = JSONObject(json)
            val type = root.getString("type")
            return RearPlatformEvent(type, root.optString("sessionId").takeUnless(String::isEmpty),
                root.optLong("generation").takeIf { root.has("generation") }, root,
                if (type == "notificationEvent") RearNotificationEvent.fromJson(root) else null)
        }
    }
}

/** Current Core-authoritative content permission for one exact logical Scene generation. */
data class RearScenePresentation(
    val sessionId: String,
    val generation: Long,
    val revision: Long,
    val visible: Boolean,
    val hiddenReason: String?
) {
    companion object {
        fun fromJson(json: String): RearScenePresentation {
            val root = JSONObject(json)
            if (!root.optBoolean("success", false)) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            return RearScenePresentation(
                root.getString("sessionId"), root.getLong("generation"), root.getLong("revision"),
                root.getBoolean("visible"), if (root.isNull("hiddenReason")) null else root.getString("hiddenReason")
            )
        }
    }
}

/** Drops delayed presentation snapshots from another logical lease or an older Core revision. */
class RearScenePresentationGate(val sessionId: String, val generation: Long) {
    private var appliedRevision = 0L
    private var currentVisible = false

    @Synchronized fun apply(snapshot: RearScenePresentation): Boolean {
        if (snapshot.sessionId != sessionId || snapshot.generation != generation || snapshot.revision < appliedRevision) return false
        // onStart may clear a window-local presentation while Core's current state has not changed.
        // Reapplying that exact same snapshot is idempotent; equal-revision conflicting state is not.
        if (snapshot.revision == appliedRevision) return snapshot.visible == currentVisible
        appliedRevision = snapshot.revision
        currentVisible = snapshot.visible
        return true
    }

    @Synchronized fun visible(): Boolean = currentVisible
    @Synchronized fun revision(): Long = appliedRevision
}

/** Invalidates Binder snapshot replies captured by an Activity window before stop/restart. */
class RearScenePresentationRequestEpoch {
    private val value = java.util.concurrent.atomic.AtomicLong(0L)
    fun begin(): Long = value.incrementAndGet()
    fun invalidate(): Long = value.incrementAndGet()
    fun isCurrent(request: Long): Boolean = value.get() == request
}

data class RearWindowReport(
    val component: ComponentName,
    val taskId: Int,
    val revision: Long,
    val displayId: Int,
    val physicalDisplayPx: Pair<Int, Int>?,
    val contentBoundsPx: IntArray,
    val windowPx: Pair<Int, Int>,
    val windowDp: Pair<Float, Float>,
    val densityDpi: Int,
    val renderBufferPx: Pair<Int, Int>?,
    val displayRotation: Int,
    val contentRotation: Int,
    val inputTransform: FloatArray,
    val region: SceneRegion = SceneRegion.SAFE,
    val cutoutKnowledge: CutoutKnowledge = CutoutKnowledge.UNKNOWN,
    val cutoutBoundsPx: List<PixelRect> = emptyList(),
) {
    internal fun toJson() = JSONObject().put("component", component.flattenToString()).put("taskId", taskId).put("viewport", JSONObject().apply {
        put("revision", revision).put("displayId", displayId)
        fun size(value: Pair<Int, Int>?) = value?.let { JSONObject().put("width", it.first).put("height", it.second) }
        put("physicalDisplayPx", size(physicalDisplayPx))
        put("contentBoundsPx", JSONObject().put("left", contentBoundsPx[0]).put("top", contentBoundsPx[1])
            .put("right", contentBoundsPx[2]).put("bottom", contentBoundsPx[3]))
        put("windowPx", size(windowPx)).put("windowDp", JSONObject().put("width", windowDp.first).put("height", windowDp.second))
        put("densityDpi", densityDpi).put("renderBufferPx", size(renderBufferPx))
        put("displayRotation", displayRotation).put("contentRotation", contentRotation)
        put("region", region.name).put("cutoutKnowledge", cutoutKnowledge.name)
        put("cutoutBoundsPx", JSONArray().apply { cutoutBoundsPx.forEach { put(JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom)) } })
        put("inputTransform", JSONObject().put("scaleX", inputTransform[0]).put("skewX", inputTransform[1]).put("translateX", inputTransform[2])
            .put("skewY", inputTransform[3]).put("scaleY", inputTransform[4]).put("translateY", inputTransform[5]))
    })
}

object RearViewportReporter {
    /** Uses the actual target window and display at first layout; no guessed display ID or copied physical DPI. */
    fun capture(activity: Activity, revision: Long): RearWindowReport = captureLegacy(activity, revision)

    /** Report only after the caller's native scene container has completed the matching measured layout. */
    fun capture(activity: Activity, revision: Long, region: SceneRegion, container: QuarterTurnSceneContainer): RearWindowReport {
        require(container.hasAppliedContentLayout) { "E_SCENE_CONTENT_NOT_APPLIED" }
        val report = captureMeasured(activity, revision, region, container.contentRotation, preserveLegacyTransform = false)
        val location = IntArray(2).also(container::getLocationOnScreen)
        require(location[0] == report.contentBoundsPx[0] && location[1] == report.contentBoundsPx[1] &&
            container.width == report.contentBoundsPx[2] - report.contentBoundsPx[0] &&
            container.height == report.contentBoundsPx[3] - report.contentBoundsPx[1]) { "E_SCENE_CONTENT_VIEWPORT_MISMATCH" }
        return report
    }

    private fun captureLegacy(activity: Activity, revision: Long): RearWindowReport {
        @Suppress("DEPRECATION") val display = if (android.os.Build.VERSION.SDK_INT >= 30) activity.display ?: activity.windowManager.defaultDisplay
        else activity.windowManager.defaultDisplay
        return captureMeasured(activity, revision, SceneRegion.SAFE, display.rotation, preserveLegacyTransform = true)
    }

    private fun captureMeasured(activity: Activity, revision: Long, region: SceneRegion, contentRotation: Int, preserveLegacyTransform: Boolean): RearWindowReport {
        require(revision > 0) { "revision must be positive" }
        require(contentRotation in 0..3) { "contentRotation must be a quarter-turn in 0..3" }
        val decor = activity.window.decorView
        val width = decor.width
        val height = decor.height
        require(width > 0 && height > 0) { "E_WINDOW_NOT_READY" }
        @Suppress("DEPRECATION") val display = if (android.os.Build.VERSION.SDK_INT >= 30) activity.display ?: activity.windowManager.defaultDisplay
        else activity.windowManager.defaultDisplay
        val metrics = activity.resources.displayMetrics
        val densityDpi = metrics.densityDpi
        val windowXy = IntArray(2).also(decor::getLocationOnScreen)
        val rootInsets = decor.rootWindowInsets
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) rootInsets?.displayCutout else null
        val cutoutInsets = if (android.os.Build.VERSION.SDK_INT < 28 || rootInsets == null) null else PixelInsets(
            cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0,
            cutout?.safeInsetTop?.coerceIn(0, height) ?: 0,
            cutout?.safeInsetRight?.coerceIn(0, width) ?: 0,
            cutout?.safeInsetBottom?.coerceIn(0, height) ?: 0,
        )
        if (cutoutInsets == null) android.util.Log.w("RearViewportReporter", "ROM did not report a verifiable display-cutout safe area")
        val modeSize = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 23) display.mode.let { it.physicalWidth to it.physicalHeight }
            else null
        }.getOrNull()
        val displaySize = Point().also(display::getRealSize)
        val cutoutBounds = cutout?.boundingRects.orEmpty().map { PixelRect(it.left, it.top, it.right, it.bottom) }
            .map { PixelRect(it.left - windowXy[0], it.top - windowXy[1], it.right - windowXy[0], it.bottom - windowXy[1]) }
        val geometry = SceneViewportGeometry.fromWindow(
            displayBounds = PixelRect(0, 0, displaySize.x, displaySize.y),
            windowBounds = PixelRect(windowXy[0], windowXy[1], windowXy[0] + width, windowXy[1] + height),
            cutoutInsets = cutoutInsets,
            cutoutBoundsInWindow = cutoutBounds,
            cutoutKnowledge = when { cutoutInsets == null -> CutoutKnowledge.UNKNOWN; cutout != null -> CutoutKnowledge.KNOWN_PRESENT; else -> CutoutKnowledge.KNOWN_NONE },
            displayRotation = display.rotation,
            contentRotation = contentRotation,
            effectiveDensityDpi = densityDpi,
            physicalModeWidthPx = modeSize?.first,
            physicalModeHeightPx = modeSize?.second,
        )
        val contentBounds = geometry.contentBounds(region)
        require(!contentBounds.isEmpty) { "E_SCENE_CONTENT_REGION_EMPTY" }
        val legacyInsets = cameraSafeInsets(activity)
        return RearWindowReport(
            component = ComponentName(activity, activity.javaClass), taskId = activity.taskId,
            revision = revision, displayId = display.displayId, physicalDisplayPx = modeSize,
            contentBoundsPx = intArrayOf(contentBounds.left, contentBounds.top, contentBounds.right, contentBounds.bottom), windowPx = width to height,
            windowDp = (width * 160f / densityDpi) to (height * 160f / densityDpi),
            densityDpi = densityDpi, renderBufferPx = null,
            displayRotation = display.rotation, contentRotation = contentRotation,
            inputTransform = if (preserveLegacyTransform) SceneContentGeometry.inputTransform(legacyInsets[0], legacyInsets[1]) else geometry.windowInputMatrix(region),
            region = region, cutoutKnowledge = geometry.cutoutKnowledge, cutoutBoundsPx = cutoutBounds
        )
    }

    /** Safe insets in the actual Activity window's current display orientation, in physical pixels. */
    @android.annotation.SuppressLint("NewApi") // DisplayCutout is read only after the API 28 guard above.
    fun cameraSafeInsets(activity: Activity): IntArray {
        val decor = activity.window.decorView
        val width = decor.width.coerceAtLeast(1)
        val height = decor.height.coerceAtLeast(1)
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) decor.rootWindowInsets?.displayCutout else null
        val left = cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0
        val top = cutout?.safeInsetTop?.coerceIn(0, height) ?: 0
        val right = cutout?.safeInsetRight?.coerceIn(0, width - left) ?: 0
        val bottom = cutout?.safeInsetBottom?.coerceIn(0, height - top) ?: 0
        return intArrayOf(left, top, right, bottom)
    }
}

/** Window-local touch coordinates are already in the current display rotation; only crop safe insets. */
internal object SceneContentGeometry {
    fun safeBounds(x: Int, y: Int, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        require(width > 0 && height > 0)
        val window = PixelRect(x, y, x + width, y + height)
        val safe = SceneViewportGeometry.fromWindow(
            displayBounds = window, windowBounds = window,
            cutoutInsets = PixelInsets(left.coerceAtLeast(0), top.coerceAtLeast(0), right.coerceAtLeast(0), bottom.coerceAtLeast(0)),
            displayRotation = 0, effectiveDensityDpi = 1
        ).safeBounds
        return intArrayOf(safe.left, safe.top, safe.right, safe.bottom)
    }

    fun inputTransform(safeLeft: Int, safeTop: Int) = floatArrayOf(
        1f, 0f, -safeLeft.toFloat(),
        0f, 1f, -safeTop.toFloat()
    )
}
