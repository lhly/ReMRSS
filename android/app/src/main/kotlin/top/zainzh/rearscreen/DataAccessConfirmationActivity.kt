package top.zainzh.rearscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** Foreground, explicit per-publisher consent for optional read-only platform data. */
class DataAccessConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_DATA_CONSENT_NONCE)
        val details = nonce?.let(RemoteRearPlatformService::dataConsentDetails)
        if (details == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val fields = details.second.toSet()
        val existing = details.third
        val canBattery = fields.any { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING }
        val canMetrics = fields.any { it.name.startsWith("SYSTEM_") }
        miuixContent {
            var battery by rememberSaveable { mutableStateOf(existing.any { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING }) }
            var metrics by rememberSaveable { mutableStateOf(existing.any { it.name.startsWith("SYSTEM_") }) }
            AppConsentPage(stringResource(R.string.data_consent_title), footer = {
                AppActionPair(stringResource(R.string.scene_activation_deny), { complete(null) },
                    stringResource(R.string.data_consent_allow), {
                        val accepted = buildSet {
                            if (battery) addAll(fields.filter { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING })
                            if (metrics) addAll(fields.filter { it.name.startsWith("SYSTEM_") })
                        }
                        complete(accepted)
                    })
            }) {
                Text(stringResource(R.string.data_consent_intro, details.first), color = AppPalette.secondary())
                AppCard {
                    if (canBattery) AppToggle(stringResource(R.string.data_consent_battery),
                        stringResource(R.string.data_consent_battery_detail), battery, onChange = { battery = it })
                    if (canBattery && canMetrics) AppDivider()
                    if (canMetrics) AppToggle(stringResource(R.string.data_consent_metrics),
                        stringResource(R.string.data_consent_metrics_detail), metrics, onChange = { metrics = it })
                }
                Text(stringResource(R.string.data_consent_footer), color = AppPalette.secondary())
            }
        }
    }

    private fun complete(fields: Set<DataField>?) {
        nonce?.let { RemoteRearPlatformService.completeDataConsent(it, fields) }
        setResult(if (fields?.isNotEmpty() == true) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
