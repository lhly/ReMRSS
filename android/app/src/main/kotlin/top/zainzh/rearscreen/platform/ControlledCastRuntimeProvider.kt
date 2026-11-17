package top.zainzh.rearscreen.platform

import android.content.Context
import top.zainzh.rearscreen.BuildConfig
import top.zainzh.rearscreen.TaskServiceManager

/** One app-process Cast owner/coordinator shared by SDK and Mod transports. */
internal class ControlledCastRuntime private constructor(context: Context) {
    private val taskService = AndroidCastTaskService(context.applicationContext)
    val owners = ControlledCastOwnerRegistry()
    val activationAuthority = CastActivationAuthority()
    val targets = AndroidHostCastTargetResolver(context.applicationContext, taskService)
    private val backend = AndroidControlledCastBackend(context.applicationContext, taskService)
    val coordinator = ControlledCastCoordinator(targets, owners, activationAuthority, backend)

    /** Capabilities are derived from the actual configured backend, never from requested mode alone. */
    fun modeAvailable(mode: CastMode): Boolean = when (mode) {
        CastMode.VIRTUAL -> BuildConfig.VIRTUAL_CAST_ENABLED &&
            TaskServiceManager.connectionStateName(taskServiceContext) == "READY"
        // Runtime support is available; each prepare still fails closed on any occupied/unknown rear owner.
        CastMode.DIRECT_PHYSICAL -> TaskServiceManager.connectionStateName(taskServiceContext) == "READY"
    }

    private val taskServiceContext: Context = context.applicationContext

    companion object {
        internal fun create(context: Context) = ControlledCastRuntime(context.applicationContext)
    }
}

internal object ControlledCastRuntimeProvider {
    @Volatile private var current: ControlledCastRuntime? = null
    fun get(context: Context): ControlledCastRuntime = current ?: synchronized(this) {
        current ?: ControlledCastRuntime.create(context).also { current = it }
    }
}
