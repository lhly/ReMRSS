package top.zainzh.rearscreen;

import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Session-scoped rear input relay running as the Shizuku shell user. */
final class RearInputProxy {
    static final String READER_LIBRARY = "libremrss_input_proxy.so";
    interface Guard { boolean allowed(int task, int display) throws Exception; }
    interface AcceptedInteraction { void afterRouterFrameAccepted(long eventTimeUptime); }
    interface LifecycleReceipt { boolean record(String state, String id, long generation, int task, int display); }
    private static final java.util.concurrent.atomic.AtomicLong generationSequence = new java.util.concurrent.atomic.AtomicLong();
    private final Guard guard;
    private volatile Session current;
    private volatile String lastStatus = "{\"state\":\"stopped\"}";
    RearInputProxy(Guard guard) { this.guard = guard; }
    private static boolean waitForProcess(Process process, long milliseconds) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + milliseconds;
        do {
            try { process.exitValue(); return true; }
            catch (IllegalThreadStateException running) { }
            Thread.sleep(Math.min(20, Math.max(1, deadline - SystemClock.uptimeMillis())));
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }
    synchronized String start(String path, int task, IBinder owner) throws Exception {
        return start(path, task, owner, RearDisplay.requireId());
    }
    synchronized String start(String path, int task, IBinder owner, int display) throws Exception {
        return start(path, task, owner, display, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback) throws Exception {
        return start(path, task, owner, display, feedback, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback, RearCastViewport viewport) throws Exception {
        return start(path, task, owner, display, feedback, viewport, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                              RearCastViewport viewport, RearTouchRouter.Menu menu) throws Exception {
        return start(path, task, owner, display, feedback, viewport, menu, 0);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                              RearCastViewport viewport, RearTouchRouter.Menu menu, int contentRotation) throws Exception {
        return start(path, task, owner, display, feedback, viewport, menu, contentRotation, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                              RearCastViewport viewport, RearTouchRouter.Menu menu, int contentRotation,
                              AcceptedInteraction acceptedInteraction) throws Exception {
        return startInternal(path, task, owner, display, feedback, viewport, menu, contentRotation,
                acceptedInteraction, true, null, null);
    }
    /** Controlled empty start: it cannot stop or replace any existing reader. */
    synchronized String startControlled(String path, int task, IBinder owner, int display,
            RearTouchRouter.Feedback feedback, RearCastViewport viewport, RearTouchRouter.Menu menu,
            int contentRotation, AcceptedInteraction acceptedInteraction) throws Exception {
        return startInternal(path, task, owner, display, feedback, viewport, menu, contentRotation,
                acceptedInteraction, false, null, null);
    }
    synchronized String startControlled(String path, int task, IBinder owner, int display,
            RearTouchRouter.Feedback feedback, RearCastViewport viewport, RearTouchRouter.Menu menu,
            int contentRotation, AcceptedInteraction acceptedInteraction, LifecycleReceipt lifecycle) throws Exception {
        return startInternal(path, task, owner, display, feedback, viewport, menu, contentRotation,
                acceptedInteraction, false, lifecycle, null);
    }
    synchronized String startWallpaper(WallpaperTouch touch, int task, IBinder owner, int display,
            RearTouchRouter.Feedback feedback, RearCastViewport viewport, RearTouchRouter.Menu menu,
            int contentRotation, AcceptedInteraction acceptedInteraction) throws Exception {
        return startInternal(touch.path, task, owner, display, feedback, viewport, menu, contentRotation,
                acceptedInteraction, false, null, touch);
    }

    private String startInternal(String path, int task, IBinder owner, int display,
                                 RearTouchRouter.Feedback feedback, RearCastViewport viewport,
                                 RearTouchRouter.Menu menu, int contentRotation,
                                 AcceptedInteraction acceptedInteraction, boolean replaceExisting,
                                 LifecycleReceipt lifecycle, WallpaperTouch wallpaperTouch) throws Exception {
        Session next = null;
        boolean occupiedOwner = false;
        try {
            if (!BuildConfig.VIRTUAL_CAST_ENABLED && !BuildConfig.INPUT_PROXY_PROTOTYPE) throw new IllegalStateException("Rear input relay is unavailable");
            RearCastViewport.validateContentRotation(contentRotation);
            if (display < 1 || owner == null || !owner.isBinderAlive() || task < 0 || !guard.allowed(task, display)) throw new IllegalStateException("Rear task/owner unavailable");
            File file = new File(path).getCanonicalFile();
            if (!isInstalledReader(file, BuildConfig.APPLICATION_ID, file.canExecute()))
                throw new SecurityException("Reader must belong to this installed APK");
            if (replaceExisting) stop(null);
            else if (current != null) {
                occupiedOwner = true;
                if (lifecycle != null) lifecycle.record("UNKNOWN", "", 0L, task, display);
                throw new IllegalStateException("controlled_start_reader_busy");
            }
            next = new Session(file.getPath(), task, owner, display, feedback, viewport, menu,
                    contentRotation, acceptedInteraction, lifecycle);
            current = next;
            if (lifecycle != null && !lifecycle.record("ALLOCATED", next.id, next.generation, task, display))
                throw new IOException("reader_allocation_receipt_rejected");
            next.begin(wallpaperTouch);
            if (lifecycle != null && !lifecycle.record("READY", next.id, next.generation, task, display))
                throw new IOException("reader_ready_receipt_rejected");
            return next.status();
        } catch (Exception e) {
            if (next == null) {
                if (lifecycle != null && !occupiedOwner) lifecycle.record("NO_READER", "", 0L, task, display);
            } else {
                CompatibilityDiagnostics.record("input_start_failed", e.toString());
                next.close(e.toString());
            }
            throw e;
        }
    }
    @FunctionalInterface
    interface WallpaperRequest { void requireCurrent() throws Exception; }

    WallpaperTouch openWallpaperTouch(String path, int host, WallpaperRequest request) throws Exception {
        File file = new File(path).getCanonicalFile();
        if (!isInstalledReader(file, BuildConfig.APPLICATION_ID, file.canExecute()))
            throw new SecurityException("Reader must belong to this installed APK");
        request.requireCurrent();
        return new WallpaperTouch(file.getPath(), host, RearInputDevices.require(), request);
    }

    private static Process openReader(String path, RearInputPolicy.Device device, String mode) throws IOException {
        return new ProcessBuilder(path, mode, device.path, device.name,
                Integer.toString(device.xmin), Integer.toString(device.xmax),
                Integer.toString(device.ymin), Integer.toString(device.ymax), Integer.toString(device.slots))
                .redirectErrorStream(true).start();
    }

    /** One process for passive wait and the eventual reader; transfers only after target creation. */
    static final class WallpaperTouch implements AutoCloseable {
        private static final long MAX_WAIT_MS = 20_000;
        final String path;
        final RearInputPolicy.Device device;
        final Process process;
        final BufferedReader lines;
        final BufferedWriter commands;
        final WallpaperRequest request;
        final int host;
        final long opened = SystemClock.uptimeMillis();
        boolean transferred;
        WallpaperTouch(String path, int host, RearInputPolicy.Device device, WallpaperRequest request) throws IOException {
            this.path = path;
            this.host = host;
            this.device = device;
            this.request = request;
            process = openReader(path, device, "--wallpaper-stream");
            lines = new BufferedReader(new InputStreamReader(process.getInputStream()));
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        }
        void requireCurrent() throws Exception {
            request.requireCurrent();
            if (SystemClock.uptimeMillis() - opened >= MAX_WAIT_MS)
                throw new IOException("wallpaper_touch_wait_expired");
        }
        void awaitNeutral() throws Exception {
            while (true) {
                requireCurrent();
                if (lines.ready()) {
                    String line = lines.readLine();
                    if ("NEUTRAL".equals(line)) {
                        android.util.Log.i("RearInputProxy", "wallpaper_neutral host=" + host + " elapsedMs="
                                + (SystemClock.uptimeMillis() - opened));
                        return;
                    }
                    if (!"WAIT_RELEASE".equals(line)) throw new IOException("wallpaper_touch_observation_lost");
                    logWaiting();
                } else {
                    if (!process.isAlive()) throw new IOException("wallpaper_touch_observer_exited");
                    Thread.sleep(35);
                }
            }
        }
        void logWaiting() {
            android.util.Log.i("RearInputProxy", "wallpaper_wait_release host=" + host + " elapsedMs="
                    + (SystemClock.uptimeMillis() - opened));
        }
        void sendArm() throws IOException {
            commands.write("R 0\n");
            commands.flush();
        }
        @Override public void close() {
            if (transferred) return;
            try { commands.close(); } catch (IOException ignored) { }
            try {
                if (!waitForProcess(process, 300)) process.destroy();
                if (!waitForProcess(process, 300)) process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            try { lines.close(); } catch (IOException ignored) { }
        }
    }

    static boolean isInstalledReader(File canonicalFile, String applicationId, boolean executable) {
        String path = canonicalFile.getPath();
        return path.startsWith("/data/app/") && path.contains("/" + applicationId + "-")
                && canonicalFile.getName().equals(READER_LIBRARY) && executable;
    }
    synchronized void stop(String id) {
        Session s = current;
        if (s != null && (id == null || s.id.equals(id))) s.close("stopped");
    }
    boolean stopAndAwait(String id) {
        if (id == null || id.isBlank()) return current == null;
        Session s = current;
        if (s != null && s.id.equals(id)) s.close("stopped");
        try {
            JSONObject state = new JSONObject(status());
            return id.equals(state.optString("id")) && "stopped".equals(state.optString("state"))
                    && !state.optBoolean("outputAvailable", true);
        } catch (Exception unknown) { return false; }
    }
    void taskLeaving(int task) { Session s = current; if (s != null && s.task == task) stop(s.id); }
    String status() {
        Session s = current;
        if (s != null) return s.status();
        try {
            JSONObject status = new JSONObject(lastStatus);
            // A clean no-reader baseline is known stopped; preserve stopping/unknown terminals
            // rather than promoting a missing current object to proof of process exit.
            boolean stopped = "stopped".equals(status.optString("state"))
                    && (!status.has("resourcesStopped") || status.optBoolean("resourcesStopped"));
            status.put("resourcesStopped", stopped).put("outputAvailable", stopped ? false
                    : status.optBoolean("outputAvailable", false));
            return status.toString();
        } catch (Exception unknown) { return "{}"; }
    }
    boolean notification(String id, long token, int left, int top, int right, int bottom, RearTouchRouter.NotificationDismiss dismiss) {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            s.router.notification(token, left, top, right, bottom, dismiss); return true;
        }
    }
    boolean renew(String id) {
        Session s = current;
        if (s == null || !s.id.equals(id) || s.closed) return false;
        s.lease = SystemClock.uptimeMillis(); return true;
    }
    boolean outputAvailable(String id, boolean available) throws Exception {
        return outputAvailable(id, available, null);
    }
    boolean outputAvailable(String id, boolean available, BooleanSupplier actionCurrent) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            if (actionCurrent != null && !actionCurrent.getAsBoolean()) return false;
            s.setOutputAvailableLocked(available);
            return true;
        }
    }
    boolean pauseForTransfer(String id, String transferToken) throws Exception {
        Session s = current;
        return s != null && s.id.equals(id) && s.pauseForTransfer(transferToken);
    }
    boolean rebindPaused(String id, String transferToken, int task, int display, IBinder nextOwner,
                         RearCastViewport viewport, int contentRotation, RearTouchRouter.Feedback feedback,
                         RearTouchRouter.Menu menu, AcceptedInteraction acceptedInteraction) throws Exception {
        Session s = current;
        return s != null && s.id.equals(id) && s.rebindPaused(transferToken, task, display, nextOwner,
                viewport, contentRotation, feedback, menu, acceptedInteraction);
    }
    boolean resumeTransfer(String id, String transferToken) throws Exception {
        Session s = current;
        return s != null && s.id.equals(id) && s.resumeTransfer(transferToken);
    }
    boolean commitTransfer(String id, String transferToken) {
        Session s = current;
        return s != null && s.id.equals(id) && s.commitTransfer(transferToken);
    }
    boolean abortTransfer(String id, String transferToken) throws Exception {
        Session s = current;
        return s != null && s.id.equals(id) && s.abortTransfer(transferToken);
    }
    void dismissMenu(String id) throws Exception {
        Session s = current;
        if (s != null && s.id.equals(id)) synchronized (s) { if (!s.closed) s.router.dismissMenu(SystemClock.uptimeMillis()); }
    }
    boolean beginRotation(String id) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            s.configuring = true; s.setOutputAvailableLocked(false); return true;
        }
    }
    boolean finishRotation(String id, int rotation, boolean available) throws Exception {
        Session s = current;
        return s != null && finishConfiguration(id, s.viewport, rotation, available);
    }
    boolean finishConfiguration(String id, RearCastViewport viewport, int rotation, boolean available) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            s.viewport = viewport; s.contentRotation = rotation; s.configuring = false; s.geometry();
            s.state.configure(viewport, rotation, SystemClock.uptimeMillis());
            s.setOutputAvailableLocked(available); return true;
        }
    }
    private final class Session {
        final String id = UUID.randomUUID().toString(), path;
        final long generation = generationSequence.incrementAndGet();
        final LifecycleReceipt lifecycle;
        volatile int task, display;
        final String physicalIdentity = RearDisplay.identity();
        volatile IBinder owner;
        volatile RearTouchRouter.Feedback feedback;
        volatile RearTouchRouter.Menu menu;
        volatile AcceptedInteraction acceptedInteraction;
        volatile RearInputInjector injector;
        private Transfer transfer;
        private volatile long ackEpoch = -1;
        private volatile java.util.concurrent.CountDownLatch ack = new java.util.concurrent.CountDownLatch(0);
        volatile boolean configuring;
        volatile int contentRotation;
        final RearCastViewport safeViewport;
        volatile RearCastViewport viewport;
        final AtomicReference<IBinder.DeathRecipient> death = new AtomicReference<>();
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        // Task validation can execute shell commands; it must not delay gesture readiness.
        final ScheduledExecutorService gestureTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "rear-gesture-clock"); thread.setDaemon(true); return thread;
        });
        final PausableGestureClock gestureClock;
        volatile long lease = SystemClock.uptimeMillis(), frames, events, backs;
        volatile boolean closed, ready;
        volatile boolean outputAvailable = true;
        volatile boolean resourcesStopped;
        volatile boolean resumeRequested;
        long outputEpoch;
        long resumeRequestedEpoch = -1;
        boolean readerReadySeen;
        volatile String reason = "starting", node = "";
        Process process;
        BufferedWriter commands;
        RearTouchState state;
        RearTouchRouter router;
        int xmin, xmax, ymin, ymax, slots, width, height, rotation, density;
        final class Transfer {
            final String token;
            final int task, display, contentRotation;
            final IBinder owner;
            final RearCastViewport viewport;
            final RearTouchRouter.Feedback feedback;
            final RearTouchRouter.Menu menu;
            final AcceptedInteraction acceptedInteraction;
            final RearInputInjector injector;
            final IBinder.DeathRecipient death;
            IBinder.DeathRecipient transferDeath;
            boolean rebound, resumed;
            Transfer(String token) {
                this.token = token; task = Session.this.task; display = Session.this.display;
                owner = Session.this.owner; viewport = Session.this.viewport;
                contentRotation = Session.this.contentRotation; feedback = Session.this.feedback;
                menu = Session.this.menu; acceptedInteraction = Session.this.acceptedInteraction;
                injector = Session.this.injector; death = Session.this.death.get();
            }
        }
        Session(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                RearCastViewport viewport, RearTouchRouter.Menu menu, int contentRotation,
                AcceptedInteraction acceptedInteraction, LifecycleReceipt lifecycle) throws Exception {
            this.path = path; this.task = task; this.owner = owner; this.display = display; this.feedback = feedback;
            this.lifecycle = lifecycle;
            this.viewport = viewport; this.safeViewport = viewport == null ? null : RearCastGeometry.read(); this.menu = menu;
            this.contentRotation = contentRotation; this.acceptedInteraction = acceptedInteraction;
            gestureClock = new PausableGestureClock(gestureTimer, () -> {
                try { if (!closed && outputAvailable && router != null) router.tick(SystemClock.uptimeMillis()); }
                catch (Exception e) { close(e.toString()); }
            }, 20);
        }
        private synchronized void linkOwner(IBinder next) throws Exception {
            if (next == null || !next.isBinderAlive()) throw new IOException("Input owner is unavailable");
            IBinder.DeathRecipient nextDeath = () -> { if (owner == next) close("owner_died"); };
            next.linkToDeath(nextDeath, 0);
            IBinder previous = owner;
            IBinder.DeathRecipient previousDeath = death.get();
            owner = next; death.set(nextDeath);
            if (previous != null && previous != next && previousDeath != null) previous.unlinkToDeath(previousDeath, 0);
        }

        boolean pauseForTransfer(String token) throws Exception {
            if (token == null || token.isEmpty()) return false;
            CountDownLatch waiting;
            long expected;
            synchronized (this) {
                if (closed || transfer != null || !ready || !outputAvailable || commands == null) return false;
                transfer = new Transfer(token);
                waiting = new CountDownLatch(1); ack = waiting;
                expected = outputEpoch + 1; ackEpoch = expected;
                setOutputAvailableLocked(false);
            }
            if (!waiting.await(1200, TimeUnit.MILLISECONDS)) {
                // A timeout is not evidence that the reader stopped. Attempt the bounded inverse
                // while the original route is still installed; caller still treats this as failure.
                try { abortTransfer(token); }
                catch (Exception rollbackFailure) { android.util.Log.e("RearInputProxy", "Transfer pause rollback failed", rollbackFailure); }
                return false;
            }
            synchronized (this) {
                return !closed && transfer != null && transfer.token.equals(token)
                        && ackEpoch == expected && !ready && !outputAvailable;
            }
        }

        boolean rebindPaused(String token, int nextTask, int nextDisplay, IBinder nextOwner,
                             RearCastViewport nextViewport, int nextRotation,
                             RearTouchRouter.Feedback nextFeedback, RearTouchRouter.Menu nextMenu,
                             AcceptedInteraction nextAccepted) throws Exception {
            if (nextTask < 0 || nextDisplay < 1 || nextOwner == null || !nextOwner.isBinderAlive()
                    || nextViewport == null || !guard.allowed(nextTask, nextDisplay)) return false;
            RearCastViewport.validateContentRotation(nextRotation);
            RearInputInjector nextInjector = RearInputInjector.shell(nextDisplay);
            synchronized (this) {
                Transfer pending = transfer;
                if (closed || pending == null || !pending.token.equals(token) || outputAvailable || ready
                        || !owner.isBinderAlive()) return false;
                if (pending.rebound) return task == nextTask && display == nextDisplay && owner == nextOwner;
                if (nextOwner != owner) {
                    IBinder.DeathRecipient nextDeath = () -> { if (owner == nextOwner) close("owner_died"); };
                    nextOwner.linkToDeath(nextDeath, 0);
                    pending.transferDeath = nextDeath;
                }
                try {
                    // End any old logical pointer while its original route is still installed.
                    state.configure(nextViewport, nextRotation, SystemClock.uptimeMillis());
                    task = nextTask; display = nextDisplay; owner = nextOwner; injector = nextInjector;
                    viewport = nextViewport; contentRotation = nextRotation;
                    feedback = nextFeedback; menu = nextMenu; acceptedInteraction = nextAccepted;
                    geometry(); router.geometry(width, height, density);
                    router.setFeedback(nextFeedback); router.setMenu(nextMenu);
                    if (pending.transferDeath != null && pending.owner != nextOwner && pending.death != null)
                        pending.owner.unlinkToDeath(pending.death, 0);
                    death.set(nextOwner == pending.owner ? pending.death : pending.transferDeath);
                    pending.rebound = true;
                    return true;
                } catch (Exception failure) {
                    restoreTransferRoute(pending);
                    throw failure;
                }
            }
        }

        boolean resumeTransfer(String token) throws Exception {
            CountDownLatch waiting;
            long expected;
            synchronized (this) {
                if (closed || transfer == null || !transfer.token.equals(token) || outputAvailable
                        || commands == null || owner == null || !owner.isBinderAlive()) return false;
                waiting = new CountDownLatch(1); ack = waiting;
                expected = outputEpoch + 1; ackEpoch = expected;
                setOutputAvailableLocked(true);
                expected = resumeRequestedEpoch;
            }
            if (!waiting.await(1200, TimeUnit.MILLISECONDS)) return false;
            synchronized (this) {
                if (closed || transfer == null || !transfer.token.equals(token) || !ready || !outputAvailable
                        || ackEpoch != expected) return false;
                transfer.resumed = true;
                return true;
            }
        }

        boolean abortTransfer(String token) throws Exception {
            CountDownLatch waiting = null;
            long expected = -1;
            synchronized (this) {
                if (closed) return false;
                if (transfer == null) return ready && outputAvailable;
                if (!transfer.token.equals(token)) return false;
                if (outputAvailable) {
                    waiting = new CountDownLatch(1); ack = waiting;
                    expected = outputEpoch + 1; ackEpoch = expected;
                    setOutputAvailableLocked(false);
                }
            }
            if (waiting != null && !waiting.await(1200, TimeUnit.MILLISECONDS)) return false;
            synchronized (this) {
                if (closed || transfer == null || !transfer.token.equals(token) || outputAvailable || ready)
                    return false;
                if (transfer.rebound) restoreTransferRoute(transfer);
            }
            if (!resumeTransfer(token)) return false;
            synchronized (this) {
                if (closed || transfer == null || !transfer.token.equals(token) || !transfer.resumed) return false;
                transfer = null;
                return true;
            }
        }

        boolean commitTransfer(String token) {
            synchronized (this) {
                if (closed || transfer == null || !transfer.token.equals(token)
                        || !transfer.rebound || !transfer.resumed || !ready || !outputAvailable) return false;
                transfer = null;
                return true;
            }
        }

        private void restoreTransferRoute(Transfer pending) throws Exception {
            IBinder failedOwner = owner;
            IBinder.DeathRecipient failedDeath = death.get();
            if (pending.owner != failedOwner) {
                pending.owner.linkToDeath(pending.death, 0);
                if (failedOwner != null && failedDeath != null) failedOwner.unlinkToDeath(failedDeath, 0);
            }
            task = pending.task; display = pending.display; owner = pending.owner; death.set(pending.death);
            injector = pending.injector; viewport = pending.viewport; contentRotation = pending.contentRotation;
            feedback = pending.feedback; menu = pending.menu; acceptedInteraction = pending.acceptedInteraction;
            state.configure(viewport, contentRotation, SystemClock.uptimeMillis());
            geometry(); router.geometry(width, height, density);
            router.setFeedback(feedback); router.setMenu(menu);
            pending.rebound = false;
        }

        void begin(WallpaperTouch wallpaperTouch) throws Exception {
            linkOwner(owner);
            injector = RearInputInjector.shell(display);
            state = new RearTouchState(new RearTouchState.Output() {
                public void event(int action, int[] ids, float[] xs, float[] ys, long down, long time) throws Exception {
                    RearInputInjector currentInjector = Session.this.injector;
                    if (currentInjector == null) throw new IOException("Input route is unavailable");
                    currentInjector.touch(action, ids, xs, ys, down, time);
                    events++;
                }
                public void ended(float x, float y) throws Exception {
                    RearInputInjector currentInjector = Session.this.injector;
                    if (currentInjector != null && RearDisplay.isRear(Session.this.display))
                        currentInjector.resetSystemGesture("scroll", x, y);
                }
            }, viewport);
            state.configure(viewport, contentRotation, SystemClock.uptimeMillis());
            RearInputPolicy.Device device = wallpaperTouch == null ? RearInputDevices.require() : wallpaperTouch.device;
            node = device.path;
            geometry();
            router = new RearTouchRouter(state, width, height, density, !RearDisplay.isRear(display), time -> {
                int targetTask = Session.this.task, targetDisplay = Session.this.display;
                RearInputInjector currentInjector = Session.this.injector;
                if (!outputAvailable || currentInjector == null || !guard.allowed(targetTask, targetDisplay))
                    throw new IOException("Virtual Back target left");
                currentInjector.back(time); backs++;
                android.util.Log.i("RearInputProxy", "back_sent id=" + id + " display=" + targetDisplay + " task=" + targetTask);
            }, feedback);
            router.setMenu(menu);
            gestureClock.resume();
            final BufferedReader responses;
            if (wallpaperTouch == null) {
                process = openReader(path, device, "--stream");
                responses = new BufferedReader(new InputStreamReader(process.getInputStream()));
                commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            } else {
                process = wallpaperTouch.process;
                commands = wallpaperTouch.commands;
                responses = wallpaperTouch.lines;
                wallpaperTouch.transferred = true;
                wallpaperTouch.requireCurrent();
                wallpaperTouch.sendArm();
            }
            CountDownLatch started = new CountDownLatch(1);
            new Thread(() -> read(started, responses, wallpaperTouch), "rear-input-reader").start();
            // Both the native watchdog and this lease are independent of Activity destruction.
            timer.scheduleAtFixedRate(() -> {
                try {
                    if (SystemClock.uptimeMillis() - lease > 4000) { close("lease_expired"); return; }
                    if (!owner.isBinderAlive() || !guard.allowed(task, display)) { close("task_or_owner_left"); return; }
                    synchronized (this) { if (!closed) { commands.write("P\n"); commands.flush(); } }
                } catch (Exception e) { close(e.toString()); }
            }, 500, 500, TimeUnit.MILLISECONDS);
            if (wallpaperTouch == null) {
                if (!started.await(3500, TimeUnit.MILLISECONDS) || !ready || closed)
                    throw new IOException("Rear grab failed: " + reason);
            } else {
                while (!started.await(100, TimeUnit.MILLISECONDS)) {
                    wallpaperTouch.requireCurrent();
                    lease = SystemClock.uptimeMillis();
                }
                if (!ready || closed) throw new IOException("Rear grab failed: " + reason);
            }
        }
        synchronized void setOutputAvailableLocked(boolean available) throws Exception {
            if (closed) return;
            if (!available) {
                boolean cancelResume = resumeRequested;
                resumeRequested = false;
                resumeRequestedEpoch = -1;
                if (!outputAvailable) {
                    if (cancelResume && commands != null) {
                        outputEpoch++; commands.write("S " + outputEpoch + "\n"); commands.flush();
                    }
                    return;
                }
                outputEpoch++;
                router.outputAvailable(false, SystemClock.uptimeMillis());
                outputAvailable = false;
                gestureClock.pause();
                router.cancel(SystemClock.uptimeMillis()); router.frame(Collections.emptyList(), SystemClock.uptimeMillis());
                if (commands != null) { commands.write("S " + outputEpoch + "\n"); commands.flush(); }
                return;
            }
            if (outputAvailable || resumeRequested) return;
            resumeRequested = true;
            resumeRequestedEpoch = ++outputEpoch;
            // Input and the 20 ms gesture clock remain paused until native confirms the
            // EVIOCGRAB was safely reacquired after all contacts ended.
            if (commands != null) { commands.write("R " + resumeRequestedEpoch + "\n"); commands.flush(); }
        }
        synchronized void geometry() throws Exception {
            if (!physicalIdentity.equals(RearDisplay.identity())) throw new IOException("Rear identity changed; restart casting");
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, RearDisplay.requireId());
            if (info == null) throw new IOException("Rear display missing");
            width = info.getClass().getField("logicalWidth").getInt(info);
            height = info.getClass().getField("logicalHeight").getInt(info);
            rotation = info.getClass().getField("rotation").getInt(info);
            density = info.getClass().getField("logicalDensityDpi").getInt(info);
            if (width < 2 || height < 2) throw new IOException("Invalid rear dimensions");
            if (!RearDisplay.isRear(display)) {
                Object target = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
                if (viewport == null || !safeViewport.key().equals(RearCastGeometry.read().key()) || target == null
                        || (!configuring && (target.getClass().getField("rotation").getInt(target) != contentRotation
                        || viewport.contentWidth(contentRotation) != target.getClass().getField("logicalWidth").getInt(target)
                        || viewport.contentHeight(contentRotation) != target.getClass().getField("logicalHeight").getInt(target))))
                    throw new IOException("Virtual/rear geometry changed; restart casting");
            }
        }
        void read(CountDownLatch started, BufferedReader responses, WallpaperTouch wallpaperTouch) {
            try (BufferedReader r = responses) {
                String line;
                while (!closed && (line = r.readLine()) != null) {
                    String[] parts = line.split(" ");
                    if (RearInputReaderLineProtocol.discardFrameIfNotReady(parts, ready && outputAvailable && !closed, slots)) continue;
                    if (wallpaperTouch != null && !readerReadySeen && "WAIT_RELEASE".equals(line)) {
                        wallpaperTouch.logWaiting();
                    } else if (wallpaperTouch != null && !readerReadySeen && "NEUTRAL".equals(line)) {
                        wallpaperTouch.requireCurrent();
                        synchronized (this) {
                            if (!closed) wallpaperTouch.sendArm();
                        }
                    } else if (parts[0].equals("READY2")) {
                        if (wallpaperTouch != null && !readerReadySeen) wallpaperTouch.requireCurrent();
                        if (parts.length != 8 || !parts[2].equals(node)) throw new IOException("Invalid reader handshake");
                        long ackEpoch = Long.parseLong(parts[1]);
                        slots = Integer.parseInt(parts[3]); xmin = Integer.parseInt(parts[4]); xmax = Integer.parseInt(parts[5]);
                        ymin = Integer.parseInt(parts[6]); ymax = Integer.parseInt(parts[7]);
                        if (slots < 1 || slots > 16 || xmax <= xmin || ymax <= ymin) throw new IOException("Invalid reader ranges");
                        synchronized (this) {
                            boolean accept = RearInputReaderAckGate.acceptReady(ackEpoch, outputEpoch,
                                    resumeRequestedEpoch, outputAvailable, closed);
                            if (accept) {
                                boolean resumed = resumeRequestedEpoch >= 0 && ackEpoch == resumeRequestedEpoch;
                                readerReadySeen = true;
                                if (resumed) {
                                    resumeRequested = false; resumeRequestedEpoch = -1; outputAvailable = true;
                                    router.outputAvailable(true, SystemClock.uptimeMillis()); gestureClock.resume();
                                }
                                ready = true; reason = "running"; started.countDown();
                                if (resumed && this.ackEpoch == ackEpoch && ack.getCount() > 0) ack.countDown();
                            }
                        }
                        android.util.Log.i("RearInputProxy", "session_ready id=" + id + " task=" + task + " available=" + outputAvailable);
                    } else if (parts[0].equals("PAUSED")) {
                        synchronized (this) {
                            if (parts.length != 2) throw new IOException("Invalid reader pause ACK");
                            long ackEpoch = Long.parseLong(parts[1]);
                            if (RearInputReaderAckGate.acceptPaused(ackEpoch, outputEpoch, outputAvailable, closed)) {
                                ready = false;
                                if (this.ackEpoch == ackEpoch && ack.getCount() > 0) ack.countDown();
                            }
                        }
                        android.util.Log.i("RearInputProxy", "session_input_released id=" + id);
                    } else if (parts[0].equals("RESET")) { synchronized (this) { router.cancel(SystemClock.uptimeMillis()); router.frame(Collections.emptyList(), SystemClock.uptimeMillis()); } }
                    else if (parts[0].equals("F")) {
                        int count = Integer.parseInt(parts[2]);
                        if (count < 0 || count > slots || parts.length != count + 3) throw new IOException("Invalid rear frame");
                        int oldRotation = rotation, oldWidth = width, oldHeight = height, oldDensity = density;
                        geometry();
                        boolean geometryChanged = rotation != oldRotation || width != oldWidth || height != oldHeight || density != oldDensity;
                        List<RearTouchState.Contact> contacts = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            String[] c = parts[i + 3].split(":");
                            if (c.length != 4) throw new IOException("Invalid rear contact");
                            float[] xy = RearTouchState.coordinate((Float.parseFloat(c[2]) - xmin) / (xmax - xmin),
                                    (Float.parseFloat(c[3]) - ymin) / (ymax - ymin), width, height, rotation);
                            contacts.add(new RearTouchState.Contact(Integer.parseInt(c[0]), Integer.parseInt(c[1]), xy[0], xy[1]));
                        }
                        long eventTime = Math.min(SystemClock.uptimeMillis(), Long.parseLong(parts[1]));
                        boolean interactionAccepted;
                        synchronized (this) {
                            if (closed) break;
                            // A frame buffered just before the native S/PAUSED handshake is not
                            // active user input once the owning output has been paused.
                            if (!ready || !outputAvailable) continue;
                            if (geometryChanged) {
                                router.cancel(SystemClock.uptimeMillis()); router.geometry(width, height, density);
                            }
                            frames++;
                            interactionAccepted = router.frame(contacts, eventTime);
                        }
                        // Task/power inspection can block. Keep it outside the InputSession monitor so
                        // pause/close can invalidate the report while its producer query is in flight.
                        try { dispatchAcceptedRouterInteraction(interactionAccepted, eventTime, acceptedInteraction); }
                        catch (RuntimeException ignored) { /* Activity reporting must not interrupt accepted input. */ }
                    } else if (!parts[0].equals("RELEASED")) throw new IOException(line);
                }
                close("reader_exited");
            } catch (Exception e) { close(e.toString()); }
            finally { started.countDown(); }
        }
        synchronized void close(String why) {
            if (closed && resourcesStopped) return;
            closed = true; ready = false; reason = why; timer.shutdown(); gestureClock.close();
            unlinkClosingOwner(owner, death);
            try { if (router != null) router.cancel(SystemClock.uptimeMillis()); else if (state != null) state.cancel(SystemClock.uptimeMillis()); } catch (Exception ignored) { }
            try { if (commands != null) { commands.write("Q\n"); commands.flush(); commands.close(); } } catch (Exception ignored) { }
            if (process != null) {
                try {
                    if (!waitForProcess(process, 700)) process.destroy();
                    if (!waitForProcess(process, 700)) process.destroyForcibly();
                    resourcesStopped = waitForProcess(process, 700);
                } catch (InterruptedException e) {
                    process.destroyForcibly(); Thread.currentThread().interrupt();
                    resourcesStopped = !process.isAlive();
                }
            } else resourcesStopped = true;
            if (resourcesStopped) {
                outputAvailable = false;
            }
            if (resourcesStopped && lifecycle != null)
                lifecycle.record("RELEASED", id, generation, task, display);
            lastStatus = status();
            android.util.Log.i("RearInputProxy", "session_stopped id=" + id + " task=" + task
                    + " reason=" + reason + " frames=" + frames + " events=" + events);
            if (resourcesStopped && current == this) current = null;
        }
        String status() {
            try { return VirtualCastTerminalStatus.write(new JSONObject().put("id", id)
                    .put("reason", reason).put("generation", generation).put("node", node).put("task", task).put("display", display)
                    .put("frames", frames).put("events", events).put("backs", backs)
                    .put("contentRotation", contentRotation).put("menuOpen", router != null && router.isMenuOpen()),
                    closed, resourcesStopped, outputAvailable).toString(); }
            catch (Exception e) { return "{}"; }
        }
    }

    /** A reader exit may need a second cleanup pass; its binder registration is released only once. */
    static void unlinkClosingOwner(IBinder owner, AtomicReference<IBinder.DeathRecipient> link) {
        IBinder.DeathRecipient linked = link.getAndSet(null);
        if (linked == null) return;
        try { owner.unlinkToDeath(linked, 0); }
        catch (NoSuchElementException alreadyUnlinked) { /* Binder has already discarded the registration. */ }
    }

    /** Called only for an accepted contact or matched final-UP frame, never RESET/CANCEL or a clock tick. */
    static boolean dispatchAcceptedRouterInteraction(boolean accepted, long eventTimeUptime, AcceptedInteraction callback) {
        if (!accepted || callback == null) return false;
        callback.afterRouterFrameAccepted(eventTimeUptime);
        return true;
    }

}
