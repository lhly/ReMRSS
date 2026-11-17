package top.zainzh.rearscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.zainzh.rearscreen.platform.DebugCastAdmissionDiagnostic

/** Fixed-action, system-DUMP-gated diagnostic; it only peeks the already-connected service. */
class DebugCastAdmissionDiagnosticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // The exported Debug manifest receiver enforces DUMP at delivery. BroadcastReceiver
        // has no sender Binder identity in onReceive, so do not attempt a second caller check.
        val remote = TaskServiceManager.peekConnectedServiceForDebug()
        if (remote == null) {
            Log.i(TAG, "status=unavailable reason=no_existing_connection")
            return
        }
        val pending = goAsync()
        Thread({
            try {
                val report = DebugCastAdmissionDiagnostic.collect(context.applicationContext, remote)
                Log.i(TAG, report.toString())
            } catch (failure: Exception) {
                Log.i(TAG, "status=unavailable reason=read_${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }, "cast-admission-diagnostic").apply { isDaemon = true }.start()
    }

    companion object {
        const val ACTION = "top.zainzh.rearscreen.debug.action.READ_CAST_ADMISSION"
        private const val TAG = "RC5CastAdmissionDiagnostic"
    }
}
