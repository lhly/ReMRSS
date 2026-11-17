package dev.remrss.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRequestEpochTerminalGateTest {
    @Test fun epochDisconnectWinsOnceAndLateReadyCannotOverwriteIt() {
        val epoch = Any()
        val gate = CastRequestEpochTerminalGate(epoch)
        val delivered = mutableListOf<String>()

        assertTrue(gate.terminate(epoch) { delivered += "SERVICE_DISCONNECTED" })
        assertFalse(gate.dispatch(epoch) { delivered += "READY" })
        assertFalse(gate.terminate(epoch) { delivered += "second terminal" })

        assertEquals(listOf("SERVICE_DISCONNECTED"), delivered)
    }

    @Test fun eventFromAnotherEpochCannotCompleteThisRequest() {
        val requestEpoch = Any()
        val gate = CastRequestEpochTerminalGate(requestEpoch)
        val delivered = mutableListOf<String>()

        assertFalse(gate.dispatch(Any()) { delivered += "stale progress" })
        assertFalse(gate.terminate(Any()) { delivered += "stale ready" })
        assertTrue(gate.dispatch(requestEpoch) { delivered += "WAITING_CONFIRMATION" })
        assertTrue(gate.terminate(requestEpoch) { delivered += "READY" })
        assertEquals(listOf("WAITING_CONFIRMATION", "READY"), delivered)
    }
}
