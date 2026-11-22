package top.zainzh.rearscreen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** One-use, host-owned approval for a prepared controlled Cast target. */
class ControlledCastConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null
    private var completed = false
    private var submitting by mutableStateOf(false)
    private var failureCode by mutableStateOf<String?>(null)
    private var expiresAtElapsedRealtimeMs = 0L
    private val expiryHandler = Handler(Looper.getMainLooper())
    private val expiryCheck = object : Runnable {
        override fun run() {
            if (completed) return
            val remaining = expiresAtElapsedRealtimeMs - SystemClock.elapsedRealtime()
            if (remaining > 0L) {
                expiryHandler.postDelayed(this, remaining)
            } else {
                completed = true
                submitting = false
                failureCode = "E_TICKET_STALE"
                castLog("confirmation_expired", "E_TICKET_STALE")
                setResult(RESULT_CANCELED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_CAST_NONCE)
        nonce?.let { activeConfirmations[it] = WeakReference(this) }
        val details = nonce?.let(RemoteRearPlatformService::castActivationDetails)
        if (details == null) {
            castLog("confirmation_unavailable", "E_TICKET_STALE")
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        expiresAtElapsedRealtimeMs = details.expiresAtElapsedRealtimeMs
        miuixContent {
            val failure = failureCode
            if (failure != null) {
                CastApprovalFailure("未能启动背屏投放",
                    if (failure == "E_TICKET_STALE") "此次确认已过期，请返回后重新发起投放。"
                    else "提交未接受：$failure") {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            } else {
                CastApprovalPane(
                    application = details.applicationLabel, mode = details.modeLabel, region = details.regionLabel,
                    rotationDegrees = details.rotationDegrees,
                    occupancy = if (details.replacingLabel == null) "背屏占用：当前没有受控投放"
                        else "替换：${details.replacingLabel}",
                    submitting = submitting, onSecondary = { complete(false) }, onConfirm = { complete(true) },
                )
            }
        }
        window.decorView.post {
            if (window.decorView.isShown && hasWindowFocus()) castLog("confirmation_view_ready")
        }
        refreshExpiryCheck()
    }

    override fun onResume() {
        super.onResume()
        refreshExpiryCheck()
    }

    private fun refreshExpiryCheck() {
        expiryHandler.removeCallbacks(expiryCheck)
        expiryCheck.run()
    }

    private fun complete(allow: Boolean) {
        if (completed || submitting) return
        if (!allow) {
            completed = true
            val result = nonce?.let { RemoteRearPlatformService.completeCastActivation(it, false) } ?: "E_TICKET_STALE"
            if (result == "E_USER_CANCELLED") {
                castLog("confirmation_cancelled", result)
                setResult(RESULT_CANCELED)
                finish()
            } else {
                failureCode = result
                submitting = false
                castLog("confirmation_rejected", result)
                setResult(RESULT_CANCELED)
            }
            return
        }
        submitting = true
        val result = nonce?.let { RemoteRearPlatformService.completeCastActivation(it, true) } ?: "E_TICKET_STALE"
        if (result == "COMMIT_ACCEPTED") {
            completed = true
            setResult(RESULT_OK)
            finish()
        } else {
            completed = true
            submitting = false
            failureCode = result
            castLog("commit_rejected", result)
            setResult(RESULT_CANCELED)
        }
    }

    override fun onDestroy() {
        expiryHandler.removeCallbacks(expiryCheck)
        nonce?.let { activeConfirmations.remove(it) }
        if (!completed) nonce?.let { RemoteRearPlatformService.completeCastActivation(it, false) }
        super.onDestroy()
    }

    private fun castLog(stage: String, code: String? = null) {
        if (BuildConfig.DEBUG) Log.i("ReMRSSCastFlow", if (code == null) "stage=$stage" else "stage=$stage code=$code")
    }

    private fun showTerminalFailure(code: String) {
        if (completed) return
        completed = true
        submitting = false
        failureCode = code
        castLog("confirmation_ended", code)
        setResult(RESULT_CANCELED)
    }

    companion object {
        private val activeConfirmations = ConcurrentHashMap<String, WeakReference<ControlledCastConfirmationActivity>>()

        internal fun notifyPendingCastFailure(nonce: String, code: String) {
            activeConfirmations[nonce]?.get()?.runOnUiThread { activeConfirmations[nonce]?.get()?.showTerminalFailure(code) }
        }
    }
}
