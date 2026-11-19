package top.zainzh.rearscreen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.zainzh.rearscreen.platform.mods.ModPackageStore

/** The package can be deleted only after the existing wallpaper journal has verified restoration. */
internal object AiRearModWallpaperRecovery {
    suspend fun restoreBeforeUninstall(context: Context, modId: String): Int {
        val installations = AiRearWallpaperInstallations.forApp(context)
        if (withContext(Dispatchers.IO) { installations.forMod(modId).isEmpty() }) return 0
        fun checkShell() = check(AiRearWallpaperSupport.shellReady(context)) {
            context.getString(R.string.ai_rear_uninstall_connect)
        }
        checkShell()
        var consumer: ServiceConnection? = null
        return try {
            val remote = withTimeoutOrNull(6_000L) {
                suspendCancellableCoroutine<ITaskService> { waiting ->
                    val request = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (waiting.isActive) waiting.resume(ITaskService.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName) = Unit
                    }
                    consumer = request
                    TaskServiceManager.acquire(context, request)
                }
            } ?: error(context.getString(R.string.ai_rear_uninstall_connect))

            val (count, keepStandby) = withContext(Dispatchers.IO) {
                synchronized(AiRearWallpaperInstallations.writeLock) {
                    checkShell()
                    val prefs = context.getSharedPreferences(AiRearWallpaperBinding.PREFS, Context.MODE_PRIVATE)
                    val restored = installations.restoreMod(modId, AiRearWallpaperConnection(remote)) {
                        // Journal was detached only after reading back the original payload.
                        prefs.edit().remove(it.bindingKey).remove(it.titleKey).apply()
                    }
                    restored to runCatching {
                        installations.hasLiveBinding { ModPackageStore.usableWallpaperTheme(context, it) != null }
                    }.getOrDefault(true)
                }
            }
            if (count > 0 && !keepStandby) RearWallpaperStandby.disarm()
            count
        } finally {
            consumer?.let(TaskServiceManager::release)
        }
    }
}
