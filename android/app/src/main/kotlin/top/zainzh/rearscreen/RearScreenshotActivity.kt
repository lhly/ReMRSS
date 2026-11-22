package top.zainzh.rearscreen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.concurrent.Executors

/** Standalone screenshot settings entry; Debug can provide a fixture shell model. */
open class RearScreenshotActivity : ComponentActivity() {
    protected open fun templateDevice(): String = android.os.Build.DEVICE
    private lateinit var page: RearScreenshotPage

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = RearScreenshotPage(this, state, ::templateDevice)
        miuixContent { AppPageWindow { page.Content(::finish) } }
    }

    override fun onSaveInstanceState(out: Bundle) {
        page.save(out)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() { page.close(); super.onDestroy() }
}

/** Settings and preview share the same template parser and compositor as real exports. */
internal class RearScreenshotPage(
    private val host: ComponentActivity,
    saved: Bundle?,
    private val templateDevice: () -> String = { android.os.Build.DEVICE },
) {
    private fun getString(id: Int) = host.getString(id)
    private fun getString(id: Int, vararg args: Any) = host.getString(id, *args)
    private fun supported() = RearShellRepository.resourceId(templateDevice(), "BK") != 0L
    private fun savedOptions() = RearScreenshotPreferences.readForDevice(host, templateDevice())
    private val worker = Executors.newSingleThreadExecutor()
    private var revision by mutableIntStateOf(0)
    private data class PreviewFrame(val bitmap: Bitmap, val color: String, val style: String)
    private var preview by mutableStateOf<PreviewFrame?>(null)
    private var loading by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var picker by mutableStateOf(saved?.getString("rear_screenshot_picker"))
    private var request = 0
    private val list = saved.listState("rear_screenshot_list")
    private var closed = false


    @Composable fun Content(back: () -> Unit, active: Boolean = true) {
        if (active) LaunchedEffect(Unit) {
            revision++
            if (savedOptions().shell && supported()) loadPreview()
        }
        val options = remember(revision) { savedOptions() }
        AppPageContent(getString(R.string.rear_screenshot_title), back, "screenshot_back", scrollState = list) { pageModifier, headerInset ->
            LazyColumn(state = list, modifier = pageModifier.testTag("screenshot_scroll"),
                contentPadding = PaddingValues(20.dp, headerInset + 8.dp, 20.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("switch") {
                    AppCard {
                        AppToggle(getString(R.string.rear_screenshot_shell),
                            getString(if (supported()) R.string.rear_screenshot_shell_help else R.string.rear_screenshot_unsupported),
                            options.shell, enabled = supported(), onChange = {
                                RearScreenshotPreferences.preferences(host).edit().putBoolean("shell", it).apply()
                                revision++
                                if (it) loadPreview() else { request++; preview = null; loading = false; failed = false }
                            })
                    }
                }
                if (options.shell && supported()) {
                    item("options") {
                        AppCard {
                            AppRow(getString(R.string.rear_screenshot_style), styleLabel(options.style),
                                Modifier.testTag("screenshot_style"), action = { picker = "style" })
                            AppDivider()
                            AppRow(getString(R.string.rear_screenshot_color), colorLabel(options.color),
                                Modifier.testTag("screenshot_color"), action = { picker = "color" })
                            AppDivider()
                            AppToggle(getString(R.string.rear_screenshot_keep_original), getString(R.string.rear_screenshot_keep_original_help),
                                options.keepOriginal, onChange = {
                                    RearScreenshotPreferences.preferences(host).edit().putBoolean("keep_original", it).apply()
                                    revision++
                                })
                        }
                    }
                    item("preview") {
                        AppCard {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(getString(R.string.rear_screenshot_preview), fontSize = 16.sp, color = AppPalette.text(),
                                    modifier = Modifier.align(Alignment.Start).semantics { heading() })
                                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                                    preview?.let { frame ->
                                        Image(frame.bitmap.asImageBitmap(), getString(R.string.rear_screenshot_preview_description,
                                            colorLabel(frame.color), styleLabel(frame.style)),
                                            Modifier.fillMaxSize().testTag("screenshot_preview"))
                                    }
                                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                                }
                                if (loading || failed) {
                                    Text(getString(if (loading) R.string.rear_screenshot_preparing else R.string.rear_screenshot_preview_failed),
                                        fontSize = 13.sp, color = AppPalette.secondary(),
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                    if (failed) TextButton(getString(R.string.rear_screenshot_retry), ::loadPreview, minHeight = 48.dp)
                                }
                            }
                        }
                    }
                    item("offline") {
                        Text(getString(R.string.rear_screenshot_download_help), fontSize = 13.sp, color = AppPalette.secondary())
                    }
                }
                item("save") {
                    Text(getString(R.string.rear_screenshot_save_help), fontSize = 13.sp, color = AppPalette.secondary())
                }
            }
        }
        WindowDialog(show = active && picker != null, title = getString(if (picker == "color") R.string.rear_screenshot_color else R.string.rear_screenshot_style),
            onDismissRequest = { picker = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .6f })
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val values = if (picker == "color") RearShellCatalog.colors(templateDevice()).toList() else listOf("full", "half")
                values.forEach { value ->
                    val selected = value == if (picker == "color") options.color else options.style
                    TextButton(if (picker == "color") colorLabel(value) else styleLabel(value), {
                        val key = picker ?: return@TextButton
                        picker = null
                        RearScreenshotPreferences.preferences(host).edit().putString(key, value).apply()
                        revision++; loadPreview()
                    }, Modifier.fillMaxWidth().semantics { this.selected = selected }, minHeight = 48.dp,
                        colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                }
            }
        }
    }
    private fun styleLabel(style: String) = getString(if (style == "half") R.string.rear_screenshot_half else R.string.rear_screenshot_full)
    private fun colorLabel(color: String) = getString(when (color) {
        "WH" -> R.string.rear_screenshot_white; "PU" -> R.string.rear_screenshot_purple
        "LG" -> R.string.rear_screenshot_green; "PK" -> R.string.rear_screenshot_pink
        "DB" -> R.string.rear_screenshot_deep_blue; "RD" -> R.string.rear_screenshot_red
        "CY" -> R.string.rear_screenshot_transparent_red; else -> R.string.rear_screenshot_black
    })
    private fun loadPreview() {
        val token = ++request
        val options = savedOptions()
        loading = true; failed = false
        worker.execute {
            val result = runCatching {
                RearShellRepository.loadForDevice(host.applicationContext, templateDevice(), options.style, options.color).use { asset ->
                    val g = asset.geometry
                    val sample = Bitmap.createBitmap(g.width.toInt(), g.height.toInt(), Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(sample)
                        canvas.drawColor(android.graphics.Color.rgb(36, 107, 223))
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE; textSize = 68f; textAlign = Paint.Align.CENTER
                        }
                        // Label the unobscured side of the physical rear display.
                        canvas.drawText("ReMRSS", sample.width * .65f, sample.height * .55f, paint)
                        val composed = RearScreenshotPipeline.compose(sample, 0, asset)
                        try { Bitmap.createScaledBitmap(composed, (600f * composed.width / composed.height).toInt(), 600, true) }
                        finally { composed.recycle() }
                    } finally { sample.recycle() }
                }
            }
            result.exceptionOrNull()?.let { android.util.Log.w("RearScreenshot", "Frame preview unavailable", it) }
            host.runOnUiThread {
                if (!closed && !host.isDestroyed && !host.isFinishing && token == request) {
                    result.getOrNull()?.let { preview = PreviewFrame(it, options.color, options.style) }
                    failed = result.isFailure; loading = false
                } else result.getOrNull()?.recycle()
            }
        }
    }

    fun save(out: Bundle) {
        out.saveList("rear_screenshot_list", list)
        out.putString("rear_screenshot_picker", picker)
    }

    fun close() {
        closed = true
        request++
        worker.shutdownNow()
    }
}
