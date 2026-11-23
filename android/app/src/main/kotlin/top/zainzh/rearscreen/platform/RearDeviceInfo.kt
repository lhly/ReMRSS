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

/** Rejects dimensions whose display-bound resource context does not match the selected panel. */
internal fun rearDeviceInfoForMetrics(
    selectedDisplayId: Int,
    metricsDisplayObjectId: Int,
    metricsResourceDisplayId: Int,
    dimensions: RearDisplayDimensions?,
    state: String?,
): RearDeviceInfo = if (selectedDisplayId <= 0 ||
    selectedDisplayId != metricsDisplayObjectId || selectedDisplayId != metricsResourceDisplayId
) {
    RearDeviceInfo(selectedDisplayId)
} else {
    RearDeviceInfo(selectedDisplayId, dimensions, state)
}

internal fun readRearDeviceInfo(context: Context): RearDeviceInfo {
    val id = runCatching { RearDisplay.id() }.getOrDefault(-1)
    if (id <= 0) return RearDeviceInfo(id)
    val selectedDisplay = context.getSystemService(DisplayManager::class.java)?.getDisplay(id)
        ?.takeIf { it.isValid } ?: return RearDeviceInfo(id)

    // Display.getRealMetrics may apply max bounds from the Resources attached to its Display.
    // A default-display Context can therefore yield D0 bounds even for a Display object with D1's
    // ID. Bind the Context to the selected panel before asking DisplayManager for its metrics.
    val displayContext = runCatching { context.createDisplayContext(selectedDisplay) }.getOrNull()
        ?: return RearDeviceInfo(id)
    val metricsDisplay = displayContext.getSystemService(DisplayManager::class.java)?.getDisplay(id)
        ?.takeIf { it.isValid && it.displayId == id }
        ?: return RearDeviceInfo(id)
    val metricsResourceDisplayId = displayContext.display?.displayId ?: return RearDeviceInfo(id)
    if (metricsResourceDisplayId != id) return RearDeviceInfo(id)

    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") metricsDisplay.getRealMetrics(metrics)
    // A logical size can reflect wm overrides; a mode size is the panel's physical raster.
    val mode = runCatching { metricsDisplay.mode }.getOrNull()
    val dimensions = runCatching {
        RearDisplayDimensions(
            mode?.physicalWidth?.takeIf { it > 0 }, mode?.physicalHeight?.takeIf { it > 0 },
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, metricsDisplay.rotation
        )
    }.getOrNull() ?: return RearDeviceInfo(id)
    val state = when (metricsDisplay.state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> "UNKNOWN"
    }
    return rearDeviceInfoForMetrics(id, metricsDisplay.displayId, metricsResourceDisplayId, dimensions, state)
}
