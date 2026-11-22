package top.zainzh.rearscreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Opens the real controls in the isolated phone build without a separate instrumentation APK. */
class RecordingControlsDiagnosticActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this) && TaskServiceManager.canConnect(this)) {
            startForegroundService(Intent(this, ScreenRecordService::class.java))
        }
        finish()
    }
}
