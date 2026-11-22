package top.zainzh.rearscreen

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** Per-publisher user consent, independent from the notification-listener app selection. */
class NotificationAccessConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null
    private var consentFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_NOTIFICATION_CONSENT_NONCE)
        val details = nonce?.let(RemoteRearPlatformService::notificationConsentDetails)
        if (details == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithoutChange()
        })
        miuixContent {
            NotificationPublisherConsentScreen(details.first, details.second, ::finishWithoutChange, ::complete)
        }
    }

    private fun finishWithoutChange() {
        if (consentFinished) return
        consentFinished = true
        nonce?.let { RemoteRearPlatformService.completeNotificationConsent(it, null) }
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun complete(allowed: Boolean) {
        if (consentFinished) return
        consentFinished = true
        nonce?.let { RemoteRearPlatformService.completeNotificationConsent(it, allowed) }
        setResult(if (allowed) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}

/** Shared by the authenticated Activity and an isolated UI fixture. */
@Composable
internal fun NotificationPublisherConsentScreen(
    publisherLabel: String,
    initiallyAllowed: Boolean,
    onCancel: () -> Unit,
    onSave: (Boolean) -> Unit,
) {
    var allowed by rememberSaveable { mutableStateOf(initiallyAllowed) }
    AppConsentPage(stringResource(R.string.notification_publisher_consent_title),
        modifier = Modifier.testTag("notification_consent_scroll"),
        footer = {
            AppActionPair(stringResource(R.string.scene_activation_deny), onCancel,
                stringResource(R.string.notification_publisher_consent_save), { onSave(allowed) },
                secondaryModifier = Modifier.testTag("notification_consent_cancel"),
                primaryModifier = Modifier.testTag("notification_consent_save"))
        }) {
        Text(stringResource(R.string.notification_publisher_consent_intro, publisherLabel), color = AppPalette.secondary())
        AppCard {
            AppToggle(stringResource(R.string.notification_publisher_consent_access),
                stringResource(R.string.notification_publisher_consent_detail), allowed, onChange = { allowed = it })
        }
        Text(stringResource(R.string.notification_publisher_consent_footer), color = AppPalette.secondary())
    }
}
