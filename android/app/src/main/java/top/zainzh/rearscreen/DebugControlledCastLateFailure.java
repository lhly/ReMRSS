package top.zainzh.rearscreen;

import android.os.SystemClock;

/** One-shot Debug-only failure after the real candidate PixelCopy and before reader handoff. */
final class DebugControlledCastLateFailure {
    private static final long ARM_TTL_MS = 150_000L;
    private static final Gate GATE = new Gate(SystemClock::elapsedRealtime);

    private DebugControlledCastLateFailure() { }

    static boolean armKnownSampleReplacement(String aSession, String aOwner, long aGeneration,
            int aTask, int aHost, String aReader, String bComponent) {
        return BuildConfig.DEBUG && GATE.arm(aSession, aOwner, aGeneration, aTask, aHost, aReader, bComponent);
    }

    static boolean bindCandidate(String aSession, String aOwner, long aGeneration, int aTask,
            int aHost, String aReader, int bTask, String bComponent, int bDisplay,
            String bOwner, long bGeneration, String operationId, String token) {
        return BuildConfig.DEBUG && GATE.bind(aSession, aOwner, aGeneration, aTask, aHost, aReader,
                bTask, bComponent, bDisplay, bOwner, bGeneration, operationId, token);
    }

    static boolean consumeAfterPixelCopy(String operationId, String token) {
        return BuildConfig.DEBUG && GATE.consume(operationId, token);
    }

    static FixtureSnapshot fixtureForOperation(String operationId, String token) {
        return BuildConfig.DEBUG ? GATE.snapshot(operationId, token) : null;
    }

    static FixtureSnapshot armedSnapshot() { return BuildConfig.DEBUG ? GATE.armedSnapshot() : null; }

    static void completeCleanup(String operationId, String token, boolean success, String reason,
            long frames, long events) {
        if (BuildConfig.DEBUG) GATE.complete(operationId, token, success, reason, frames, events);
    }

    static FixtureSnapshot awaitCleanup(long timeoutMs) throws InterruptedException {
        return BuildConfig.DEBUG ? GATE.awaitCleanup(timeoutMs) : null;
    }

    static void clearIfNotInjected() { if (BuildConfig.DEBUG) GATE.clearIfNotInjected(); }

    static final class FixtureSnapshot {
        final String aSession, aOwner, aReader, bComponent, bOwner, operationId, token, cleanupReason;
        final long aGeneration, bGeneration, postFrames, postEvents;
        final int aTask, aHost, bTask, bDisplay;
        final boolean injected, cleanupComplete, cleanupSucceeded;

        FixtureSnapshot(Fixture f) {
            aSession = f.aSession; aOwner = f.aOwner; aReader = f.aReader; bComponent = f.bComponent;
            bOwner = f.bOwner; operationId = f.operationId; token = f.token; cleanupReason = f.cleanupReason;
            aGeneration = f.aGeneration; bGeneration = f.bGeneration;
            aTask = f.aTask; aHost = f.aHost; bTask = f.bTask; bDisplay = f.bDisplay;
            postFrames = f.postFrames; postEvents = f.postEvents;
            injected = f.injected; cleanupComplete = f.cleanupComplete; cleanupSucceeded = f.cleanupSucceeded;
        }
    }

    static final class Gate {
        interface Clock { long now(); }
        private final Clock clock;
        private Fixture fixture;

        Gate(Clock clock) { this.clock = clock; }

        synchronized boolean arm(String aSession, String aOwner, long aGeneration,
                int aTask, int aHost, String aReader, String bComponent) {
            expire();
            if (fixture != null || blank(aSession) || blank(aOwner)
                    || !aOwner.contains("transportPackageName=dev.remrss.sample.rc5consumer") || aGeneration <= 0L
                    || aTask <= 0 || aHost <= 0 || blank(aReader)
                    || !"dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity".equals(bComponent)) return false;
            fixture = new Fixture(clock.now() + ARM_TTL_MS, aSession, aOwner, aGeneration,
                    aTask, aHost, aReader, bComponent);
            return true;
        }

