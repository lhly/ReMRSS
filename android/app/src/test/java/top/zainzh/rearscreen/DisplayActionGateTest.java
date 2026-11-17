package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DisplayActionGateTest {
    @Test public void reservationSerializesActionsAndOnlyExactTokenCanReleaseIt() throws Exception {
        DisplayActionGate gate = new DisplayActionGate();
        DisplayActionGate.Reservation token = gate.reserve();
        assertTrue(gate.isCurrent(token));
        assertNull(gate.reserve());

        AtomicInteger actions = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            gate.run(actions::incrementAndGet);
            finished.countDown();
        });
        worker.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (worker.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        assertTrue("ordinary action is waiting at the shared gate",
                worker.getState() == Thread.State.WAITING || worker.getState() == Thread.State.TIMED_WAITING);
        assertEquals(0, actions.get());
        assertFalse("the ordinary owner action waits behind this exact reservation",
                finished.await(50, TimeUnit.MILLISECONDS));
        assertFalse(gate.complete(null));
        assertTrue(gate.complete(token));
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertEquals(1, actions.get());
        assertFalse(gate.isCurrent(token));
    }

    @Test public void expiredReservationRejectsLateCallbackAndDoesNotBlockNewOwner() throws Exception {
        DisplayActionGate gate = new DisplayActionGate(10);
        DisplayActionGate.Reservation expired = gate.reserve();
        Thread.sleep(25);
        assertFalse(gate.isCurrent(expired));
        assertFalse(gate.complete(expired));
        assertTrue(gate.reserve() != null);
    }
}
