package top.zainzh.rearscreen

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Rc6LayoutAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun largeDarkLandscapeDurationShowsValueAndBothActionsWithoutScrolling() {
        val resolver = compose.activity.contentResolver
        val previousFont = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f)
        val previousOrientation = compose.activity.requestedOrientation
        val mode = compose.activity.getSystemService(android.app.UiModeManager::class.java).nightMode
        val prefs = compose.activity.getSharedPreferences("mrss_settings", 0)
        val savedDuration = prefs.getInt("notification_duration", 10)
        try {
            shell("cmd uimode night yes")
            shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                val configuration = compose.activity.resources.configuration
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    configuration.fontScale >= 1.59f &&
                    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            compose.onNodeWithContentDescription(s("notifications_title")).performClick()
            compose.onNodeWithTag("notifications_list").performScrollToNode(hasText(s("notification_rules")))
            compose.onNodeWithText(s("notification_rules")).performClick()
            compose.onNodeWithTag("notification_rules_list").performScrollToNode(hasText(s("notification_duration_title")))
            compose.onNodeWithText(s("notification_duration_title")).performClick()
            capture("duration-dark-large-landscape")
            compose.onNodeWithTag("notification_duration_value").assertIsDisplayed()
            compose.onNodeWithTag("notification_duration_unit").assertIsDisplayed()
            compose.onNodeWithTag("notification_duration_persist").assertIsDisplayed()
            val help = compose.onNodeWithTag("notification_duration_persist_help").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("Battery and end conditions must be fully visible at 1.6×",
                help.boundsInRoot.height + 1f >= help.size.height)
            compose.onNodeWithTag("notification_duration_cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("notification_duration_confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("notification_duration_cancel").performClick()
            assertEquals(savedDuration, prefs.getInt("notification_duration", 10))
            compose.onNodeWithText(s("notification_duration_title")).performClick()
            val originalPersistent = prefs.getBoolean("notification_never_auto_close", false)
            try {
                compose.onNodeWithTag("notification_duration_persist").performClick()
                compose.onNodeWithTag("notification_duration_confirm").performClick()
                compose.waitUntil(5_000) {
                    prefs.getBoolean("notification_never_auto_close", false) != originalPersistent
                }
                assertEquals(savedDuration, prefs.getInt("notification_duration", 10))
            } finally {
                prefs.edit().putBoolean("notification_never_auto_close", originalPersistent).apply()
            }
        } finally {
            shell("settings put system font_scale $previousFont")
            shell("cmd uimode night ${when (mode) {
                android.app.UiModeManager.MODE_NIGHT_YES -> "yes"
                android.app.UiModeManager.MODE_NIGHT_NO -> "no"
                android.app.UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
                else -> "auto"
            }}")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previousOrientation }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val width = minOf(screenshot.width, 960)
        val preview = Bitmap.createScaledBitmap(screenshot, width, screenshot.height * width / screenshot.width, true)
        try {
            val buffer = ByteArrayOutputStream()
            check(preview.compress(Bitmap.CompressFormat.JPEG, 88, buffer))
            instrumentation.addResults(Bundle().apply { putString("rc6_jpg_$name", Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)) })
        } finally {
            if (preview !== screenshot) preview.recycle()
            screenshot.recycle()
        }
    }

    private fun s(key: String) = NativeStrings.get(compose.activity, key)

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }
}
