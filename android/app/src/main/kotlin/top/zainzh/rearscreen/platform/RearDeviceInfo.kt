package top.zainzh.rearscreen.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import top.zainzh.rearscreen.RearDisplay
import top.zainzh.rearscreen.TaskServiceManager
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

private fun powerState(state: Int) = when (state) {
    Display.STATE_ON -> "ON"
    Display.STATE_OFF -> "OFF"
    Display.STATE_DOZE -> "DOZE"
    Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
    Display.STATE_VR -> "VR"
    else -> "UNKNOWN"
}

private fun Display?.physicalMode(): Pair<Int, Int>? = this?.takeIf { it.isValid }?.let { panel ->
    runCatching { panel.mode.let { it.physicalWidth to it.physicalHeight } }.getOrNull()
        ?.takeIf { (width, height) -> width > 0 && height > 0 }
}

/** The backend uses DisplayInfo.logicalWidth/Height, without the host app's window bounds. */
internal fun rearDeviceInfoFromInventory(snapshot: JSONObject, physicalMode: Pair<Int, Int>?): RearDeviceInfo {
    val id = snapshot.optInt("selectedId", -1)
    val unavailable = RearDeviceInfo(id)
    if (id <= 0) return unavailable
    val identity = snapshot.optString("selectedUnique")
    val rows = snapshot.optJSONArray("displays") ?: return unavailable
    val panel = (0 until rows.length()).mapNotNull(rows::optJSONObject)
        .singleOrNull { it.optInt("id", -1) == id } ?: return unavailable
    if (identity.isBlank() || panel.optString("unique") != identity) return unavailable
    val dimensions = runCatching {
        RearDisplayDimensions(physicalMode?.first, physicalMode?.second,
            panel.getInt("width"), panel.getInt("height"), panel.getInt("density"), panel.getInt("rotation"))
    }.getOrNull() ?: return unavailable
    return RearDeviceInfo(id, dimensions, powerState(panel.optInt("state", Display.STATE_UNKNOWN)))
}

/** Public metrics may repeat the primary window bounds on this ROM, even for a valid rear Display. */
internal fun ambiguousPublicSize(
    reported: Pair<Int, Int>, primary: Pair<Int, Int>?, rearMode: Pair<Int, Int>?, rotation: Int,
): Boolean {
    if (reported != primary) return false
    val rotatedMode = rearMode?.let { (width, height) -> if (rotation % 2 == 0) width to height else height to width }
    return reported != rotatedMode
}

@Suppress("DEPRECATION")
internal fun readRearDeviceInfo(context: Context): RearDeviceInfo {
    val manager = context.applicationContext.getSystemService(DisplayManager::class.java)
    val snapshot = TaskServiceManager.readConnectedRearDisplayState()
    if (snapshot != null) {
        val inventory = runCatching { JSONObject(snapshot) }.getOrNull() ?: return RearDeviceInfo(-1)
        val id = inventory.optInt("selectedId", -1)
        val mode = if (id > 0) manager?.getDisplay(id).physicalMode() else null
        return rearDeviceInfoFromInventory(inventory, mode)
    }

    // Keep public-panel reads working when the privileged backend is not connected.
    val id = runCatching { RearDisplay.id() }.getOrDefault(-1)
    val unavailable = RearDeviceInfo(id)
    if (id <= 0 || manager == null) return unavailable
    val panel = manager.getDisplay(id)?.takeIf { it.displayId == id && it.isValid } ?: return unavailable
    return runCatching {
        val rear = DisplayMetrics().also(panel::getRealMetrics)
        val mode = panel.physicalMode()
        val primary = manager.getDisplay(Display.DEFAULT_DISPLAY)?.let { display ->
            DisplayMetrics().also(display::getRealMetrics).let { it.widthPixels to it.heightPixels }
        }
        val rotation = panel.rotation
        if (ambiguousPublicSize(rear.widthPixels to rear.heightPixels, primary, mode, rotation)) return unavailable
        RearDeviceInfo(id, RearDisplayDimensions(mode?.first, mode?.second,
            rear.widthPixels, rear.heightPixels, rear.densityDpi, rotation), powerState(panel.state))
    }.getOrDefault(unavailable)
}
