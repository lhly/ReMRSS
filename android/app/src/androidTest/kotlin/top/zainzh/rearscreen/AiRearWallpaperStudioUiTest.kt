package top.zainzh.rearscreen

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.LocaleList
import android.util.Base64
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AiRearWallpaperStudioUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val contentId = View.generateViewId()

    @Test fun sparsePreviewStaysCenteredAndCanReplaceAcrossRecreation() {
        val apps = demoApps()
        val model = mutableStateOf(studio(apps).copy(grid = AiRearGrid(),
            work = AiRearStudioWork("林间微光", "11月19日 · 91ae27")))
        var edits = 0
        var requested: AiRearStudioAction? = null
        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use { scene ->
            fun install(dark: Boolean, font: Float = 1f) = scene.onActivity { activity ->
                present(activity, dark, font) {
                    AiRearWallpaperStudio(model.value, rememberLazyListState(), {}, { edits++ },
                        {}, { requested = it }, thumbnail = {})
                }
            }
            fun checkRow(slots: List<Int>) {
                val canvas = compose.onNodeWithTag("ai_rear_canvas").performScrollTo().fetchSemanticsNode().boundsInRoot
                val area = slots.map { compose.onNodeWithTag("ai_rear_tile_$it").fetchSemanticsNode().boundsInRoot }
                val viewport = model.value.viewport
                val expectedX = canvas.left + canvas.width *
                    (viewport.left + viewport.width - viewport.right) / (2 * viewport.width)
                assertTrue(abs((area.first().left + area.last().right) / 2 - expectedX) < 3f)
                assertTrue(abs(area.first().center.y - canvas.center.y) < 3f)
                assertTrue(area.all { abs(it.center.y - area.first().center.y) < 2f })
            }

            install(dark = false)
            compose.onNodeWithTag("ai_rear_add_shortcuts").performClick()
            assertEquals(1, edits)
            compose.onNodeWithTag("ai_rear_primary_action").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle {
                model.value = model.value.copy(grid = AiRearGrid().bind(0, apps[0].component).bind(2, apps[2].component))
            }
            checkRow(listOf(0, 2))
            compose.onNodeWithTag("ai_rear_tile_1").assertDoesNotExist()
            compose.onNodeWithTag("ai_rear_tile_5").assertDoesNotExist()
            capture("adaptive-grid-light-two")
            compose.onNodeWithTag("ai_rear_primary_action").performScrollTo().assertIsEnabled().performClick()
            assertEquals(AiRearStudioAction.REPLACE, requested)

            compose.runOnIdle { model.value = model.value.copy(grid = model.value.grid.copy(allApps = true)) }
            scene.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var landscape = false
                scene.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            install(dark = true, font = 1.6f)
            checkRow(listOf(0, 2, 5))
            compose.onNodeWithTag("ai_rear_tile_5").assertHasClickAction().performClick()
            assertEquals(2, edits)
            capture("adaptive-grid-dark-large-with-list")
            compose.onNodeWithTag("ai_rear_primary_action").performScrollTo().assertIsEnabled()
        }
    }

    @Test fun previewTilesEditDirectlyAndWorkActionsStayTogether() {
        val apps = demoApps()
        val model = mutableStateOf(studio(apps))
        var editRequests = 0
        var requested: AiRearStudioAction? = null
        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use { scene ->
            fun install() = scene.onActivity { activity ->
                present(activity, dark = false) {
                    AiRearWallpaperStudio(model.value, rememberLazyListState(), {}, { editRequests++ },
                        { model.value = model.value.copy(allowUnlocked = it) },
                        {
                            requested = it
                            if (it == AiRearStudioAction.PICK_WORK)
                                model.value = model.value.copy(work = AiRearStudioWork("林间微光", "11月19日 · 91ae27"))
                            if (it == AiRearStudioAction.REPLACE)
                                model.value = model.value.copy(installed = true)
                        }, thumbnail = {})
                }
            }
            install()
            compose.onNodeWithText("背屏启动器").assertIsDisplayed()
            val canvas = compose.onNodeWithTag("ai_rear_canvas").assertIsDisplayed()
            val appearance = compose.onNodeWithTag("ai_rear_appearance").assertIsDisplayed()
            assertTrue("appearance belongs above the artwork", appearance.fetchSemanticsNode().boundsInRoot.bottom <=
                canvas.fetchSemanticsNode().boundsInRoot.top)
            assertTrue("appearance belongs on the trailing side of the preview",
                appearance.fetchSemanticsNode().boundsInRoot.center.x > canvas.fetchSemanticsNode().boundsInRoot.center.x)
            compose.onNodeWithTag("ai_rear_tile_5").assertHasClickAction()
            capture("wallpaper-studio-light")
            appearance.performTouchInput { click(center) }
            assertEquals(AiRearStudioAction.APPEARANCE, requested)
            val tile = compose.onNodeWithTag("ai_rear_tile_2")
            tile.performTouchInput { swipeUp() }
            assertEquals(0, editRequests)
            tile.performScrollTo().performTouchInput { click(center) }
            compose.onNodeWithTag("ai_rear_tile_5").performScrollTo().performTouchInput { click(center) }
            assertEquals("both an app tile and the All apps tile open the same editor", 2, editRequests)
            compose.onNodeWithTag("ai_rear_pick_work").performScrollTo().performClick()
            assertEquals(AiRearStudioAction.PICK_WORK, requested)
            compose.onNodeWithTag("ai_rear_primary_action").performScrollTo().performClick()
            assertEquals(AiRearStudioAction.REPLACE, requested)
            compose.onNodeWithTag("ai_rear_restore").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ai_rear_allow_unlocked").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(true, model.value.allowUnlocked) }
            scene.recreate()
            install()
            compose.onNodeWithTag("ai_rear_restore").performScrollTo().assertIsDisplayed()
            assertEquals(true, model.value.allowUnlocked)
        }
    }

    @Test fun oneEditorCanParkReorderRemoveAndAddWithoutLeaving() {
        val apps = demoApps()
        val original = AiRearShortcutDraft.fromGrid(AiRearGrid(apps.take(6).map { it.component }))
        val draft = mutableStateOf(original)
        val editing = mutableStateOf(false)
        val fifthKey = original.entries[4].key
        val sixthKey = original.entries[5].key
        val parkedText = "暂不显示 · 第六格由「全部应用」占用"
        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use { scene ->
            fun install(dark: Boolean = false, font: Float = 1f) = scene.onActivity { activity ->
                present(activity, dark, font) {
                    val studioScroll = rememberLazyListState()
                    if (editing.value) {
                        AiRearShortcutEditor(draft.value, apps, loading = false, back = { editing.value = false }) {
                            draft.value = it
                        }
                    } else {
                        val model = studio(apps).copy(grid = draft.value.grid)
                        AiRearWallpaperStudio(model, studioScroll, {}, { editing.value = true },
                            {}, {}, thumbnail = {})
                    }
                }
            }
            install()
            compose.onNodeWithTag("ai_rear_tile_0").performTouchInput { click(center) }
            val list = compose.onNodeWithTag("ai_rear_shortcuts").assertIsDisplayed()
            val library = compose.onNodeWithTag("ai_rear_library")
            library.performClick()
            assertTrue(draft.value.allApps)
            assertEquals(original.entries, draft.value.entries)
            list.performScrollToIndex(2)
            compose.onNodeWithText(parkedText).assertIsDisplayed()

            val fifth = compose.onNodeWithTag("ai_rear_drag_$fifthKey", useUnmergedTree = true).assertIsDisplayed()
            val sixth = compose.onNodeWithTag("ai_rear_drag_$sixthKey", useUnmergedTree = true).assertIsDisplayed()
            val distance = sixth.fetchSemanticsNode().boundsInRoot.center.y - fifth.fetchSemanticsNode().boundsInRoot.center.y
            assertTrue(distance > 0)
            compose.mainClock.autoAdvance = false
            try {
                sixth.performTouchInput {
                    down(center)
                    repeat(8) { moveBy(Offset(0f, -distance / 8f), delayMillis = 22L) }
                }
                compose.mainClock.advanceTimeBy(64)
                compose.onNodeWithTag("ai_rear_dragging_$sixthKey").assertIsDisplayed()
                sixth.performTouchInput { up() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.runOnIdle {
                assertEquals(sixthKey, draft.value.entries[4].key)
                assertEquals(fifthKey, draft.value.entries[5].key)
                assertTrue(draft.value.parked(5))
            }
            list.performScrollToIndex(0)
            library.performClick()
            assertEquals(AiRearGrid.Tile.App(original.entries[4].component), draft.value.grid.tile(5))
            library.performClick()
            list.performScrollToIndex(2)
            capture("launcher-shortcuts-configured")
            list.performScrollToIndex(3)
            compose.onNodeWithTag("ai_rear_available_${apps[6].component}").assertIsNotEnabled()
            list.performScrollToIndex(2)
            compose.onNodeWithTag("ai_rear_remove_0").performClick()
            compose.onNodeWithTag("ai_rear_remove_1").performClick()
            assertEquals(4, draft.value.entries.size)
            list.performScrollToIndex(3)
            capture("launcher-shortcuts-available")
            val search = compose.onNodeWithTag("ai_rear_search")
            search.performTextReplacement(apps[6].component)
            compose.onNodeWithTag("ai_rear_available_${apps[6].component}").performScrollTo().performTouchInput { click(center) }
            assertEquals(5, draft.value.entries.size)
            assertTrue("adding an app should not exit the configuration page", editing.value)
            list.performScrollToIndex(3)
            search.performTextReplacement(apps[7].component)
            scene.recreate()
            install()
            val next = compose.onNodeWithTag("ai_rear_available_${apps[7].component}").assertExists()
            search.assertTextContains(apps[7].component)
            next.performScrollTo().performTouchInput { click(center) }
            assertEquals(6, draft.value.entries.size)
            assertEquals(apps[7].component, draft.value.entries[5].component)
            assertTrue(draft.value.parked(5))
            compose.onNodeWithTag("ai_rear_shortcuts_back").performClick()
            compose.onNodeWithTag("ai_rear_tile_5").performTouchInput { click(center) }
            compose.onNodeWithTag("ai_rear_shortcuts").assertIsDisplayed()

            scene.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var landscape = false
                scene.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            install(dark = true, font = 1.6f)
            compose.onNodeWithTag("ai_rear_shortcuts").performScrollToIndex(0)
            compose.onNodeWithTag("ai_rear_library").assertIsDisplayed()
            capture("launcher-shortcuts-dark-large")
            compose.onNodeWithTag("ai_rear_shortcuts").performScrollToIndex(3)
            compose.onNodeWithTag("ai_rear_search").assertIsDisplayed()
        }
    }

    @Test fun largeLandscapeKeepsActionsAndTheRearShelfCanScrollSelectAndClose() {
        val apps = demoApps()
        val model = studio(apps).copy(work = AiRearStudioWork("林间微光", "11月19日 · 91ae27"),
            installed = true, ready = true)
        var selected: String? = null
        var closed = false
        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use { scene ->
            scene.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var landscape = false
                scene.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            scene.onActivity { activity ->
                present(activity, dark = true, font = 1.6f) {
                    AiRearWallpaperStudio(model, rememberLazyListState(), {}, {}, {}, {}, thumbnail = {})
                }
            }
            compose.onNodeWithTag("ai_rear_canvas").assertIsDisplayed()
            capture("wallpaper-studio-dark-large")
            compose.onNodeWithTag("ai_rear_restore").performScrollTo().assertIsDisplayed()
            scene.onActivity { activity ->
                present(activity, dark = true) {
                    CompositionLocalProvider(LocalDensity provides Density(2.8125f, 1f)) {
                        Box(Modifier.fillMaxSize().background(AppPalette.background()), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(347.dp, 212.dp).testTag("ai_rear_shelf_fixture")) {
                                AiRearAppShelf(RearCastViewport.safe(976, 596, 0, 450, 296, 0, 0, 0),
                                    apps, loadFailed = false, selectionFailed = false, dismiss = { closed = true }) {
                                    selected = it.component
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("ai_rear_shelf_grid").assertIsDisplayed()
            capture("wallpaper-rear-shelf", cropRear = true)
            compose.onNodeWithTag("ai_rear_shelf_${apps.first().component}").performTouchInput { click(center) }
            assertEquals(apps.first().component, selected)
            compose.onNodeWithTag("ai_rear_shelf_grid").performScrollToIndex(apps.lastIndex)
            compose.onNodeWithTag("ai_rear_shelf_${apps.last().component}").assertIsNotEnabled()
            compose.onNodeWithTag("ai_rear_shelf_back").performClick()
            assertEquals(true, closed)
        }
    }

    private fun studio(apps: List<AiRearApp>) = AiRearStudioModel(
        AiRearGrid(apps.take(6).map { it.component }, allApps = true),
        apps.associateBy { it.component }, AiRearWallpaperArt.referenceViewport, background = null, work = null,
        connected = true, installed = false, ready = false, applying = false, preparing = false,
        editingImage = false, allowUnlocked = false, message = "",
    )

    private fun demoApps(): List<AiRearApp> {
        val context = instrument.targetContext
        val locale = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("zh-CN"))
        }
        val catalog = AiRearAppCatalog.list(context.createConfigurationContext(locale))
        val preferred = listOf("com.android.settings", "com.google.android.calculator", "com.google.android.deskclock",
            "com.google.android.apps.maps", "com.android.chrome", "com.google.android.calendar")
        val sorted = catalog.sortedBy { preferred.indexOf(it.component.substringBefore('/')).takeIf { order -> order >= 0 } ?: 20 }
        check(sorted.size >= 6)
        return sorted.take(18).mapIndexed { index, app -> app.copy(canCast = index != sorted.take(18).lastIndex) }
    }

    private fun present(activity: NativeModuleRendererTestHostActivity, dark: Boolean, font: Float = 1f,
        body: @Composable () -> Unit,
    ) {
        activity.prepareMiuixWindow()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val configuration = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = font
            setLocales(LocaleList.forLanguageTags("zh-CN"))
        }
        activity.setContentView(ComposeView(activity.createConfigurationContext(configuration)).apply {
            id = contentId
            setContent { AppTheme { AiRearArtworkScope(body) } }
        })
    }

    private fun capture(name: String, cropRear: Boolean = false) {
        compose.waitForIdle()
        val screenshot = requireNotNull(instrument.uiAutomation.takeScreenshot())
        val area = if (cropRear) Bitmap.createBitmap(screenshot, (screenshot.width - 976) / 2,
            (screenshot.height - 596) / 2, 976, 596) else screenshot
        val width = if (area.width > area.height) 700 else 460
        val preview = Bitmap.createScaledBitmap(area, width, area.height * width / area.width, true)
        try {
            val buffer = ByteArrayOutputStream()
            check(preview.compress(Bitmap.CompressFormat.JPEG, 86, buffer))
            instrument.addResults(Bundle().apply { putString(name, Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)) })
        } finally {
            preview.recycle()
            if (area !== screenshot) area.recycle()
            screenshot.recycle()
        }
    }
}
