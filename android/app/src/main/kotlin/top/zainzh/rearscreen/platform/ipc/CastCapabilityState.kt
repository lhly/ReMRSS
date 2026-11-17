package top.zainzh.rearscreen.platform.ipc

import top.zainzh.rearscreen.platform.CapabilityAvailability
import top.zainzh.rearscreen.platform.CapabilityState

/**
 * Describes runtime mode availability without granting a caller permission or implying an
 * operation has started. The scene snapshot is the single source for backend/power discovery.
 */
internal fun castModeCapabilityState(
    name: String,
    backendConnected: Boolean,
    deviceAvailable: Boolean,
    backendUnavailableState: CapabilityAvailability,
    modeAvailable: Boolean,
): CapabilityState {
    val availability = when {
        !backendConnected -> backendUnavailableState.takeIf { it != CapabilityAvailability.AVAILABLE }
            ?: CapabilityAvailability.BACKEND_UNAVAILABLE
        !deviceAvailable -> CapabilityAvailability.DISPLAY_UNAVAILABLE
        modeAvailable -> CapabilityAvailability.AVAILABLE
        else -> CapabilityAvailability.BACKEND_UNAVAILABLE
    }
    return CapabilityState(
        name = name,
        version = 1,
        availability = availability,
        granted = false,
        reasonCode = if (availability == CapabilityAvailability.AVAILABLE) null else availability.name.lowercase()
    )
}
