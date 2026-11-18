package top.zainzh.rearscreen.platform.mods

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.zainzh.rearscreen.platform.RearCapabilityCore
import top.zainzh.rearscreen.platform.RearDeviceInfo
import top.zainzh.rearscreen.platform.RearViewportSnapshot
import top.zainzh.rearscreen.platform.SessionOwner

internal object ModRearInfoBindings {
    const val CAPABILITY = "rear.info"
    val paths = setOf(
        "rear.info.status", "rear.info.state", "rear.info.densityDpi", "rear.info.rotation",
        "rear.info.physicalModePx.width", "rear.info.physicalModePx.height",
        "rear.info.logicalDisplayPx.width", "rear.info.logicalDisplayPx.height",
        "rear.viewport.status", "rear.viewport.revision", "rear.viewport.region",
        "rear.viewport.densityDpi", "rear.viewport.displayRotation", "rear.viewport.contentRotation",
        "rear.viewport.windowPx.width", "rear.viewport.windowPx.height",
        "rear.viewport.windowDp.width", "rear.viewport.windowDp.height",
        "rear.viewport.contentBoundsPx.left", "rear.viewport.contentBoundsPx.top",
        "rear.viewport.contentBoundsPx.right", "rear.viewport.contentBoundsPx.bottom",
        "rear.viewport.contentBoundsPx.width", "rear.viewport.contentBoundsPx.height",
        "rear.viewport.cutoutKnowledge",
    )

    fun declared(mod: VerifiedModPackage): Boolean =
        mod.requiredCapabilities.any { it.name == CAPABILITY } || mod.optionalCapabilities.any { it.name == CAPABILITY }

    fun withPreviewData(mod: VerifiedModPackage, data: JSONObject): JSONObject =
        if (!declared(mod)) data else merge(data, JSONObject()
            .put("info", JSONObject().put("status", "PREVIEW"))
            .put("viewport", JSONObject().put("status", "PREVIEW")))

    fun merge(data: JSONObject, rear: JSONObject) = JSONObject().apply {
        data.keys().forEach { key -> put(key, data.get(key)) }
        put("rear", rear)
    }

    /** The renderer is the only consumer; none of these values enter reducer input or persistent state. */
    fun encode(sample: Read, rear: Boolean, viewport: RearViewportSnapshot?): JSONObject {
        fun status(value: String) = JSONObject().put("status", value)
        val device = when (sample) {
            Read.Pending -> status("PENDING")
            Read.Denied -> status("NOT_GRANTED")
            is Read.Granted -> when {
                sample.device.displayId <= 0 -> status("DISPLAY_UNAVAILABLE")
                sample.device.dimensions == null -> status("INFO_UNAVAILABLE")
                else -> sample.device.dimensions.toJson().put("status", "AVAILABLE").put("state", sample.device.state)
            }
        }
        val measured = when {
            sample == Read.Denied -> status("NOT_GRANTED")
            sample == Read.Pending -> status("PENDING")
            !rear -> status("NOT_APPLICABLE")
            viewport == null -> status("NOT_READY")
            else -> with(viewport) {
                status("READY").put("revision", revision).put("region", region.name).put("densityDpi", densityDpi)
                    .put("displayRotation", displayRotation).put("contentRotation", contentRotation)
                    .put("windowPx", JSONObject().put("width", windowPx.width).put("height", windowPx.height))
                    .put("windowDp", JSONObject().put("width", windowDp.width).put("height", windowDp.height))
                    .put("contentBoundsPx", JSONObject().put("left", contentBoundsPx.left).put("top", contentBoundsPx.top)
                        .put("right", contentBoundsPx.right).put("bottom", contentBoundsPx.bottom)
                        .put("width", contentBoundsPx.right - contentBoundsPx.left)
                        .put("height", contentBoundsPx.bottom - contentBoundsPx.top))
                    .put("cutoutKnowledge", cutoutKnowledge.name)
            }
        }
        return JSONObject().put("info", device).put("viewport", measured)
    }

    sealed interface Read {
        data object Pending : Read
        data object Denied : Read
        data class Granted(val device: RearDeviceInfo) : Read
    }
}

@Composable
internal fun withModRearInfo(
    context: Context,
    mod: VerifiedModPackage,
    data: JSONObject,
    visible: Boolean,
    rear: Boolean = false,
    owner: SessionOwner? = null,
    viewport: RearViewportSnapshot? = null,
): JSONObject {
    if (!visible || !ModRearInfoBindings.declared(mod)) return data
    val grantRevision by ModPackageStore.rearInfoChanges.collectAsState()
    var sample by remember(mod, owner, grantRevision) {
        mutableStateOf<ModRearInfoBindings.Read>(ModRearInfoBindings.Read.Pending)
    }
    LaunchedEffect(mod, owner, grantRevision, context) {
        val core = RearCapabilityCore.get(context)
        if (!withContext(Dispatchers.IO) { core.canReadModRearInfo(mod, owner) }) {
            sample = ModRearInfoBindings.Read.Denied
            return@LaunchedEffect
        }
        rearDisplayChanges(context).conflate().collectLatest {
            sample = withContext(Dispatchers.IO) {
                if (!core.canReadModRearInfo(mod, owner)) ModRearInfoBindings.Read.Denied
                else ModRearInfoBindings.Read.Granted(core.readRearDeviceInfo())
            }
        }
    }
    return remember(data, sample, rear, viewport) {
        ModRearInfoBindings.merge(data, ModRearInfoBindings.encode(sample, rear, viewport))
    }
}

private fun rearDisplayChanges(context: Context) = callbackFlow {
    val manager = context.getSystemService(DisplayManager::class.java)
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) trySend(Unit)
        }
    }
    manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    trySend(Unit)
    awaitClose { manager?.unregisterDisplayListener(listener) }
}
