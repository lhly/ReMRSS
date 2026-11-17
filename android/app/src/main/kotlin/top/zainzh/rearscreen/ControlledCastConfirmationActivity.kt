package top.zainzh.rearscreen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService
import android.util.Log
import top.zainzh.rearscreen.BuildConfig
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
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("确认投放到背屏", fontSize = 22.sp, color = AppPalette.text())
                        Text("透明覆盖中保留原主屏目标；确认后仍会重新核验目标与占用。", color = AppPalette.secondary())
                    }
                }
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目标应用：${details.applicationLabel}")
                        Text("投放方式：${details.modeLabel}")
                        Text("背屏区域：${details.regionLabel}")
                        Text(if (details.replacingLabel == null) "占用：背屏当前没有受控投放" else "替换：${details.replacingLabel}", color = AppPalette.secondary())
                    }
                }
                when {
                    submitting -> Text("正在提交到背屏…", color = AppPalette.secondary())
                    failureCode != null -> Text(
                        if (failureCode == "E_TICKET_STALE") "此确认已过期，请返回样例重新开始。"
                        else "提交未接受：$failureCode",
                        color = AppPalette.destructive()
                    )
                }
                if (failureCode != null) {
                    TextButton("返回样例", onClick = { setResult(RESULT_CANCELED); finish() }, minHeight = 48.dp)
                } else {
                    TextButton("取消", onClick = { complete(false) }, minHeight = 48.dp, enabled = !submitting)
                    TextButton(if (submitting) "正在提交…" else "确认并投放", onClick = { complete(true) }, minHeight = 48.dp, enabled = !submitting)
                }
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
