package top.zainzh.rearscreen.platform

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ControlledCastCoordinatorTest {
    private val owner = SessionOwner(12001, "example.client", "cert", SessionPrincipal.AndroidClient(
        12001, "example.client", "cert", 7L))
    private val ownerB = SessionOwner(12002, "example.writer", "cert-b", SessionPrincipal.AndroidClient(
        12002, "example.writer", "cert-b", 8L))
    private val ownerC = SessionOwner(12003, "example.viewer", "cert-c", SessionPrincipal.AndroidClient(
        12003, "example.viewer", "cert-c", 9L))
    private val target = HostCastTarget("proof", 41, "example.target/.Main", null,
        0, 7, "rear:7", CastMode.VIRTUAL, CastRegion.FULL, 0, null)
    private val request = CastTargetRequest(CastTargetSelector.CurrentForeground, CastMode.VIRTUAL,
        CastRegion.FULL, 0)

    @Test fun supersededRequestEpochDoesNotConsumeActivationOrStartBackend() {
        val backend = FakeBackend()
        val queued = ArrayDeque<Runnable>()
        var current = true
        var activationConsumed = false
        val coordinator = coordinator(backend,
            activation = { _, _, _, _ -> activationConsumed = true; true },
            work = Executor { queued.add(it) })
        val events = mutableListOf<CastEvent>()
        val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
        try {
            assertTrue(coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("old-request"), object : CastObserver {
                    override fun onCastEvent(event: CastEvent) { events.add(event) }
                }, requestIsCurrent = { current }) is CastCommitResult.Accepted)
            current = false
            queued.removeFirst().run()
            assertFalse("replacement must not consume the old activation", activationConsumed)
            assertEquals("stale epoch is rejected before backend entry", 0, backend.startCalls)
            assertEquals(CastFailureCode.TICKET_STALE,
                events.filterIsInstance<CastEvent.CommitFailed>().single().failure.code)
        } finally { coordinator.close() }
    }

    @Test fun requestRevocationAtSideEffectGateAndLateReadyCompensateExactBackendSession() {
        val backend = FakeBackend()
        val events = mutableListOf<CastEvent>()
        val coordinator = coordinator(backend)
        var current = true
        try {
            val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("request"), object : CastObserver {
                    override fun onCastEvent(event: CastEvent) { events.add(event) }
                }, requestIsCurrent = { current })
            assertEquals(1, backend.startCalls)
            current = false
            assertFalse("backend authorization observes supersession", backend.authorizedCheck?.invoke() == true)
            assertFalse("actual side-effect claim observes supersession", backend.claimCheck?.invoke() == true)

            backend.callback!!(BackendEvent.Ready("late-session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            assertEquals("late exact session is compensated", "late-session", backend.abortedOperationId)
            assertTrue("stale request never receives READY", events.none { it is CastEvent.Ready })
            assertTrue(events.filterIsInstance<CastEvent.CommitFailed>().any {
                it.failure.code == CastFailureCode.TICKET_STALE
            })
            assertTrue(coordinator.query(owner, CastSessionHandle(prepared.ticket.ticketId,
                prepared.ticket.generation)) is CastQueryResult.Rejected)
        } finally { coordinator.close() }
    }

    @Test fun prepareIsPreviewOnlyAndHandleWaitsForOutputAndInputReady() {
        val backend = FakeBackend()
        var activationConsumed = false
        val events = mutableListOf<CastEvent>()
        val coordinator = coordinator(backend, activation = { _, _, _, _ -> activationConsumed = true; true })
        try {
            val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            assertEquals(0, backend.startCalls)
            assertEquals(0, backend.abortCalls)

            assertTrue(coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("one-shot"), object : CastObserver {
                    override fun onCastEvent(event: CastEvent) { events.add(event) }
                }) is CastCommitResult.Accepted)
            assertTrue(activationConsumed)
            assertEquals(1, backend.startCalls)
            assertTrue(events.isEmpty())

            backend.callback!!(BackendEvent.Ready("session", target, outputReady = true, inputReady = false,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            assertTrue("A partial backend must not produce a caller handle",
                events.none { it is CastEvent.Ready })
            assertTrue(events.any { it is CastEvent.CommitFailed })
            assertEquals(1, backend.abortCalls)
        } finally { coordinator.close() }
    }

    @Test fun commitRechecksOccupancyBeforeStartingAndRejectsStaleActivation() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend, activation = { _, _, _, _ -> false })
        try {
            val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            backend.occupant = CastOccupantIdentity(CastMode.VIRTUAL, "other", "old", 9, 2, 22,
                31, "reader", "other/.Activity", 7, "rear:7")
            coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("bad"))
            assertEquals("occupant drift must reject before backend start", 0, backend.startCalls)
        } finally { coordinator.close() }
    }

    @Test fun oldConnectionCannotCommitPreparedPreview() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        try {
            val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            val replacementConnection = owner.copy(principal = SessionPrincipal.AndroidClient(
                12001, "example.client", "cert", 8L))
            val result = coordinator.commit(replacementConnection, prepared.ticket.ticketId,
                prepared.ticket.generation, UserActivationToken("token"))
            assertTrue(result is CastCommitResult.Rejected)
            assertEquals(0, backend.startCalls)
        } finally { coordinator.close() }
    }

    @Test fun onlyExactFullyReadyBackendCanIssueHandle() {
        val backend = FakeBackend()
        val events = mutableListOf<CastEvent>()
        val coordinator = coordinator(backend)
        try {
            val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("valid"), object : CastObserver {
                    override fun onCastEvent(event: CastEvent) { events.add(event) }
                })
            backend.callback!!(BackendEvent.Ready("session", target, outputReady = true, inputReady = true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            val ready = events.filterIsInstance<CastEvent.Ready>().single()
            assertEquals(prepared.ticket.generation, ready.handle.generation)
            assertTrue(target.sameTarget(ready.target))
            assertEquals(1L, ready.appliedViewport?.revision)
        } finally { coordinator.close() }
    }

    @Test fun physicalReplaceReadyClosesAAndInstallsBAtomically() {
        val backend = FakeBackend()
        val physicalTarget = HostCastTarget("physical", 81, "reader/.Main", null,
            0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
        var resolvedTarget = physicalTarget
        val coordinator = ControlledCastCoordinator(
            targets = object : HostCastTargetResolver {
                override fun resolve(owner: SessionOwner, request: CastTargetRequest) = resolvedTarget
                override fun isCurrent(target: HostCastTarget) = target.sameTarget(resolvedTarget)
            },
            authority = object : CastOwnerAuthority { override fun isCurrentForActivation(owner: SessionOwner) = true },
            activations = CastActivationVerifier { _, _, _, _ -> true },
            backend = backend,
            clock = { 1_000L },
            randomId = { "replacement-${System.nanoTime()}" },
            work = Executor { it.run() }
        )
        val events = mutableListOf<CastEvent>()
        try {
            val first = coordinator.prepare(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(owner, first.ticket.ticketId, first.ticket.generation, UserActivationToken("A"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events.add(event) } })
            backend.callback!!(BackendEvent.Ready("physical:81:5", physicalTarget, true, true,
                CastPresentationKind.SYSTEM_MANAGED, null))
            val a = events.filterIsInstance<CastEvent.Ready>().single().handle
            val bTarget = HostCastTarget("physical-b", 82, "writer/.Main", null,
                0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
            resolvedTarget = bTarget
            backend.occupant = CastOccupantIdentity(CastMode.DIRECT_PHYSICAL, CastOwnerKey.from(owner).toString(), "physical:81:5",
                5L, a.generation, 81, -1, "", "reader/.Main", 7, "rear:7",
                CastOccupantSource.PHYSICAL_PROJECTION_OWNER)
            backend.replaceable = true
            val prepared = coordinator.prepare(ownerB, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            assertEquals(backend.occupant, prepared.ticket.observedOccupant)
            coordinator.commit(ownerB, prepared.ticket.ticketId, prepared.ticket.generation, UserActivationToken("B"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events.add(event) } })
            backend.callback!!(BackendEvent.ReplacementReady("physical:81:5", a.generation,
                "physical:82:6", bTarget, true, true, CastPresentationKind.SYSTEM_MANAGED, null))
            assertEquals(CastSessionState.CLOSED,
                (coordinator.query(owner, a) as CastQueryResult.Snapshot).state)
            val b = events.filterIsInstance<CastEvent.Ready>().single { it.target.taskId == 82 }.handle
            assertTrue("B owns its replacement handle", coordinator.query(ownerB, b) is CastQueryResult.Snapshot)
            assertTrue("B cannot query A's closed handle", coordinator.query(ownerB, a) is CastQueryResult.Rejected)
            assertTrue(events.filterIsInstance<CastEvent.Closed>().any { it.handle == a })
            assertEquals(1, backend.confirmReplacementCalls)

            // A second, different caller replaces B with virtual C; B's old handle remains B-only.
            val cTarget = HostCastTarget("virtual-c", 83, "viewer/.Main", null,
                0, 7, "rear:7", CastMode.VIRTUAL, CastRegion.SAFE, 0, 200)
            resolvedTarget = cTarget
            backend.occupant = CastOccupantIdentity(CastMode.DIRECT_PHYSICAL,
                CastOwnerKey.from(ownerB).toString(), "physical:82:6", 6L, b.generation,
                82, -1, "", "writer/.Main", 7, "rear:7", CastOccupantSource.PHYSICAL_PROJECTION_OWNER)
            val cTicket = coordinator.prepare(ownerC, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.VIRTUAL, CastRegion.SAFE, 0, 200)) as CastPrepareResult.Accepted
            coordinator.commit(ownerC, cTicket.ticket.ticketId, cTicket.ticket.generation, UserActivationToken("C"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events.add(event) } })
            backend.callback!!(BackendEvent.ReplacementReady("physical:82:6", b.generation,
                "virtual:83:9", cTarget, true, true, CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            assertEquals(CastSessionState.CLOSED,
                (coordinator.query(ownerB, b) as CastQueryResult.Snapshot).state)
            val c = events.filterIsInstance<CastEvent.Ready>().single { it.target.taskId == 83 }.handle
            assertTrue(coordinator.query(ownerC, c) is CastQueryResult.Snapshot)
            assertTrue("C cannot query B's closed handle", coordinator.query(ownerC, b) is CastQueryResult.Rejected)
            assertEquals(2, backend.confirmReplacementCalls)
            assertEquals("durable accept precedes each owner switch", 2, backend.markReplacementAcceptedCalls)
        } finally { coordinator.close() }
    }

    @Test fun replacementReadyCannotCloseSessionWhenObservedOldOwnerIsForged() {
        val backend = FakeBackend()
        var resolvedTarget = HostCastTarget("physical-a", 81, "reader/.Main", null,
            0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
        val coordinator = ControlledCastCoordinator(
            targets = object : HostCastTargetResolver {
                override fun resolve(owner: SessionOwner, request: CastTargetRequest) = resolvedTarget
                override fun isCurrent(target: HostCastTarget) = target.sameTarget(resolvedTarget)
            },
            authority = object : CastOwnerAuthority { override fun isCurrentForActivation(owner: SessionOwner) = true },
            activations = CastActivationVerifier { _, _, _, _ -> true }, backend = backend,
            randomId = { "forged-${System.nanoTime()}" }, clock = { 1_000L }, work = Executor { it.run() })
        val events = mutableListOf<CastEvent>()
        try {
            val aTicket = coordinator.prepare(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(owner, aTicket.ticket.ticketId, aTicket.ticket.generation, UserActivationToken("A"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events += event } })
            backend.callback!!(BackendEvent.Ready("physical:81:5", resolvedTarget, true, true,
                CastPresentationKind.SYSTEM_MANAGED, null))
            val a = events.filterIsInstance<CastEvent.Ready>().single().handle
            val bTarget = HostCastTarget("physical-b", 82, "writer/.Main", null,
                0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
            resolvedTarget = bTarget
            backend.occupant = CastOccupantIdentity(CastMode.DIRECT_PHYSICAL, "forged-owner",
                "physical:81:5", 5L, a.generation, 81, -1, "", "reader/.Main", 7, "rear:7",
                CastOccupantSource.PHYSICAL_PROJECTION_OWNER)
            backend.replaceable = true
            val bTicket = coordinator.prepare(ownerB, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(ownerB, bTicket.ticket.ticketId, bTicket.ticket.generation, UserActivationToken("B"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events += event } })
            backend.callback!!(BackendEvent.ReplacementReady("physical:81:5", a.generation,
                "physical:82:6", bTarget, true, true, CastPresentationKind.SYSTEM_MANAGED, null))
            assertEquals(CastSessionState.READY, coordinatorSessionState(coordinator, a.sessionId))
            assertTrue("A's owner can still query its original handle",
                coordinator.query(owner, a) is CastQueryResult.Snapshot)
            assertEquals(0, backend.confirmReplacementCalls)
            assertTrue(events.filterIsInstance<CastEvent.CommitFailed>().any {
                it.failure.code == CastFailureCode.OWNER_UNVERIFIED
            })
            assertTrue("forged previous identity is rolled back", backend.abortedOperationId == "physical:82:6")
        } finally { coordinator.close() }
    }

    @Test fun expiredReplacementTicketCannotPassFinalIncumbentSwitch() {
        val backend = FakeBackend().apply { replaceable = true }
        val aTarget = HostCastTarget("physical-a", 81, "reader/.Main", null,
            0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
        var resolvedTarget = aTarget
        var now = 1_000L
        val coordinator = ControlledCastCoordinator(
            targets = object : HostCastTargetResolver {
                override fun resolve(owner: SessionOwner, request: CastTargetRequest) = resolvedTarget
                override fun isCurrent(target: HostCastTarget) = target.sameTarget(resolvedTarget)
            },
            authority = object : CastOwnerAuthority { override fun isCurrentForActivation(owner: SessionOwner) = true },
            activations = CastActivationVerifier { _, _, _, _ -> true }, backend = backend,
            randomId = { "expired-${System.nanoTime()}" }, clock = { now }, work = Executor { it.run() })
        val events = mutableListOf<CastEvent>()
        try {
            val first = coordinator.prepare(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(owner, first.ticket.ticketId, first.ticket.generation, UserActivationToken("A"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events += event } })
            backend.callback!!(BackendEvent.Ready("physical:81:5", aTarget, true, true,
                CastPresentationKind.SYSTEM_MANAGED, null))
            val a = events.filterIsInstance<CastEvent.Ready>().single().handle
            val bTarget = HostCastTarget("physical-b", 82, "writer/.Main", null,
                0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
            resolvedTarget = bTarget
            backend.occupant = CastOccupantIdentity(CastMode.DIRECT_PHYSICAL, CastOwnerKey.from(owner).toString(),
                "physical:81:5", 5L, a.generation, 81, -1, "", "reader/.Main", 7, "rear:7",
                CastOccupantSource.PHYSICAL_PROJECTION_OWNER)
            val ticket = coordinator.prepare(ownerB, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(ownerB, ticket.ticket.ticketId, ticket.ticket.generation, UserActivationToken("B"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events += event } })
            now += 46_000L
            backend.callback!!(BackendEvent.ReplacementReady("physical:81:5", a.generation,
                "physical:82:6", bTarget, true, true, CastPresentationKind.SYSTEM_MANAGED, null))
            assertEquals(CastSessionState.READY, coordinatorSessionState(coordinator, a.sessionId))
            assertEquals(0, backend.markReplacementAcceptedCalls)
            assertTrue(events.filterIsInstance<CastEvent.CommitFailed>().any {
                it.failure.code == CastFailureCode.OWNER_UNVERIFIED || it.failure.code == CastFailureCode.TICKET_STALE
            })
        } finally { coordinator.close() }
    }

    @Test fun explicitOldCloseBetweenReplacementChecksCannotBeOverwrittenByNewReady() {
        val backend = FakeBackend().apply { closeResult = BackendCloseResult.Closed }
        var resolvedTarget = HostCastTarget("physical-a", 81, "reader/.Main", null,
            0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
        lateinit var coordinator: ControlledCastCoordinator
        coordinator = ControlledCastCoordinator(
            targets = object : HostCastTargetResolver {
                override fun resolve(owner: SessionOwner, request: CastTargetRequest) = resolvedTarget
                override fun isCurrent(target: HostCastTarget) = target.sameTarget(resolvedTarget)
            },
            authority = object : CastOwnerAuthority { override fun isCurrentForActivation(owner: SessionOwner) = true },
            activations = CastActivationVerifier { _, _, _, _ -> true }, backend = backend,
            randomId = { "close-race-${System.nanoTime()}" }, clock = { 1_000L }, work = Executor { it.run() })
        val events = mutableListOf<CastEvent>()
        try {
            val aTicket = coordinator.prepare(owner, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(owner, aTicket.ticket.ticketId, aTicket.ticket.generation, UserActivationToken("A"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events += event } })
            backend.callback!!(BackendEvent.Ready("physical:81:5", resolvedTarget, true, true,
                CastPresentationKind.SYSTEM_MANAGED, null))
            val a = events.filterIsInstance<CastEvent.Ready>().single().handle
            resolvedTarget = HostCastTarget("physical-b", 82, "writer/.Main", null,
                0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
            backend.occupant = CastOccupantIdentity(CastMode.DIRECT_PHYSICAL, CastOwnerKey.from(owner).toString(),
                "physical:81:5", 5L, a.generation, 81, -1, "", "reader/.Main", 7, "rear:7",
                CastOccupantSource.PHYSICAL_PROJECTION_OWNER)
            backend.replaceable = true
            val requestChecks = java.util.concurrent.atomic.AtomicInteger()
            val ticket = coordinator.prepare(ownerB, CastTargetRequest(CastTargetSelector.CurrentForeground,
                CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0)) as CastPrepareResult.Accepted
            coordinator.commit(ownerB, ticket.ticket.ticketId, ticket.ticket.generation, UserActivationToken("B"),
                object : CastObserver { override fun onCastEvent(event: CastEvent) { events += event } },
                requestIsCurrent = {
                    if (requestChecks.incrementAndGet() == 3) coordinator.close(owner, a)
                    true
                })
            backend.callback!!(BackendEvent.ReplacementReady("physical:81:5", a.generation,
                "physical:82:6", resolvedTarget, true, true, CastPresentationKind.SYSTEM_MANAGED, null))
            assertEquals(CastSessionState.CLOSED,
                (coordinator.query(owner, a) as CastQueryResult.Snapshot).state)
            assertTrue("B is rejected rather than overwriting A's explicit close",
                events.filterIsInstance<CastEvent.CommitFailed>().any { it.failure.code == CastFailureCode.OWNER_UNVERIFIED })
            assertEquals("B transaction is exact-rolled back", "physical:82:6", backend.abortedOperationId)
            assertEquals("A was closed only by its own handle", 0,
                events.filterIsInstance<CastEvent.Closed>().count { it.handle != a })
        } finally { coordinator.close() }
    }

    @Test fun ticketCancellationIsLinearizedBeforeBackendStartAndUsesPreRegisteredOperationId() {
        val backend = FakeBackend()
        lateinit var coordinator: ControlledCastCoordinator
        coordinator = coordinator(backend)
        backend.onStart = { operationId ->
            backend.ticketToCancel?.let { ticket -> coordinator.abort(owner, ticket.first, ticket.second) }
            assertFalse("start authorization must become false before a privileged effect", backend.authorizedCheck?.invoke() == true)
            assertFalse("cancelled ticket cannot claim a privileged effect", backend.claimCheck?.invoke() == true)
        }
        try {
            val prepared = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            backend.ticketToCancel = prepared.ticket.ticketId to prepared.ticket.generation
            coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("valid"))
            assertEquals(1, backend.startCalls)
            assertEquals("the same pre-registered operation is aborted", backend.startOperationId, backend.abortedOperationId)
        } finally { coordinator.close() }
    }

    @Test fun directPhysicalReadyIsExplicitlySystemManagedWithoutViewport() {
        val backend = FakeBackend()
        val physical = HostCastTarget("proof-physical", 41, "example.target/.Main", null,
            0, 7, "rear:7", CastMode.DIRECT_PHYSICAL, CastRegion.SAFE, 0, null)
        val directRequest = CastTargetRequest(CastTargetSelector.CurrentForeground, CastMode.DIRECT_PHYSICAL)
        val events = mutableListOf<CastEvent>()
        val coordinator = coordinator(backend, castTarget = physical)
        try {
            val prepared = coordinator.prepare(owner, directRequest) as CastPrepareResult.Accepted
            coordinator.commit(owner, prepared.ticket.ticketId, prepared.ticket.generation,
                UserActivationToken("valid"), object : CastObserver {
                    override fun onCastEvent(event: CastEvent) { events.add(event) }
                })
            backend.callback!!(BackendEvent.Ready("physical-session", physical, outputReady = true,
                inputReady = true, presentationKind = CastPresentationKind.SYSTEM_MANAGED, appliedViewport = null))
            val ready = events.filterIsInstance<CastEvent.Ready>().single()
            assertEquals(CastPresentationKind.SYSTEM_MANAGED, ready.presentationKind)
            assertNull(ready.appliedViewport)
        } finally { coordinator.close() }
    }

    @Test fun closeRetainsOnlyExactOwnerBackendConfirmedTerminalAndExpiresIt() {
        var now = 100L
        val backend = FakeBackend().apply { closeResult = BackendCloseResult.Closed }
        val coordinator = coordinator(backend, clock = { now })
        try {
            val ticket = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            coordinator.commit(owner, ticket.ticket.ticketId, ticket.ticket.generation, UserActivationToken("valid"))
            backend.callback!!(BackendEvent.Ready("session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            val handle = CastSessionHandle(ticket.ticket.ticketId, ticket.ticket.generation)
            var closedEvents = 0
            val closeObserver = object : CastObserver {
                override fun onCastEvent(event: CastEvent) { if (event is CastEvent.Closed) closedEvents++ }
            }
            assertTrue(coordinator.close(owner, handle, closeObserver) is CastOperationResult.Accepted)
            assertEquals("backend confirmation notifies once", 1, closedEvents)
            val terminal = coordinator.query(owner, handle) as CastQueryResult.Snapshot
            assertEquals(CastSessionState.CLOSED, terminal.state)
            assertNull("CLOSED tombstone must not retain the Activity/SDK observer", sessionObserver(coordinator, handle.sessionId))
            assertEquals("confirmed terminal queries do not ask a removed backend session", 0, backend.queryCalls)
            assertEquals(CastOperationResult.Completed, coordinator.close(owner, handle))
            assertEquals("closed is idempotent", 1, backend.closeCalls)
            val wrongOwner = owner.copy(principal = SessionPrincipal.AndroidClient(12002, "other.client", "cert", 7L))
            assertEquals(CastFailureCode.OWNER_MISMATCH,
                (coordinator.query(wrongOwner, handle) as CastQueryResult.Rejected).code)
            assertEquals(CastFailureCode.OWNER_MISMATCH,
                (coordinator.query(owner, handle.copy(generation = handle.generation + 1)) as CastQueryResult.Rejected).code)
            now += 11 * 60_000L
            assertEquals(CastFailureCode.OWNER_MISMATCH,
                (coordinator.query(owner, handle) as CastQueryResult.Rejected).code)
        } finally { coordinator.close() }
    }

    @Test fun unconfirmedBackendCloseNeverBecomesClosed() {
        val backend = FakeBackend().apply { closeResult = BackendCloseResult.Indeterminate }
        val coordinator = coordinator(backend)
        try {
            val ticket = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            coordinator.commit(owner, ticket.ticket.ticketId, ticket.ticket.generation, UserActivationToken("valid"))
            backend.callback!!(BackendEvent.Ready("session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            val handle = CastSessionHandle(ticket.ticket.ticketId, ticket.ticket.generation)
            val failureObserver = object : CastObserver {
                val events = mutableListOf<CastEvent>()
                override fun onCastEvent(event: CastEvent) { events.add(event) }
            }
            assertTrue(coordinator.close(owner, handle, failureObserver) is CastOperationResult.Accepted)
            assertEquals("one failed close result is delivered exactly once", 1, failureObserver.events.size)
            assertTrue(failureObserver.events.single() is CastEvent.Failed)
            assertNull("failed close must release its one-shot observer", sessionObserver(coordinator, handle.sessionId))
            assertEquals(CastSessionState.INDETERMINATE,
                (coordinator.query(owner, handle) as CastQueryResult.Snapshot).state)
            assertEquals(1, backend.closeCalls)

            backend.closeResult = BackendCloseResult.Closed
            val retryObserver = object : CastObserver {
                val events = mutableListOf<CastEvent>()
                override fun onCastEvent(event: CastEvent) { events.add(event) }
            }
            assertTrue("same exact owner can retry with a fresh observer",
                coordinator.close(owner, handle, retryObserver) is CastOperationResult.Accepted)
            assertEquals(1, retryObserver.events.size)
            assertTrue(retryObserver.events.single() is CastEvent.Closed)
            assertEquals("the prior failed observer is not reused", 1, failureObserver.events.size)
            assertNull(sessionObserver(coordinator, handle.sessionId))
        } finally { coordinator.close() }
    }

    @Test fun acceptedCloseReportsClosingWithoutConsultingTheDisappearingPresentation() {
        val backend = FakeBackend()
        val work = ArrayDeque<Runnable>()
        val coordinator = coordinator(backend, work = Executor { work.addLast(it) })
        try {
            val ticket = (coordinator.prepare(owner, request) as CastPrepareResult.Accepted).ticket
            coordinator.commit(owner, ticket.ticketId, ticket.generation, UserActivationToken("valid"))
            work.removeFirst().run()
            backend.callback!!(BackendEvent.Ready("session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            val handle = CastSessionHandle(ticket.ticketId, ticket.generation)
            assertTrue(coordinator.close(owner, handle) is CastOperationResult.Accepted)
            val pending = coordinator.query(owner, handle) as CastQueryResult.Snapshot
            assertEquals(CastSessionState.CLOSING, pending.state)
            assertEquals(viewport(), pending.appliedViewport)
            assertNull(pending.failure)
            assertEquals(0, backend.queryCalls)
            assertEquals(CastFailureCode.OWNER_MISMATCH,
                (coordinator.query(ownerB, handle) as CastQueryResult.Rejected).code)
            assertEquals(CastFailureCode.OWNER_MISMATCH,
                (coordinator.query(owner, handle.copy(generation = handle.generation + 1)) as CastQueryResult.Rejected).code)
            work.removeFirst().run()
            assertEquals(CastSessionState.INDETERMINATE,
                (coordinator.query(owner, handle) as CastQueryResult.Snapshot).state)
            assertEquals(1, backend.closeCalls)
        } finally { coordinator.close() }
    }

    @Test fun queryInFlightCannotMaskAnAcceptedOrCompletedClose() {
        for (complete in listOf(false, true)) {
            val backend = FakeBackend().apply { closeResult = BackendCloseResult.Closed }
            val work = ArrayDeque<Runnable>()
            val coordinator = coordinator(backend, work = Executor { work.addLast(it) })
            try {
                val ticket = (coordinator.prepare(owner, request) as CastPrepareResult.Accepted).ticket
                coordinator.commit(owner, ticket.ticketId, ticket.generation, UserActivationToken("valid"))
                work.removeFirst().run()
                backend.callback!!(BackendEvent.Ready("session", target, true, true,
                    CastPresentationKind.MANAGED_VIEWPORT, viewport()))
                val handle = CastSessionHandle(ticket.ticketId, ticket.generation)
                backend.onQuery = {
                    assertTrue(coordinator.close(owner, handle) is CastOperationResult.Accepted)
                    if (complete) work.removeFirst().run()
                }
                val result = coordinator.query(owner, handle) as CastQueryResult.Snapshot
                assertEquals(if (complete) CastSessionState.CLOSED else CastSessionState.CLOSING, result.state)
                assertNull(result.failure)
                if (!complete) work.removeFirst().run()
                assertEquals(CastSessionState.CLOSED,
                    (coordinator.query(owner, handle) as CastQueryResult.Snapshot).state)
                assertEquals(1, backend.queryCalls)
                assertEquals(1, backend.closeCalls)
            } finally { coordinator.close() }
        }
    }

    @Test fun rejectedCloseQueueReleasesObserverAndAllowsExactOwnerRetry() {
        val backend = FakeBackend().apply { closeResult = BackendCloseResult.Closed }
        var rejectWork = false
        val coordinator = coordinator(backend, work = Executor {
            if (rejectWork) throw java.util.concurrent.RejectedExecutionException()
            it.run()
        })
        try {
            val ticket = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
            coordinator.commit(owner, ticket.ticket.ticketId, ticket.ticket.generation, UserActivationToken("valid"))
            backend.callback!!(BackendEvent.Ready("session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            val handle = CastSessionHandle(ticket.ticket.ticketId, ticket.ticket.generation)
            val rejectedObserver = object : CastObserver {
                var calls = 0
                override fun onCastEvent(event: CastEvent) { calls++ }
            }
            rejectWork = true
            assertEquals(CastFailureCode.BACKEND_BUSY,
                (coordinator.close(owner, handle, rejectedObserver) as CastOperationResult.Rejected).code)
            assertEquals("queue rejection has no async event", 0, rejectedObserver.calls)
            assertNull("rejected close must release the observer", sessionObserver(coordinator, handle.sessionId))
            assertEquals(CastSessionState.INDETERMINATE,
                (coordinator.query(owner, handle) as CastQueryResult.Snapshot).state)

            rejectWork = false
            val retryObserver = object : CastObserver {
                val events = mutableListOf<CastEvent>()
                override fun onCastEvent(event: CastEvent) { events.add(event) }
            }
            assertTrue(coordinator.close(owner, handle, retryObserver) is CastOperationResult.Accepted)
            assertEquals(1, retryObserver.events.size)
            assertTrue(retryObserver.events.single() is CastEvent.Closed)
            assertEquals(0, rejectedObserver.calls)
            assertEquals(1, backend.closeCalls)
        } finally { coordinator.close() }
    }

    @Test fun failedReplacementCancellationDoesNotLeaveAClosingHandleWithoutAWorker() {
        val backend = FakeBackend().apply { closeResult = BackendCloseResult.Closed }
        val coordinator = coordinator(backend)
        try {
            val ticket = (coordinator.prepare(owner, request) as CastPrepareResult.Accepted).ticket
            coordinator.commit(owner, ticket.ticketId, ticket.generation, UserActivationToken("valid"))
            backend.callback!!(BackendEvent.Ready("session", target, true, true,
                CastPresentationKind.MANAGED_VIEWPORT, viewport()))
            val handle = CastSessionHandle(ticket.ticketId, ticket.generation)
            val observer = object : CastObserver {
                override fun onCastEvent(event: CastEvent) = error("a rejected close must not notify this observer")
            }
            backend.cancelReplacementFails = true
            assertEquals(CastOperationResult.Rejected(CastFailureCode.BACKEND_FAILED, true),
                coordinator.close(owner, handle, observer))
            assertNull(sessionObserver(coordinator, handle.sessionId))
            assertEquals(CastSessionState.INDETERMINATE,
                (coordinator.query(owner, handle) as CastQueryResult.Snapshot).state)
            assertEquals(0, backend.closeCalls)
            backend.cancelReplacementFails = false
            assertTrue(coordinator.close(owner, handle) is CastOperationResult.Accepted)
            assertEquals(CastSessionState.CLOSED,
                (coordinator.query(owner, handle) as CastQueryResult.Snapshot).state)
            assertEquals(1, backend.closeCalls)
        } finally { coordinator.close() }
    }

    @Test fun confirmedCloseHistoryHasAHardEntryCap() {
        val backend = FakeBackend().apply { closeResult = BackendCloseResult.Closed }
        val coordinator = coordinator(backend)
        var oldest: CastSessionHandle? = null
        var newest: CastSessionHandle? = null
        try {
            repeat(65) {
                val ticket = coordinator.prepare(owner, request) as CastPrepareResult.Accepted
                coordinator.commit(owner, ticket.ticket.ticketId, ticket.ticket.generation, UserActivationToken("valid"))
                backend.callback!!(BackendEvent.Ready("backend-${ticket.ticket.generation}", target, true, true,
                    CastPresentationKind.MANAGED_VIEWPORT, viewport()))
                val handle = CastSessionHandle(ticket.ticket.ticketId, ticket.ticket.generation)
                assertTrue(coordinator.close(owner, handle) is CastOperationResult.Accepted)
                if (oldest == null) oldest = handle
                newest = handle
            }
            assertEquals(CastFailureCode.OWNER_MISMATCH,
                (coordinator.query(owner, requireNotNull(oldest)) as CastQueryResult.Rejected).code)
            assertEquals(CastSessionState.CLOSED,
                (coordinator.query(owner, requireNotNull(newest)) as CastQueryResult.Snapshot).state)
        } finally { coordinator.close() }
    }

    private fun coordinator(backend: FakeBackend, activation: (SessionOwner, String, Long, UserActivationToken) -> Boolean = { _, _, _, _ -> true },
                            castTarget: HostCastTarget = target, clock: () -> Long = { 100L },
                            work: Executor = Executor { it.run() }) =
        ControlledCastCoordinator(
            targets = object : HostCastTargetResolver {
                override fun resolve(owner: SessionOwner, request: CastTargetRequest) = castTarget
                override fun isCurrent(target: HostCastTarget) = target.sameTarget(castTarget)
            },
            authority = object : CastOwnerAuthority { override fun isCurrentForActivation(owner: SessionOwner) = true },
            activations = CastActivationVerifier(activation),
            backend = backend,
            clock = clock,
            randomId = { "ticket-${System.nanoTime()}" },
            work = work,
            reportStartGateRejection = {},
        )

    private fun viewport() = AppliedCastViewport(1, 7, CastRegion.FULL, 440, 0, 0, 1080, 1920,
        1080, 1920, 0, 0, 0, 0, 1080, 1920, 1f, 0f, 1f, 0f, 0f, 0f)

    private fun sessionObserver(coordinator: ControlledCastCoordinator, sessionId: String): Any? {
        val sessions = coordinator.javaClass.getDeclaredField("sessions").apply { isAccessible = true }
            .get(coordinator) as Map<*, *>
        val record = sessions[sessionId] ?: return null
        return record.javaClass.getDeclaredField("observer").apply { isAccessible = true }.get(record)
    }

    private fun coordinatorSessionState(coordinator: ControlledCastCoordinator, sessionId: String): CastSessionState? {
        val sessions = coordinator.javaClass.getDeclaredField("sessions").apply { isAccessible = true }
            .get(coordinator) as Map<*, *>
        val record = sessions[sessionId] ?: return null
        return record.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(record) as CastSessionState
    }

    private class FakeBackend : ControlledCastBackend {
        var occupant: CastOccupantIdentity? = null
        var virtualOwnerAuthorized = false
        var replaceable = false
        var confirmReplacementCalls = 0
        var markReplacementAcceptedCalls = 0
        var rollbackReplacementCalls = 0
        var startCalls = 0
        var abortCalls = 0
        var closeCalls = 0
        var queryCalls = 0
        var onQuery: (() -> Unit)? = null
        var cancelReplacementFails = false
        var closeResult: BackendCloseResult = BackendCloseResult.Indeterminate
        var startOperationId: String? = null
        var abortedOperationId: String? = null
        var authorizedCheck: (() -> Boolean)? = null
        var claimCheck: (() -> Boolean)? = null
        var onStart: ((String) -> Unit)? = null
        var ticketToCancel: Pair<String, Long>? = null
        var callback: ((BackendEvent) -> Unit)? = null
        override fun inspectOccupant(rearDisplayId: Int, rearIdentity: String) = occupant
        override fun isExactControlledVirtualOwner(occupant: CastOccupantIdentity) =
            virtualOwnerAuthorized && this.occupant == occupant
        override fun canReplace(target: HostCastTarget, occupant: CastOccupantIdentity) = replaceable
        override fun confirmReplacement(backendSessionId: String): Boolean { confirmReplacementCalls++; return true }
        override fun markReplacementAccepted(backendSessionId: String, coordinatorGeneration: Long,
            ownerIdentity: String): Boolean { markReplacementAcceptedCalls++; return true }
        override fun rollbackReplacement(backendSessionId: String): Boolean {
            rollbackReplacementCalls++; abortedOperationId = backendSessionId; return true
        }
        override fun start(operationId: String, owner: CastOwnerKey, generation: Long, target: HostCastTarget,
                           expectedOccupant: CastOccupantIdentity?, isAuthorized: () -> Boolean,
                           claimSideEffect: () -> Boolean, callback: (BackendEvent) -> Unit) {
            startCalls++
            startOperationId = operationId
            authorizedCheck = isAuthorized
            claimCheck = claimSideEffect
            this.callback = callback
            onStart?.invoke(operationId)
        }
        override fun abort(operationId: String) { abortCalls++; abortedOperationId = operationId }
        override fun cancelPendingReplacement(incumbentBackendSessionId: String): Boolean {
            check(!cancelReplacementFails) { "replacement cancellation unavailable" }
            return false
        }
        override fun query(backendSessionId: String, generation: Long): BackendRuntimeSnapshot? {
            queryCalls++
            onQuery?.invoke()
            return null
        }
        override fun close(backendSessionId: String, generation: Long): BackendCloseResult { closeCalls++; return closeResult }
    }
}
