package top.zainzh.rearscreen

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real input dispatch; no Compose idling or set-text actions while the keyboard is open. */
class LandscapeNotificationKeyboardTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrument.uiAutomation
    private val handler = Handler(Looper.getMainLooper())
    private val controllerField = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
    private val pickerField = SettingsController::class.java.getDeclaredField("picker").apply { isAccessible = true }

    private data class Readout(
        val query: String, val keyboard: Boolean, val includeSystem: Boolean, val loading: Boolean,
        val total: Int, val first: Int, val offset: Int, val visible: List<Any>, val width: Int, val height: Int,
    )
    private fun <T> readMain(read: () -> T): T {
        val task = FutureTask(read)
        handler.post(task)
        return task.get(2, TimeUnit.SECONDS)
    }
    private fun read(host: MainActivity): Readout = readMain {
        val picker = pickerField.get(controllerField.get(host)) as AppPickerModel
        val state = picker.renderState()
        val view = host.window.decorView
        Readout(state.search, view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true,
            state.includeSystem, state.loading, state.list.layoutInfo.totalItemsCount,
            state.list.firstVisibleItemIndex, state.list.firstVisibleItemScrollOffset,
            state.list.layoutInfo.visibleItemsInfo.map { it.key }, view.width, view.height)
    }
    private fun await(host: MainActivity, stage: String, ready: (Readout) -> Boolean): Readout {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var last = read(host)
        while (!ready(last) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(80)
            last = read(host)
        }
        assertTrue("$stage: $last", ready(last))
        instrument.addResults(Bundle().apply { putString("landscape_ime_$stage", last.toString()) })
        return last
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).use { it.readBytes().decodeToString().trim() }

    /** Capture the actual display, including the Android input method. */
    private fun capture(name: String) {
        val full = requireNotNull(automation.takeScreenshot())
        val width = minOf(full.width, 960)
        val scaled = Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
        try {
            VisualAuditBitmap.write(instrument, "audit-landscape-ime", name, scaled)
        } finally {
            if (full !== scaled) scaled.recycle()
            full.recycle()
        }
    }

    private fun tree(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root != null) {
            yield(root)
            for (i in 0 until root.childCount) yieldAll(tree(root.getChild(i)))
        }
    }

    private fun control(label: String, editable: Boolean = false, timeout: Long = 8_000): Rect {
        val limit = SystemClock.uptimeMillis() + timeout
        var summary = emptyList<String>()
        while (SystemClock.uptimeMillis() < limit) {
            val nodes = tree(automation.rootInActiveWindow).toList()
            val found = nodes.firstOrNull {
                it.isVisibleToUser && if (editable) it.isEditable else
                    (it.text?.toString() == label || it.contentDescription?.toString() == label)
            }
            if (found != null) {
                val box = Rect().also(found::getBoundsInScreen)
                if (box.width() > 0 && box.height() > 8) return box
            }
            summary = nodes.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.take(20)
            SystemClock.sleep(100)
        }
        error("Visible control not found: $label; $summary")
    }
    private fun tap(area: Rect) { shell("input tap ${area.centerX()} ${area.centerY()}") }
    /** Centers of the English QWERTY keys in the isolated Pixel AVD's 960 px reference frame. */
    private fun softKey(key: Char) {
        val (x, y) = when (key) {
            'c' -> 414 to 316
            'l' -> 868 to 271
            'o' -> 823 to 225
            'k' -> 778 to 271
            'z' -> 232 to 316
            '\b' -> 890 to 316
            else -> error("Unknown key: $key")
        }
        shell("input tap ${x * 5 / 2} ${y * 5 / 2}")
        SystemClock.sleep(100)
    }

    @Test fun keyboardFiltersClearsAndListKeepsScrolling() {
        val previousFont = shell("settings get system font_scale")
        val previousMode = shell("cmd uimode night").substringAfterLast(' ').takeIf { it in setOf("auto", "yes", "no") } ?: "auto"
        val previousIme = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("cmd uimode night yes")
            shell("settings put system font_scale 1.6")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            // Start at the existing route; bottom-tab animation is unrelated to input behavior.
            val route = Intent(instrument.targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NAVIGATION_TARGET, 100)
            ActivityScenario.launch<MainActivity>(route).use { scenario ->
                lateinit var host: MainActivity
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                val deadline = SystemClock.uptimeMillis() + 10_000
                var stable = false
                while (!stable && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { host = it }
                    stable = readMain {
                        host.resources.configuration.let {
                            it.orientation == Configuration.ORIENTATION_LANDSCAPE && it.fontScale >= 1.59f &&
                                it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                        }
                    }
                    if (!stable) SystemClock.sleep(80)
                }
                assertTrue("dark 1.6× landscape required", stable)
                fun label(key: String) = NativeStrings.get(host, key)
                try {
                    val search = control(label("search_apps"), editable = true)
                    val system = control(label("system_apps"))
                    await(host, "initial") { !it.loading && it.query.isEmpty() && !it.keyboard && it.width > it.height }
                    tap(system)
                    val catalog = await(host, "system_apps") { it.includeSystem && it.total > 12 }
                    tap(search)
                    val keyboard = await(host, "keyboard_open") { it.keyboard }
                    assertTrue("Expected a 2400 × 1080 Gboard fixture", keyboard.width == 2400 &&
                        keyboard.height == 1080 && shell("settings get secure default_input_method")
                            .startsWith("com.google.android.inputmethod.latin/"))
                    SystemClock.sleep(750) // Wait for the real IME window's entrance animation.
                    capture("01-keyboard-open")
                    "clock".forEach(::softKey)
                    await(host, "typed") { it.query == "clock" && it.keyboard && it.total in 3 until catalog.total }
                    SystemClock.sleep(250) // Let Gboard's last key preview disappear for the evidence frame.
                    capture("02-typed-clock")
                    softKey('z')
                    await(host, "no_match") { it.query == "clockz" && it.total == 3 }
                    repeat(6) { softKey('\b') }
                    await(host, "cleared") { it.query.isEmpty() && it.keyboard && it.total == catalog.total }
                    capture("03-cleared-with-keyboard")
                    shell("input keyevent KEYCODE_BACK")
                    await(host, "keyboard_closed") { !it.keyboard && it.query.isEmpty() && it.total == catalog.total }
                    SystemClock.sleep(750) // The IME surface can still accept touches during its exit animation.
                    val restored = read(host)
                    val x = restored.width / 2
                    shell("input swipe $x ${restored.height * 80 / 100} $x ${restored.height * 27 / 100} 470")
                    await(host, "scrolled") { !it.keyboard && it.query.isEmpty() &&
                        (it.first > restored.first || it.first == restored.first && it.offset > restored.offset) &&
                        it.visible.any { key -> key.toString().contains('.') } }
                    SystemClock.sleep(250)
                    capture("04-restored-list-scrolled")
                } catch (failure: Throwable) {
                    runCatching { capture("failure-last-frame") }
                    throw failure
                }
            }
        } finally {
            shell("cmd uimode night $previousMode")
            shell(if (previousFont == "null") "settings delete system font_scale" else "settings put system font_scale $previousFont")
            shell(if (previousIme == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $previousIme")
        }
    }
}