        synchronized boolean bind(String aSession, String aOwner, long aGeneration, int aTask,
                int aHost, String aReader, int bTask, String bComponent, int bDisplay,
                String bOwner, long bGeneration, String operationId, String token) {
            expire();
            Fixture f = fixture;
            if (f == null || !f.aSession.equals(aSession) || !f.aOwner.equals(aOwner)
                    || f.aGeneration != aGeneration || f.aTask != aTask || f.aHost != aHost
                    || !f.aReader.equals(aReader) || bTask <= 0 || bTask == f.aTask
                    || !f.bComponent.equals(bComponent) || bDisplay <= 0 || blank(bOwner)
                    || !bOwner.contains("transportPackageName=dev.remrss.sample.rc5candidate")
                    || bGeneration <= 0 || blank(operationId) || blank(token)) return false;
            if (f.operationId != null)
                return f.operationId.equals(operationId) && f.token.equals(token);
            f.bTask = bTask; f.bDisplay = bDisplay; f.bOwner = bOwner; f.bGeneration = bGeneration;
            f.operationId = operationId; f.token = token;
            return true;
        }

        synchronized boolean consume(String operationId, String token) {
            expire();
            Fixture f = fixture;
            if (f == null || f.operationId == null || !f.operationId.equals(operationId)
                    || !f.token.equals(token) || f.injected) return false;
            f.injected = true;
            notifyAll();
            return true;
        }

        synchronized FixtureSnapshot snapshot(String operationId, String token) {
            expire();
            Fixture f = fixture;
            return f != null && f.injected && eq(f.operationId, operationId) && eq(f.token, token)
                    ? new FixtureSnapshot(f) : null;
        }

        synchronized FixtureSnapshot armedSnapshot() {
            expire();
            return fixture == null ? null : new FixtureSnapshot(fixture);
        }

        synchronized void complete(String operationId, String token, boolean success, String reason,
                long frames, long events) {
            Fixture f = fixture;
            if (f == null || !f.injected || !eq(f.operationId, operationId) || !eq(f.token, token)) return;
            f.cleanupComplete = true; f.cleanupSucceeded = success; f.cleanupReason = reason;
            f.postFrames = frames; f.postEvents = events;
            notifyAll();
        }

        synchronized FixtureSnapshot awaitCleanup(long timeoutMs) throws InterruptedException {
            long end = clock.now() + timeoutMs;
            while (fixture != null && !fixture.cleanupComplete && clock.now() < end) {
                long remaining = end - clock.now();
                wait(Math.max(1L, Math.min(remaining, 250L)));
            }
            expire();
            return fixture == null ? null : new FixtureSnapshot(fixture);
        }

        synchronized void clearIfNotInjected() {
            if (fixture != null && !fixture.injected) { fixture = null; notifyAll(); }
        }

        private void expire() {
            if (fixture != null && clock.now() >= fixture.deadline && !fixture.injected) {
                fixture = null; notifyAll();
            }
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
        private static boolean eq(String a, String b) { return a != null && a.equals(b); }
    }

    private static final class Fixture {
        final long deadline, aGeneration;
        final String aSession, aOwner, aReader, bComponent;
        final int aTask, aHost;
        String bOwner, operationId, token, cleanupReason;
        long bGeneration;
        int bTask, bDisplay;
        long postFrames, postEvents;
        boolean injected, cleanupComplete, cleanupSucceeded;
        Fixture(long deadline, String aSession, String aOwner, long aGeneration,
                int aTask, int aHost, String aReader, String bComponent) {
            this.deadline = deadline; this.aSession = aSession; this.aOwner = aOwner;
            this.aGeneration = aGeneration; this.aTask = aTask; this.aHost = aHost;
            this.aReader = aReader; this.bComponent = bComponent;
        }
    }
}
