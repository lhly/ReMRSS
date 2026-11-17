package top.zainzh.rearscreen.platform.mods

import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Host-only identity for the native surface that produced a Cast click. Never sent to Mod JS. */
data class ModCastActivationSource(
    val modId: String,
    val surface: Surface,
    val instanceId: String,
    val sceneSessionId: String? = null,
    val sceneGeneration: Long? = null,
) {
    enum class Surface { MAIN_SETTINGS, REAR_SCENE }

    fun isWellFormed(): Boolean = modId.isNotBlank() && instanceId.isNotBlank() && when (surface) {
        Surface.MAIN_SETTINGS -> sceneSessionId == null && sceneGeneration == null
        Surface.REAR_SCENE -> !sceneSessionId.isNullOrBlank() && (sceneGeneration ?: 0L) > 0L
    }
}

/** Weak registrations make delayed worker effects resolve only to the exact live Activity source. */
internal object ModCastActivationSources {
    private val consumers = ConcurrentHashMap<ModCastActivationSource, WeakReference<ModHostEffectRouter.Consumer>>()

    fun register(source: ModCastActivationSource, consumer: ModHostEffectRouter.Consumer): Closeable {
        require(source.isWellFormed())
        val reference = WeakReference(consumer)
        consumers[source] = reference
        return Closeable { consumers.remove(source, reference) }
    }

    fun resolve(source: ModCastActivationSource, modId: String): ModHostEffectRouter.Consumer? {
        if (!source.isWellFormed() || source.modId != modId) return null
        val registration = consumers[source] ?: return null
        val consumer = registration.get()
        if (consumer == null) consumers.remove(source, registration)
        return consumer
    }

    fun isRegistered(source: ModCastActivationSource, modId: String = source.modId): Boolean =
        resolve(source, modId) != null

    fun complete(source: ModCastActivationSource, requestId: String, accepted: Boolean, code: String?, result: org.json.JSONObject? = null): Boolean {
        val consumer = resolve(source, source.modId) ?: return false
        if (requestId.isBlank()) return false
        consumer.completeCast(source, requestId, accepted, code, result)
        return true
    }

    fun isRequestCurrent(source: ModCastActivationSource, requestId: String): Boolean =
        requestId.isNotBlank() && resolve(source, source.modId)?.isCastRequestCurrent(source, requestId) == true

    fun dispatch(
        source: ModCastActivationSource,
        mod: InstalledMod,
        eventId: String,
        effect: ModCastStartEffect,
        complete: (Boolean, String?, org.json.JSONObject?) -> Unit,
    ): Boolean {
        val consumer = resolve(source, mod.id)
        if (consumer == null) {
            complete(false, "E_CAST_SOURCE_STALE", null)
            return false
        }
        consumer.requestCastWithResult(mod, eventId, effect, source, complete)
        return true
    }
}
