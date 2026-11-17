package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class PhysicalReaderReceiptGateTest {
    @Test public void concurrentEofAndActiveClosePersistReleaseExactlyOnceWithoutMonitorCycle() throws Exception {
        PhysicalReaderReceiptGate gate = new PhysicalReaderReceiptGate();
        AtomicInteger writes = new AtomicInteger();
        assertTrue(gate.record("REQUESTED", "", 0, (s, id, gen) -> true));
        assertTrue(gate.record("ALLOCATED", "reader-7", 7, (s, id, gen) -> true));
        assertTrue(gate.record("READY", "reader-7", 7, (s, id, gen) -> true));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean[] firstResult = {false};
        Thread eof = new Thread(() -> firstResult[0] = gate.record("RELEASED", "reader-7", 7,
                (s, id, gen) -> {
                    writes.incrementAndGet(); entered.countDown();
                    try { return release.await(1, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                }));
        eof.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(gate.record("RELEASED", "reader-7", 7, (s, id, gen) -> {
            writes.incrementAndGet(); return true;
        }));
        release.countDown();
        eof.join(1000);
        assertFalse(eof.isAlive());
        assertTrue(firstResult[0]);
        assertEquals("RELEASED", gate.state());
        assertTrue(gate.record("RELEASED", "reader-7", 7, (s, id, gen) -> {
            writes.incrementAndGet(); return true;
        }));
        assertEquals(1, writes.get());
    }
}
