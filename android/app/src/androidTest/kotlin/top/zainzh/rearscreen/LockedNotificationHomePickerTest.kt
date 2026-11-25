package top.zainzh.rearscreen

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LockedNotificationHomeFixtureActivity : Activity()

class LockedNotificationHomePickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun theLockedHomePickerRequiresIndividualChoiceAndRemovingSourceRevokesIt() {
        val activity = compose.activity
        val prefs = activity.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
        val sourceKey = "notification_selected_apps"
        val homeKey = RearNotificationCastRequest.HOME_APPS_PREF
        val fixture = instrumentation.context.packageName
        val fixtureTag = "picker_app_" + fixture
        val oldSources = prefs.getStringSet(sourceKey, emptySet()).orEmpty().toSet()
        val oldHomes = prefs.getStringSet(homeKey, emptySet()).orEmpty().toSet()
        val sources = setOf(fixture, "dev.remrss.sample.not_installed")
        val label = activity.getString(R.string.ui_notification_locked_home_warning)
        var model: AppPickerModel? = null

        fun render(lockedHome: Boolean) {
            compose.runOnUiThread {
                model?.destroy()
                val modal = MiuixDialogUi(activity)
                val selectedMode = AppPickerModel(activity, null, Runnable {}, lockedHome, modal, Runnable {})
                model = selectedMode
                val picker = ComposeView(activity).apply {
                    setContent {
                        AppTheme {
                            // The real settings route applies system-bar insets outside the picker.
                            Box(Modifier.fillMaxSize().background(AppPalette.background())
                                .windowInsetsPadding(WindowInsets.safeDrawing)) {
                                selectedMode.Content()
                            }
                        }
                    }
                }
                activity.setContentView(android.widget.FrameLayout(activity).apply {
                    addView(picker, android.widget.FrameLayout.LayoutParams(-1, -1))
                    addView(modal.view, android.widget.FrameLayout.LayoutParams(-1, -1))
                })
            }
            compose.waitUntil(5_000) { model?.loading == false }
        }
        fun fixtureCheckbox() = compose.onNode(hasText(fixture) and isToggleable())
        fun scrollToFixture() = compose.onNodeWithTag("app_picker_list").performScrollToNode(hasTestTag(fixtureTag))
        try {
            prefs.edit().putStringSet(sourceKey, sources).remove(homeKey).commit()
            render(lockedHome = true)
            compose.onNodeWithText(label).assertExists()
            compose.onNodeWithText(activity.getString(R.string.ui_select_all)).assertDoesNotExist()
            scrollToFixture()
            fixtureCheckbox().assertIsDisplayed().assertIsOff().performClick()
            if (AiRearWallpaperSupport.shellReady(activity)) {
                fixtureCheckbox().assertIsOn()
            } else {
                compose.onNodeWithText(activity.getString(R.string.ui_notification_locked_home_shizuku_required)).assertExists()
                compose.onNodeWithText(activity.getString(R.string.ui_cancel)).performClick()
                fixtureCheckbox().assertIsOff()
                assertEquals(emptySet<String>(), prefs.getStringSet(homeKey, emptySet()))
                // Seed an existing grant to exercise removal independently of the device backend.
                prefs.edit().putStringSet(homeKey, setOf(fixture)).commit()
            }
            assertEquals(setOf(fixture), prefs.getStringSet(homeKey, emptySet()))
            assertEquals(sources, prefs.getStringSet(sourceKey, emptySet()))
            render(lockedHome = true)
            scrollToFixture()
            fixtureCheckbox().assertIsOn()

            render(lockedHome = false)
            scrollToFixture()
            fixtureCheckbox().assertIsOn().performClick()
            compose.onNodeWithText(activity.getString(R.string.ui_notification_disable_source_revokes_home)).assertExists()
            compose.onNodeWithText(activity.getString(R.string.ui_cancel)).performClick()
            assertEquals(sources, prefs.getStringSet(sourceKey, emptySet()))
            assertEquals(setOf(fixture), prefs.getStringSet(homeKey, emptySet()))
            fixtureCheckbox().assertIsOn().performClick()
            compose.onNodeWithText(activity.getString(R.string.ui_notification_disable_source_confirm)).performClick()
            assertEquals(setOf("dev.remrss.sample.not_installed"), prefs.getStringSet(sourceKey, emptySet()))
            assertEquals(emptySet<String>(), prefs.getStringSet(homeKey, emptySet()))
        } finally {
            compose.runOnUiThread { model?.destroy() }
            prefs.edit().putStringSet(sourceKey, oldSources).putStringSet(homeKey, oldHomes).commit()
        }
    }
}
