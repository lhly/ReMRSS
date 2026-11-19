package top.zainzh.rearscreen;

import android.os.IBinder;
import java.lang.reflect.Proxy;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.*;

public final class RearInputCloseTest {
    private static IBinder owner(IBinder.DeathRecipient expected, AtomicInteger attempts, boolean alreadyUnlinked) {
        return (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class},
                (object, method, args) -> {
                    assertEquals("unlinkToDeath", method.getName());
                    assertSame(expected, args[0]);
                    if (attempts.incrementAndGet() > 1 || alreadyUnlinked)
                        throw new NoSuchElementException("Death link does not exist");
                    return true;
                });
    }

    @Test public void watchdogAndReaderExitDoNotUnlinkTheSameOwnerTwice() {
        IBinder.DeathRecipient recipient = () -> { };
        AtomicReference<IBinder.DeathRecipient> link = new AtomicReference<>(recipient);
        AtomicInteger attempts = new AtomicInteger();
        IBinder owner = owner(recipient, attempts, false);
        RearInputProxy.unlinkClosingOwner(owner, link);
        RearInputProxy.unlinkClosingOwner(owner, link);
        assertEquals(1, attempts.get());
        assertNull(link.get());
    }

    @Test public void aDiscardedBinderRegistrationDoesNotAbortReaderCleanup() {
        IBinder.DeathRecipient recipient = () -> { };
        AtomicReference<IBinder.DeathRecipient> link = new AtomicReference<>(recipient);
        AtomicInteger attempts = new AtomicInteger();
        IBinder owner = owner(recipient, attempts, true);
        RearInputProxy.unlinkClosingOwner(owner, link);
        RearInputProxy.unlinkClosingOwner(owner, link);
        assertEquals(1, attempts.get());
        assertNull(link.get());
    }

    @Test public void aGestureTickCanCloseItsClockWithoutInterruptingItsCleanup() throws Exception {
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            var interrupted = new CompletableFuture<Boolean>();
            var current = new AtomicReference<PausableGestureClock>();
            var ticks = new AtomicInteger();
            current.set(new PausableGestureClock(executor, () -> {
                ticks.incrementAndGet();
                current.get().close();
                interrupted.complete(Thread.currentThread().isInterrupted());
            }, 1L));
            current.get().resume();
            assertFalse(interrupted.get(2, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, ticks.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
