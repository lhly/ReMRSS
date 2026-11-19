package top.zainzh.rearscreen;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import top.zainzh.rearscreen.platform.CastOccupantIdentity;
import top.zainzh.rearscreen.platform.LegacyDisplayOccupancy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Hosts the rear output Surface and controls the owned virtual casting session. */
@androidx.annotation.RequiresApi(33)
public final class VirtualRearHostActivity extends androidx.activity.ComponentActivity implements SurfaceHolder.Callback {
    public static volatile VirtualRearHostActivity instance;
    private static final long WALLPAPER_OPENING_HINT_MS = 700L;
    private static final Set<String> cancelledControlledOperations = ConcurrentHashMap.newKeySet();
    private static volatile String lastControlledStartCategory = "not_run";
    public volatile String status = "{}";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final IBinder owner = new Binder();
    private volatile ITaskService service;
    private volatile SurfaceHolder holder;
    private SurfaceView output;
    private volatile SurfaceView candidateOutput;
    private volatile SurfaceHolder candidateHolder;
    private RearCastViewport candidateViewport;
    private JSONObject activeSessionStatus;
    private volatile String candidateToken;
    private volatile String candidateOperationId;
    private volatile String controlledOperationId, controlledOwnerIdentity, controlledExpectedBaseComponent;
    private volatile long controlledOwnerGeneration;
    private volatile String candidateOwnerIdentity, candidateExpectedBaseComponent;
    private volatile ITaskService candidateIdentityService;
    private volatile ISceneTaskActionGate candidateSourceActionGate;
    private volatile long candidateOwnerGeneration;
    private volatile int candidateRearDisplayId;
    private volatile String candidateRearIdentity;
    private volatile int candidateDisplayId = -1;
    private String candidatePackage, candidateComponent;
    private int candidateTask = -1, candidateDensity, candidateRotation, candidateFrameWidth, candidateFrameHeight;
    private boolean candidateCompatibility, candidateFull, candidatePrepared, candidatePaused, candidateBound;
    private int candidateFrameAttempts;
    private volatile DisplayActionGate.Reservation candidateReservation;
    private final top.zainzh.rearscreen.platform.CandidateActionGateSlot<ISceneTaskActionGate> candidateActionGate =
            new top.zainzh.rearscreen.platform.CandidateActionGateSlot<>();
    private FrameLayout root;
    private final ViewTreeObserver.OnGlobalLayoutListener presentationLayout = this::layoutViewport;
    private androidx.activity.OnBackPressedCallback presentationBack;
    private androidx.activity.OnBackPressedCallback wallpaperPickerBack;
    private androidx.compose.ui.platform.ComposeView wallpaperPicker;
    private androidx.compose.ui.platform.ComposeView wallpaperOpeningHint;
    private volatile boolean wallpaperStartCancelled;
    private RearWallpaperStandby wallpaperStandby;
    private RearCastViewport viewport;
    private RearCastViewport safeViewport;
    private boolean densityCompatibility;
    private int requestedLayoutDensity = VirtualCastRequest.DEFAULT_DENSITY_DPI;
    private int initialRotation;
    private boolean initialFull;
    private volatile String sessionPackage;
    private boolean resizing, resizeSubmitted;
    private boolean viewportPlaced;
    private String lastViewportLayout;
    private volatile String session;
    private volatile boolean starting;
    private volatile boolean reconnecting;
    private volatile boolean powerSuspendedOutput;
    private volatile boolean surfaceConfigured;
    private final VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate missingSurfacePresentationGate =
            new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
    private int missingSurfaceLossEpoch;
    private int outputTimeoutLossEpoch;
    private int outputTimeoutId;
    private volatile int outputGeneration;
    private volatile boolean ending;
    private volatile int recoveryActionEpoch;
    private volatile int screenOffCycleEpoch;
    private volatile int outputPowerEpoch;
    private volatile int gesturePreviewGeneration;
    private RearBackGestureView backIndicator;
    private RearCastMenuUi castMenu;
    private RearCameraGapBatteryController cameraGapBattery;
    private RearCameraStatusController cameraStatus;
    private RearCastNotificationOverlay castNotification;
    private boolean hostResumed;
    private boolean primaryInteractive;
    private boolean primaryKeyguardLocked;
    private int observedRearDisplayState = android.view.Display.STATE_UNKNOWN;
    private final android.hardware.display.DisplayManager.DisplayListener rearDisplayListener = new android.hardware.display.DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId != RearDisplay.id()) return;
            android.view.Display display = getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
            int state = display == null ? android.view.Display.STATE_UNKNOWN : display.getState();
            int previous = observedRearDisplayState;
            observedRearDisplayState = state;
            if (VirtualCastOutputRecoveryPolicy.isRearDisplaySuspendEdge(starting && session != null, ending, previous, state))
                main.post(VirtualRearHostActivity.this::onRearDisplaySuspended);
            else if (VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(starting && session != null, ending, previous, state))
                main.post(() -> VirtualRearHostActivity.this.onRearDisplayResumed(true));
        }
    };
    private final RearCastNotification.Target notificationTarget = new RearCastNotification.Target() {
        public void show(RearCastNotification.Message message) {
            main.post(() -> { if (!ending && instance == VirtualRearHostActivity.this && castNotification != null) castNotification.offer(message); });
        }
        public void clearNotification(long token) {
            clearNotification(token, "CANCELLED");
        }
        public void clearNotification(long token, String reason) {
            main.post(() -> { if (!ending && instance == VirtualRearHostActivity.this && castNotification != null) castNotification.dismiss(token, reason); });
        }
        public void clearCharging() {
            main.post(() -> { if (!ending && instance == VirtualRearHostActivity.this && castNotification != null) castNotification.dismissCharging(); });
        }
        public void presentationAvailable() {
            main.post(() -> {
                if (!ending && instance == VirtualRearHostActivity.this && session != null)
                    top.zainzh.rearscreen.platform.RearCapabilityCore.get(VirtualRearHostActivity.this)
                            .onNotificationPresentationAvailable();
            });
        }
    };
    private boolean menuVisible, menuBusy;
    private final Runnable viewportTimeout = () -> { if (resizing) fail(new IllegalStateException("Rear viewport change timed out")); };
    private void toggleViewport() {
        if (ending || menuBusy || session == null || reconnecting || resizing) return;
        menuBusy = true; castMenu.setBusy(true); menuVisible = false; castMenu.show(false); clearFeedback();
        resizing = true; resizeSubmitted = false; outputGeneration++;
        updateCameraGapBattery();
        main.postDelayed(viewportTimeout, 4500);
        String id = session; boolean full = !viewport.isFull();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Control service disconnected");
                RearCastViewport next = RearCastGeometry.decode(service.beginRearCastViewport(id, full));
                main.post(() -> {
                    if (ending || !id.equals(session)) return;
                    viewport = next; viewportPlaced = false; layoutViewport();
                });
            } catch (Exception e) { fail(e); }
        });
    }
    private void maybeFinishViewport() {
        if (!resizing || resizeSubmitted || ending || !viewportPlaced || holder == null
                || width != viewport.bufferWidth || height != viewport.bufferHeight || !holder.getSurface().isValid()) return;
        resizeSubmitted = true;
        String id = session; Surface surface = holder.getSurface();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Control service disconnected");
                String confirmed = service.finishRearCastViewport(id, surface);
                status = confirmed;
                rememberPresentation(confirmed, false);
                main.post(() -> {
                    if (ending || !id.equals(session)) return;
                    resizing = false; resizeSubmitted = false; menuBusy = false;
                    updateCameraGapBattery();
                    castMenu.updateViewportMode(viewport.isFull()); castMenu.setBusy(false);
                    main.removeCallbacks(viewportTimeout);
                    android.util.Log.i("VirtualRearHost", status);
                });
            } catch (Exception e) { fail(e); }
        });
    }
    private void dismissMenu() {
        if (ending || menuBusy || !menuVisible) return;
        castMenu.show(false); menuVisible = false; updateCastNotification();
        String id = session;
        worker.execute(() -> { try { if (service != null && id != null) service.dismissRearCastMenu(id); } catch (Exception e) { fail(e); } });
    }
    private void rotateContent(int direction) {
        if (ending || menuBusy || session == null || reconnecting) return;
        menuBusy = true; castMenu.setBusy(true); menuVisible = false; castMenu.show(false); String id = session;
        updateCastNotification();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Control service disconnected");
                String confirmed = service.rotateRearVirtualCast(id, direction);
                status = confirmed;
                rememberPresentation(confirmed, true);
                main.post(() -> { menuBusy = false; castMenu.setBusy(false); updateCastNotification(); });
            } catch (Exception e) {
                android.util.Log.w("VirtualRearHost", "Content rotation failed", e);
                main.post(() -> {
                    if (ending) return;
                    menuBusy = false; castMenu.setBusy(false); updateCastNotification();
                    android.widget.Toast.makeText(this, R.string.rear_cast_menu_failed, android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    private void rememberPresentation(String confirmed, boolean rotationChanged) throws Exception {
        JSONObject state = new JSONObject(confirmed);
        if (ending || !"running".equals(state.optString("state")) || !state.optString("id").equals(session)
                || sessionPackage == null || !sessionPackage.equals(state.getString("package"))) return;
        if (rotationChanged) RearPresentationPreferences.saveRotation(this, sessionPackage, state.getInt("contentRotation"));
        else RearPresentationPreferences.saveViewport(this, sessionPackage, state.getBoolean("fullViewport"));
    }
    private void renderMenu(String id, boolean open) {
        if (ending || id == null || !id.equals(session) || (open && (reconnecting || menuBusy))) return;
        if (open) {
            float anchor = Float.NaN;
            synchronized (feedbackLock) { if (id.equals(feedbackSession) && safeViewport != null) anchor = feedbackY - safeViewport.top; }
            castMenu.anchor(anchor);
        }
        clearFeedback(); menuVisible = open; castMenu.show(open);
        updateCastNotification();
        if (open) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }
    private final Object feedbackLock = new Object();
    private String feedbackSession;
    private float feedbackInward, feedbackY;
    private boolean feedbackActive, feedbackReady, feedbackMenuArmed, feedbackPosted;
    private int feedbackGeneration;
    private final Runnable renderFeedback = () -> {
        String id; float inward, y; boolean active, ready, menuArmed; int generation;
        synchronized (feedbackLock) {
            feedbackPosted = false; id = feedbackSession; inward = feedbackInward; y = feedbackY;
            active = feedbackActive; ready = feedbackReady; menuArmed = feedbackMenuArmed; generation = feedbackGeneration;
        }
        if (ending || reconnecting || resizing || menuVisible || id == null || !id.equals(session) || generation != outputGeneration) return;
        if (active) backIndicator.progress(inward, y, ready, menuArmed); else backIndicator.finish(ready);
    };
    private final Runnable scheduleFeedback = () -> {
        if (!ending && backIndicator != null) backIndicator.postOnAnimation(renderFeedback);
    };
    private void queueFeedback(String id, float inward, float y, boolean active, boolean ready, boolean menuArmed) {
        if (ending || id == null || !id.equals(session)) return;
        synchronized (feedbackLock) {
            feedbackSession = id; feedbackInward = inward; feedbackY = y;
            feedbackActive = active; feedbackReady = ready; feedbackMenuArmed = menuArmed; feedbackGeneration = outputGeneration;
            if (feedbackPosted) return;
            feedbackPosted = true;
        }
        main.post(scheduleFeedback);
    }
    private void queueMenuTouch(String id, int action, float x, float y, long down, long time) {
            int generation = outputGeneration;
            main.post(() -> {
                if (ending || reconnecting || menuBusy || !menuVisible || !id.equals(session) || generation != outputGeneration) return;
                int[] xy = new int[2]; castMenu.getView().getLocationOnScreen(xy);
                if (action == MotionEvent.ACTION_DOWN && (x < xy[0] || y < xy[1]
                        || x >= xy[0] + castMenu.getView().getWidth() || y >= xy[1] + castMenu.getView().getHeight())) {
                    dismissMenu(); return;
                }
                MotionEvent event = MotionEvent.obtain(down, time, action, x - xy[0], y - xy[1], 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { castMenu.getView().dispatchTouchEvent(event); } finally { event.recycle(); }
            });
    }
    private final IRearGestureListener gestures = new IRearGestureListener.Stub() {
        @Override public void onNotificationDismiss(String id, long token) {
            main.post(() -> { if (!ending && id.equals(session) && castNotification != null) castNotification.dismiss(token); });
        }
        @Override public void onCastMenu(String id, boolean open) {
            int generation = outputGeneration;
            main.post(() -> { if (generation == outputGeneration) renderMenu(id, open); });
        }
        @Override public void onCastMenuTouch(String id, int action, float x, float y, long down, long time) {
            queueMenuTouch(id, action, x, y, down, time);
        }
        @Override public void onBackGesture(String id, float inward, float y, boolean active, boolean ready, boolean menuArmed) {
            gesturePreviewGeneration++; // A real gesture always supersedes a local visual preview.
            queueFeedback(id, inward, y, active, ready, menuArmed);
        }
    };
    private void clearFeedback() {
        gesturePreviewGeneration++;
        main.removeCallbacks(scheduleFeedback);
        if (backIndicator != null) { backIndicator.removeCallbacks(renderFeedback); backIndicator.clear(); }
        synchronized (feedbackLock) { feedbackPosted = false; feedbackSession = null; }
    }
    private boolean screenReceiverRegistered;
    private volatile boolean lockRecoveryRequested, wakeAttempted;
    private int invalidateRecoveryActions() { return ++recoveryActionEpoch; }
    private ISceneTaskActionGate recoveryGate(String id, int epoch, boolean waking) {
        return new VirtualCastRecoveryActionGate(() -> {
            if (!VirtualCastOutputRecoveryPolicy.allowRecoveryAction(starting && session != null, ending,
                    !isFinishing() && instance == this, id != null && id.equals(session),
                    epoch == (waking ? screenOffCycleEpoch : recoveryActionEpoch),
                    waking, lockRecoveryRequested)) return false;
            android.view.Display display = getDisplay();
            if (display == null) return false;
            int state = display.getState();
            return waking ? state != android.view.Display.STATE_ON : state == android.view.Display.STATE_ON;
        });
    }
    private ISceneTaskActionGate outputPowerGate(String id, int epoch, boolean available) {
        return new VirtualCastRecoveryActionGate(() -> {
            if (ending || !starting || instance != this || id == null || !id.equals(session)
                    || isFinishing()
                    || epoch != outputPowerEpoch) return false;
            android.view.Display display = getDisplay();
            if (display == null) return false;
            return available ? display.getState() == android.view.Display.STATE_ON
                    : VirtualCastOutputPowerGate.isSleeping(display.getState());
        });
    }
    private void onPrimaryDisplayAvailable(boolean actualResumeEdge) {
        if (ending || isFinishing() || !starting || session == null || !reconnecting) return;
        armOutputTimeout(actualResumeEdge);
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        if (rearState != android.view.Display.STATE_ON) {
            if (!lockRecoveryRequested) requestLockRecovery(150);
        } else if (reconnecting) {
            onRearDisplayResumed(actualResumeEdge);
        } else presentExistingHost();
    }
    private void onRearDisplaySuspended() {
        if (ending || isFinishing() || !starting || session == null || resizing) return;
        reconnecting = true;
        powerSuspendedOutput = true;
        missingSurfaceLossEpoch = missingSurfacePresentationGate.beginPowerCycleLoss();
        missingSurfacePresentationGate.pauseDeadlineForPowerSuspension(missingSurfaceLossEpoch);
        outputGeneration++;
        outputPowerEpoch++;
        invalidateRecoveryActions();
        main.removeCallbacks(outputTimeout);
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        updateCameraGapBattery();
        String id = session;
        int epoch = outputPowerEpoch;
        int generation = outputGeneration;
        worker.execute(() -> {
            try {
                if (isCurrentOutputPowerRequest(id, generation, epoch) && service != null) {
                    String result = service.setRearVirtualCastOutputAvailable(id, false, null,
                            outputPowerGate(id, epoch, false));
                    android.util.Log.i("VirtualRearHost", "Rear sleep output pause session=" + id + " result=" + result);
                }
            } catch (Exception e) {
                main.post(() -> { if (isCurrentOutputPowerRequest(id, generation, epoch)) fail(e); });
            }
        });
    }
    private void onRearDisplayResumed(boolean actualResumeEdge) {
        if (!reconnecting || ending || isFinishing()) return;
        armOutputTimeout(actualResumeEdge);
        outputPowerEpoch++;
        String id = session;
        Surface surface = holder == null || !surfaceConfigured ? null : holder.getSurface();
        boolean surfaceValid = surface != null && surface.isValid();
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        final int generation = outputGeneration;
        final int epoch = outputPowerEpoch;
        final int lossEpoch = missingSurfaceLossEpoch;
        VirtualCastOutputRecoveryPolicy.dispatchRearResume(starting && id != null, ending, reconnecting,
                powerSuspendedOutput, surfaceValid, rearState,
                new VirtualCastOutputRecoveryPolicy.RearResumeEffects() {
                    @Override public void reattachOutputSurface() {
                        worker.execute(() -> {
                            try {
                                if (!isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) return;
                                ITaskService remote = service;
                                if (remote == null) return;
                                String result = remote.setRearVirtualCastOutputAvailable(id, true, surface,
                                        outputPowerGate(id, epoch, true));
                                if (!VirtualCastOutputRecoveryPolicy.isOutputAvailableResult(result)) {
                                    android.util.Log.i("VirtualRearHost", "Rear resume remains pending session=" + id + " result=" + result);
                                    return;
                                }
                                main.post(() -> commitCurrentSurfaceRecovery(id, generation, lossEpoch, surface));
                            } catch (Exception e) {
                                main.post(() -> { if (isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) fail(e); });
                            }
                        });
                    }

                    @Override public void waitForReplacementSurface() {
                        armOutputTimeout(actualResumeEdge);
                        android.util.Log.i("VirtualRearHost", "Rear is ON; waiting for owned Surface session=" + id);
                    }

                    @Override public void presentOwnedHost() {
                        if (surfaceValid) {
                            submitSurfaceRecovery(id, surface, generation, lossEpoch);
                        } else {
                            int attempt = requestOwnedHostForMissingSurface();
                            if (attempt != 0) presentExistingHost(missingSurfaceLossEpoch, attempt);
                        }
                    }
                });
    }
    private void armOutputTimeout(boolean actualResumeEdge) {
        VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(missingSurfacePresentationGate,
                missingSurfaceLossEpoch, actualResumeEdge,
                (lossEpoch, deadlineId, timeoutMillis) -> {
                    outputTimeoutLossEpoch = lossEpoch;
                    outputTimeoutId = deadlineId;
                    main.postDelayed(outputTimeout, timeoutMillis);
                });
    }
    private int requestOwnedHostForMissingSurface() {
        if (ending || isFinishing() || !reconnecting || session == null) return 0;
        int attempt = missingSurfacePresentationGate.beginAttempt(missingSurfaceLossEpoch);
        if (attempt == 0) return 0;
        android.util.Log.i("VirtualRearHost", "Presenting owned host to restore missing Surface session=" + session);
        return attempt;
    }
    private void presentExistingHost() {
        presentExistingHost(0, 0);
    }
    private void presentExistingHost(int lossEpoch, int attempt) {
        String id = session;
        int epoch = recoveryActionEpoch;
        if (id == null || ending || isFinishing()) return;
        int host = getTaskId();
        worker.execute(() -> {
            try {
                ITaskService remote = service;
                if (remote == null || ending || !id.equals(session)) {
                    if (attempt != 0) main.post(() -> missingSurfacePresentationGate.complete(lossEpoch, attempt));
                    return;
                }
                ISceneTaskActionGate actionGate = attempt == 0
                        ? recoveryGate(id, epoch, false)
                        : new VirtualCastRecoveryActionGate(() -> {
                            android.view.Display display = getDisplay();
                            return missingSurfacePresentationGate.isCurrent(lossEpoch, attempt,
                                    starting && session != null && !isFinishing(), ending, instance == this,
                                    id.equals(session), reconnecting,
                                    display != null && display.getState() == android.view.Display.STATE_ON);
                        });
                String result = remote.recoverRearVirtualCastHost(id, host, RearDisplay.requireId(),
                        RearVirtualCast.RECOVERY_PRESENT_HOST, actionGate);
                android.util.Log.i("VirtualRearHost", "Owned host present session=" + id + " result=" + result);
                if (attempt != 0) main.post(() -> missingSurfacePresentationGate.complete(lossEpoch, attempt));
            } catch (Exception e) {
                if (attempt != 0) main.post(() -> missingSurfacePresentationGate.complete(lossEpoch, attempt));
                android.util.Log.w("VirtualRearHost", "Owned host presentation deferred", e);
            }
        });
    }
    private void requestLockRecovery(long delay) {
        if (ending || isFinishing() || !VirtualCastOutputRecoveryPolicy.shouldScheduleWakeForPowerCycle(lockRecoveryRequested)) return;
        lockRecoveryRequested = true;
        wakeAttempted = false;
        invalidateRecoveryActions();
        main.postDelayed(wakeRearAfterMainLock, delay);
    }
    private final Runnable wakeRearAfterMainLock = this::wakeRearOnce;
    private void wakeRearOnce() {
        if (ending || isFinishing() || !starting || session == null || !lockRecoveryRequested || wakeAttempted) return;
        wakeAttempted = true;
        String id = session;
        int host = getTaskId();
        int epoch = screenOffCycleEpoch;
        worker.execute(() -> {
            try {
                ITaskService remote = service;
                if (remote == null || ending || !id.equals(session)) return;
                String result = remote.recoverRearVirtualCastHost(id, host, RearDisplay.requireId(),
                        RearVirtualCast.RECOVERY_WAKE_REAR, recoveryGate(id, epoch, true));
                android.util.Log.i("VirtualRearHost", "Rear one-shot wake session=" + id + " result=" + result);
                if (VirtualCastOutputRecoveryPolicy.shouldReconcileAlreadyOnWake(result,
                        starting && session != null, ending, id.equals(session), reconnecting)) {
                    main.post(() -> {
                        android.view.Display display = getDisplay();
                        if (!ending && starting && id.equals(session) && reconnecting && display != null
                                && display.getState() == android.view.Display.STATE_ON)
                            onRearDisplayResumed(false);
                    });
                }
            } catch (Exception e) { fail(e); }
        });
    }
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                boolean wasPrimaryInteractive = primaryInteractive;
                primaryInteractive = false;
                VirtualCastOutputRecoveryPolicy.PrimaryScreenOffDecision decision =
                        VirtualCastOutputRecoveryPolicy.onPrimaryScreenOff(missingSurfacePresentationGate,
                                lockRecoveryRequested, wasPrimaryInteractive, reconnecting);
                missingSurfaceLossEpoch = decision.lossEpoch;
                missingSurfacePresentationGate.pauseDeadlineForPowerSuspension(missingSurfaceLossEpoch);
                main.removeCallbacks(outputTimeout);
                invalidateRecoveryActions();
                if (decision.newPrimaryCycle) screenOffCycleEpoch++;
                android.view.Display rear = getDisplay();
                boolean rearIsOn = rear != null && rear.getState() == android.view.Display.STATE_ON;
                VirtualCastOutputRecoveryPolicy.dispatchPrimaryScreenOff(decision, rearIsOn,
                        new VirtualCastOutputRecoveryPolicy.PrimaryScreenOffEffects() {
                            @Override public void scheduleWake() { requestLockRecovery(350); }
                            @Override public void reconcileAlreadyOnRear() { onPrimaryDisplayAvailable(false); }
                        });
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                boolean resumeEdge = !primaryInteractive;
                primaryInteractive = true;
                if (resumeEdge) {
                    screenOffCycleEpoch++;
                    lockRecoveryRequested = false;
                    wakeAttempted = false;
                }
                primaryKeyguardLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
                onPrimaryDisplayAvailable(resumeEdge);
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                boolean resumeEdge = primaryKeyguardLocked;
                primaryKeyguardLocked = false;
                onPrimaryDisplayAvailable(resumeEdge);
            }
        }
    };
    private final Runnable outputTimeout = this::handleOutputTimeout;
    private void handleOutputTimeout() {
        int lossEpoch = outputTimeoutLossEpoch;
        int deadlineId = outputTimeoutId;
        if (!missingSurfacePresentationGate.isCurrentDeadline(lossEpoch, deadlineId) || !reconnecting) return;
        PowerManager power = getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        boolean mainLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked() || !interactive;
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        boolean stillWaiting = VirtualCastOutputRecoveryPolicy.waitForDisplayResume(mainLocked, interactive, rearState);
        if (!missingSurfacePresentationGate.finishDeadline(lossEpoch, deadlineId, stillWaiting)) return;
        if (stillWaiting) {
            android.util.Log.i("VirtualRearHost", "Output remains suspended for display resume; session=" + session
                    + " rearState=" + rearState + " mainLocked=" + mainLocked);
            return;
        }
        fail(new IllegalStateException("Rear output reconnect timeout"));
    }
    private int width, height;
    private final Runnable startAfterLayout = this::startSession;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (ending) return;
            worker.execute(() -> {
                try {
                    ITaskService remote = service; String id = session;
                    if (remote != null && id != null) {
                        if (!remote.renewRearVirtualCastLease(id)) {
                            main.post(() -> returnEndedCast(id));
                            return;
                        }
                        String current = remote.getRearVirtualCastStatus();
                        JSONObject state = new JSONObject(current);
                        if (wallpaperStandby == null || (id.equals(state.optString("id"))
                                && "running".equals(state.optString("state")))) {
                            status = current;
                            activeSessionStatus = state;
                        }
                    }
                } catch (Exception e) { fail(e); }
            });
            main.postDelayed(this, 1000);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ITaskService connected = ITaskService.Stub.asInterface(binder);
            service = connected;
            FrameLayout presentingRoot = root;
            worker.execute(() -> {
                try {
                    android.util.Log.i("VirtualRearHost", "Query safe viewport");
                    RearCastViewport safe = RearCastGeometry.decode(connected.getRearCastViewport());
                    int task = getIntent().getIntExtra("task", -1), dpi = getIntent().getIntExtra("dpi", VirtualCastRequest.DEFAULT_DENSITY_DPI);
                    String component = getIntent().getStringExtra("component");
                    JSONObject plan = new JSONObject(connected.getRearCastRenderPlan(task, component, dpi, false, false));
                    String pkg = plan.getString("package");
                    boolean compatibility = RearDensityPreferences.enabled(VirtualRearHostActivity.this, pkg);
                    int layoutDpi = RearCastCompatibility.layoutDensity(compatibility, dpi,
                            RearDensityPreferences.layoutDensity(VirtualRearHostActivity.this, pkg),
                            getIntent().getBooleanExtra("explicitDpi", false));
                    RearPresentationPreferences.Selection presentation = RearPresentationPreferences.read(VirtualRearHostActivity.this, pkg);
                    boolean requestedFull = getIntent().hasExtra("fullViewport")
                            ? getIntent().getBooleanExtra("fullViewport", false) : presentation.full;
                    if (compatibility || requestedFull || layoutDpi != dpi)
                        plan = new JSONObject(connected.getRearCastRenderPlan(task, component, layoutDpi, compatibility,
                                requestedFull));
                    RearCastViewport render = RearCastGeometry.decode(plan.toString());
                    android.util.Log.i("VirtualRearHost", "Received safe viewport=" + safe.key());
                    main.post(() -> {
                        if (ending || root != presentingRoot) return;
                        if (safeViewport != null && !safeViewport.key().equals(safe.key())) {
                            fail(new IllegalStateException("Rear safe area changed; restart casting")); return;
                        }
                        safeViewport = safe;
                        densityCompatibility = compatibility;
                        requestedLayoutDensity = layoutDpi;
                        initialRotation = getIntent().hasExtra("candidateRotation")
                                ? getIntent().getIntExtra("candidateRotation", presentation.rotation) : presentation.rotation;
                        initialFull = getIntent().hasExtra("fullViewport")
                                ? getIntent().getBooleanExtra("fullViewport", presentation.full) : presentation.full;
                        if (viewport == null) viewport = render;
                        castMenu.updateViewportMode(initialFull);
                        root.post(VirtualRearHostActivity.this::layoutViewport);
                    });
                } catch (Exception e) { main.post(() -> { if (root == presentingRoot) fail(e); }); }
            });
        }
        public void onServiceDisconnected(ComponentName name) { service = null; fail(new IllegalStateException("Control service disconnected")); }
    };
    private final SurfaceHolder.Callback candidateSurfaceCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder value) { candidateHolder = value; }
        @Override public void surfaceChanged(SurfaceHolder value, int format, int w, int h) {
            if (candidateViewport == null || candidateHolder != value) return;
            candidateFrameWidth = w; candidateFrameHeight = h;
            if (w == candidateViewport.bufferWidth && h == candidateViewport.bufferHeight) prepareCandidateBackend();
        }
        @Override public void surfaceDestroyed(SurfaceHolder value) {
            if (candidateHolder == value) candidateHolder = null;
            if (candidateOutput != null) abortCandidate("candidate_surface_lost");
        }
    };

    private void beginReplacement(Intent intent) {
        String requestedOperationId = intent == null ? null : intent.getStringExtra("rc5_operation_id");
        if (!controlledRequestCurrent(intent, requestedOperationId)) {
            android.util.Log.w("VirtualRearHost", "replacement_rejected reason=action_gate_stale_at_entry");
            return;
        }
        if (ending || !starting || session == null || service == null || candidateOutput != null) {
            android.util.Log.w("VirtualRearHost", "replacement_rejected reason=host_not_available");
            return;
        }
        candidateTask = intent.getIntExtra("task", -1);
        candidateOperationId = intent.getStringExtra("rc5_operation_id");
        candidateOwnerIdentity = intent.getStringExtra("rc5_owner_identity");
        candidateOwnerGeneration = intent.getLongExtra("rc5_owner_generation", 0L);
        candidateExpectedBaseComponent = intent.getStringExtra("rc5_expected_base");
        candidateRearDisplayId = intent.getIntExtra("rc5_rear_display", -1);
        candidateRearIdentity = intent.getStringExtra("rc5_rear_identity");
        candidateIdentityService = taskServiceFromIntent(intent);
        candidateSourceActionGate = sourceActionGateFromIntent(intent);
        candidateComponent = intent.getStringExtra("component");
        if (candidateOperationId == null || candidateOwnerIdentity == null || candidateOwnerGeneration <= 0L
                || candidateExpectedBaseComponent == null || !sameServiceBinder(service, candidateIdentityService)
                || verifyRearIdentity(candidateIdentityService, candidateRearDisplayId, candidateRearIdentity)
                    != ControlledRearIdentityGate.Result.MATCH
                || cancelledControlledOperations.contains(candidateOperationId)
                || !matchesExpectedPrevious(readExpectedPrevious(intent), candidateIdentityService)) {
            candidateOperationId = null; candidateIdentityService = null;
            candidateSourceActionGate = null;
            android.util.Log.w("VirtualRearHost", "replacement_rejected reason=exact_identity_changed");
            return;
        }
        if (!controlledRequestCurrent(intent, candidateOperationId)) {
            candidateOperationId = null; candidateIdentityService = null; candidateSourceActionGate = null;
            android.util.Log.w("VirtualRearHost", "replacement_rejected reason=action_gate_stale_after_owner_check");
            return;
        }
        candidatePackage = intent.getStringExtra("targetPackage");
        candidateRotation = intent.getIntExtra("candidateRotation", initialRotation);
        int requestedDensity = intent.getIntExtra("dpi", VirtualCastRequest.DEFAULT_DENSITY_DPI);
        boolean explicitDpi = intent.getBooleanExtra("explicitDpi", false);
        boolean requestedFull = intent.getBooleanExtra("fullViewport", false);
        String previous = session;
        worker.execute(() -> {
            try {
                ITaskService remote = service;
                if (remote == null || !previous.equals(session)) throw new IllegalStateException("previous_session_changed");
                JSONObject plan = new JSONObject(remote.getRearCastRenderPlan(candidateTask, candidateComponent,
                        requestedDensity, false, requestedFull));
                String pkg = plan.optString("package", candidatePackage);
                candidatePackage = pkg;
                candidateCompatibility = RearDensityPreferences.enabled(VirtualRearHostActivity.this, pkg);
                candidateDensity = RearCastCompatibility.layoutDensity(candidateCompatibility, requestedDensity,
                        RearDensityPreferences.layoutDensity(VirtualRearHostActivity.this, pkg), explicitDpi);
                RearPresentationPreferences.Selection presentation = RearPresentationPreferences.read(
                        VirtualRearHostActivity.this, pkg);
                if (!intent.hasExtra("candidateRotation")) candidateRotation = presentation.rotation;
                candidateFull = intent.hasExtra("fullViewport") ? requestedFull : presentation.full;
                plan = new JSONObject(remote.getRearCastRenderPlan(candidateTask, candidateComponent,
                        candidateDensity, candidateCompatibility, candidateFull));
                candidateViewport = RearCastGeometry.decode(plan.toString());
                main.post(() -> {
                    if (ending || !previous.equals(session) || candidateOutput != null) return;
                    if (!candidateRequestCurrent()) {
                        abortCandidate("action_gate_stale_at_plan_completion");
                        return;
                    }
                    createCandidateOutput();
                });
            } catch (Exception failure) {
                main.post(() -> android.util.Log.e("VirtualRearHost", "replacement_prepare_failed stage=plan", failure));
            }
        });
    }

    private void createCandidateOutput() {
        if (!candidateRequestCurrent()
                || !sameServiceBinder(service, candidateIdentityService)
                || verifyRearIdentity(candidateIdentityService, candidateRearDisplayId, candidateRearIdentity)
                    != ControlledRearIdentityGate.Result.MATCH) {
            abortCandidate("controlled_authority_stale_before_surface");
            return;
        }
        if (root == null || candidateViewport == null) return;
        SurfaceView view = new SurfaceView(this);
        candidateOutput = view; candidateHolder = view.getHolder();
        view.setAlpha(0f);
        if (Build.VERSION.SDK_INT >= 34) view.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        view.getHolder().addCallback(candidateSurfaceCallback);
        root.addView(view, Math.min(1, root.getChildCount()), new FrameLayout.LayoutParams(-1, -1));
        layoutCandidateOutput();
        android.util.Log.i("VirtualRearHost", "replacement_candidate_surface_attached task=" + candidateTask);
    }

    private void layoutCandidateOutput() {
        SurfaceView view = candidateOutput;
        RearCastViewport render = candidateViewport;
        if (view == null || render == null || root == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        android.graphics.Rect frame = view.getHolder().getSurfaceFrame();
        if (frame.width() != render.bufferWidth || frame.height() != render.bufferHeight)
            view.getHolder().setFixedSize(render.bufferWidth, render.bufferHeight);
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(render.width(), render.height());
        lp.leftMargin = render.left - origin[0]; lp.topMargin = render.top - origin[1];
        view.setLayoutParams(lp);
    }

    private void prepareCandidateBackend() {
        SurfaceView view = candidateOutput;
        SurfaceHolder candidate = candidateHolder;
        ITaskService remote = service;
        ITaskService authority = candidateIdentityService;
        if (candidatePrepared || view == null || candidate == null || remote == null || candidateViewport == null
                || candidateFrameWidth != candidateViewport.bufferWidth || candidateFrameHeight != candidateViewport.bufferHeight
                || !candidate.getSurface().isValid()) return;
        if (!candidateRequestCurrent()) { abortCandidate("action_gate_stale_before_prepare"); return; }
        candidatePrepared = true;
        worker.execute(() -> {
            try {
                JSONObject previous = activeSessionStatus == null ? new JSONObject(status) : activeSessionStatus;
                JSONObject input = previous.optJSONObject("input");
                String readerId = input == null ? "" : input.optString("id", "");
                String expectedBase = candidateTask >= 0
                        ? remote.getTaskBaseComponent(candidateTask, 0) : candidateComponent;
                if (!sameServiceBinder(remote, authority)
                        || verifyRearIdentity(authority, candidateRearDisplayId, candidateRearIdentity)
                            != ControlledRearIdentityGate.Result.MATCH
                        || expectedBase == null || expectedBase.isEmpty()
                        || !canonicalComponent(expectedBase).equals(canonicalComponent(candidateExpectedBaseComponent))
                        || !candidateRequestCurrent())
                    throw new IllegalStateException("replacement_target_identity_changed");
                String prepared = remote.prepareRearVirtualCastReplacementWithTargetGate(previous.optString("id"),
                        previous.optLong("generation", -1L), previous.optLong("ownerGeneration", -1L),
                        previous.optString("ownerIdentity", ""), previous.optInt("task", -1),
                        previous.optInt("host", -1), readerId, previous.optString("baseComponent", ""),
                        candidateRearIdentity, candidateExpectedBaseComponent, candidateRearDisplayId, candidateOperationId,
                        candidateOwnerGeneration, candidateOwnerIdentity, candidate.getSurface(),
                        new File(getApplicationInfo().nativeLibraryDir, RearInputProxy.READER_LIBRARY).getPath(),
                        candidateTask, getTaskId(), candidateDensity, candidateComponent, owner, gestures,
                        RearCastGeometry.encode(candidateViewport), candidateCompatibility, candidateRotation, candidateFull,
                        candidateSourceActionGate);
                JSONObject state = new JSONObject(prepared);
                String token = state.getString("id");
                main.post(() -> {
                    if (ending || candidateOutput != view || !candidate.getSurface().isValid()
                            || !candidateRequestCurrent()) {
                        abortCandidateBackend(token); return;
                    }
                    candidateToken = token;
                    candidateDisplayId = state.optInt("display", -1);
                    try {
                        JSONObject liveA = activeSessionStatus == null ? new JSONObject(status) : activeSessionStatus;
                        if (DebugControlledCastLateFailure.bindCandidate(liveA.optString("id"),
                                liveA.optString("ownerIdentity"), liveA.optLong("ownerGeneration", -1L),
                                liveA.optInt("task", -1), liveA.optInt("host", -1),
                                liveA.optJSONObject("input") == null ? "" : liveA.optJSONObject("input").optString("id"),
                                candidateTask, canonicalComponent(candidateExpectedBaseComponent), candidateDisplayId,
                                candidateOwnerIdentity, candidateOwnerGeneration, candidateOperationId, token)) {
                            android.util.Log.i("VirtualRearHost", "test_fixture_bound stage=after_pixelcopy_before_reader");
                        }
                    } catch (org.json.JSONException ignored) { }
                    android.util.Log.i("VirtualRearHost", "replacement_candidate_ready token=" + token
                            + " task=" + state.optInt("task", -1) + " display=" + state.optInt("display", -1));
                    candidateFrameAttempts = 0;
                    sampleCandidateFrame(token, view);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    android.util.Log.e("VirtualRearHost", "replacement_prepare_failed stage=backend", failure);
                    abortCandidate("replacement_prepare_failed");
                });
            }
        });
    }

    private boolean candidateRequestCurrent() {
        String operationId = candidateOperationId;
        ISceneTaskActionGate gate = candidateSourceActionGate;
        if (operationId == null || operationId.isEmpty() || gate == null
                || cancelledControlledOperations.contains(operationId)) return false;
        try { return gate.isCurrent() && !cancelledControlledOperations.contains(operationId); }
        catch (Exception stale) { return false; }
    }

    private void sampleCandidateFrame(String token, SurfaceView view) {
        if (ending || !token.equals(candidateToken) || view != candidateOutput) return;
        if (++candidateFrameAttempts > 30) { abortCandidate("candidate_frame_timeout"); return; }
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888);
        android.view.PixelCopy.request(view, bitmap, result -> {
            // A successful PixelCopy is a real produced frame; legitimately black app content is valid.
            boolean hasFrame = result == android.view.PixelCopy.SUCCESS;
            bitmap.recycle();
            if (hasFrame) acknowledgeCandidateFrame(token);
            else main.postDelayed(() -> sampleCandidateFrame(token, view), 100);
        }, main);
    }

    private void acknowledgeCandidateFrame(String token) {
        String operationId = candidateOperationId;
        if (operationId != null && token.equals(candidateToken)
                && DebugControlledCastLateFailure.consumeAfterPixelCopy(operationId, token)) {
            android.util.Log.w("VirtualRearHost", "test_fixture_injected stage=after_pixelcopy_before_reader operation=" + operationId);
            abortCandidate("test_late_failure_after_pixelcopy");
            return;
        }
        ITaskService remote = service;
        ITaskService authority = candidateIdentityService;
        worker.execute(() -> {
            try {
                if (!sameServiceBinder(remote, authority)
                        || verifyRearIdentity(authority, candidateRearDisplayId, candidateRearIdentity)
                            != ControlledRearIdentityGate.Result.MATCH || !candidateRequestCurrent())
                    throw new IllegalStateException("replacement_handoff_authority_stale");
                if (remote == null || !remote.reportRearVirtualCastReplacementOutputFrame(token))
                    throw new IllegalStateException("candidate_output_not_verified");
                main.post(() -> beginCandidateHandoff(token));
            } catch (Exception failure) { main.post(() -> abortCandidate("candidate_output_unverified")); }
        });
    }

    private void beginCandidateHandoff(String token) {
        ITaskService remote = service;
        ITaskService authority = candidateIdentityService;
        if (remote == null || !token.equals(candidateToken) || candidateOutput == null) return;
        worker.execute(() -> {
            DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
            if (reservation == null) { main.post(() -> abortCandidate("display_action_busy")); return; }
            candidateReservation = reservation;
            ISceneTaskActionGate gate = candidateGate(reservation, token);
            if (gate == null || !candidateActionGate.bind(token, gate)) {
                main.post(() -> abortCandidate("display_action_stale")); return;
            }
            try {
                if (!sameServiceBinder(remote, authority)
                        || verifyRearIdentity(authority, candidateRearDisplayId, candidateRearIdentity)
                            != ControlledRearIdentityGate.Result.MATCH || !candidateRequestCurrent())
                    throw new IllegalStateException("replacement_handoff_authority_stale");
                if (!remote.beginRearVirtualCastReplacementHandoff(token, gate))
                    throw new IllegalStateException("reader_pause_unverified");
                candidatePaused = true;
                main.post(() -> {
                    if (ending || !token.equals(candidateToken) || !candidateRequestCurrent()) {
                        abortCandidate("candidate_cancelled"); return;
                    }
                    output.setAlpha(0f); candidateOutput.setAlpha(1f);
                    bindCandidateInput(token);
                });
            } catch (Exception failure) { main.post(() -> abortCandidate("reader_pause_failed")); }
        });
    }

    private void bindCandidateInput(String token) {
        ITaskService remote = service;
        ITaskService authority = candidateIdentityService;
        if (remote == null || !token.equals(candidateToken)) { abortCandidate("candidate_stale"); return; }
        ISceneTaskActionGate gate = candidateActionGate.get(token);
        if (gate == null || !candidateRequestCurrent()) { abortCandidate("candidate_gate_stale"); return; }
        worker.execute(() -> {
            try {
                if (!sameServiceBinder(remote, authority)
                        || verifyRearIdentity(authority, candidateRearDisplayId, candidateRearIdentity)
                            != ControlledRearIdentityGate.Result.MATCH || !candidateRequestCurrent())
                    throw new IllegalStateException("replacement_bind_authority_stale");
                if (!remote.bindRearVirtualCastReplacementInput(token, gate))
                    throw new IllegalStateException("candidate_input_bind_unverified");
                candidateBound = true;
                if (!sameServiceBinder(remote, authority)
                        || verifyRearIdentity(authority, candidateRearDisplayId, candidateRearIdentity)
                            != ControlledRearIdentityGate.Result.MATCH || !candidateRequestCurrent())
                    throw new IllegalStateException("replacement_finish_authority_stale");
                String committed;
                try { committed = remote.finishRearVirtualCastReplacement(token, true, gate); }
                catch (Exception uncertain) { resolveCandidateCommit(token, remote); return; }
                JSONObject state;
                try { state = new JSONObject(committed); }
                catch (Exception malformed) { resolveCandidateCommit(token, remote); return; }
                if (!sameServiceBinder(remote, authority)
                        || verifyRearIdentity(authority, candidateRearDisplayId, candidateRearIdentity)
                            != ControlledRearIdentityGate.Result.MATCH) {
                    resolveCandidateCommit(token, remote); return;
                }
                if (!"running".equals(state.optString("state")) || !token.equals(state.optString("id"))) {
                    resolveCandidateCommit(token, remote); return;
                }
                main.post(() -> commitCandidateView(token, state));
            } catch (Exception failure) { main.post(() -> abortCandidate("candidate_handoff_failed")); }
        });
    }

    private void resolveCandidateCommit(String token, ITaskService remote) {
        try {
            JSONObject actual = new JSONObject(remote.getRearVirtualCastStatus());
            if (token.equals(actual.optString("id")) && "running".equals(actual.optString("state"))) {
                main.post(() -> commitCandidateView(token, actual));
            } else if (session.equals(actual.optString("id")) && "running".equals(actual.optString("state"))) {
                main.post(() -> abortCandidate("candidate_commit_rejected"));
            } else {
                android.util.Log.e("VirtualRearHost", "replacement_commit_unverified token=" + token
                        + " state=" + actual.optString("state"));
                releaseCandidateReservation(candidateReservation);
            }
        } catch (Exception unreadable) {
            android.util.Log.e("VirtualRearHost", "replacement_owner_unverified token=" + token, unreadable);
            releaseCandidateReservation(candidateReservation);
        }
    }

    private void commitCandidateView(String token, JSONObject newStatus) {
        if (!token.equals(candidateToken) || candidateOutput == null || candidateHolder == null) return;
        if (candidateOperationId == null || cancelledControlledOperations.contains(candidateOperationId)) {
            abortCandidate("controlled_cancel_after_commit");
            return;
        }
        SurfaceView oldOutput = output;
        SurfaceHolder oldHolder = holder;
        if (oldHolder != null) oldHolder.removeCallback(this);
        output = candidateOutput; holder = candidateHolder;
        holder.removeCallback(candidateSurfaceCallback); holder.addCallback(this);
        width = candidateFrameWidth; height = candidateFrameHeight; surfaceConfigured = true;
        viewport = candidateViewport; viewportPlaced = true;
        requestedLayoutDensity = candidateDensity; densityCompatibility = candidateCompatibility;
        initialRotation = candidateRotation; initialFull = candidateFull;
        session = token; sessionPackage = candidatePackage;
        status = newStatus.toString(); activeSessionStatus = newStatus;
        candidateOutput = null; candidateHolder = null; candidateViewport = null;
        controlledOperationId = candidateOperationId; controlledOwnerIdentity = candidateOwnerIdentity;
        controlledOwnerGeneration = candidateOwnerGeneration; controlledExpectedBaseComponent = candidateExpectedBaseComponent;
        candidateToken = null; candidateOperationId = null; candidateOwnerIdentity = null;
        candidateIdentityService = null;
        candidateOwnerGeneration = 0L; candidateExpectedBaseComponent = null;
        candidateRearDisplayId = -1; candidateRearIdentity = null;
        candidateDisplayId = -1;
        candidatePrepared = candidatePaused = candidateBound = false;
        if (oldOutput != null) root.removeView(oldOutput);
        output.setAlpha(1f);
        outputGeneration++; updateCameraGapBattery(); updateCastNotification();
        if (getIntent().getBooleanExtra("managed", false)
                && !getIntent().getBooleanExtra("rc5_defer_keeper", false)) {
            Intent keeper = new Intent(this, RearScreenKeeperService.class)
                    .putExtra(VirtualCastUriFlow.EXTRA_SESSION, session)
                    .putExtra("lastMovedTask", newStatus.optString("package") + ":" + newStatus.optInt("task"))
                    .putExtra("keepScreenOnEnabled", getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                                    .getBoolean("flutter.keep_screen_on_enabled", true));
            try { startForegroundService(keeper); }
            catch (Exception failure) { android.util.Log.e("VirtualRearHost", "replacement_keeper_update_failed", failure); }
        }
        releaseCandidateReservation(candidateReservation);
        android.util.Log.i("VirtualRearHost", "replacement_committed session=" + session + " task=" + newStatus.optInt("task", -1));
    }

    private void abortCandidate(String reason) {
        String token = candidateToken;
        DebugControlledCastLateFailure.clearIfNotInjected();
        if (token != null) abortCandidateBackend(token);
        SurfaceView view = candidateOutput;
        candidateOutput = null; candidateHolder = null; candidateViewport = null; candidateToken = null;
        candidateOperationId = null; candidateOwnerIdentity = null; candidateOwnerGeneration = 0L;
        candidateIdentityService = null;
        candidateExpectedBaseComponent = null; candidateRearDisplayId = -1; candidateRearIdentity = null;
        candidateDisplayId = -1;
        candidatePrepared = candidatePaused = candidateBound = false; candidateFrameAttempts = 0;
        if (view != null) {
            view.getHolder().removeCallback(candidateSurfaceCallback);
            root.removeView(view);
        }
        if (output != null) output.setAlpha(1f);
        android.util.Log.w("VirtualRearHost", "replacement_aborted reason=" + reason);
    }

    private void abortCandidateBackend(String token) {
        ITaskService remote = service;
        final String operationId = candidateOperationId;
        final String ownerIdentity = candidateOwnerIdentity;
        final long ownerGeneration = candidateOwnerGeneration;
        final int taskId = candidateTask;
        final String targetBase = canonicalComponent(candidateExpectedBaseComponent);
        final int displayId = candidateDisplayId;
        final DebugControlledCastLateFailure.FixtureSnapshot fixture =
                DebugControlledCastLateFailure.fixtureForOperation(operationId, token);
        DisplayActionGate.Reservation reservation = candidateReservation;
        candidateReservation = null;
        if (remote == null) { releaseCandidateReservation(reservation); return; }
        worker.execute(() -> {
            try {
                boolean aborted = remote.abortRearVirtualCastReplacement(token);
                // Abort may race the privileged commit: if B already became current, close only
                // that exact operation/session rather than leaving a committed orphan or touching A.
                if (operationId != null && ownerIdentity != null) {
                    JSONObject live = new JSONObject(remote.getRearVirtualCastStatus());
                    if (token.equals(live.optString("id"))
                            && operationId.equals(live.optString("coordinatorOperationId"))
                            && ownerGeneration == live.optLong("ownerGeneration", -1L)
                            && ownerIdentity.equals(live.optString("ownerIdentity")))
                        remote.stopRearVirtualCast(token);
                }
                if (fixture != null) {
                    long[] evidence = aborted
                            ? verifyDebugLateFailureCleanup(remote, fixture, taskId, targetBase, displayId) : null;
                    boolean clean = evidence != null;
                    DebugControlledCastLateFailure.completeCleanup(operationId, token, clean,
                            clean ? "A_same_session_reader_and_frame;B_exact_task_on_main;candidate_display_released"
                                    : "A_or_B_or_candidate_display_cleanup_unverified",
                            evidence == null ? -1L : evidence[0], evidence == null ? -1L : evidence[1]);
                    android.util.Log.i("VirtualRearHost", "test_fixture_cleanup_verified=" + clean);
                }
            } catch (Exception failure) {
                android.util.Log.e("VirtualRearHost", "replacement_cleanup_unverified token=" + token, failure);
                if (fixture != null) DebugControlledCastLateFailure.completeCleanup(operationId, token,
                        false, failure.getClass().getSimpleName(), -1L, -1L);
            } finally { releaseCandidateReservation(reservation); }
        });
    }

    private long[] verifyDebugLateFailureCleanup(ITaskService remote,
            DebugControlledCastLateFailure.FixtureSnapshot fixture, int taskId, String targetBase, int displayId) {
        long deadline = android.os.SystemClock.uptimeMillis() + 8_000L;
        android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                JSONObject live = new JSONObject(remote.getRearVirtualCastStatus());
                JSONObject input = live.optJSONObject("input");
                boolean sameA = fixture.aSession.equals(live.optString("id"))
                        && "running".equals(live.optString("state"))
                        && fixture.aOwner.equals(live.optString("ownerIdentity"))
                        && fixture.aGeneration == live.optLong("ownerGeneration", -1L)
                        && fixture.aTask == live.optInt("task", -1) && fixture.aHost == live.optInt("host", -1)
                        && fixture.aReader.equals(input == null ? "" : input.optString("id"))
                        && live.optBoolean("outputAvailable")
                        && input != null && "running".equals(input.optString("state"))
                        && input.optBoolean("outputAvailable");
                boolean bReturned = taskId == fixture.bTask && targetBase.equals(fixture.bComponent)
                        && remote.isTaskOnDisplay(taskId, 0)
                        && targetBase.equals(canonicalComponent(remote.getTaskBaseComponent(taskId, 0)));
                boolean displayReleased = displayId == fixture.bDisplay && dm != null && dm.getDisplay(displayId) == null;
                boolean aHasFrame = sameA && controlledOutputHasFrame(fixture.aSession, 600L);
                if (sameA && bReturned && displayReleased && aHasFrame)
                    return new long[]{input.optLong("frames", -1L), input.optLong("events", -1L)};
            } catch (Exception ignored) { }
            try { Thread.sleep(100L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return null;
            }
        }
        return null;
    }

    private ISceneTaskActionGate candidateGate(DisplayActionGate.Reservation reservation, String token) {
        if (reservation == null || !RearAnimationManager.isCurrentDisplayAction(reservation)) return null;
        return new ISceneTaskActionGate.Stub() {
            @Override public boolean isCurrent() {
                SurfaceHolder currentHolder = candidateHolder;
                return !ending && token != null && token.equals(candidateToken) && currentHolder != null
                        && currentHolder.getSurface().isValid()
                        && candidateOperationId != null && !cancelledControlledOperations.contains(candidateOperationId)
                        && RearAnimationManager.isCurrentDisplayAction(reservation);
            }
            @Override public boolean isCurrentBeforeTargetMove() { return isCurrent(); }
        };
    }

    private void releaseCandidateReservation(DisplayActionGate.Reservation reservation) {
        candidateActionGate.clear();
        if (reservation != null) RearAnimationManager.completeDisplayAction(reservation);
        if (candidateReservation == reservation) candidateReservation = null;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PowerManager initialPower = getSystemService(PowerManager.class);
        primaryInteractive = initialPower != null && initialPower.isInteractive();
        primaryKeyguardLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
        if (Build.VERSION.SDK_INT < 33) { finish(); return; }
        android.util.Log.i("VirtualRearHost", "Launch display=" + getDisplay().getDisplayId() + " rear=" + RearDisplay.id()
                + " task=" + getIntent().getIntExtra("task", -1) + " stop=" + getIntent().getBooleanExtra("stop", false));
        if (!BuildConfig.VIRTUAL_CAST_ENABLED || !RearDisplay.isRear(getDisplay().getDisplayId()) || getIntent().getBooleanExtra("stop", false)) {
            finish(); return;
        }
        if (getIntent().getBooleanExtra(RearWallpaperStandby.PREPARE, false)) {
            prepareWallpaperStandby(getIntent());
            return;
        }
        // A retained task can be recreated with a bare intent before Android delivers the prepare
        // command to this singleInstance. Reject an empty host only after pending intents arrive.
        if (getIntent().getIntExtra("task", -1) < 0 && getIntent().getStringExtra("component") == null) return;
        initializePresentation();
    }

    private void prepareWallpaperStandby(Intent intent) {
        wallpaperStandby = RearWallpaperStandby.prepare(this, intent.getIntExtra(RearWallpaperStandby.REAR, -1));
        if (wallpaperStandby != null) {
            setIntent(intent);
            if (hasWindowFocus()) wallpaperStandby.focusChanged(true);
        }
    }

    void showWallpaperStandbyWindow() {
        detachWallpaperPicker();
        setShowWhenLocked(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new FrameLayout(this));
    }

    void showWallpaperAppPicker(ITaskService remote, boolean showApps, boolean launchFailed,
                                Runnable dismiss, java.util.function.Predicate<ComponentName> choose) {
        detachWallpaperPicker();
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        wallpaperPicker = AiRearAppPickerView.create(this, remote, showApps, launchFailed, dismiss, choose);
        setContentView(wallpaperPicker);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        wallpaperPickerBack = new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { dismiss.run(); }
        };
        getOnBackPressedDispatcher().addCallback(this, wallpaperPickerBack);
    }

    private void detachWallpaperPicker() {
        if (wallpaperPickerBack != null) {
            wallpaperPickerBack.remove();
            wallpaperPickerBack = null;
        }
        if (wallpaperPicker != null) {
            wallpaperPicker.disposeComposition();
            wallpaperPicker = null;
        }
    }

    void initializePreparedWallpaperCast(ComponentName target) {
        detachWallpaperPicker();
        wallpaperStartCancelled = false;
        // Standby was created while unlocked; use the current power/lock state for
        // the renderer's wake-edge bookkeeping, just as on a fresh presentation.
        PowerManager power = getSystemService(PowerManager.class);
        primaryInteractive = power != null && power.isInteractive();
        primaryKeyguardLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
        // Configuration only: this is still the already-running physical task.
        setIntent(new Intent(this, VirtualRearHostActivity.class)
                .putExtra("task", -1).putExtra("component", target.flattenToString())
                .putExtra("targetPackage", target.getPackageName()).putExtra("managed", true));
        initializePresentation();
    }

    private void initializePresentation() {
        ending = false;
        instance = this;
        setShowWhenLocked(true);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        if (getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE).getBoolean("flutter.keep_screen_on_enabled", true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        output = new SurfaceView(this);
        // Keep the producer alive while HyperOS temporarily puts its launcher in front on power-off.
        if (Build.VERSION.SDK_INT >= 34) output.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        output.getHolder().addCallback(this);
        root = new RearCastFrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        root.addView(output, new FrameLayout.LayoutParams(-1, -1));
        RearCameraGapBatteryView batteryLayer = new RearCameraGapBatteryView(this);
        root.addView(batteryLayer, new FrameLayout.LayoutParams(-1, -1));
        cameraGapBattery = new RearCameraGapBatteryController(this, batteryLayer);
        RearCameraStatusView statusLayer = new RearCameraStatusView(this);
        root.addView(statusLayer, new FrameLayout.LayoutParams(-1, -1));
        cameraStatus = new RearCameraStatusController(this, statusLayer);
        castNotification = new RearCastNotificationOverlay(this, (token, left, top, right, bottom) -> {
            String id = session;
            if (id == null) return;
            worker.execute(() -> {
                boolean success = false;
                try {
                    ITaskService remote = service;
                    if (!ending && remote != null && id.equals(session))
                        success = remote.setRearCastNotificationBounds(id, token, left, top, right, bottom);
                } catch (Exception error) { android.util.Log.w("VirtualRearHost", "Notification touch region unavailable", error); }
                boolean registered = success;
                main.post(() -> {
                    if (!ending && id.equals(session)) castNotification.regionRegistered(token, left, top, right, bottom, registered);
                });
            });
        });
        root.addView(castNotification, new FrameLayout.LayoutParams(1, -2));
        backIndicator = new RearBackGestureView(this);
        root.addView(backIndicator, new FrameLayout.LayoutParams(backIndicator.dp(32), backIndicator.dp(40), Gravity.RIGHT | Gravity.TOP));
        castMenu = new RearCastMenuUi(this, this::exitPresentation, () -> rotateContent(-1), () -> rotateContent(1), this::toggleViewport, this::dismissMenu);
        root.addView(castMenu.getView(), new FrameLayout.LayoutParams(1, 1));
        root.getViewTreeObserver().addOnGlobalLayoutListener(presentationLayout);
        setContentView(root);
        RearCastNotification.attach(notificationTarget);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        IntentFilter screenEvents = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, screenEvents, Context.RECEIVER_NOT_EXPORTED);
        screenReceiverRegistered = true;
        android.hardware.display.DisplayManager displayManager = getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display rearDisplay = displayManager == null ? null : displayManager.getDisplay(RearDisplay.requireId());
        observedRearDisplayState = rearDisplay == null ? android.view.Display.STATE_UNKNOWN : rearDisplay.getState();
        if (displayManager != null) displayManager.registerDisplayListener(rearDisplayListener, main);
        TaskServiceManager.acquire(this, connection);
        presentationBack = new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { exitPresentation(); }
        };
        getOnBackPressedDispatcher().addCallback(this, presentationBack);
        main.post(heartbeat);
    }
    private void layoutViewport() {
        if (ending || root == null || viewport == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        android.graphics.Rect buffer = output.getHolder().getSurfaceFrame();
        if (buffer.width() != viewport.bufferWidth || buffer.height() != viewport.bufferHeight)
            output.getHolder().setFixedSize(viewport.bufferWidth, viewport.bufferHeight);
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        String layoutState = "root=" + origin[0] + "," + origin[1] + " " + root.getWidth() + "x" + root.getHeight()
                + " output=" + output.getLeft() + "," + output.getTop() + " " + output.getWidth() + "x" + output.getHeight();
        if (!layoutState.equals(lastViewportLayout)) { lastViewportLayout = layoutState; android.util.Log.i("VirtualRearHost", layoutState); }
        if (origin[0] > viewport.left || origin[1] > viewport.top
                || origin[0] + root.getWidth() < viewport.right || origin[1] + root.getHeight() < viewport.bottom) {
            fail(new IllegalStateException("Rear host does not contain the safe viewport")); return;
        }
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(viewport.width(), viewport.height());
        layout.leftMargin = viewport.left - origin[0]; layout.topMargin = viewport.top - origin[1];
        FrameLayout.LayoutParams menuLayout = (FrameLayout.LayoutParams) castMenu.getView().getLayoutParams();
        if (menuLayout.width != safeViewport.width() || menuLayout.height != safeViewport.height()
                || menuLayout.leftMargin != safeViewport.left - origin[0] || menuLayout.topMargin != safeViewport.top - origin[1]) {
            FrameLayout.LayoutParams safeLayout = new FrameLayout.LayoutParams(safeViewport.width(), safeViewport.height());
            safeLayout.leftMargin = safeViewport.left - origin[0]; safeLayout.topMargin = safeViewport.top - origin[1];
            castMenu.getView().setLayoutParams(safeLayout);
        }
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) output.getLayoutParams();
        if (old.width != layout.width || old.height != layout.height || old.leftMargin != layout.leftMargin || old.topMargin != layout.topMargin) {
            viewportPlaced = false; output.setLayoutParams(layout); layoutCandidateOutput(); return;
        }
        int[] xy = new int[2]; output.getLocationOnScreen(xy);
        if (xy[0] != viewport.left || xy[1] != viewport.top
                || output.getWidth() != viewport.width() || output.getHeight() != viewport.height()) { layoutCandidateOutput(); return; }
        if (!viewportPlaced) android.util.Log.i("VirtualRearHost", "Safe viewport=" + viewport.key() + " surface=" + xy[0] + "," + xy[1]);
        viewportPlaced = true;
        layoutCandidateOutput();
        updateCameraGapBattery();
        if (resizing) maybeFinishViewport(); else maybeStart();
    }
    private boolean isCurrentRecoveryEpoch(String id, int generation, int lossEpoch) {
        return VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(
                starting && session != null && !ending && !isFinishing(), instance == this,
                id != null && id.equals(session),
                reconnecting, generation, outputGeneration, true,
                missingSurfacePresentationGate.isEpisodeCurrent(lossEpoch));
    }
    private boolean isCurrentOutputPowerRequest(String id, int generation, int powerEpoch) {
        return !ending && !isFinishing() && starting && instance == this && id != null && id.equals(session)
                && reconnecting && generation == outputGeneration && powerEpoch == outputPowerEpoch;
    }
    private boolean isCurrentSurfaceRecovery(String id, int generation, int lossEpoch, Surface surface) {
        SurfaceHolder currentHolder = holder;
        Surface currentSurface = currentHolder == null ? null : currentHolder.getSurface();
        return VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(
                starting && session != null, !ending && !isFinishing() && instance == this,
                id != null && id.equals(session), reconnecting, generation, outputGeneration,
                surfaceConfigured && surface != null && currentSurface == surface,
                missingSurfacePresentationGate.isEpisodeCurrent(lossEpoch));
    }
    private void commitCurrentSurfaceRecovery(String id, int generation, int lossEpoch, Surface surface) {
        if (!VirtualCastOutputRecoveryPolicy.canCommitSurfaceRecovery(true,
                starting && session != null, !ending && !isFinishing() && instance == this,
                id != null && id.equals(session), reconnecting, generation, outputGeneration,
                surfaceConfigured && surface != null && surface.isValid()
                        && holder != null && holder.getSurface() == surface,
                missingSurfacePresentationGate.isEpisodeCurrent(lossEpoch))
                || !missingSurfacePresentationGate.commitRecovery(lossEpoch)) return;
        reconnecting = false;
        powerSuspendedOutput = false;
        main.removeCallbacks(outputTimeout);
        invalidateRecoveryActions();
        updateCameraGapBattery();
        android.util.Log.i("VirtualRearHost", "Rear output recovery backend commit accepted session=" + id);
        presentExistingHost();
    }
    private void submitSurfaceRecovery(String id, Surface surface, int generation, int lossEpoch) {
        worker.execute(() -> {
            try {
                if (!isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) return;
                ITaskService remote = service;
                if (remote == null) throw new IllegalStateException("Control service disconnected");
                boolean accepted = remote.updateRearVirtualCastSurface(id, surface);
                main.post(() -> {
                    if (!isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) return;
                    if (!accepted) {
                        fail(new IllegalStateException("Rear output session ended"));
                        return;
                    }
                    commitCurrentSurfaceRecovery(id, generation, lossEpoch, surface);
                });
            } catch (Exception e) {
                main.post(() -> { if (isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) fail(e); });
            }
        });
    }
    public void surfaceCreated(SurfaceHolder value) {
        if (starting && holder != value) outputGeneration++;
        surfaceConfigured = false;
        holder = value;
    }
    public void surfaceChanged(SurfaceHolder value, int format, int w, int h) {
        android.util.Log.i("VirtualRearHost", "Surface " + w + "x" + h + " starting=" + starting);
        if (starting && !resizing && (width != w || height != h)) { fail(new IllegalStateException("Rear Surface resized; restart casting")); return; }
        if (starting) outputGeneration++;
        Surface surface = value.getSurface();
        holder = value; surfaceConfigured = true; width = w; height = h;
        if (resizing) { layoutViewport(); return; }
        if (starting && reconnecting && !ending && !isFinishing()) {
            if (VirtualCastOutputPowerGate.deferSurfaceChangeWhilePowerSuspended(powerSuspendedOutput)) {
                android.view.Display rear = getDisplay();
                if (rear != null && rear.getState() == android.view.Display.STATE_ON)
                    main.post(() -> VirtualRearHostActivity.this.onRearDisplayResumed(false));
                return; // The gated ON transaction uses this latest Surface, never stale Backend state.
            }
            String id = session;
            int generation = outputGeneration;
            int lossEpoch = missingSurfaceLossEpoch;
            submitSurfaceRecovery(id, surface, generation, lossEpoch);
        } else maybeStart();
    }
    public void surfaceDestroyed(SurfaceHolder value) {
        holder = null; surfaceConfigured = false; outputGeneration++; main.removeCallbacks(startAfterLayout);
        missingSurfaceLossEpoch = missingSurfacePresentationGate.beginLoss();
        updateCameraGapBattery();
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        if (!starting || ending || isFinishing()) return;
        if (resizing) { fail(new IllegalStateException("Rear output lost during viewport change")); return; }
        boolean mainLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(PowerManager.class).isInteractive();
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        android.util.Log.i("VirtualRearHost", "Surface lost; mainLocked=" + mainLocked + " rearState=" + rearState
                + " session=" + session);
        PowerManager power = getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        boolean waitingForDisplay = VirtualCastOutputRecoveryPolicy.waitForDisplayResume(mainLocked, interactive, rearState);
        reconnecting = true;
        String lostSession = session;
        int lostGeneration = outputGeneration;
        int lossEpoch = missingSurfaceLossEpoch;
        invalidateRecoveryActions();
        if (rearState == android.view.Display.STATE_ON) armOutputTimeout(false);
        worker.execute(() -> {
            try {
                if (!ending && isCurrentRecoveryEpoch(lostSession, lostGeneration, lossEpoch)
                        && service != null && !service.updateRearVirtualCastSurface(lostSession, null))
                    throw new IllegalStateException("Rear output session ended");
            } catch (Exception e) {
                main.post(() -> { if (isCurrentRecoveryEpoch(lostSession, lostGeneration, lossEpoch)) fail(e); });
            }
        });
        if (!waitingForDisplay) {
            main.post(() -> {
                if (!ending && starting && instance == this && reconnecting
                        && lostSession != null && lostSession.equals(session)
                        && lossEpoch == missingSurfaceLossEpoch) onRearDisplayResumed(false);
            });
        } else {
            requestLockRecovery(150);
        }
    }
    private void maybeStart() {
        main.removeCallbacks(startAfterLayout);
        if (ending || starting) return;
        if (wallpaperStandby == null) {
            main.postDelayed(startAfterLayout, 300);
        } else if (surfaceConfigured && viewportPlaced && !wallpaperStartCancelled) {
            // The OEM may reclaim the rear screen before the ordinary presentation debounce.
            // Start from the correctly sized output; requireColdStart and the touch gate still
            // verify the exact foreground host, display, and all pointer releases.
            main.post(startAfterLayout);
        }
    }
    private void startSession() {
        ITaskService remote = service;
        if (ending || starting || remote == null || holder == null || viewport == null || !viewportPlaced
                || width != viewport.bufferWidth || height != viewport.bufferHeight) return;
        if (wallpaperStandby != null && (!surfaceConfigured || wallpaperStartCancelled)) return;
        starting = true;
        if (wallpaperStandby != null) main.postDelayed(this::showWallpaperOpeningHint, WALLPAPER_OPENING_HINT_MS);
        Surface surface = holder.getSurface();
        int target = getIntent().getIntExtra("task", -1), host = getTaskId();
        String operationId = getIntent().getStringExtra("rc5_operation_id");
        long ownerGeneration = getIntent().getLongExtra("rc5_owner_generation", 0L);
        String ownerIdentity = getIntent().getStringExtra("rc5_owner_identity");
        String expectedBase = getIntent().getStringExtra("rc5_expected_base");
        ISceneTaskActionGate sourceActionGate = ISceneTaskActionGate.Stub.asInterface(
                getIntent().getBundleExtra("rc5_gate_bundle") == null ? null
                        : getIntent().getBundleExtra("rc5_gate_bundle").getBinder("source_action_gate"));
        worker.execute(() -> {
            try {
                RearWallpaperStandby probe = wallpaperStandby;
                if (probe != null) probe.requireColdStart(remote);
                String path = new File(getApplicationInfo().nativeLibraryDir, RearInputProxy.READER_LIBRARY).getPath();
                if (operationId != null) {
                    ITaskService authority = taskServiceFromIntent(getIntent());
                    int rearId = getIntent().getIntExtra("rc5_rear_display", -1);
                    String rearIdentity = getIntent().getStringExtra("rc5_rear_identity");
                    if (!sameServiceBinder(remote, authority)
                            || verifyRearIdentity(authority, rearId, rearIdentity) != ControlledRearIdentityGate.Result.MATCH)
                        throw new IllegalStateException("controlled_start_authority_identity_rejected");
                }
                if (operationId != null && cancelledControlledOperations.contains(operationId))
                    throw new IllegalStateException("controlled_start_cancelled_before_backend");
                if (probe != null) {
                    SurfaceHolder expectedHolder = holder;
                    ISceneTaskActionGate gate = new VirtualCastRecoveryActionGate(() ->
                            !ending && !wallpaperStartCancelled && holder == expectedHolder
                                    && surface.isValid() && probe.isCurrentStart(remote));
                    status = remote.startParkedWallpaperCast(surface, path, host,
                            requestedLayoutDensity, getIntent().getStringExtra("component"), owner, gestures,
                            RearCastGeometry.encode(viewport), densityCompatibility, initialRotation, initialFull, gate);
                } else if (operationId == null) {
                    status = remote.startRearVirtualCast(surface, path, target, host,
                            requestedLayoutDensity, getIntent().getStringExtra("component"), owner, gestures,
                            RearCastGeometry.encode(viewport), densityCompatibility, initialRotation, initialFull);
                } else {
                    status = startControlledWithActionGate(remote, surface, path, target, host, expectedBase,
                            operationId, ownerGeneration, ownerIdentity, sourceActionGate);
                }
                JSONObject state = new JSONObject(status);
                if (operationId != null && cancelledControlledOperations.contains(operationId)) {
                    String id = state.optString("id");
                    if (!id.isEmpty() && operationId.equals(state.optString("coordinatorOperationId"))
                            && ownerGeneration == state.optLong("ownerGeneration", -1L)
                            && ownerIdentity.equals(state.optString("ownerIdentity")))
                        try { remote.stopRearVirtualCast(id); } catch (Exception cleanup) {
                            android.util.Log.e("VirtualRearHost", "controlled_start_cancel_cleanup_unverified", cleanup);
                        }
                    throw new IllegalStateException("controlled_start_cancelled_after_backend");
                }
                if (operationId != null && (!operationId.equals(state.optString("coordinatorOperationId"))
                        || ownerGeneration != state.optLong("ownerGeneration", -1L)
                        || !ownerIdentity.equals(state.optString("ownerIdentity"))))
                    throw new IllegalStateException("controlled_session_identity_mismatch");
                activeSessionStatus = state;
                session = state.getString("id");
                if (operationId != null) {
                    controlledOperationId = operationId; controlledOwnerGeneration = ownerGeneration;
                    controlledOwnerIdentity = ownerIdentity; controlledExpectedBaseComponent = expectedBase;
                }
                sessionPackage = state.getString("package");
                if (probe != null) {
                    probe.recordStarted(state);
                    main.post(() -> {
                        if (isFinishing() || isDestroyed() || !state.optString("id").equals(session)) return;
                        detachWallpaperOpeningHint();
                        if (wallpaperStartCancelled) exitPresentation();
                    });
                }
                main.post(this::updateCameraGapBattery);
                if (densityCompatibility && !"unrestricted".equals(state.optString("sizePolicy")))
                    main.post(() -> { if (!ending) android.widget.Toast.makeText(this, R.string.rear_density_partial_support,
                            android.widget.Toast.LENGTH_LONG).show(); });
                String monitored = state.optString("package") + ":" + state.optInt("task");
                android.util.Log.i("VirtualRearHost", status);
                if (shouldStartManagedKeeper(getIntent().getBooleanExtra("managed", false),
                        getIntent().getBooleanExtra("rc5_defer_keeper", false))) main.post(() -> {
                    if (ending || (probe != null && wallpaperStartCancelled)) return;
                    Intent keeper = new Intent(this, RearScreenKeeperService.class)
                            .putExtra(VirtualCastUriFlow.EXTRA_SESSION, session)
                            .putExtra("lastMovedTask", monitored)
                            .putExtra("keepScreenOnEnabled", getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                                    .getBoolean("flutter.keep_screen_on_enabled", true));
                    startForegroundService(keeper);
                });
            } catch (Exception e) { fail(e); }
        });
    }

    static boolean shouldStartManagedKeeper(boolean managed, boolean deferKeeper) {
        return managed && !deferKeeper;
    }

    static boolean shouldDeferReplacementLauncherRestore(boolean replacing, boolean physicalOwner) {
        return replacing && physicalOwner;
    }

    private String startControlledWithActionGate(ITaskService remote, Surface surface, String path,
            int target, int host, String expectedBase, String operationId, long ownerGeneration,
            String ownerIdentity, ISceneTaskActionGate sourceActionGate) throws Exception {
        long reservationStartedAtMs = System.nanoTime() / 1_000_000L;
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        if (reservation == null) throw new IllegalStateException("controlled_start_action_busy");
        SurfaceHolder expectedHolder = holder;
        Intent startIntent = getIntent();
        boolean physicalReplacement = startIntent.getBooleanExtra("rc5_defer_launcher_restore", false);
        LegacyDisplayOccupancy.PhysicalReplacementJournal replacement = physicalReplacement
                ? LegacyDisplayOccupancy.physicalReplacementJournal(this) : null;
        AtomicBoolean gateRejectLogged = new AtomicBoolean(false);
        ISceneTaskActionGate gate = new ISceneTaskActionGate.Stub() {
            private boolean reject(String reason) {
                if (BuildConfig.DEBUG && gateRejectLogged.compareAndSet(false, true))
                    android.util.Log.w("VirtualRearHostActivity",
                            "stage=controlled_host_action_gate_rejected reason=" + reason);
                return false;
            }

            private boolean rejectExpiredReservation() {
                long elapsedMs = Math.max(0L, System.nanoTime() / 1_000_000L - reservationStartedAtMs);
                if (BuildConfig.DEBUG && gateRejectLogged.compareAndSet(false, true))
                    android.util.Log.w("VirtualRearHostActivity",
                            "stage=controlled_host_action_gate_rejected reason=display_action_reservation_stale elapsed_ms="
                                    + Math.min(elapsedMs, 60_000L));
                return false;
            }

            @Override public boolean isCurrent() {
                SurfaceHolder current = holder;
                if (ending) return reject("host_ended");
                Intent intent = getIntent();
                if (intent == null || !operationId.equals(intent.getStringExtra("rc5_operation_id")))
                    return reject("operation_identity_changed");
                if (ownerGeneration != intent.getLongExtra("rc5_owner_generation", 0L))
                    return reject("owner_generation_changed");
                if (!ownerIdentity.equals(intent.getStringExtra("rc5_owner_identity")))
                    return reject("owner_identity_changed");
                if (target != intent.getIntExtra("task", -1)) return reject("target_task_changed");
                if (sourceActionGate == null) return reject("source_gate_missing");
                final boolean sourceCurrent;
                try { sourceCurrent = sourceActionGate.isCurrent(); }
                catch (RemoteException stale) { return reject("source_gate_unavailable"); }
                if (!sourceCurrent) return reject("source_gate_stale");
                if (physicalReplacement && !isCurrentPhysicalVirtualStart(replacement, operationId,
                        target, expectedBase, ownerGeneration, ownerIdentity, intent))
                    return reject("physical_replacement_owner_stale");
                if (cancelledControlledOperations.contains(operationId)) return reject("operation_cancelled");
                if (expectedHolder == null || current != expectedHolder) return reject("output_holder_changed");
                if (!current.getSurface().isValid() || !surface.isValid()) return reject("output_surface_invalid");
                if (!RearAnimationManager.isCurrentDisplayAction(reservation)) return rejectExpiredReservation();
                return true;
            }
            @Override public boolean isCurrentBeforeTargetMove() {
                try { return isCurrent() && sourceActionGate.isCurrentBeforeTargetMove() && isCurrent(); }
                catch (RemoteException stale) { return false; }
            }
        };
        try {
            if (!gate.isCurrent()) throw new IllegalStateException("controlled_start_gate_stale");
            Bundle receiptBundle = startIntent.getBundleExtra("rc5_gate_bundle");
            IBinder receiptBinder = receiptBundle == null ? null : receiptBundle.getBinder("physical_reader_lifecycle");
            IPhysicalCastReaderLifecycle readerLifecycle = IPhysicalCastReaderLifecycle.Stub.asInterface(receiptBinder);
            if (physicalReplacement && readerLifecycle == null)
                throw new IllegalStateException("physical_reader_lifecycle_missing");
            String started = physicalReplacement
                    ? remote.startControlledRearVirtualCastForPhysicalReplacementWithA(surface, path, target, host,
                        requestedLayoutDensity, getIntent().getStringExtra("component"), owner, gestures,
                        RearCastGeometry.encode(viewport), densityCompatibility, initialRotation, initialFull,
                        expectedBase, getIntent().getIntExtra("rc5_rear_display", -1),
                        getIntent().getStringExtra("rc5_rear_identity"), operationId, ownerGeneration,
                        ownerIdentity, gate, replacement.getOldTask(), replacement.getOldPackage(),
                        replacement.getOldBase(), readerLifecycle)
                    : remote.startControlledRearVirtualCastWithGate(surface, path, target, host,
                    requestedLayoutDensity, getIntent().getStringExtra("component"), owner, gestures,
                    RearCastGeometry.encode(viewport), densityCompatibility, initialRotation, initialFull,
                    expectedBase, getIntent().getIntExtra("rc5_rear_display", -1),
                    getIntent().getStringExtra("rc5_rear_identity"), operationId, ownerGeneration,
                    ownerIdentity, gate);
            return started;
        } finally { RearAnimationManager.completeDisplayAction(reservation); }
    }
    private boolean isCurrentPhysicalVirtualStart(LegacyDisplayOccupancy.PhysicalReplacementJournal expected,
            String operationId, int target, String expectedBase, long generation, String ownerIdentity, Intent intent) {
        if (expected == null) return false;
        LegacyDisplayOccupancy.PhysicalReplacementJournal live = LegacyDisplayOccupancy.physicalReplacementJournal(this);
        if (live == null || !operationId.equals(live.getToken()) || !live.getNewVirtual()
                || !"PREPARING".equals(live.getPhase()) || !"NONE".equals(live.getDecision())
                || live.getNewTask() != target || !live.getNewBase().equals(expectedBase)
                || live.getNewCoordinatorGeneration() != generation || !live.getNewOwnerIdentity().equals(ownerIdentity)
                || live.getDisplayId() != intent.getIntExtra("rc5_rear_display", -1)
                || !live.getIdentity().equals(intent.getStringExtra("rc5_rear_identity"))
                || !expected.getToken().equals(live.getToken()) || expected.getOldTask() != live.getOldTask()
                || expected.getOldGeneration() != live.getOldGeneration()
                || !expected.getOldPackage().equals(live.getOldPackage())
                || !expected.getOldBase().equals(live.getOldBase())) return false;
        String oldExact = live.getOldPackage() + ":" + live.getOldTask();
        return oldExact.equals(RearScreenKeeperService.physicalCastTask())
                && RearScreenKeeperService.physicalCastOwnerGeneration() == live.getOldGeneration()
                && LegacyDisplayOccupancy.isCurrentClaimedPhysicalReplacementOwner(this, live);
    }

    private void fail(Exception e) {
        if (ending || isFinishing()) return;
        RearWallpaperStandby probe = wallpaperStandby;
        boolean cancelled = probe != null && wallpaperStartCancelled;
        if (!cancelled) android.util.Log.e("VirtualRearHost", "Virtual casting stopped", e);
        String id = session;
        if (probe != null && id != null) {
            main.post(() -> returnEndedCast(id));
            return;
        }
        ending = true;
        if (probe == null) {
            main.post(this::finish);
            return;
        }
        // A rejected app need not consume the prepared task. Only reuse it if the backend is clean.
        worker.execute(() -> {
            boolean clean = false;
            try {
                ITaskService remote = service;
                if (TaskServiceManager.isConnectedServiceCurrent(remote)) {
                    JSONObject state = new JSONObject(remote.getRearVirtualCastStatus());
                    clean = "stopped".equals(state.optString("state"))
                            && Boolean.TRUE.equals(state.opt("managerResourcesStopped"));
                }
            } catch (Exception unavailable) {
                android.util.Log.w("VirtualRearHost", "Wallpaper cast cleanup unverified", unavailable);
            }
            final boolean reusable = clean;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!reusable) { probe.retire("parked_start_cleanup_unverified"); return; }
                resetPresentationForWallpaperStandby();
                if (cancelled) probe.afterCancelledStart();
                else probe.afterStartFailure();
            });
        });
    }

    private void showWallpaperOpeningHint() {
        if (ending || wallpaperStartCancelled || !starting || session != null
                || root == null || safeViewport == null || wallpaperOpeningHint != null) return;
        wallpaperOpeningHint = AiRearAppPickerView.opening(this, safeViewport, this::cancelWallpaperStart);
        root.addView(wallpaperOpeningHint, new FrameLayout.LayoutParams(-1, -1));
    }

    private void cancelWallpaperStart() {
        wallpaperStartCancelled = true;
        if (session != null) exitPresentation();
    }

    private void detachWallpaperOpeningHint() {
        if (wallpaperOpeningHint == null) return;
        if (root != null) root.removeView(wallpaperOpeningHint);
        wallpaperOpeningHint.disposeComposition();
        wallpaperOpeningHint = null;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Before the exclusive reader is ready, Android still dispatches rear touches normally.
        if (wallpaperPicker != null || wallpaperOpeningHint != null) return super.dispatchTouchEvent(event);
        return true;
    }
    private void updateCameraGapBattery() {
        updateCastNotification();
        if (cameraGapBattery == null || root == null) return;
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        cameraGapBattery.session(safeViewport, !ending && session != null && viewportPlaced && holder != null
                && !resizing && !reconnecting, viewport == null || viewport.isFull(), origin[0], origin[1]);
        if (cameraStatus != null) cameraStatus.session(safeViewport, !ending && session != null && viewportPlaced && holder != null
                && !resizing && !reconnecting, viewport == null || viewport.isFull(), origin[0], origin[1]);
    }
    private void updateCastNotification() {
        if (castNotification == null || root == null) return;
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        castNotification.hostState(safeViewport, origin[0], origin[1], hostResumed && !ending && session != null
                && viewportPlaced && holder != null && !resizing && !reconnecting && !menuVisible && !menuBusy,
                31 * java.util.Objects.hashCode(session) + outputGeneration + (viewport == null ? 0 : viewport.key().hashCode()));
    }
    @Override protected void onResume() {
        super.onResume();
        if (wallpaperStandby != null) wallpaperStandby.resumed();
        hostResumed = true; updateCastNotification();
        if (cameraGapBattery != null) { cameraGapBattery.visible(true); updateCameraGapBattery(); }
        if (cameraStatus != null) cameraStatus.visible(true);
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        if (root == null && wallpaperStandby == null && !isFinishing()) finish();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (wallpaperStandby != null) wallpaperStandby.focusChanged(focused);
    }
    @Override protected void onStop() {
        super.onStop();
        RearWallpaperStandby probe = wallpaperStandby;
        if (probe == null) return;
        probe.stopped();
        if (root == null || ending || session != null || isFinishing()) return;
        wallpaperStartCancelled = true;
        android.util.Log.i("RearParkProbe", "rear_hidden_before_cast task=" + getTaskId()
                + " backendRequested=" + starting);
        if (!starting) {
            // No backend transaction was submitted. Drop the hidden Surface now and re-arm this task.
            resetPresentationForWallpaperStandby();
            probe.afterCancelledStart();
        }
        // If a transaction is in flight its action gate rejects it, or its normal completion
        // stops the resulting session. That path owns teardown and must not race this callback.
    }
    @Override protected void onPause() {
        if (wallpaperStandby != null) wallpaperStandby.paused();
        hostResumed = false; updateCastNotification();
        if (cameraGapBattery != null) cameraGapBattery.visible(false);
        if (cameraStatus != null) cameraStatus.visible(false);
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(RearWallpaperStandby.PREPARE, false) && !intent.getBooleanExtra("stop", false)) {
            if (wallpaperStandby == null && root == null && !isFinishing()) prepareWallpaperStandby(intent);
            return;
        }
        if (wallpaperStandby != null) {
            if (intent.getBooleanExtra("stop", false)) { finish(); return; }
            if (root != null || wallpaperPicker != null) return;
            if (intent.getIntExtra("task", -1) >= 0 || intent.getStringExtra("component") != null) {
                // An idle prepared window must not block a normal cast addressed to this component.
                wallpaperStandby.close();
                wallpaperStandby = null;
                setIntent(intent);
                initializePresentation();
            }
            return;
        }
        setIntent(intent);
        if (intent.getBooleanExtra("stop", false)) { finish(); return; }
        if (intent.getBooleanExtra("rc5_cancel_candidate", false)) {
            String operation = intent.getStringExtra("rc5_operation_id");
            if (operation != null && operation.equals(candidateOperationId)) abortCandidate("controlled_cancel");
            return;
        }
        if (intent.getBooleanExtra("rc5_replace", false)) {
            beginReplacement(intent);
            return;
        }
        // DUMP-protected software visual/button QA only; does not simulate the native hold/grab chain.
        if (BuildConfig.DEBUG && session != null && !ending && !reconnecting) {
            if (intent.getBooleanExtra("preview_menu", false)) {
                android.util.Log.i("VirtualRearHost", "menu_visual_probe session=" + session);
                renderMenu(session, true);
            }
            if (intent.hasExtra("preview_menu_x") && menuVisible) {
                float x = intent.getFloatExtra("preview_menu_x", 0), y = intent.getFloatExtra("preview_menu_y", 0);
                long down = SystemClock.uptimeMillis(); String id = session;
                queueMenuTouch(id, MotionEvent.ACTION_DOWN, x, y, down, down);
                main.postDelayed(() -> queueMenuTouch(id, MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis()), 70);
            }
        }
        if (BuildConfig.DEBUG) {
        String preview = intent.getStringExtra("preview_back");
        if (preview != null && session != null && !reconnecting && !ending) {
            clearFeedback();
            int generation = gesturePreviewGeneration; String id = session;
            try {
                Class<?> previewType = Class.forName("top.zainzh.rearscreen.RearBackGesturePreview");
                java.lang.reflect.Method play = previewType.getDeclaredMethod("play", Handler.class, String.class, int.class, RearTouchRouter.Feedback.class, java.util.function.BooleanSupplier.class, Runnable.class);
                play.setAccessible(true);
                play.invoke(null, main, preview, getResources().getDisplayMetrics().densityDpi,
                    (RearTouchRouter.Feedback) (distance, y, active, ready, menuArmed) -> queueFeedback(id, distance, y, active, ready, menuArmed),
                    (java.util.function.BooleanSupplier) () -> !ending && !reconnecting && generation == gesturePreviewGeneration && id.equals(session), (Runnable) () -> renderMenu(id, true));
            } catch (ReflectiveOperationException e) { android.util.Log.e("VirtualRearHost", "Debug preview unavailable", e); }
        }
        }
    }

    /** Internal typed bridge for the in-process Cast coordinator; never reached through a URI route. */
    public static boolean startControlledCast(Context context, int taskId, String component, String targetPackage,
            String targetBaseComponent, int rearDisplayId, String rearIdentity, int density, boolean explicitDpi, boolean full, int rotation,
            CastOccupantIdentity expectedPrevious, long ownerGeneration, String ownerIdentity, String operationId,
            ISceneTaskActionGate sourceActionGate, ITaskService currentTaskService) {
        lastControlledStartCategory = "checking";
        if (context == null) return rejectControlledStart("context_missing");
        if (taskId <= 0) return rejectControlledStart("task_invalid");
        if (targetPackage == null || targetPackage.isEmpty()) return rejectControlledStart("target_package_missing");
        if (targetBaseComponent == null || targetBaseComponent.isEmpty()) return rejectControlledStart("target_component_missing");
        if (rearIdentity == null || rearIdentity.isEmpty()) return rejectControlledStart("rear_identity_missing");
        ControlledRearIdentityGate.Result rearCheck = verifyRearIdentity(currentTaskService, rearDisplayId, rearIdentity);
        if (rearCheck != ControlledRearIdentityGate.Result.MATCH)
            return rejectControlledStart("authoritative_rear_" + rearCheck.name().toLowerCase(java.util.Locale.ROOT));
        if (ownerIdentity == null || ownerIdentity.isEmpty()) return rejectControlledStart("owner_identity_missing");
        if (ownerGeneration <= 0) return rejectControlledStart("owner_generation_invalid");
        if (operationId == null || operationId.isEmpty()) return rejectControlledStart("operation_id_missing");
        if (sourceActionGate == null) return rejectControlledStart("action_gate_missing");
        if (cancelledControlledOperations.contains(operationId)) return rejectControlledStart("operation_cancelled");
        try { if (!sourceActionGate.isCurrent()) return rejectControlledStart("action_gate_stale_before_intent"); }
        catch (RemoteException stale) { return rejectControlledStart("action_gate_remote_" + stale.getClass().getSimpleName()); }
        rearCheck = verifyRearIdentity(currentTaskService, rearDisplayId, rearIdentity);
        if (rearCheck != ControlledRearIdentityGate.Result.MATCH)
            return rejectControlledStart("authoritative_rear_" + rearCheck.name().toLowerCase(java.util.Locale.ROOT));
        boolean replacing = expectedPrevious != null;
        Intent request = newControlledStartIntent(context)
                .putExtra("task", taskId).putExtra("component", component).putExtra("targetPackage", targetPackage)
                .putExtra("rc5_expected_base", targetBaseComponent).putExtra("rc5_rear_display", rearDisplayId)
                .putExtra("rc5_rear_identity", rearIdentity)
                .putExtra("dpi", density).putExtra("explicitDpi", explicitDpi)
                .putExtra("fullViewport", full).putExtra("candidateRotation", rotation)
                .putExtra("managed", true).putExtra("rc5_controlled_start", true)
                .putExtra("rc5_operation_id", operationId).putExtra("rc5_owner_generation", ownerGeneration)
                .putExtra("rc5_owner_identity", ownerIdentity);
        android.os.Bundle gateBundle = new android.os.Bundle();
        gateBundle.putBinder("source_action_gate", sourceActionGate.asBinder());
        gateBundle.putBinder("current_task_service", currentTaskService.asBinder());
        if (expectedPrevious != null && expectedPrevious.getMode()
                == top.zainzh.rearscreen.platform.CastMode.DIRECT_PHYSICAL) {
            IPhysicalCastReaderLifecycle lifecycle = new IPhysicalCastReaderLifecycle.Stub() {
                @Override public boolean onState(String callbackOperationId, String virtualSessionId,
                        long virtualGeneration, String state, String readerId, long readerGeneration,
                        int callbackTaskId, int callbackDisplayId, String callbackRearIdentity,
                        long callbackOwnerGeneration, String callbackOwnerIdentity) {
                    if (!operationId.equals(callbackOperationId) || callbackTaskId != taskId
                            || callbackDisplayId != rearDisplayId || !rearIdentity.equals(callbackRearIdentity)
                            || callbackOwnerGeneration != ownerGeneration || !ownerIdentity.equals(callbackOwnerIdentity))
                        return false;
                    try { return LegacyDisplayOccupancy.recordPhysicalReplacementReaderState(context,
                            operationId, virtualSessionId, virtualGeneration, state, readerId,
                            readerGeneration, taskId, rearDisplayId, rearIdentity, ownerGeneration, ownerIdentity); }
                    catch (RuntimeException unavailable) { return false; }
                }
            };
            gateBundle.putBinder("physical_reader_lifecycle", lifecycle.asBinder());
        }
        request.putExtra("rc5_gate_bundle", gateBundle);
        VirtualRearHostActivity active = instance;
        if (active != null && active.session != null && !active.ending) {
            if (!replacing) return rejectControlledStart("active_session_requires_replacement");
            try {
                if (!active.matchesExpectedPrevious(expectedPrevious, currentTaskService)) return rejectControlledStart("active_session_owner_mismatch");
            } catch (RuntimeException unavailable) {
                return rejectControlledStart("active_session_check_" + unavailable.getClass().getSimpleName());
            }
            request.putExtra("rc5_replace", true)
                    .putExtra("rc5_previous_session", expectedPrevious.getSessionId())
                    .putExtra("rc5_previous_generation", expectedPrevious.getGeneration())
                    .putExtra("rc5_previous_owner_generation", expectedPrevious.getOwnerGeneration())
                    .putExtra("rc5_previous_owner_identity", expectedPrevious.getOwner())
                    .putExtra("rc5_previous_task", expectedPrevious.getTaskId())
                    .putExtra("rc5_previous_host", expectedPrevious.getHostTaskId())
                    .putExtra("rc5_previous_input", expectedPrevious.getInputSessionId())
                    .putExtra("rc5_previous_base", expectedPrevious.getBaseComponent())
                    .putExtra("rc5_rear_display", expectedPrevious.getRearDisplayId())
                    .putExtra("rc5_rear_identity", rearIdentity)
                    .putExtra("rc5_previous_rear_identity", expectedPrevious.getRearIdentity());
            if (!active.starting || active.candidateOutput != null || active.candidateToken != null)
                return rejectControlledStart("active_instance_not_replaceable");
            if (!controlledRequestCurrent(request, operationId))
                return rejectControlledStart("action_gate_stale_after_owner_check");
            active.main.post(() -> active.beginReplacement(request));
            lastControlledStartCategory = "replacement_queued";
            return true;
        }
        if (replacing && expectedPrevious.getMode() != top.zainzh.rearscreen.platform.CastMode.DIRECT_PHYSICAL)
            return rejectControlledStart("replacement_owner_missing");
        if (active != null) return rejectControlledStart("host_instance_without_reusable_session");
        if (replacing) request.putExtra("rc5_defer_keeper", true);
        if (shouldDeferReplacementLauncherRestore(replacing,
                expectedPrevious != null && expectedPrevious.getMode() == top.zainzh.rearscreen.platform.CastMode.DIRECT_PHYSICAL))
            request.putExtra("rc5_defer_launcher_restore", true);
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            if (!sourceActionGate.isCurrent()) return rejectControlledStart("action_gate_stale_before_start");
            if (cancelledControlledOperations.contains(operationId)) return rejectControlledStart("operation_cancelled_before_start");
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(rearDisplayId);
            rearCheck = verifyRearIdentity(currentTaskService, rearDisplayId, rearIdentity);
            if (rearCheck != ControlledRearIdentityGate.Result.MATCH)
                return rejectControlledStart("authoritative_rear_" + rearCheck.name().toLowerCase(java.util.Locale.ROOT));
            if (!controlledRequestCurrent(request, operationId))
                return rejectControlledStart("action_gate_stale_after_identity_check");
            context.startActivity(request, options.toBundle());
            lastControlledStartCategory = "context_start_accepted";
            return true;
        } catch (RemoteException unavailable) {
            return rejectControlledStart("action_gate_remote_" + unavailable.getClass().getSimpleName());
        } catch (RuntimeException unavailable) {
            return rejectControlledStart("context_start_" + unavailable.getClass().getSimpleName());
        }
    }

    /** Returns only the actual presenter task bound to this in-flight operation, never a task chosen by a Mod. */
    public static int controlledPresenterTaskForMove(String operationId, long generation, String ownerIdentity,
            int targetTask, int rearDisplayId, String rearIdentity, ITaskService expectedService) {
        VirtualRearHostActivity active = instance;
        if (active == null || active.ending || !active.starting || operationId == null
                || ownerIdentity == null || rearIdentity == null || generation <= 0 || targetTask <= 0
                || rearDisplayId <= 0 || !sameServiceBinder(active.service, expectedService)
                || cancelledControlledOperations.contains(operationId)) return -1;
        SurfaceHolder outputHolder = active.holder;
        if (active.session == null) {
            Intent intent = active.getIntent();
            if (intent == null || !operationId.equals(intent.getStringExtra("rc5_operation_id"))
                    || generation != intent.getLongExtra("rc5_owner_generation", 0L)
                    || !ownerIdentity.equals(intent.getStringExtra("rc5_owner_identity"))
                    || targetTask != intent.getIntExtra("task", -1)
                    || rearDisplayId != intent.getIntExtra("rc5_rear_display", -1)
                    || !rearIdentity.equals(intent.getStringExtra("rc5_rear_identity"))) return -1;
        } else {
            if (!operationId.equals(active.candidateOperationId) || generation != active.candidateOwnerGeneration
                    || !ownerIdentity.equals(active.candidateOwnerIdentity) || targetTask != active.candidateTask
                    || rearDisplayId != active.candidateRearDisplayId || !rearIdentity.equals(active.candidateRearIdentity)
                    || !sameServiceBinder(active.candidateIdentityService, expectedService)) return -1;
            outputHolder = active.candidateHolder;
        }
        return outputHolder != null && outputHolder.getSurface().isValid() ? active.getTaskId() : -1;
    }

    private static Intent newControlledStartIntent(Context context) {
        return new Intent(context, VirtualRearHostActivity.class);
    }

    private static boolean sameServiceBinder(ITaskService first, ITaskService second) {
        try {
            IBinder a = first == null ? null : first.asBinder();
            IBinder b = second == null ? null : second.asBinder();
            return a != null && a == b && a.isBinderAlive();
        } catch (RuntimeException unavailable) { return false; }
    }

    private static ITaskService taskServiceFromIntent(Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra("rc5_gate_bundle");
        IBinder binder = bundle == null ? null : bundle.getBinder("current_task_service");
        return ITaskService.Stub.asInterface(binder);
    }

    private static ISceneTaskActionGate sourceActionGateFromIntent(Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra("rc5_gate_bundle");
        IBinder binder = bundle == null ? null : bundle.getBinder("source_action_gate");
        return ISceneTaskActionGate.Stub.asInterface(binder);
    }

    private static boolean controlledRequestCurrent(Intent intent, String operationId) {
        if (operationId == null || operationId.isEmpty() || cancelledControlledOperations.contains(operationId)) return false;
        ISceneTaskActionGate gate = sourceActionGateFromIntent(intent);
        if (gate == null) return false;
        try { return gate.isCurrent() && !cancelledControlledOperations.contains(operationId); }
        catch (Exception stale) { return false; }
    }

    private static ControlledRearIdentityGate.Service rearIdentityAuthority(ITaskService remote) {
        return new ControlledRearIdentityGate.Service() {
            @Override public boolean isCurrent() { return TaskServiceManager.isConnectedServiceCurrent(remote); }
            @Override public ControlledRearIdentityGate.Selection readFresh() throws Exception {
                JSONObject state = new JSONObject(remote.getRearDisplayState());
                return new ControlledRearIdentityGate.Selection(state.optInt("selectedId", -1),
                        state.optString("selectedUnique", ""));
            }
        };
    }

    private static ControlledRearIdentityGate.Result verifyRearIdentity(ITaskService remote,
            int expectedDisplayId, String expectedIdentity) {
        return ControlledRearIdentityGate.verify(rearIdentityAuthority(remote), expectedDisplayId, expectedIdentity);
    }

    private static boolean rejectControlledStart(String category) {
        lastControlledStartCategory = category;
        if (BuildConfig.DEBUG) android.util.Log.i("RC5HostStart", "reject=" + category);
        return false;
    }

    /** Read-only fields consumed by the DUMP-protected Debug diagnostic receiver. */
    public static JSONObject debugControlledStartSnapshot(Context context, ITaskService remote) {
        JSONObject result = new JSONObject();
        VirtualRearHostActivity active = instance;
        try {
            result.put("lastStartOutcomeCategory", lastControlledStartCategory)
                    .put("instancePresent", active != null)
                    .put("instanceHasSession", active != null && active.session != null && !active.session.isEmpty())
                    .put("instanceEnding", active != null && active.ending);
        } catch (Exception ignored) { }
        int selectedId = -1;
        String localIdentity = null;
        try { selectedId = RearDisplay.requireId(); }
        catch (RuntimeException failure) { resultPut(result, "localSelection", "unavailable_" + failure.getClass().getSimpleName()); }
        try { localIdentity = RearDisplay.identity(); }
        catch (RuntimeException failure) { resultPut(result, "localIdentity", "unavailable_" + failure.getClass().getSimpleName()); }
        try {
            JSONObject serviceState = new JSONObject(remote.getRearDisplayState());
            int serviceId = serviceState.optInt("selectedId", -1);
            String serviceUnique = serviceState.optString("selectedUnique", "");
            result.put("localSelectedId", selectedId).put("serviceSelectedId", serviceId)
                    .put("selectedIdMatches", selectedId > 0 && selectedId == serviceId)
                    .put("localIdentityAvailable", localIdentity != null && !localIdentity.isBlank())
                    .put("localIdentityMatchesService", localIdentity != null
                            && localIdentity.equals(serviceId + ":" + serviceUnique));
            ControlledRearIdentityGate.Result authorityResult = verifyRearIdentity(remote, serviceId,
                    serviceId + ":" + serviceUnique);
            result.put("controlledAuthorityGate", authorityResult.name().toLowerCase(java.util.Locale.ROOT));
            String category = selectedId < 1 ? "local_selection_unavailable"
                    : serviceId < 1 ? "service_selection_unavailable"
                    : selectedId != serviceId ? "selected_id_mismatch"
                    : localIdentity == null ? "local_identity_unavailable"
                    : !localIdentity.equals(serviceId + ":" + serviceUnique) ? "identity_mismatch" : "matched";
            result.put("identityComparison", category);
            if (selectedId > 0) {
                Intent exactHost = newControlledStartIntent(context)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                result.put("hostIntentComponentMatches", exactHost.getComponent() != null
                        && context.getPackageName().equals(exactHost.getComponent().getPackageName())
                        && VirtualRearHostActivity.class.getName().equals(exactHost.getComponent().getClassName()))
                        .put("hostIntentFlags", exactHost.getFlags())
                        .put("hostIntentShape", "same_explicit_component_and_flags_as_default_start");
                try {
                    java.lang.reflect.Method allowed = ActivityManager.class.getMethod(
                            "isActivityStartAllowedOnDisplay", Context.class, int.class, Intent.class);
                    ActivityManager manager = context.getSystemService(ActivityManager.class);
                    if (manager == null) {
                        result.put("activityStartCheckAvailable", false)
                                .put("activityStartResultCategory", "activity_manager_unavailable");
                        return result;
                    }
                    Object value = allowed.invoke(manager, context, selectedId, exactHost);
                    result.put("activityStartCheckAvailable", true)
                            .put("activityStartAllowed", value instanceof Boolean ? (Boolean) value : JSONObject.NULL)
                            .put("activityStartResultCategory", value instanceof Boolean ? "returned_boolean" : "unexpected_return_type");
                } catch (NoSuchMethodException missing) {
                    result.put("activityStartCheckAvailable", false).put("activityStartResultCategory", "method_unavailable");
                } catch (java.lang.reflect.InvocationTargetException failed) {
                    Throwable cause = failed.getCause();
                    result.put("activityStartCheckAvailable", true).put("activityStartResultCategory",
                            "threw_" + (cause == null ? "unknown" : cause.getClass().getSimpleName()));
                } catch (Exception failed) {
                    result.put("activityStartCheckAvailable", true)
                            .put("activityStartResultCategory", "reflection_" + failed.getClass().getSimpleName());
                }
            } else {
                result.put("hostIntentComponentMatches", false).put("activityStartCheckAvailable", false)
                        .put("activityStartResultCategory", "selected_display_unavailable");
            }
        } catch (Exception failed) {
            resultPut(result, "serviceSnapshotCategory", "unavailable_" + failed.getClass().getSimpleName());
        }
        return result;
    }

    private static void resultPut(JSONObject result, String key, String value) {
        try { result.put(key, value); } catch (Exception ignored) { }
    }

    public static void cancelControlledCast(Context context, String operationId) {
        if (operationId == null || operationId.isEmpty()) return;
        cancelledControlledOperations.add(operationId);
        VirtualRearHostActivity active = instance;
        if (active != null) active.main.post(() -> {
            if (operationId.equals(active.candidateOperationId)) active.abortCandidate("controlled_cancel");
            else if (operationId.equals(active.controlledOperationId)) active.stopControlledExact(operationId);
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> cancelledControlledOperations.remove(operationId), 30_000L);
    }

    private static CastOccupantIdentity readExpectedPrevious(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("rc5_replace", false)) return null;
        try {
            return new CastOccupantIdentity(top.zainzh.rearscreen.platform.CastMode.VIRTUAL,
                    intent.getStringExtra("rc5_previous_owner_identity"),
                    intent.getStringExtra("rc5_previous_session"),
                    intent.getLongExtra("rc5_previous_generation", -1L),
                    intent.getLongExtra("rc5_previous_owner_generation", -1L),
                    intent.getIntExtra("rc5_previous_task", -1),
                    intent.getIntExtra("rc5_previous_host", -1),
                    intent.getStringExtra("rc5_previous_input"),
                    intent.getStringExtra("rc5_previous_base"),
                    intent.getIntExtra("rc5_rear_display", -1),
                    intent.getStringExtra("rc5_previous_rear_identity"),
                    top.zainzh.rearscreen.platform.CastOccupantSource.VIRTUAL_OWNER);
        } catch (RuntimeException malformed) { return null; }
    }

    private static String canonicalComponent(String flattened) {
        android.content.ComponentName component = android.content.ComponentName.unflattenFromString(flattened);
        return component == null ? "" : component.flattenToString();
    }

    private boolean matchesExpectedPrevious(CastOccupantIdentity expected, ITaskService authority) {
        try {
            if (expected == null || !"VIRTUAL".equals(expected.getMode().name())
                    || !sameServiceBinder(service, authority)
                    || verifyRearIdentity(authority, expected.getRearDisplayId(), expected.getRearIdentity())
                        != ControlledRearIdentityGate.Result.MATCH
                    || !expected.getSessionId().equals(session) || !expected.getOwner().equals(controlledOwnerIdentity)
                    || expected.getOwnerGeneration() != controlledOwnerGeneration
                    || expected.getHostTaskId() != getTaskId()) return false;
            JSONObject live = activeSessionStatus == null ? new JSONObject(status) : activeSessionStatus;
            JSONObject input = live.optJSONObject("input");
            return expected.getSessionId().equals(live.optString("id"))
                    && expected.getGeneration() == live.optLong("generation", -1L)
                    && expected.getOwnerGeneration() == live.optLong("ownerGeneration", -1L)
                    && expected.getOwner().equals(live.optString("ownerIdentity"))
                    && expected.getTaskId() == live.optInt("task", -1)
                    && expected.getHostTaskId() == live.optInt("host", -1)
                    && expected.getRearDisplayId() == live.optInt("rearDisplayId", -1)
                    && expected.getInputSessionId().equals(input == null ? "" : input.optString("id", ""))
                    && canonicalComponent(expected.getBaseComponent())
                        .equals(canonicalComponent(live.optString("baseComponent")));
        } catch (Exception unavailable) { return false; }
    }

    private void stopControlledExact(String operationId) {
        ITaskService remote = service;
        String id = session;
        if (remote == null || id == null || !operationId.equals(controlledOperationId)) return;
        worker.execute(() -> {
            try {
                JSONObject live = new JSONObject(remote.getRearVirtualCastStatus());
                if (id.equals(live.optString("id")) && operationId.equals(live.optString("coordinatorOperationId"))
                        && controlledOwnerGeneration == live.optLong("ownerGeneration", -1L)
                        && controlledOwnerIdentity.equals(live.optString("ownerIdentity")))
                    remote.stopRearVirtualCast(id);
            } catch (Exception failure) { android.util.Log.w("VirtualRearHost", "controlled_exact_cancel_unverified", failure); }
        });
    }

    /** Package-private AndroidTest entry: arms only for an already-running sample A session. */
    static boolean armDebugLateFailureForKnownSampleReplacement() {
        if (!BuildConfig.DEBUG) return false;
        VirtualRearHostActivity active = instance;
        if (active == null || active.ending || active.candidateOutput != null || active.candidateToken != null)
            return false;
        JSONObject a = active.activeSessionStatus;
        if (a == null) {
            try { a = new JSONObject(active.status); }
            catch (Exception malformed) { return false; }
        }
        JSONObject input = a.optJSONObject("input");
        String sessionId = a.optString("id");
        String owner = a.optString("ownerIdentity");
        long generation = a.optLong("ownerGeneration", -1L);
        int task = a.optInt("task", -1), host = a.optInt("host", -1);
        String reader = input == null ? "" : input.optString("id");
        if (!"running".equals(a.optString("state")) || !"dev.remrss.sample.rc5consumer".equals(a.optString("package"))
                || !owner.contains("transportPackageName=dev.remrss.sample.rc5consumer")
                || generation <= 0 || task <= 0 || task == host || host != active.getTaskId()
                || reader.isBlank() || input == null || !"running".equals(input.optString("state"))
                || !input.optBoolean("outputAvailable") || !a.optBoolean("outputAvailable")
                || !active.controlledOutputHasFrame(sessionId, 1_000L)) return false;
        boolean armed = DebugControlledCastLateFailure.armKnownSampleReplacement(sessionId, owner,
                generation, task, host, reader,
                "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity");
        if (armed) {
            android.util.Log.i("VirtualRearHost", "test_fixture_armed A=dev.remrss.sample.rc5consumer B=dev.remrss.sample.rc5candidate");
            active.main.post(() -> android.widget.Toast.makeText(active,
                    "测试夹具已就绪：打开 rc5candidate，点击 Start virtual cast，并批准确认页",
                    android.widget.Toast.LENGTH_LONG).show());
        }
        return armed;
    }

    /** Waits for a real evdev contact on the preserved A reader; this method never sends input. */
    static boolean awaitDebugLateFailurePhysicalTouch(DebugControlledCastLateFailure.FixtureSnapshot fixture,
            long timeoutMs) {
        VirtualRearHostActivity active = instance;
        if (!BuildConfig.DEBUG || active == null || fixture == null || timeoutMs <= 0) return false;
        ITaskService remote = active.service;
        if (remote == null) return false;
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                JSONObject live = new JSONObject(remote.getRearVirtualCastStatus());
                JSONObject input = live.optJSONObject("input");
                boolean sameOwnerReader = fixture.aSession.equals(live.optString("id"))
                        && "running".equals(live.optString("state"))
                        && fixture.aOwner.equals(live.optString("ownerIdentity"))
                        && fixture.aGeneration == live.optLong("ownerGeneration", -1L)
                        && fixture.aTask == live.optInt("task", -1) && fixture.aHost == live.optInt("host", -1)
                        && input != null && fixture.aReader.equals(input.optString("id"))
                        && "running".equals(input.optString("state")) && input.optBoolean("outputAvailable");
                long frames = input == null ? -1L : input.optLong("frames", -1L);
                long events = input == null ? -1L : input.optLong("events", -1L);
                if (sameOwnerReader && frames > fixture.postFrames && events > fixture.postEvents) {
                    android.util.Log.i("VirtualRearHost", "test_fixture_second_touch_observed same_reader=true frames="
                            + frames + " events=" + events);
                    return true;
                }
            } catch (Exception ignored) { }
            try { Thread.sleep(100L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return false;
            }
        }
        return false;
    }

    /** True only after PixelCopy reads a produced buffer from this exact controlled session.
     * Legitimately black app content is valid; this is not proof of physical panel visibility.
     */
    public static boolean controlledOutputHasFrame(String sessionId, long timeoutMs) {
        VirtualRearHostActivity active = instance;
        if (active == null || sessionId == null || sessionId.isEmpty()) return false;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean pixels = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable copy = () -> {
            if (instance != active || !sessionId.equals(active.session) || active.output == null
                    || active.output.getHolder() == null || !active.output.getHolder().getSurface().isValid()) {
                done.countDown(); return;
            }
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(active.output, bitmap, result -> {
                try { pixels.set(result == android.view.PixelCopy.SUCCESS); }
                finally { bitmap.recycle(); done.countDown(); }
            }, active.main);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) copy.run(); else active.main.post(copy);
        try { return done.await(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS) && pixels.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    /** Window-relative input transform source for the same host/revision that owns the Surface. */
    public static int[] controlledHostWindowGeometry(String sessionId) {
        VirtualRearHostActivity active = instance;
        if (active == null || sessionId == null || !sessionId.equals(active.session) || active.root == null) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) return active.readHostWindowGeometry();
        final int[][] result = new int[1][];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        active.main.post(() -> { result[0] = active.readHostWindowGeometry(); done.countDown(); });
        try { if (!done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) return null; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        return result[0];
    }

    private int[] readHostWindowGeometry() {
        FrameLayout currentRoot = root;
        if (currentRoot == null || currentRoot.getWidth() <= 0 || currentRoot.getHeight() <= 0) return null;
        int[] origin = new int[2]; currentRoot.getLocationOnScreen(origin);
        return new int[]{origin[0], origin[1], currentRoot.getWidth(), currentRoot.getHeight()};
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (backIndicator != null) backIndicator.applyTheme();
        if (cameraGapBattery != null) cameraGapBattery.themeChanged();
        if (cameraStatus != null) cameraStatus.themeChanged();
        if (castNotification != null) castNotification.applyTheme();
        if (!ending && safeViewport != null) worker.execute(() -> {
            try {
                if (!ending && !safeViewport.key().equals(RearCastGeometry.decode(service.getRearCastViewport()).key()))
                    throw new IllegalStateException("Rear display geometry changed; restart casting");
            } catch (Exception e) { fail(e); }
        });
    }
    private void returnEndedCast(String id) {
        if (ending || isFinishing() || !id.equals(session)) return;
        if (wallpaperStandby == null) { finish(); return; }
        android.util.Log.i("RearParkProbe", "return_after_guest_end task=" + getTaskId());
        exitPresentation();
    }

    private void exitPresentation() {
        RearWallpaperStandby probe = wallpaperStandby;
        if (probe == null) { finish(); return; }
        if (ending) return;
        String id = session;
        if (id == null) {
            if (starting) cancelWallpaperStart();
            else {
                resetPresentationForWallpaperStandby();
                probe.afterCancelledStart();
            }
            return;
        }
        if (!probe.beginReturn()) return;
        ending = true; // Stop lease renewal and new input/Surface work for this round.
        ITaskService remote = service;
        int hostTask = getTaskId();
        JSONObject owned = activeSessionStatus;
        Runnable showOfficialRear = () -> {
            if (!ending || !id.equals(session) || isFinishing() || isDestroyed()) return;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            probe.outputReleased();
        };
        IRearWallpaperOutputReleased receipt = new IRearWallpaperOutputReleased.Stub() {
            @Override public void onReleased(String releasedId, int releasedHost) {
                if (releasedHost != hostTask || !id.equals(releasedId)) return;
                main.post(showOfficialRear);
            }
        };
        worker.execute(() -> {
            try {
                if (!TaskServiceManager.isConnectedServiceCurrent(remote))
                    throw new IllegalStateException("parked_cast_service_disconnected");
                JSONObject released = ParkedWallpaperCastReturn.release(id, owned,
                        () -> new JSONObject(remote.getRearVirtualCastStatus()),
                        exactId -> remote.stopParkedWallpaperCast(exactId, receipt));
                if (released == null) throw new IllegalStateException("parked_cast_cleanup_unverified");
                // The backend may have already released the guest, without a menu-exit receipt.
                main.post(showOfficialRear);
                android.util.Log.i("RearParkProbe", "terminal_reader_display task=" + hostTask + " id=" + id);
                if (!RearScreenKeeperService.stopVirtualSessionAndAwait(id, 2_000))
                    throw new IllegalStateException("parked_cast_keeper_cleanup_unverified");
                boolean officialFront = probe.officialRearOwnsDisplay(remote);
                android.util.Log.i("RearParkProbe", "return_readback task=" + hostTask
                        + " officialFront=" + officialFront);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    resetPresentationForWallpaperStandby();
                    probe.afterReturn(released, officialFront);
                });
            } catch (Exception e) {
                android.util.Log.e("VirtualRearHost", "Cannot return parked cast", e);
                main.post(() -> probe.retire("return_cleanup_failed"));
            }
        });
    }

    private void detachPresentationUi() {
        ending = true; outputPowerEpoch++; screenOffCycleEpoch++; invalidateRecoveryActions();
        missingSurfacePresentationGate.invalidate(); main.removeCallbacksAndMessages(null);
        detachWallpaperOpeningHint();
        RearCastNotification.detach(notificationTarget);
        if (castNotification != null) castNotification.close();
        if (cameraGapBattery != null) cameraGapBattery.close();
        if (cameraStatus != null) cameraStatus.close();
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        android.hardware.display.DisplayManager displayManager = getSystemService(android.hardware.display.DisplayManager.class);
        if (displayManager != null) displayManager.unregisterDisplayListener(rearDisplayListener);
        if (presentationBack != null) { presentationBack.remove(); presentationBack = null; }
        if (root != null) root.getViewTreeObserver().removeOnGlobalLayoutListener(presentationLayout);
    }

    private void resetPresentationForWallpaperStandby() {
        detachPresentationUi();
        output.getHolder().removeCallback(this);
        if (instance == this) instance = null;
        TaskServiceManager.release(connection); // The parked task still holds its minimal Shizuku binding.
        service = null;
        session = null;
        sessionPackage = null;
        activeSessionStatus = null;
        status = "{}";
        outputGeneration++;
        output = null;
        holder = null;
        root = null;
        cameraGapBattery = null;
        cameraStatus = null;
        castNotification = null;
        backIndicator = null;
        castMenu = null;
        safeViewport = null;
        viewport = null;
        lastViewportLayout = null;
        width = height = 0;
        surfaceConfigured = viewportPlaced = false;
        starting = reconnecting = powerSuspendedOutput = false;
        resizing = resizeSubmitted = menuBusy = false;
        lockRecoveryRequested = wakeAttempted = false;
        showWallpaperStandbyWindow();
    }

    @Override protected void onDestroy() {
        detachWallpaperPicker();
        if (wallpaperStandby != null) {
            wallpaperStandby.close();
            if (root == null) { worker.shutdown(); super.onDestroy(); return; }
        }
        detachPresentationUi();
        worker.execute(() -> {
            try {
                if (service != null && candidateToken != null)
                    service.abortRearVirtualCastReplacement(candidateToken);
                if (service != null && session != null) service.stopRearVirtualCast(session);
            }
            catch (Exception e) { android.util.Log.e("VirtualRearHost", "Stop", e); }
            finally {
                releaseCandidateReservation(candidateReservation);
                TaskServiceManager.release(connection);
            }
        });
        if (session != null) RearScreenKeeperService.stopVirtualSession(session);
        worker.shutdown();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
