package dev.remrss.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConnectionHandshakeGateTest {
    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { synchronized(tasks) { tasks.addLast(command) } }
        fun drain() {
            while (true) {
                val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() } ?: return
                next.run()
            }
        }
        fun hasTasks() = synchronized(tasks) { tasks.isNotEmpty() }
    }

    @Test fun closeDuringHandshakeFailsWaiterAndRejectsLateReady() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val waiterResults = mutableListOf<Result<Unit>>()
        val start = gate.connect { waiterResults += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val oldBinder = Any()
        assertTrue(gate.serviceConnected(start.attemptId, oldBinder))

        val closed = gate.close()
        assertFalse(closed.wasBound) // bindService has not returned yet
        val dispatcher = ClientResultDispatcher(Executor { it.run() }) { true }
        val completion = closed.completion!!
        completion.waiters.forEach { waiter -> dispatcher.dispatch(waiter, completion.result) }
        assertEquals(RearSdkErrorCode.CLIENT_CLOSED, waiterResults.single().exceptionOrNull()?.message)
        var published = false
        assertNull(gate.handshakeCompleted(start.attemptId, oldBinder, "remrss.platform/1", publish = { published = true }))
        assertFalse(published)
    }

    @Test fun bindCompletingAfterCloseIsCompensatedAndCannotReopenClient() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val start = gate.connect {} as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val bindEntered = CountDownLatch(1)
        val completeBind = CountDownLatch(1)
        val unbinds = AtomicInteger(0)
        val releaseGuard = OnceBindingRelease()
        val originalHandle = Any()
        val releasedHandles = mutableListOf<Any>()
        val bindThread = Thread {
            val accepted = attemptBinding({
                bindEntered.countDown()
                check(completeBind.await(5, TimeUnit.SECONDS))
                true
            }) {}
            val result = gate.bindingCompleted(start.attemptId, accepted)
            if (result.unbind) releaseGuard.request { releasedHandles += originalHandle; unbinds.incrementAndGet() }
        }
        bindThread.start()
        assertTrue(bindEntered.await(5, TimeUnit.SECONDS))

        val closed = gate.close()
        assertFalse(closed.wasBound)
        completeBind.countDown()
        bindThread.join(5_000)
        assertFalse(bindThread.isAlive)
        assertEquals(1, unbinds.get())
        assertSame(originalHandle, releasedHandles.single())
        assertFalse(releaseGuard.request { unbinds.incrementAndGet() })
        assertEquals(1, unbinds.get())
        assertFalse(gate.serviceConnected(start.attemptId, Any()))
        assertEquals(ConnectionHandshakeGate.ConnectDecision.Rejected(RearSdkErrorCode.CLIENT_CLOSED), gate.connect {})
    }

    @Test fun oldBinderHandshakeCannotReplaceAReconnectedRemote() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val results = mutableListOf<Result<Unit>>()
        val first = gate.connect { results += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
        assertTrue(gate.serviceConnected(first.attemptId, "old"))
        assertFalse(gate.bindingCompleted(first.attemptId, accepted = true).unbind)
        val disconnected = gate.disconnected(first.attemptId)!!
        val disconnectedWaiters = disconnected.completion!!
        disconnectedWaiters.waiters.forEach { it(disconnectedWaiters.result) }
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, results.single().exceptionOrNull()?.message)

        results.clear()
        assertEquals(ConnectionHandshakeGate.ConnectDecision.AwaitingBinding, gate.connect { results += it })
        assertTrue(gate.serviceConnected(first.attemptId, "new"))
        var currentRemote: String? = null
        assertNull(gate.handshakeCompleted(first.attemptId, "old", "remrss.platform/1", publish = { currentRemote = "old" }))
        val ready = gate.handshakeCompleted(first.attemptId, "new", "remrss.platform/1", publish = { currentRemote = "new" })
        assertNotNull(ready)
        assertEquals("new", currentRemote)
        assertTrue(gate.isCurrent(first.attemptId, "new"))
        assertFalse(gate.isCurrent(first.attemptId, "old"))
    }


    @Test fun staleUnbindCannotClearAReconnectedBindingHandle() {
        val slot = ActiveBindingSlot<Any>()
        val oldBinding = Any()
        val newBinding = Any()
        slot.publish(oldBinding)
        assertTrue(slot.clearIfCurrent(oldBinding))
        slot.publish(newBinding)
        assertFalse(slot.clearIfCurrent(oldBinding))
        assertSame(newBinding, slot.current())
    }


    @Test fun oldCallbackKeepsItsExactBindingHandleAfterNewAttemptIsPublished() {
        val slot = ActiveBindingSlot<ServiceBindingHandle<Any>>()
        val old = ServiceBindingHandle<Any>(1).apply { connection = Any() }
        val fresh = ServiceBindingHandle<Any>(2).apply { connection = Any() }
        slot.publish(old)
        val oldCallback = { old }

        // A new connect may publish after the old gate is reset; the old callback closure still owns old.
        slot.publish(fresh)
        val callbackHandle = oldCallback()
        assertSame(old, callbackHandle)
        assertTrue(callbackHandle.unbound.request {})
        assertFalse(callbackHandle.unbound.request {})
        assertFalse(slot.clearIfCurrent(old))
        assertSame(fresh, slot.current())
    }

    @Test fun bindFalseOrThrowDrainsWaitersAndAllowsExplicitRetry() {
        val failedBinds = listOf<() -> Boolean>({ false }, { throw IllegalStateException("bind failed") })
        failedBinds.forEach { bind ->
            val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
            val results = mutableListOf<Result<Unit>>()
            val failedAttempt = gate.connect { results += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
            val releaseGuard = OnceBindingRelease()
            val originalHandle = Any()
            val releasedHandles = mutableListOf<Any>()
            val failureOrder = mutableListOf<String>()
            assertFalse(attemptBinding(bind) {
                releaseGuard.request { releasedHandles += originalHandle; failureOrder += "unbind" }
                failureOrder += "fail"
                val failed = gate.bindingFailed(failedAttempt.attemptId, RearSdkErrorCode.SERVICE_UNAVAILABLE)!!
                val completion = failed.completion!!
                completion.waiters.forEach { it(completion.result) }
            })
            assertEquals(listOf("unbind", "fail"), failureOrder)
            assertSame(originalHandle, releasedHandles.single())
            assertFalse(releaseGuard.request { releasedHandles += Any() }) // concurrent close cannot unbind twice
            assertEquals(1, releasedHandles.size)
            assertEquals(RearSdkErrorCode.SERVICE_UNAVAILABLE, results.single().exceptionOrNull()?.message)

            results.clear()
            val retry = gate.connect { results += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
            assertTrue(retry.attemptId > failedAttempt.attemptId)
            var prematureUnbind = false
            assertTrue(attemptBinding({ true }) { prematureUnbind = true })
            assertFalse(prematureUnbind)
            assertFalse(gate.bindingCompleted(retry.attemptId, accepted = true).unbind)
            assertTrue(gate.serviceConnected(retry.attemptId, "binder"))
            val ready = gate.handshakeCompleted(retry.attemptId, "binder", "remrss.platform/1")!!
            ready.completion!!.waiters.forEach { it(ready.completion.result) }
            assertTrue(results.single().isSuccess)
        }
    }

    @Test fun handshakeFailureCodesAreNotReclassifiedButStaleSuccessStillDisconnects() {
        listOf(
            null to RearSdkErrorCode.SERVICE_UNAVAILABLE,
            "remrss.platform/0" to RearSdkErrorCode.CONTRACT_INCOMPATIBLE,
        ).forEach { (contract, expectedCode) ->
            val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
            var delivered: Result<Unit>? = null
            val attempt = gate.connect { delivered = it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
            val binder = Any()
            assertFalse(gate.bindingCompleted(attempt.attemptId, accepted = true).unbind)
            assertTrue(gate.serviceConnected(attempt.attemptId, binder))
            val completion = gate.handshakeCompleted(attempt.attemptId, binder, contract)!!.completion!!
            val dispatcher = ClientResultDispatcher(Executor { it.run() }) { false }
            val expectedEpoch: Any? = null
            completion.waiters.forEach { waiter ->
                dispatcher.dispatch(waiter, completion.result) {
                    connectionWaiterCompletionIsCurrent(completion.result, expectedEpoch, currentEpoch = null)
                }
            }
            assertEquals(expectedCode, delivered?.exceptionOrNull()?.message)
        }

        var staleSuccess: Result<Unit>? = null
        val successful = Result.success(Unit)
        val oldEpoch = Any()
        val newEpoch = Any()
        ClientResultDispatcher(Executor { it.run() }) { false }.dispatch({ staleSuccess = it }, successful) {
            connectionWaiterCompletionIsCurrent(successful, oldEpoch, newEpoch)
        }
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, staleSuccess?.exceptionOrNull()?.message)

        var closedResult: Result<Unit>? = null
        ClientResultDispatcher(Executor { it.run() }) { true }.dispatch({ closedResult = it },
            Result.failure(IllegalStateException(RearSdkErrorCode.SERVICE_UNAVAILABLE))) { true }
        assertEquals(RearSdkErrorCode.CLIENT_CLOSED, closedResult?.exceptionOrNull()?.message)
    }

    @Test fun alreadyConnectedSuccessUsesTheEpochCapturedWithTheGateDecision() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val first = gate.connect {} as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val binder = Any()
        assertFalse(gate.bindingCompleted(first.attemptId, accepted = true).unbind)
        assertTrue(gate.serviceConnected(first.attemptId, binder))
        val connectedEpoch = Any()
        assertTrue(gate.handshakeCompleted(first.attemptId, binder, "remrss.platform/1",
            publish = {}).let { it != null })

        var currentEpoch: Any? = connectedEpoch
        var delivered: Result<Unit>? = null
        val decision = gate.connect({ currentEpoch }) { delivered = it }
                as ConnectionHandshakeGate.ConnectDecision.AlreadyConnected
        assertSame(connectedEpoch, decision.identity)

        val queued = QueueExecutor()
        val success = Result.success(Unit)
        ClientResultDispatcher(queued) { false }.dispatch({ delivered = it }, success) {
            connectionWaiterCompletionIsCurrent(success, decision.identity, currentEpoch)
        }
        currentEpoch = Any()
        queued.drain()
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, delivered?.exceptionOrNull()?.message)
    }

    @Test fun alreadyConnectedEpochProviderRunsUnderTheGateMonitor() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val first = gate.connect {} as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val binder = Any()
        assertFalse(gate.bindingCompleted(first.attemptId, accepted = true).unbind)
        assertTrue(gate.serviceConnected(first.attemptId, binder))
        assertNotNull(gate.handshakeCompleted(first.attemptId, binder, "remrss.platform/1"))

        val epoch = Any()
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val decision = AtomicReference<ConnectionHandshakeGate.ConnectDecision?>()
        val connectThread = Thread {
            decision.set(gate.connect({
                providerEntered.countDown()
                check(releaseProvider.await(5, TimeUnit.SECONDS))
                epoch
            }) {})
        }
        connectThread.start()
        assertTrue(providerEntered.await(1, TimeUnit.SECONDS))

        val disconnectStarted = CountDownLatch(1)
        val disconnectFinished = CountDownLatch(1)
        val disconnectThread = Thread {
            disconnectStarted.countDown()
            gate.disconnected(first.attemptId)
            disconnectFinished.countDown()
        }
        disconnectThread.start()
        assertTrue(disconnectStarted.await(1, TimeUnit.SECONDS))
        assertFalse(disconnectFinished.await(100, TimeUnit.MILLISECONDS))
        releaseProvider.countDown()
        assertTrue(disconnectFinished.await(1, TimeUnit.SECONDS))
        connectThread.join(1_000)
        disconnectThread.join(1_000)
        val connected = decision.get() as ConnectionHandshakeGate.ConnectDecision.AlreadyConnected
        assertSame(epoch, connected.identity)
    }

    @Test fun handshakeSuccessDoesNotAdoptAnEpochPublishedByImmediateReconnect() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        var delivered: Result<Unit>? = null
        val first = gate.connect { delivered = it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val oldBinder = Any()
        assertFalse(gate.bindingCompleted(first.attemptId, accepted = true).unbind)
        assertTrue(gate.serviceConnected(first.attemptId, oldBinder))
        var currentEpoch: Any? = null
        var publishedEpoch: Any? = null
        val oldCompletion = gate.handshakeCompleted(first.attemptId, oldBinder, "remrss.platform/1",
            publish = { publishedEpoch = Any(); currentEpoch = publishedEpoch })!!.completion!!

        assertNotNull(gate.disconnected(first.attemptId) { currentEpoch = null })
        val newBinder = Any()
        assertTrue(gate.serviceConnected(first.attemptId, newBinder))
        gate.handshakeCompleted(first.attemptId, newBinder, "remrss.platform/1",
            publish = { currentEpoch = Any() })

        val capturedOldEpoch = publishedEpoch
        val queued = QueueExecutor()
        ClientResultDispatcher(queued) { false }.dispatch(oldCompletion.waiters.single(), oldCompletion.result) {
            connectionWaiterCompletionIsCurrent(oldCompletion.result, capturedOldEpoch, currentEpoch)
        }
        queued.drain()
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, delivered?.exceptionOrNull()?.message)
    }

    @Test fun staleEventsAndQueuedOperationsCannotCrossConnectionEpochs() {
        var closed = false
        val current = AtomicReference<Any?>(Any())
        val oldEpoch = current.get()!!
        val newEpoch = Any()
        val worker = QueueExecutor()
        val callbacks = QueueExecutor()
        val results = ClientResultDispatcher(callbacks) { closed }
        val invalidatedOperations = mutableListOf<Any>()
        val operations = ClientOperationQueue(worker, results, { closed }, { current.get() === it }) {
            invalidatedOperations += it
        }
        val events = ClientEventDispatcher(callbacks, { closed }) { current.get() === it }
        val oldEvents = mutableListOf<String>()
        val oldResults = mutableListOf<Result<Int>>()
        var oldActionCalls = 0

        events.dispatch(oldEpoch) { oldEvents += "queued-before-disconnect" }
        operations.submit(oldEpoch, { oldActionCalls++; 1 }) { oldResults += it }
        current.set(newEpoch)
        events.dispatch(oldEpoch) { oldEvents += "arrived-after-reconnect" }
        worker.drain()
        callbacks.drain()
        assertTrue(oldEvents.isEmpty())
        assertEquals(0, oldActionCalls)
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, oldResults.single().exceptionOrNull()?.message)

        val currentEvents = mutableListOf<String>()
        val currentResults = mutableListOf<Result<Int>>()
        events.dispatch(newEpoch) { currentEvents += "current" }
        operations.submit(newEpoch, { 7 }) { currentResults += it }
        worker.drain()
        callbacks.drain()
        assertEquals(listOf("current"), currentEvents)
        assertEquals(7, currentResults.single().getOrThrow())
        assertTrue(invalidatedOperations.isEmpty())
    }

    @Test fun closeCompletesInflightLocalResultAndUnbindsWithoutWaitingForBinderWorker() {
        var closed = false
        val currentEpoch = Any()
        val worker = Executors.newSingleThreadExecutor()
        val enteredBinderCall = CountDownLatch(1)
        val unblockBinderCall = CountDownLatch(1)
        val compensationFinished = CountDownLatch(1)
        val resultsSeen = mutableListOf<Result<Int>>()
        var hostOwnerExists = false
        var hostReleaseCalls = 0
        val resultDispatcher = ClientResultDispatcher(Executor { it.run() }) { closed }
        val operations = ClientOperationQueue(worker, resultDispatcher, { closed }, { it === currentEpoch }) {
            hostOwnerExists = false
            hostReleaseCalls++
            compensationFinished.countDown()
        }
        var unbindCalls = 0
        try {
            operations.submit(currentEpoch, {
                enteredBinderCall.countDown()
                check(unblockBinderCall.await(5, TimeUnit.SECONDS))
                hostOwnerExists = true // Host creates the owner after the eager close release.
                99
            }) { resultsSeen += it }
            assertTrue(enteredBinderCall.await(5, TimeUnit.SECONDS))

            closed = true
            ClientCloseSequence(
                operations = operations,
                unbind = { unbindCalls++ },
                scheduleHostRelease = {
                    hostOwnerExists = false
                    hostReleaseCalls++
                },
                shutdownWorker = { worker.shutdown() }
            ).close()

            assertEquals(1, resultsSeen.size)
            assertEquals(RearSdkErrorCode.CLIENT_CLOSED, resultsSeen.single().exceptionOrNull()?.message)
            assertEquals(1, unbindCalls)
            assertEquals(1, hostReleaseCalls)
            assertFalse(hostOwnerExists)
            assertFalse(worker.isTerminated)

            unblockBinderCall.countDown()
            assertTrue(compensationFinished.await(5, TimeUnit.SECONDS))
            assertFalse(hostOwnerExists)
            assertEquals(2, hostReleaseCalls)
        } finally {
            unblockBinderCall.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(1, resultsSeen.size)
    }

    @Test fun staleOldEpochOperationFinishesAfterReconnectAndReleasesOnlyItsOwner() {
        var closed = false
        val oldEpoch = Any()
        val newEpoch = Any()
        val currentEpoch = AtomicReference<Any?>(oldEpoch)
        val worker = Executors.newSingleThreadExecutor()
        val enteredBinderCall = CountDownLatch(1)
        val unblockBinderCall = CountDownLatch(1)
        val compensationFinished = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        val owners = mutableSetOf<Any>()
        val currentResults = mutableListOf<Result<Int>>()
        val callbacks = ClientResultDispatcher(Executor { it.run() }) { closed }
        val operations = ClientOperationQueue(
            worker, callbacks, { closed }, { currentEpoch.get() === it },
            { epoch -> synchronized(owners) { owners.remove(epoch) }; compensationFinished.countDown() }
        )
        try {
            operations.submit(oldEpoch, {
                enteredBinderCall.countDown()
                check(unblockBinderCall.await(5, TimeUnit.SECONDS))
                synchronized(owners) { owners += oldEpoch }
                12
            }) { currentResults += it; resultDelivered.countDown() }
            assertTrue(enteredBinderCall.await(5, TimeUnit.SECONDS))

            currentEpoch.set(newEpoch)
            assertTrue(synchronized(owners) { owners.add(newEpoch) })
            unblockBinderCall.countDown()
            assertTrue(compensationFinished.await(5, TimeUnit.SECONDS))
            assertTrue(resultDelivered.await(5, TimeUnit.SECONDS))
            assertEquals(1, synchronized(owners) { owners.size })
            assertTrue(synchronized(owners) { owners.contains(newEpoch) })
            assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, currentResults.single().exceptionOrNull()?.message)
        } finally {
            unblockBinderCall.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidatedOperationFailureStillTriggersCompensatingRelease() {
        var closed = false
        val epoch = Any()
        val worker = Executors.newSingleThreadExecutor()
        val enteredBinderCall = CountDownLatch(1)
        val unblockBinderCall = CountDownLatch(1)
        val compensationFinished = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        var ownerExists = false
        val resultsSeen = mutableListOf<Result<Int>>()
        val callbacks = ClientResultDispatcher(Executor { it.run() }) { closed }
        val operations = ClientOperationQueue(worker, callbacks, { closed }, { it === epoch }) {
            ownerExists = false
            compensationFinished.countDown()
        }
        try {
            operations.submit(epoch, {
                enteredBinderCall.countDown()
                check(unblockBinderCall.await(5, TimeUnit.SECONDS))
                ownerExists = true
                throw IllegalStateException("Host registered before failing")
            }) { resultsSeen += it; resultDelivered.countDown() }
            assertTrue(enteredBinderCall.await(5, TimeUnit.SECONDS))
            closed = true
            operations.close()
            assertEquals(RearSdkErrorCode.CLIENT_CLOSED, resultsSeen.single().exceptionOrNull()?.message)
            unblockBinderCall.countDown()
            assertTrue(compensationFinished.await(5, TimeUnit.SECONDS))
            assertTrue(resultDelivered.await(5, TimeUnit.SECONDS))
            assertFalse(ownerExists)
        } finally {
            unblockBinderCall.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
