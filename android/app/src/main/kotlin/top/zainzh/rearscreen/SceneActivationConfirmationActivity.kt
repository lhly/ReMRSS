package top.zainzh.rearscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** Foreground, user-visible approval for one independently launched rear Activity. */
class SceneActivationConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_ACTIVATION_NONCE)
        val details = nonce?.let(RemoteRearPlatformService::activationDetails)
        if (details == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        miuixContent {
            AppConsentPage(stringResource(R.string.scene_activation_title), footer = {
                AppActionPair(stringResource(R.string.scene_activation_deny), { complete(false) },
                    stringResource(R.string.scene_activation_allow), { complete(true) })
            }) {
                AppCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scene_activation_request, details.first, details.second))
                        Text(stringResource(R.string.scene_activation_effect), color = AppPalette.secondary())
                        if (details.third) Text(stringResource(R.string.scene_activation_keep_visible), color = AppPalette.secondary())
                    }
                }
            }
        }
    }

    private fun complete(allow: Boolean) {
        nonce?.let { RemoteRearPlatformService.completeActivation(it, allow) }
        setResult(if (allow) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
