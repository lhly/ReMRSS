package top.zainzh.rearscreen.platform.actions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.AppActionPair
import top.zainzh.rearscreen.AppConsentPage
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** User-confirmed invocation route for SDK clients; the PendingIntent contains only a host nonce. */
class RegisteredActionInvocationActivity : ComponentActivity() {
    private var nonce: String? = null
    private var completed = false
    private val prompt by lazy { nonce?.let(RemoteRearPlatformService::actionInvocationPrompt) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_ACTION_INVOCATION_NONCE)
        val value = prompt
        if (value == null) { finish(); return }
        miuixContent {
            RegisteredActionInvocationContent(
                value.appLabel, value.actionLabel, value.targetLabel, ::cancel, ::confirm,
            )
        }
    }

    private fun confirm() {
        if (completed) return
        completed = true
        nonce?.let { RemoteRearPlatformService.completeActionInvocation(it, true) }
        finish()
    }

    private fun cancel() {
        if (completed) { finish(); return }
        completed = true
        nonce?.let { RemoteRearPlatformService.completeActionInvocation(it, false) }
        finish()
    }

    override fun onDestroy() {
        if (!completed) nonce?.let { RemoteRearPlatformService.completeActionInvocation(it, false) }
        super.onDestroy()
    }
}


/** The host uses the same fixed-decision layout for one-time approval and permission requests. */
@Composable
internal fun RegisteredActionInvocationContent(
    appLabel: String,
    actionLabel: String,
    targetLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppConsentPage("调用快捷操作", modifier = Modifier.testTag("registered_action_invocation_body"), footer = {
        AppActionPair("取消", onCancel, "确认调用", onConfirm,
            secondaryModifier = Modifier.testTag("registered_action_invocation_cancel"),
            primaryModifier = Modifier.testTag("registered_action_invocation_confirm"))
    }) {
        Text("${appLabel} 请求调用你为它登记的操作：${actionLabel}", color = AppPalette.text())
        Text("目标：${targetLabel}。每次调用都需要你在此确认。", color = AppPalette.secondary())
    }
}
