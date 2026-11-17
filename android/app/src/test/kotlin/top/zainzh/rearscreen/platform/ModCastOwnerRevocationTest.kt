package top.zainzh.rearscreen.platform

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ModCastOwnerRevocationTest {
    private val target = HostCastTarget("exact", 42, "target.app/target.app.Main", null,
        0, 7, "rear:7", CastMode.VIRTUAL, CastRegion.FULL, 1, null)

    @Test fun explicitModRevocationDisablesActivationAndClearsPendingAndCommittedOwner() {
        val owner = SessionOwner(12001, "remrss.host", "host-cert",
            SessionPrincipal.SignedMod("author-cert", "fixture.mod", "logical-7"))
        val owners = ControlledCastOwnerRegistry().apply { modAuthorizationChanged(owner, true) }
        val backend = RevocationBackend()
        val coordinator = coordinator(owners, backend)
        try {
            val readyEvents = mutableListOf<CastEvent>()
            val committed = coordinator.prepare(owner, request()) as CastPrepareResult.Accepted
            coordinator.commit(owner, committed.ticket.ticketId, committed.ticket.generation,
                UserActivationToken("confirm"), object : CastObserver {
                    override fun onCastEvent(event: CastEvent) { readyEvents += event }
                })
            backend.callback!!(BackendEvent.Ready("backend-session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            assertTrue(readyEvents.single() is CastEvent.Ready)

            val pending = coordinator.prepare(owner, request()) as CastPrepareResult.Accepted
            val order = mutableListOf<String>()
            repeat(2) {
                val submission = ModCastOwnerRevocation.revoke(owner,
                    { actual, authorized -> assertEquals(owner, actual); order += "authority:$authorized"; owners.modAuthorizationChanged(actual, authorized) },
                    { actual -> assertEquals(owner, actual); order += "coordinator"; coordinator.revokeOwner(actual) })
                assertTrue(submission.authorizationRevoked)
                assertTrue(submission.cleanupRequested)
            }

            assertEquals(listOf("authority:false", "coordinator", "authority:false", "coordinator"), order)
            assertFalse(owners.isCurrentForActivation(owner))
            val staleCommit = coordinator.commit(owner, pending.ticket.ticketId, pending.ticket.generation, UserActivationToken("stale"))
            assertTrue("prepared ticket is no longer committable: $staleCommit", staleCommit is CastCommitResult.Rejected)
            assertEquals("already committed session is closed exactly once", 1, backend.closeCalls)
            assertEquals(CastPrepareResult.Rejected(CastFailureCode.OWNER_STALE, false), coordinator.prepare(owner, request()))
        } finally { coordinator.close() }
    }

    @Test fun sdkPackageInvalidationIsExactToInstalledPackageAndSigner() {
        val owner = SessionOwner(12001, "example.sdk", "CERT-A", SessionPrincipal.AndroidClient(
            12001, "example.sdk", "CERT-A", 11L))
        assertFalse(SdkCastIdentityRevocation.shouldRevoke(owner, "other.sdk", null))
        assertFalse(SdkCastIdentityRevocation.shouldRevoke(owner, "example.sdk", "cert-a"))
        assertTrue(SdkCastIdentityRevocation.shouldRevoke(owner, "example.sdk", "CERT-B"))
        assertTrue(SdkCastIdentityRevocation.shouldRevoke(owner, "example.sdk", null))
        val mod = owner.copy(principal = SessionPrincipal.SignedMod("author", "fixture", "logical"))
        assertFalse(SdkCastIdentityRevocation.shouldRevoke(mod, "example.sdk", null))
    }

    private fun coordinator(owners: ControlledCastOwnerRegistry, backend: RevocationBackend) =
        ControlledCastCoordinator(
            targets = object : HostCastTargetResolver {
                override fun resolve(owner: SessionOwner, request: CastTargetRequest) = target
                override fun isCurrent(target: HostCastTarget) = target.sameTarget(this@ModCastOwnerRevocationTest.target)
            },
            authority = owners,
            activations = CastActivationVerifier { _, _, _, _ -> true },
            backend = backend,
            clock = { 100L },
            randomId = { "ticket-${System.nanoTime()}" },
            work = Executor { it.run() }
        )

    private fun request() = CastTargetRequest(CastTargetSelector.CurrentForeground, CastMode.VIRTUAL,
        CastRegion.FULL, 1)

    private fun viewport() = AppliedCastViewport(1, 7, CastRegion.FULL, 440, 0, 0, 1080, 1920,
        1080, 1920, 0, 1, 0, 0, 1080, 1920, 0f, -1f, 1f, 0f, 0f, 0f)

    private class RevocationBackend : ControlledCastBackend {
        var callback: ((BackendEvent) -> Unit)? = null
        var closeCalls = 0
        override fun inspectOccupant(rearDisplayId: Int, rearIdentity: String): CastOccupantIdentity? = null
        override fun canReplace(target: HostCastTarget, occupant: CastOccupantIdentity) = false
        override fun start(operationId: String, owner: CastOwnerKey, generation: Long, target: HostCastTarget,
                           expectedOccupant: CastOccupantIdentity?, isAuthorized: () -> Boolean,
                           claimSideEffect: () -> Boolean, callback: (BackendEvent) -> Unit) {
            this.callback = callback
        }
        override fun abort(operationId: String) = Unit
        override fun query(backendSessionId: String, generation: Long): BackendRuntimeSnapshot? = null
        override fun close(backendSessionId: String, generation: Long): BackendCloseResult {
            closeCalls++
            return BackendCloseResult.Closed
        }
    }
}
