package top.zainzh.rearscreen;

import java.util.concurrent.Callable;

/** Shared serialization boundary with a short, cancellable reservation for staged ownership transfer. */
final class DisplayActionGate {
    private static final long RESERVATION_MS = 5000;
    private final Object lock = new Object();
    private final long reservationMs;
    private Reservation active;

    DisplayActionGate() { this(RESERVATION_MS); }
    DisplayActionGate(long reservationMs) { this.reservationMs = Math.max(1, reservationMs); }

    static final class Reservation {
        final long deadline;
        private Reservation(long deadline) { this.deadline = deadline; }
    }

    void run(Runnable action) {
        synchronized (lock) {
            awaitReservation();
            action.run();
        }
    }

    <T> T call(Callable<T> action) throws Exception {
        synchronized (lock) {
            awaitReservation();
            return action.call();
        }
    }

    Reservation reserve() {
        synchronized (lock) {
            clearExpired();
            if (active != null) return null;
            active = new Reservation(nowMs() + reservationMs);
            return active;
        }
    }

    boolean isCurrent(Reservation reservation) {
        synchronized (lock) {
            clearExpired();
            return reservation != null && active == reservation;
        }
    }

    boolean complete(Reservation reservation) {
        synchronized (lock) {
            clearExpired();
            if (reservation == null || active != reservation) return false;
            active = null;
            lock.notifyAll();
            return true;
        }
    }

    private void awaitReservation() {
        boolean interrupted = false;
        while (true) {
            clearExpired();
            if (active == null) break;
            long remaining = active.deadline - nowMs();
            if (remaining <= 0) continue;
            try { lock.wait(remaining); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void clearExpired() {
        if (active != null && nowMs() >= active.deadline) {
            active = null;
            lock.notifyAll();
        }
    }

    private static long nowMs() { return System.nanoTime() / 1_000_000L; }
}
