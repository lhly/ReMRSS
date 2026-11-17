package top.zainzh.rearscreen.platform.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import top.zainzh.rearscreen.platform.CapabilityAvailability

class CastCapabilityStateTest {
    @Test fun reportsAvailableOnlyWhenBackendRearAndModeAreAllAvailableWithoutGrant() {
        val state = castModeCapabilityState(
            "rear.cast.direct_physical", backendConnected = true, deviceAvailable = true,
            backendUnavailableState = CapabilityAvailability.AVAILABLE, modeAvailable = true
        )
        assertEquals(CapabilityAvailability.AVAILABLE, state.availability)
        assertFalse("discovery is not user authorization", state.granted)
        assertNull(state.reasonCode)
    }

    @Test fun preservesBackendConnectingAuthorizationAndUnavailableSemantics() {
        listOf(
            CapabilityAvailability.CONNECTING,
            CapabilityAvailability.REQUIRES_AUTHORIZATION,
            CapabilityAvailability.BACKEND_UNAVAILABLE,
        ).forEach { backendState ->
            val state = castModeCapabilityState(
                "rear.cast.direct_physical", backendConnected = false, deviceAvailable = true,
                backendUnavailableState = backendState, modeAvailable = false
            )
            assertEquals(backendState, state.availability)
            assertFalse(state.granted)
        }
    }

    @Test fun knownRearUnavailablePreventsCapabilityEvenWhenBackendModeExists() {
        val state = castModeCapabilityState(
            "rear.cast.direct_physical", backendConnected = true, deviceAvailable = false,
            backendUnavailableState = CapabilityAvailability.AVAILABLE, modeAvailable = true
        )
        assertEquals(CapabilityAvailability.DISPLAY_UNAVAILABLE, state.availability)
        assertEquals("display_unavailable", state.reasonCode)
        assertFalse(state.granted)
    }

    @Test fun modeMissingFromConnectedBackendIsNotAdvertised() {
        val state = castModeCapabilityState(
            "rear.cast.virtual", backendConnected = true, deviceAvailable = true,
            backendUnavailableState = CapabilityAvailability.AVAILABLE, modeAvailable = false
        )
        assertEquals(CapabilityAvailability.BACKEND_UNAVAILABLE, state.availability)
        assertEquals("backend_unavailable", state.reasonCode)
        assertFalse(state.granted)
    }
}
