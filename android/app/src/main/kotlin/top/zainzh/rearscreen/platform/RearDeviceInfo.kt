package top.zainzh.rearscreen.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import top.zainzh.rearscreen.RearDisplay
import top.zainzh.rearscreen.platform.ipc.RearDisplayDimensions

/** Physical identity and readable metrics are independent; a private panel can be known but unreadable. */
internal data class RearDeviceInfo(
    val displayId: Int,
    val dimensions: RearDisplayDimensions? = null,
    val state: String? = null,
) {
    fun toDisplayJson(): JSONObject? = dimensions?.let {
        JSONObject().put("displayId", displayId).put("dimensions", it.toJson()).put("state", state)
    }
}

internal fun readRearDeviceInfo(context: Context): RearDeviceInfo {
    val id = runCatching { RearDisplay.id() }.getOrDefault(-1)
    if (id <= 0) return RearDeviceInfo(id)
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(id)
        ?.takeIf { it.isValid } ?: return RearDeviceInfo(id)
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") display.getRealMetrics(metrics)
    // A logical size can reflect wm overrides; a mode size is the panel's physical raster.
    val mode = runCatching { display.mode }.getOrNull()
    val dimensions = runCatching {
        RearDisplayDimensions(
            mode?.physicalWidth?.takeIf { it > 0 }, mode?.physicalHeight?.takeIf { it > 0 },
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation
        )
    }.getOrNull() ?: return RearDeviceInfo(id)
    return RearDeviceInfo(id, dimensions, when (display.state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> "UNKNOWN"
    })
}
