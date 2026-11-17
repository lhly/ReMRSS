package top.zainzh.rearscreen.platform.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCastActivationGateTest {
    @Test fun exactTicketDeadlineWinsOnceAndLateConfirmationCannotCommit() {
        val connection = Any()
        val gate = PendingCastActivationGate(connection, expiresAtElapsedRealtimeMs = 45_000L)
        val terminal = mutableListOf<String>()

        assertTrue(gate.isOpenFor(connection, 44_999L))
        val expired = gate.expire(connection, 45_000L)
        if (expired == PendingCastActivationGate.Claim.EXPIRED) terminal += "E_TICKET_STALE"
        assertEquals(PendingCastActivationGate.Claim.EXPIRED, expired)
        val lateCommit = gate.claim(connection, 45_001L)
        if (lateCommit == PendingCastActivationGate.Claim.CLAIMED) terminal += "COMMIT_ACCEPTED"
        assertEquals(PendingCastActivationGate.Claim.STALE, lateCommit)
        assertEquals(listOf("E_TICKET_STALE"), terminal)
    }

    @Test fun wrongBinderEpochCannotConsumeConfirmationAndOwnerCanCancelOnce() {
        val connection = Any()
        val gate = PendingCastActivationGate(connection, expiresAtElapsedRealtimeMs = 20_000L)
        assertEquals(PendingCastActivationGate.Claim.STALE, gate.claim(Any(), 1_000L))
        assertTrue(gate.isOpenFor(connection, 1_000L))
        assertEquals(PendingCastActivationGate.Claim.CLAIMED, gate.claim(connection, 1_001L))
        assertEquals(PendingCastActivationGate.Claim.STALE, gate.expire(connection, 20_001L))
    }
}
