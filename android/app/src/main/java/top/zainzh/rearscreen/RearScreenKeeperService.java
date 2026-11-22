/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.List;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import top.zainzh.rearscreen.platform.LegacyDisplayOccupancy;

/**
 * 前台Service - 保持背屏常亮
 * 
 * 为什么用Service而不是Activity：
 * - Activity方案失败了3次（FLAG_NOT_FOCUSABLE、屏幕外、alpha=0都会被onStop）
 * - Service不会被onPause/onStop，系统很难杀死前台Service
 * - 可以直接持有WakeLock保持屏幕常亮
 * 
 * 注意：WakeLock可能会让两个屏幕都保持常亮（无法指定特定display）
 */
public class RearScreenKeeperService extends Service implements SensorEventListener {
    private static final String TAG = "RearScreenKeeperService";
    private static final String CHANNEL_ID = "rear_screen_keeper";
    private static final int NOTIFICATION_ID = 10001;

    private static volatile RearScreenKeeperService instance = null;
    private static final Object VIRTUAL_SESSION_LOCK = new Object();
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService = null;
    private volatile String virtualSessionId;
    private volatile int latestServiceStartId;
    private volatile long physicalCastOwnerGeneration;
    private volatile PhysicalStopRequest physicalStopRequest;

    private static final class PhysicalStopRequest {
        final String task;
        final long ownerGeneration;
        final CountDownLatch destroyed = new CountDownLatch(1);
        final AtomicBoolean stopped = new AtomicBoolean(false);
        PhysicalStopRequest(String task, long ownerGeneration) {
            this.task = task; this.ownerGeneration = ownerGeneration;
        }
    }
    static boolean isPhysicalCastRunning() {
        return physicalCastTask() != null;
    }
    static boolean hasAnyKeeperInstance() { return instance != null; }
    public static String physicalCastTask() {
        RearScreenKeeperService s = instance;
        return s != null && s.virtualSessionId == null ? s.monitoredTaskInfo : null;
    }
    public static long physicalCastOwnerGeneration() {
        RearScreenKeeperService s = instance;
        return s != null && s.virtualSessionId == null ? s.physicalCastOwnerGeneration : 0L;
    }
    static boolean stopPhysicalSession(String exactTask, long ownerGeneration) {
        RearScreenKeeperService s = instance;
        if (s == null || exactTask == null || ownerGeneration <= 0L || s.handler == null) return false;
        PhysicalStopRequest request = new PhysicalStopRequest(exactTask, ownerGeneration);
        if (!s.handler.post(() -> {
            PhysicalKeeperStopPolicy.Decision decision = PhysicalKeeperStopPolicy.decide(
                    exactTask, ownerGeneration, s.monitoredTaskInfo, s.physicalCastOwnerGeneration,
                    s.latestServiceStartId, s.virtualSessionId == null, true);
            if (instance != s || !decision.allowed) {
                request.destroyed.countDown();
                return;
            }
            s.physicalStopRequest = request;
            if (!s.stopSelfResult(decision.latestStartId)) {
                if (s.physicalStopRequest == request) s.physicalStopRequest = null;
                request.destroyed.countDown();
            }
        })) return false;
        try { return request.destroyed.await(5000, TimeUnit.MILLISECONDS) && request.stopped.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    public static void stopVirtualSession(String id) {
        RearScreenKeeperService s = instance;
        if (s != null && id != null && id.equals(s.virtualSessionId)) s.handler.post(s::stopSelf);
    }

    public static boolean virtualSessionMatches(String id) {
        RearScreenKeeperService s = instance;
        return s != null && id != null && id.equals(s.virtualSessionId);
    }

    /** Bounded acknowledgement for controlled virtual-session Keeper transfer. */
    public static boolean awaitVirtualSessionClaim(String id, long timeoutMillis) {
        if (id == null || id.isEmpty()) return false;
        long deadline = System.nanoTime() + Math.max(0L, Math.min(timeoutMillis, 2000L)) * 1_000_000L;
        synchronized (VIRTUAL_SESSION_LOCK) {
            while (true) {
                RearScreenKeeperService s = instance;
                if (s != null && id.equals(s.virtualSessionId)) return true;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                try { VIRTUAL_SESSION_LOCK.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
        }
    }

    /** Stops only the exact virtual-session Keeper and waits for its instance to leave. */
    public static boolean stopVirtualSessionAndAwait(String id, long timeoutMillis) {
        if (id == null || id.isEmpty()) return false;
        RearScreenKeeperService s = instance;
        if (s == null) return true;
        if (!id.equals(s.virtualSessionId) || s.handler == null) return false;
        if (!s.handler.post(() -> { if (instance == s && id.equals(s.virtualSessionId)) s.stopSelf(); })) return false;
        long deadline = System.nanoTime() + Math.max(0L, Math.min(timeoutMillis, 2000L)) * 1_000_000L;
        synchronized (VIRTUAL_SESSION_LOCK) {
            while (instance == s) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                try { VIRTUAL_SESSION_LOCK.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return true;
    }

    /** One-shot, generation-bound recovery action for an indeterminate controlled physical return. */
    public static void notifyControlledPhysicalRecovery(Context supplied, int expectedTask, long expectedGeneration) {
        Context context = supplied.getApplicationContext();
        try {
            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity owner =
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, RearDisplay.requireId());
            LegacyDisplayOccupancy.PhysicalReplacementJournal replacement =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            boolean exactOwner = owner != null && owner.getTaskId() == expectedTask
                    && owner.getGeneration() == expectedGeneration;
            boolean exactJournal = replacement != null && replacement.getOldTask() == expectedTask
                    && replacement.getOldGeneration() == expectedGeneration
                    && !"ACCEPTED_B".equals(replacement.getPhase());
            if (!exactOwner && !exactJournal) return;
            String replacementToken = exactJournal ? replacement.getToken() : null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.notif_kernel_service), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
            }
            Intent retry = new Intent(context, RearScreenKeeperService.class)
                    .setAction("ACTION_RETURN_TO_MAIN")
                    .setData(android.net.Uri.parse("remrss://controlled-return/" + expectedTask
                            + "/" + expectedGeneration + (replacementToken == null ? "" : "/" + replacementToken)))
                    .putExtra("controlledTask", expectedTask)
                    .putExtra("controlledGeneration", expectedGeneration);
            if (replacementToken != null) retry.putExtra("controlledReplacementToken", replacementToken);
            int request = 0x52000000;
            PendingIntent action = PendingIntent.getService(context, request, retry,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_mrss)
                    .setContentTitle(exactJournal ? "受控投放仍在恢复" : "直接投放仍在恢复")
                    .setContentText(exactJournal ? "点按“安全恢复”重试精确接管恢复" : "点按“返回主屏”重试精确归还")
                    .addAction(0, exactJournal ? "安全恢复" : "返回主屏", action)
                    .setAutoCancel(true).setOngoing(false).setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            context.getSystemService(NotificationManager.class).notify(request, notification);
        } catch (RuntimeException failure) {
            Log.w(TAG, "controlled_recovery_notification_unavailable", failure);
        }
    }

    public static void clearControlledPhysicalRecoveryNotification(Context supplied, long generation) {
        if (generation <= 0L) return;
        Context context = supplied.getApplicationContext();
        try {
            Long currentGeneration = LegacyDisplayOccupancy.controlledPhysicalProjectionGeneration(context);
            if (currentGeneration != null && currentGeneration != generation) return;
            int request = 0x52000000;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(request);
        } catch (RuntimeException failure) {
            Log.w(TAG, "controlled_recovery_notification_cancel_unavailable", failure);
        }
    }

    static boolean matchesControlledReturnRequest(boolean hasTask, boolean hasGeneration,
            int requestedTask, long requestedGeneration, int ownerTask, long ownerGeneration) {
        return hasTask && hasGeneration && requestedTask > 0 && ownerTask > 0 && ownerGeneration > 0L
                && requestedTask == ownerTask && requestedGeneration == ownerGeneration;
    }

    static boolean delayedProximityOwnerStillCurrent(int triggerStartId, int currentStartId,
            boolean triggerHadControlledOwner, int triggerTask, long triggerGeneration,
            boolean currentHasControlledOwner, int currentTask, long currentGeneration) {
        // Preserve the legacy debounce path when no controlled owner exists at either point.
        if (!triggerHadControlledOwner && !currentHasControlledOwner) return true;
        if (triggerStartId != currentStartId || !triggerHadControlledOwner || !currentHasControlledOwner) return false;
        return triggerTask > 0 && triggerGeneration > 0L && triggerTask == currentTask
                && triggerGeneration == currentGeneration;
    }
    private RearInputProxySession inputProxySession;
    private void stopInputProxy() {
        if (inputProxySession != null) { inputProxySession.close(); inputProxySession = null; }
    }
    private void startInputProxy() {
        stopInputProxy();
        if (virtualSessionId != null || hasExclusiveDisplayLease()) return;
        if (!monitoringPaused && taskService != null && RearInputProxySession.enabled(this)
                && monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            try {
                int task = Integer.parseInt(monitoredTaskInfo.substring(monitoredTaskInfo.lastIndexOf(':') + 1));
                inputProxySession = new RearInputProxySession(this, taskService, task);
            } catch (RuntimeException e) { Log.w(TAG, "Invalid proxy cast task", e); }
        }
    }
    public static void refreshInputProxyPreference() {
        RearScreenKeeperService s = instance;
        if (s != null && s.handler != null) s.handler.post(() -> {
            if (s.virtualSessionId == null && s.hasExclusiveDisplayLease()) return;
            s.stopRearScreenWakeup();
            s.startInputProxy();
            s.startRearScreenWakeup();
        });
    }

    // V12.3: 初始杀进程策略 - 只杀1次，不持续监控
    private static final int INITIAL_KILL_COUNT = 1; // 初始杀1次
    private static final long KILL_INTERVAL_MS = 200; // 每次间隔200ms

    // V12.1: 接近传感器监听
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private boolean proximityListenerRegistered;
    private boolean isProximityCovered = false;
    private long lastProximityTime = 0;
    private static final long PROXIMITY_DEBOUNCE_MS = 1500; // 防抖动：1500ms内连续覆盖才触发（降低灵敏度）

    // V2.2: 接近传感器开关状态
    private boolean proximitySensorEnabled = true; // 默认启用

    // V14.5: 监听应用是否手动移回主屏
    private static final long CHECK_TASK_INTERVAL_MS = 2000; // 每2秒检查一次
    private volatile String monitoredTaskInfo = null; // 格式: "packageName:taskId"
    private volatile LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity pendingControlledReturn;
    private volatile int pendingControlledReturnStartId;
    private volatile String pendingReplacementRecoveryToken;
    private volatile int pendingReplacementRecoveryStartId;
    private volatile boolean sceneLeaseSuspended;
    private volatile String sceneLeaseId;
    private boolean sceneLeaseHadMonitoringPaused;
    private int sceneLeaseResumeAttempts;
    private boolean startCommandProcessed;

    // V2.3: 临时暂停监控（充电动画显示期间）
    private boolean monitoringPaused = false;

    // V2.4: 持续唤醒背屏（防止自动熄屏）
    private static final long WAKEUP_INTERVAL_MS = 100; // 持续发送，每0.1秒唤醒一次（对熄屏几乎无感）
    private boolean keepScreenOnEnabled = true; // 默认启用背屏常亮
    private boolean wakeupLoopRunning;

    private boolean hasExclusiveDisplayLease() {
        return sceneLeaseSuspended || SceneLegacyServiceLease.hasActiveLease();
    }

    private boolean mayRunLegacyWakeup() {
        return LegacyDisplayKeeperPolicy.mayWake(keepScreenOnEnabled, sceneLeaseSuspended,
                SceneLegacyServiceLease.hasActiveLease());
    }

    public static void pauseMonitoring() {
        if (instance != null && (instance.virtualSessionId != null || !SceneLegacyServiceLease.hasActiveLease())) {
            instance.monitoringPaused = true;
            instance.stopInputProxy();

            // ✅ 取消所有pending的检查任务
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                Log.d(TAG, "⏸️ Monitoring paused, all checks cancelled");
            } else {
                Log.d(TAG, "⏸️ Monitoring paused");
            }
        }
    }

    public static void resumeMonitoring() {
        if (instance != null && (instance.virtualSessionId != null || !SceneLegacyServiceLease.hasActiveLease())) {
            instance.monitoringPaused = false;
            instance.startInputProxy();
            Log.d(TAG, "▶️ Monitoring resumed");

            // ✅ 延迟5秒后才开始检查，给投送app足够时间恢复到前台
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                instance.handler.postDelayed(instance.checkTaskRunnable, 5000);
                Log.d(TAG, "⏰ Next check scheduled in 5 seconds");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        String activeSceneLease = SceneLegacyServiceLease.activeLeaseId();
        if (activeSceneLease != null) {
            sceneLeaseSuspended = true;
            sceneLeaseId = activeSceneLease;
        }

        // 创建通知渠道
        createNotificationChannel();

        // 创建Handler用于定时任务
        handler = new Handler(Looper.getMainLooper());

        // V2.2: 从SharedPreferences恢复传感器开关状态
        loadProximitySensorSetting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean controlledPhysicalStart = intent != null && intent.getBooleanExtra("controlledPhysicalOwner", false);
        if (controlledPhysicalStart) {
            String exactTask = intent.getStringExtra("lastMovedTask");
            int display = intent.getIntExtra("controlledPhysicalDisplayId", -1);
            String identity = intent.getStringExtra("controlledPhysicalIdentity");
            long ownerGeneration = intent.getLongExtra("physicalCastOwnerGeneration", 0L);
            boolean firstControlledStart = !startCommandProcessed;
            boolean claimed = exactTask != null && identity != null
                    && LegacyDisplayOccupancy.claimPhysicalKeeperStart(this, exactTask, display, identity,
                        ownerGeneration, () -> {
                            monitoredTaskInfo = exactTask;
                            physicalCastOwnerGeneration = ownerGeneration;
                        });
            if (!claimed) {
                // startForegroundService must be acknowledged promptly; an obsolete delayed start
                // is immediately retired without taking the task or generation into service state.
                if (firstControlledStart) {
                    startCommandProcessed = true;
                    latestServiceStartId = startId;
                    startForeground(NOTIFICATION_ID, buildNotification());
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);
                    stopSelf(startId);
                }
                return firstControlledStart ? START_NOT_STICKY : START_STICKY;
            }
        }
        latestServiceStartId = startId;
        startCommandProcessed = true;
        String requestedSession = intent == null ? null : intent.getStringExtra(VirtualCastUriFlow.EXTRA_SESSION);
        boolean startsProjection = intent != null && VirtualCastRequest.startsProjection(
                intent.getAction(), intent.getStringExtra("lastMovedTask"), requestedSession);
        String physicalTaskRequest = startsProjection && requestedSession == null
                ? intent.getStringExtra("lastMovedTask") : null;
        long requestedOwnerGeneration = physicalTaskRequest == null ? 0L
                : intent.getLongExtra("physicalCastOwnerGeneration", 0L);
        if (physicalTaskRequest != null && requestedOwnerGeneration <= 0L) {
            int taskId = taskIdFrom(physicalTaskRequest);
            if (taskId > 0)
                requestedOwnerGeneration = LegacyDisplayOccupancy.physicalProjectionGeneration(
                        this, taskId, RearDisplay.requireId());
        }
        physicalCastOwnerGeneration = PhysicalKeeperStopPolicy.ownerGenerationAfterServiceStart(
                physicalCastOwnerGeneration, physicalTaskRequest, requestedOwnerGeneration, startId);
        if (startsProjection) {
            // A new cast must not inherit an old animation's pause.
            monitoringPaused = false;
        }
        if (BuildConfig.VIRTUAL_CAST_ENABLED && (virtualSessionId != null || requestedSession != null)) {
            if ("ACTION_RETURN_TO_MAIN".equals(intent == null ? null : intent.getAction())) {
                // A cold stale PendingIntent must never enter legacy global launcher restoration.
                if (virtualSessionId == null) {
                    if (monitoredTaskInfo != null) return START_NOT_STICKY;
                    virtualSessionId = requestedSession;
                }
                if (virtualSessionId == null || requestedSession == null || requestedSession.equals(virtualSessionId)) stopSelf();
                return START_NOT_STICKY;
            }
            if (requestedSession != null) {
                virtualSessionId = requestedSession;
                monitoredTaskInfo = intent.getStringExtra("lastMovedTask");
                synchronized (VIRTUAL_SESSION_LOCK) { VIRTUAL_SESSION_LOCK.notifyAll(); }
            }
            keepScreenOnEnabled = false;
            stopRearScreenWakeup();
            startForeground(NOTIFICATION_ID, buildNotification());
            bindTaskService();
            if (sensorManager == null) initProximitySensor();
            handler.removeCallbacks(checkTaskRunnable);
            startTaskMonitoring();
            return START_NOT_STICKY;
        }

        // V14.6: 处理点击通知返回主屏的事件
        if (intent != null && "ACTION_RETURN_TO_MAIN".equals(intent.getAction())) {

            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity controlled =
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(this, RearDisplay.requireId());
            LegacyDisplayOccupancy.PhysicalReplacementJournal replacement =
                    LegacyDisplayOccupancy.physicalReplacementJournal(this);
            if (LegacyDisplayOccupancy.hasControlledPhysicalProjectionRecord(this) && controlled == null
                    && replacement == null) {
                Log.w(TAG, "controlled_return_indeterminate selected_rear_identity_unavailable");
                return START_NOT_STICKY;
            }
            boolean hasControlledTask = intent.hasExtra("controlledTask");
            boolean hasControlledGeneration = intent.hasExtra("controlledGeneration");
            if (controlled != null && !matchesControlledReturnRequest(hasControlledTask,
                    hasControlledGeneration, intent.getIntExtra("controlledTask", -1),
                    intent.getLongExtra("controlledGeneration", 0L), controlled.getTaskId(), controlled.getGeneration())) {
                Log.i(TAG, "controlled_return_ignored missing_or_stale_owner");
                return START_NOT_STICKY;
            }
            if (hasControlledTask || hasControlledGeneration) {
                long requestedGeneration = intent.getLongExtra("controlledGeneration", 0L);
                int requestedTask = intent.getIntExtra("controlledTask", -1);
                if (controlled == null && replacement != null
                        && requestedTask == replacement.getOldTask()
                        && requestedGeneration == replacement.getOldGeneration()
                        && replacement.getToken().equals(intent.getStringExtra("controlledReplacementToken"))) {
                    pendingReplacementRecoveryToken = replacement.getToken();
                    pendingReplacementRecoveryStartId = startId;
                    startForeground(NOTIFICATION_ID, buildNotification());
                    if (taskService != null) runControlledReplacementRecovery(replacement.getToken(), startId);
                    else bindTaskService();
                    return START_NOT_STICKY;
                }
                if (controlled == null || !matchesControlledReturnRequest(hasControlledTask,
                        hasControlledGeneration, requestedTask, requestedGeneration,
                        controlled.getTaskId(), controlled.getGeneration())) {
                    clearControlledPhysicalRecoveryNotification(this, requestedGeneration);
                    Log.i(TAG, "controlled_return_ignored stale_owner");
                    return START_NOT_STICKY;
                }
            }
            if (controlled != null) {
                pendingControlledReturn = controlled;
                pendingControlledReturnStartId = startId;
                startForeground(NOTIFICATION_ID, buildNotification());
                if (taskService != null) runControlledReturn(controlled, startId);
                else bindTaskService();
                return START_NOT_STICKY;
            }

            // 将监控的任务移回主屏
            if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":") && taskService != null) {
                try {
                    String[] parts = monitoredTaskInfo.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // 获取应用名
                    String appName = getAppName(packageName);

                    taskService.moveTaskToDisplay(taskId, 0);

                    // 先移除前台通知
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);

                    // 延迟显示Toast提示
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " 已返回主屏", Toast.LENGTH_SHORT).show();
                    }, 100);

                    // 停止服务
                    stopSelf();
                    return START_NOT_STICKY;

                } catch (Exception e) {
                    Log.w(TAG, "Failed to return task to main", e);
                }
            }
        }

        // V2.2: 处理接近传感器开关设置
        if (intent != null && "ACTION_SET_PROXIMITY_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            proximitySensorEnabled = enabled;

            Log.d(TAG, "🔧 传感器开关状态已更新: " + enabled);

            // 如果关闭了传感器，且当前正在监听，则注销监听
            if (!enabled && sensorManager != null && proximitySensor != null) {
                unregisterProximitySensor();
                Log.d(TAG, "⏸️ 传感器监听器已注销");
            }
            // 如果打开了传感器，且当前没有监听，则注册监听
            else if (enabled && sensorManager != null && proximitySensor != null) {
                if (hasExclusiveDisplayLease()) {
                    unregisterProximitySensor();
                } else {
                    boolean registered = sensorManager.registerListener(this, proximitySensor,
                            SensorManager.SENSOR_DELAY_NORMAL);
                    proximityListenerRegistered = registered;
                    if (registered) {
                        Log.d(TAG, "✅ 传感器监听器已注册");
                    } else {
                        Log.w(TAG, "⚠ 传感器监听器注册失败");
                    }
                }
            }

            return START_STICKY;
        }

        // V2.5: 处理背屏常亮开关设置
        if (intent != null && "ACTION_SET_KEEP_SCREEN_ON_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            keepScreenOnEnabled = enabled;

            Log.d(TAG, "🔆 背屏常亮开关已" + (enabled ? "开启" : "关闭"));

            // 如果关闭了常亮，停止发送WAKEUP
            if (!enabled && handler != null) {
                stopRearScreenWakeup();
                Log.d(TAG, "⏸️ 背屏WAKEUP发送已停止");
            }
            // 如果打开了常亮，启动发送WAKEUP
            else if (enabled && handler != null) {
                if (hasExclusiveDisplayLease()) {
                    stopRearScreenWakeup();
                } else {
                    stopRearScreenWakeup();
                    startRearScreenWakeup();
                }
            }

            return START_STICKY;
        }

        try {
            // V14.7: 先从Intent获取要监控的任务信息
            if (intent != null) {
                String newMonitoredTask = intent.getStringExtra("lastMovedTask");
                if (newMonitoredTask != null) {
                    monitoredTaskInfo = newMonitoredTask;
                    PhysicalStopRequest pending = physicalStopRequest;
                    if (pending != null && (!pending.task.equals(newMonitoredTask)
                            || pending.ownerGeneration != physicalCastOwnerGeneration)) physicalStopRequest = null;
                    startInputProxy();
                }
            }

            // V2.5: 从Intent获取背屏常亮开关状态
            if (intent != null) {
                keepScreenOnEnabled = intent.getBooleanExtra("keepScreenOnEnabled", true);
                Log.d(TAG, "🔆 背屏常亮开关状态: " + (keepScreenOnEnabled ? "开启" : "关闭"));
            }

            // V15.1: 立即显示通知，不等待其他操作
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);

            // 在后台线程执行耗时操作，不阻塞通知显示
            new Thread(() -> {
                // 绑定Shizuku TaskService
                bindTaskService();

                // 初始化接近传感器
                initProximitySensor();
            }).start();

            // 2. 获取WakeLock保持屏幕常亮
            if (wakeLock == null || !wakeLock.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

                // 使用SCREEN_BRIGHT_WAKE_LOCK保持屏幕亮起
                // 注意：这会让屏幕保持亮起，但可能无法指定是哪个display
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK, // 移除ACQUIRE_CAUSES_WAKEUP避免唤醒主屏
                        "ReMRSS::RearScreenKeeper");

                // 持续持有WakeLock（不设置超时）
                wakeLock.acquire();

            } else {
            }

            // 3. V12.2: 初始杀进程（只杀几次，不持续监控）
            performInitialKills();

            // 4. V14.5: 启动定期检查任务
            if (monitoredTaskInfo != null) {
                startTaskMonitoring();
            }

            // 5. V2.5: 启动持续唤醒背屏（每0.5秒，根据开关状态）
            startRearScreenWakeup();

        } catch (Exception e) {
            Log.e(TAG, "✗ Error starting service", e);
        }

        // START_STICKY: 如果被系统杀死，会自动重启
        return START_STICKY;
    }

    /**
     * V15.2: 启动任务监听 - 检测应用是否在前台
     * 监控被投放到背屏的应用，如果不在前台了（被关闭或切换），自动停止服务并清除通知
     */
    private final Runnable checkTaskRunnable = new Runnable() {
        @Override
        public void run() {
            if (virtualSessionId != null) {
                try {
                    if (taskService != null) {
                        org.json.JSONObject state = new org.json.JSONObject(taskService.getRearVirtualCastStatus());
                        if (!virtualSessionId.equals(state.optString("id")) || !"running".equals(state.optString("state"))) {
                            stopSelf(); return;
                        }
                    }
                } catch (Exception e) { stopSelf(); return; }
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                return;
            }
            if (hasExclusiveDisplayLease()) return;
            // V2.3: 如果监控已暂停（充电动画显示中），跳过本次检查
            if (monitoringPaused) {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                return;
            }

            if (monitoredTaskInfo != null && taskService != null) {
                try {
                    // V15.2: 检查当前背屏的前台应用是否还是我们监控的应用
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(RearDisplay.requireId());

                    // V2.3: 排除充电动画/通知动画（临时占用背屏，不应导致Service销毁）
                    if (rearForegroundApp != null && (rearForegroundApp.contains("RearScreenChargingActivity")
                            || rearForegroundApp.contains("RearScreenNotificationActivity"))) {
                        // 充电动画正在显示，跳过本次检查
                        handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                        return;
                    }

                    // 如果背屏前台应用不是我们监控的应用，说明它被关闭或切换了
                    if (rearForegroundApp == null || !rearForegroundApp.equals(monitoredTaskInfo)) {
                        // During an exact controlled replacement A remains the live input/keeper owner
                        // while candidate B is prepared. Do not let the ordinary foreground heuristic
                        // tear A down; the transaction itself must explicitly stop/transfer this keeper.
                        int taskId = taskIdFrom(monitoredTaskInfo);
                        if (taskId > 0 && physicalCastOwnerGeneration > 0L
                                && LegacyDisplayOccupancy.isPhysicalReplacementProtecting(
                                        RearScreenKeeperService.this, taskId, physicalCastOwnerGeneration)) {
                            handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                            return;
                        }
                        // 应用不在背屏前台了（被关闭或切换），停止服务
                        stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        stopSelf();
                        return;
                    }

                    // 继续监听
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);

                } catch (Exception e) {
                    Log.w(TAG, "Task check failed: " + e.getMessage());
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                }
            } else {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
            }
        }
    };

    private void startTaskMonitoring() {
        if (monitoredTaskInfo != null && handler != null
                && (virtualSessionId != null || !hasExclusiveDisplayLease())) {
            handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
        }
    }

    /**
     * V2.5: 持续唤醒背屏任务 - 每0.5秒发送WAKEUP，防止背屏自动熄屏
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            if (virtualSessionId != null) { wakeupLoopRunning = false; return; }
            if (!mayRunLegacyWakeup()) {
                wakeupLoopRunning = false;
                return;
            }
            if (RearInputProxySession.enabled(RearScreenKeeperService.this)) {
                wakeupLoopRunning = false;
                return;
            }
            // 检查开关状态
            if (keepScreenOnEnabled && taskService != null) {
                try {
                    // 向当前背屏发送WAKEUP唤醒信号
                    taskService.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
                    // Log.d(TAG, "✨ 背屏保活唤醒已发送"); // 注释掉以减少日志
                } catch (Exception e) {
                    Log.w(TAG, "背屏唤醒失败: " + e.getMessage());
                }
            }

            // 持续发送，每0.5秒执行一次
            if (keepScreenOnEnabled) {
                handler.postDelayed(this, WAKEUP_INTERVAL_MS);
            } else {
                wakeupLoopRunning = false;
            }
        }
    };

    private void startRearScreenWakeup() {
        if (virtualSessionId != null) return;
        if (!mayRunLegacyWakeup()) return;
        if (RearInputProxySession.enabled(this)) return;
        if (handler != null && keepScreenOnEnabled && !wakeupLoopRunning) {
            // 立即执行第一次唤醒，然后开始持续发送
            wakeupLoopRunning = true;
            handler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ 背屏持续唤醒已启动 (0.5秒间隔)");
        }
    }

    private void stopRearScreenWakeup() {
        if (handler != null) handler.removeCallbacks(wakeupRearScreenRunnable);
        wakeupLoopRunning = false;
    }

    /**
     * V12.3: 初始杀进程 - 只杀1次，不持续监控
     */
    private void performInitialKills() {

        for (int i = 0; i < INITIAL_KILL_COUNT; i++) {
            final int killNumber = i + 1;

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (taskService != null) {
                        try {
                            taskService.killLauncherProcess();
                        } catch (Exception e) {
                            Log.w(TAG, "⚠ Kill #" + killNumber + " failed: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "⚠ TaskService not available for kill #" + killNumber);
                    }

                    // 最后一次杀完后的总结
                    if (killNumber == INITIAL_KILL_COUNT) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                            }
                        }, 100);
                    }
                }
            }, i * KILL_INTERVAL_MS);
        }
    }

    /**
     * Shizuku TaskService连接回调
     */
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            startInputProxy();
            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity controlled = pendingControlledReturn;
            if (controlled != null) runControlledReturn(controlled, pendingControlledReturnStartId);
            String replacementToken = pendingReplacementRecoveryToken;
            if (replacementToken != null) runControlledReplacementRecovery(replacementToken, pendingReplacementRecoveryStartId);

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            stopInputProxy();
            taskService = null;
            if (virtualSessionId != null) stopSelf();
        }
    };

    /**
     * 绑定Shizuku TaskService
     */
    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }

    private void runControlledReturn(LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity owner, int returnStartId) {
        ITaskService remote = taskService;
        if (remote == null || owner == null) return;
        pendingControlledReturn = null;
        pendingControlledReturnStartId = 0;
        new Thread(() -> {
            ControlledPhysicalCastOperations.ReturnResult result = ControlledPhysicalCastOperations.stop(this, remote,
                    "physical:" + owner.getTaskId() + ":" + owner.getGeneration(), owner.getTaskId(),
                    owner.getPackageName(), owner.getBaseComponent(), owner.getDisplayId(), owner.getIdentity(),
                    owner.getGeneration());
            new Handler(Looper.getMainLooper()).post(() -> {
                if (result.stopped) {
                    // A newer Keeper start owns a larger startId; never stop it from this retry.
                    boolean stoppingThisInstance = returnStartId > 0 && stopSelfResult(returnStartId);
                    if (stoppingThisInstance) stopForeground(Service.STOP_FOREGROUND_REMOVE);
                    Toast.makeText(this, "投放已停止，应用已返回主屏", Toast.LENGTH_SHORT).show();
                } else {
                    Log.w(TAG, "controlled_return_indeterminate result=" + result.result);
                    notifyControlledPhysicalRecovery(this, owner.getTaskId(), owner.getGeneration());
                    Toast.makeText(this, "直接投放仍在恢复，请稍后再次点击返回", Toast.LENGTH_LONG).show();
                }
            });
        }, "controlled-physical-return").start();
    }

    private void runControlledReplacementRecovery(String expectedToken, int recoveryStartId) {
        ITaskService remote = taskService;
        if (remote == null || expectedToken == null) return;
        LegacyDisplayOccupancy.PhysicalReplacementJournal journal =
                LegacyDisplayOccupancy.physicalReplacementJournal(this);
        if (journal == null || !expectedToken.equals(journal.getToken())) return;
        pendingReplacementRecoveryToken = null;
        pendingReplacementRecoveryStartId = 0;
        new Thread(() -> {
            boolean recovered = ControlledPhysicalCastOperations.recoverPhysicalReplacement(this, remote);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (recovered) {
                    boolean stoppingThisInstance = recoveryStartId > 0 && stopSelfResult(recoveryStartId);
                    if (stoppingThisInstance) stopForeground(Service.STOP_FOREGROUND_REMOVE);
                } else {
                    Log.w(TAG, "controlled_replacement_recovery_indeterminate token=" + expectedToken);
                    notifyControlledPhysicalRecovery(this, journal.getOldTask(), journal.getOldGeneration());
                }
            });
        }, "controlled-replacement-recovery").start();
    }

    /**
     * 解绑TaskService
     */
    private void unbindTaskService() {
        TaskServiceManager.release(taskServiceConnection);
        taskService = null;
    }

    @Override
    public void onDestroy() {
        taskServiceReleased = true;
        stopInputProxy();
        super.onDestroy();
        if (virtualSessionId != null) {
            PhysicalStopRequest pendingStop = physicalStopRequest;
            if (pendingStop != null) {
                physicalStopRequest = null;
                pendingStop.destroyed.countDown();
            }
            String id = virtualSessionId;
            ITaskService remote = taskService;
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            handler.removeCallbacksAndMessages(null);
            unregisterProximitySensor();
            instance = null;
            synchronized (VIRTUAL_SESSION_LOCK) { VIRTUAL_SESSION_LOCK.notifyAll(); }
            new Thread(() -> {
                try { if (remote != null) remote.stopRearVirtualCast(id); }
                catch (Exception e) { Log.w(TAG, "Virtual cast stop", e); }
                finally {
                    LegacyDisplayOccupancy.PhysicalReplacementJournal replacement =
                            LegacyDisplayOccupancy.physicalReplacementJournal(this);
                    if (remote != null && replacement != null && replacement.getNewVirtual()
                            && id.equals(replacement.getNewSessionId())) {
                        if (!ControlledPhysicalCastOperations.recoverPhysicalReplacement(this, remote))
                            notifyControlledPhysicalRecovery(this, replacement.getOldTask(), replacement.getOldGeneration());
                    }
                    unbindTaskService();
                }
            }, "virtual-cast-stop").start();
            return;
        }

        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "⚠ Service onDestroy called");

        // 立即移除前台通知
        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        // 清理所有待执行的任务
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        PhysicalStopRequest returnRequest = physicalStopRequest;
        boolean explicitPhysicalReturn = returnRequest != null
                && returnRequest.task.equals(monitoredTaskInfo)
                && returnRequest.ownerGeneration == physicalCastOwnerGeneration;
        boolean launcherRestored = false;
        // An explicit return has already made (or deliberately declined) the owner-gated decision.
        // Never issue a second unguarded global restore while this same Keeper is destroyed.
        if (taskService != null && !explicitPhysicalReturn && mayRestoreLauncherOnNaturalExit()) {
            try {
                launcherRestored = taskService.restoreSubScreenLauncherOnly();
                Thread.sleep(300);
            } catch (Exception e) {
                Log.w(TAG, "Failed to restore launcher", e);
            }
        } else if (explicitPhysicalReturn) {
            Log.i(TAG, "explicit physical return owns launcher decision; skip teardown restore");
        } else {
            Log.i(TAG, "current rear owner prevents natural Keeper launcher restore");
        }

        // 解绑TaskService
        unbindTaskService();

        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 注销接近传感器
        unregisterProximitySensor();

        if (!explicitPhysicalReturn && launcherRestored && physicalCastOwnerGeneration > 0L) {
            LegacyDisplayOccupancy.clearPhysicalProjection(this, taskIdFrom(monitoredTaskInfo),
                    RearDisplay.requireId(), physicalCastOwnerGeneration);
        }
        instance = null;
        if (explicitPhysicalReturn) {
            returnRequest.stopped.set(true);
            returnRequest.destroyed.countDown();
            if (physicalStopRequest == returnRequest) physicalStopRequest = null;
        }
        synchronized (VIRTUAL_SESSION_LOCK) { VIRTUAL_SESSION_LOCK.notifyAll(); }
        Log.w(TAG, "═══════════════════════════════════════");
    }

    private boolean mayRestoreLauncherOnNaturalExit() {
        if (virtualSessionId != null || hasExclusiveDisplayLease() || taskService == null) return false;
        // An indeterminate controlled return keeps its exact recovery lease; teardown is not a return.
        if (LegacyDisplayOccupancy.hasControlledPhysicalProjectionRecord(this))
            return false;
        try {
            org.json.JSONObject state = new org.json.JSONObject(taskService.getRearVirtualCastStatus());
            String castState = state.optString("state", "unknown");
            boolean virtualActive = "running".equals(castState) || "starting".equals(castState);
            RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(
                    taskService, getPackageName(), RearDisplay.requireId());
            return PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(false, false, virtualActive,
                    occupancy.disposition);
        } catch (Exception unknownOwner) {
            return false;
        }
    }

    private static int taskIdFrom(String packageTask) {
        if (packageTask == null) return -1;
        int separator = packageTask.lastIndexOf(':');
        if (separator < 0) return -1;
        try { return Integer.parseInt(packageTask.substring(separator + 1)); }
        catch (NumberFormatException invalid) { return -1; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不支持绑定
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+必需）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW // 低重要性，减少干扰
            );
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            channel.setShowBadge(false); // 不显示角标
            channel.enableLights(false); // 不闪烁LED
            channel.enableVibration(false); // 不振动

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);

        }
    }

    /**
     * 获取应用名称
     */
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get app name: " + e.getMessage());
        }
        return packageName; // 失败时返回包名
    }

    /**
     * V2.4: 创建通用的Service前台通知（供多个Service共用）
     */
    public static Notification createServiceNotification(Context context) {
        // 创建通知渠道
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notif_kernel_service))
                .setContentText(context.getString(R.string.notif_mrss_running))
                .setSmallIcon(R.drawable.ic_stat_mrss)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 构建前台通知
     */
    private Notification buildNotification() {
        // 获取应用名称
        String appName = "应用";

        if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            String packageName = monitoredTaskInfo.split(":")[0];
            appName = getAppName(packageName);
        } else {
            Log.w(TAG, "⚠ Invalid monitored task info: " + monitoredTaskInfo);
        }

        // 点击通知切换回主屏
        Intent returnIntent = new Intent(this, RearScreenKeeperService.class);
        returnIntent.setAction("ACTION_RETURN_TO_MAIN");
        LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity controlled =
                LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(this, RearDisplay.requireId());
        int requestCode = 0;
        if (controlled != null) {
            returnIntent.putExtra("controlledTask", controlled.getTaskId());
            returnIntent.putExtra("controlledGeneration", controlled.getGeneration());
            requestCode = (int) (controlled.getGeneration() & 0x7fffffff);
        }
        if (virtualSessionId != null) returnIntent.putExtra(VirtualCastUriFlow.EXTRA_SESSION, virtualSessionId);
        PendingIntent pendingIntent = PendingIntent.getService(
                this, requestCode, returnIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " " + getString(R.string.notif_running_on_rear))
                .setContentText(getString(R.string.notif_click_to_return, appName))
                .setSmallIcon(R.drawable.ic_stat_mrss)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级
                .setOngoing(true) // 持续通知，不可滑动清除
                .setShowWhen(false) // 不显示时间
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 检查Service是否正在运行
     */
    public static boolean isRunning() {
        return instance != null;
    }

    /** Pause only the existing Keeper instance; a Scene never starts this legacy service. */
    public static void suspendForSceneLease(String leaseId) {
        if (!SceneLegacyServiceLease.isCurrent(leaseId)) return;
        RearScreenKeeperService service = instance;
        if (service == null || service.virtualSessionId != null || service.handler == null) return;
        if (!service.sceneLeaseSuspended) {
            service.sceneLeaseHadMonitoringPaused = service.monitoringPaused;
            service.sceneLeaseHadWakeLock = service.wakeLock != null && service.wakeLock.isHeld();
        }
        service.sceneLeaseSuspended = true;
        service.sceneLeaseId = leaseId;
        // Synchronously prevent an already-queued 100ms callback from issuing another wake.
        service.stopRearScreenWakeup();
        service.handler.removeCallbacks(service.checkTaskRunnable);
        service.handler.post(() -> {
            if (instance != service || !SceneLegacyServiceLease.isCurrent(leaseId)
                    || service.virtualSessionId != null) return;
            service.stopInputProxy();
            service.unregisterProximitySensor();
            if (service.wakeLock != null && service.wakeLock.isHeld()) service.wakeLock.release();
        });
    }

    /** Restore the same service and prior user-selected mode only after the exact Scene lease ends. */
    public static void resumeAfterSceneLease(String leaseId, boolean restorePriorMode) {
        RearScreenKeeperService service = instance;
        if (service == null || service.handler == null || !leaseId.equals(service.sceneLeaseId)) return;
        service.handler.post(() -> {
            if (instance != service || !leaseId.equals(service.sceneLeaseId)
                    || SceneLegacyServiceLease.hasActiveLease()) return;
            if (!restorePriorMode || service.virtualSessionId != null || service.taskServiceReleased) {
                service.clearSceneLeaseSnapshot();
                return;
            }
            final String taskInfo = service.monitoredTaskInfo;
            final ITaskService remote = service.taskService;
            new Thread(() -> {
                boolean taskStillOwned = taskInfo == null;
                if (taskInfo != null && remote != null) {
                    try {
                        int taskId = Integer.parseInt(taskInfo.substring(taskInfo.lastIndexOf(':') + 1));
                        taskStillOwned = taskInfo.equals(remote.getForegroundAppOnDisplay(RearDisplay.requireId()))
                                && remote.isTaskOnDisplay(taskId, RearDisplay.requireId());
                    } catch (Exception ignored) { taskStillOwned = false; }
                }
                final boolean stillOwned = taskStillOwned;
                service.handler.post(() -> {
                    if (instance != service || !leaseId.equals(service.sceneLeaseId)
                            || SceneLegacyServiceLease.hasActiveLease()
                            || service.virtualSessionId != null || service.taskServiceReleased) return;
                    if (taskInfo != null && (service.taskService != remote || !stillOwned
                            || !taskInfo.equals(service.monitoredTaskInfo))) {
                        service.clearSceneLeaseSnapshot();
                        service.stopSelf();
                        return;
                    }
                    service.monitoringPaused = service.sceneLeaseHadMonitoringPaused;
                    service.sceneLeaseSuspended = false;
                    service.sceneLeaseId = null;
                    if (service.sceneLeaseHadWakeLock && service.keepScreenOnEnabled
                            && (service.wakeLock == null || !service.wakeLock.isHeld())) {
                        try {
                            PowerManager pm = (PowerManager) service.getSystemService(POWER_SERVICE);
                            service.wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ReMRSS::RearScreenKeeper");
                            service.wakeLock.acquire();
                        } catch (Exception ignored) { }
                    }
                    if (service.proximitySensorEnabled && service.sensorManager != null && service.proximitySensor != null) {
                        service.proximityListenerRegistered = service.sensorManager.registerListener(
                                service, service.proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                        if (service.proximityListenerRegistered && (SceneLegacyServiceLease.hasActiveLease() || service.taskServiceReleased)) {
                            service.unregisterProximitySensor();
                        }
                    }
                    if (!service.monitoringPaused && service.monitoredTaskInfo != null) service.startTaskMonitoring();
                    if (service.monitoredTaskInfo != null) service.startInputProxy();
                    if (service.keepScreenOnEnabled) service.startRearScreenWakeup();
                    service.clearSceneLeaseSnapshot();
                });
            }, "keeper-scene-lease-restore").start();
        });
    }

    private boolean sceneLeaseHadWakeLock;

    private void clearSceneLeaseSnapshot() {
        sceneLeaseSuspended = false;
        sceneLeaseId = null;
        sceneLeaseHadMonitoringPaused = false;
        sceneLeaseHadWakeLock = false;
        sceneLeaseResumeAttempts = 0;
    }

    /**
     * 停止Service
     */
    public static void stop() {
        if (instance != null) {
            instance.stopSelf();
        }
    }

    // ========================================
    // 接近传感器相关方法
    // ========================================

    /**
     * 从SharedPreferences加载传感器开关状态
     */
    private void loadProximitySensorSetting() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            proximitySensorEnabled = prefs.getBoolean("flutter.proximity_sensor_enabled", true);
            Log.d(TAG, "🔧 传感器开关状态已恢复: " + proximitySensorEnabled);
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载传感器设置失败", e);
            proximitySensorEnabled = true; // 默认启用
        }
    }

    /**
     * 初始化接近传感器（背屏接近传感器）
     */
    private void initProximitySensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            if (sensorManager != null) {
                // 获取所有传感器列表
                List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);

                // 查找背屏接近传感器（名称包含 "Back" 和 "Proximity"）
                // 优先选择 Wakeup 版本，如果没有则选择 Non-wakeup 版本
                Sensor wakeupSensor = null;
                Sensor nonWakeupSensor = null;

                for (Sensor sensor : allSensors) {
                    String name = sensor.getName();
                    if (name.contains("Proximity") && name.contains("Back")) {
                        if (name.contains("Wakeup")) {
                            wakeupSensor = sensor;
                        } else {
                            nonWakeupSensor = sensor;
                        }
                    }
                }

                // 优先使用 Wakeup 版本
                if (wakeupSensor != null) {
                    proximitySensor = wakeupSensor;
                } else if (nonWakeupSensor != null) {
                    proximitySensor = nonWakeupSensor;
                    Log.w(TAG, "→ Using NON-WAKEUP sensor (may not provide continuous data)");
                }

                // 如果找不到背屏传感器，回退到默认传感器
                if (proximitySensor == null) {
                    Log.w(TAG, "⚠ Rear proximity sensor not found, using default");
                    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                }

                if (proximitySensor != null) {
                    // V2.2: 只有在传感器开关启用时才注册监听器
                    if (proximitySensorEnabled && !taskServiceReleased && !hasExclusiveDisplayLease()) {
                        boolean registered = sensorManager.registerListener(
                                this,
                                proximitySensor,
                                SensorManager.SENSOR_DELAY_NORMAL);
                        proximityListenerRegistered = registered;

                        // Sensor discovery can run off the main thread while a Scene lease
                        // starts. Recheck after registration so that race cannot leave a
                        // legacy back sensor client active behind the Scene.
                        if (registered && (taskServiceReleased || hasExclusiveDisplayLease())) {
                            unregisterProximitySensor();
                        }

                        if (registered && proximityListenerRegistered) {
                            Log.d(TAG, "✅ 接近传感器已注册 (开关状态: " + proximitySensorEnabled + ")");
                        } else if (!registered) {
                            Log.w(TAG, "⚠ Failed to register proximity sensor");
                        }
                    } else {
                        proximityListenerRegistered = false;
                        Log.d(TAG, "⏸️ 接近传感器已禁用，跳过注册");
                    }
                } else {
                    Log.w(TAG, "⚠ No proximity sensor available");
                }
            } else {
                Log.w(TAG, "⚠ SensorManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing proximity sensor", e);
        }
    }

    /**
     * 注销接近传感器
     */
    private void unregisterProximitySensor() {
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error unregistering proximity sensor", e);
        } finally {
            proximityListenerRegistered = false;
        }
    }

    /**
     * 传感器数据变化回调
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (virtualSessionId == null && hasExclusiveDisplayLease()) return;
        // V2.2: 如果传感器已关闭，不处理事件
        if (!proximitySensorEnabled) {
            return;
        }

        // 检查是否是我们的背屏接近传感器
        if (event.sensor == proximitySensor) {
            float distance = event.values[0];
            float maxRange = proximitySensor.getMaximumRange();

            // 详细日志 - 每次传感器变化都记录

            // 当距离接近0（被覆盖）时触发
            boolean isCovered = (distance < maxRange * 0.2f); // 小于最大距离的20%视为覆盖

            long currentTime = System.currentTimeMillis();

            if (isCovered && !isProximityCovered) {
                // 从未覆盖到覆盖
                isProximityCovered = true;
                lastProximityTime = currentTime;
                final int triggerStartId = latestServiceStartId;
                final boolean triggerHadControlledOwner =
                        LegacyDisplayOccupancy.hasControlledPhysicalProjectionRecord(this);
                final LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity triggerOwner =
                        triggerHadControlledOwner
                                ? LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(this, RearDisplay.requireId())
                                : null;

                Log.w(TAG, "👋 PROXIMITY COVERED! Distance: " + distance + " cm");
                Log.w(TAG, "👋 Starting debounce timer (" + PROXIMITY_DEBOUNCE_MS + "ms)...");

                // 防抖动：延迟检查
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isProximityCovered &&
                                (System.currentTimeMillis() - lastProximityTime >= PROXIMITY_DEBOUNCE_MS)) {
                            boolean currentHasControlledOwner =
                                    LegacyDisplayOccupancy.hasControlledPhysicalProjectionRecord(RearScreenKeeperService.this);
                            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity currentOwner = currentHasControlledOwner
                                    ? LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(
                                            RearScreenKeeperService.this, RearDisplay.requireId()) : null;
                            if (!delayedProximityOwnerStillCurrent(triggerStartId, latestServiceStartId,
                                    triggerHadControlledOwner, triggerOwner == null ? -1 : triggerOwner.getTaskId(),
                                    triggerOwner == null ? 0L : triggerOwner.getGeneration(), currentHasControlledOwner,
                                    currentOwner == null ? -1 : currentOwner.getTaskId(),
                                    currentOwner == null ? 0L : currentOwner.getGeneration())) {
                                Log.i(TAG, "proximity_return_ignored stale_trigger_owner");
                                return;
                            }
                            // 确认覆盖超过500ms，触发拉回主屏
                            Log.w(TAG, "👋 Debounce timer expired - triggering return to main display!");
                            handleProximityCovered(triggerOwner, triggerHadControlledOwner);
                        } else {
                        }
                    }
                }, PROXIMITY_DEBOUNCE_MS);

            } else if (!isCovered && isProximityCovered) {
                // 从覆盖到未覆盖
                isProximityCovered = false;
            } else if (isCovered && isProximityCovered) {
                // 持续覆盖中
            } else {
                // 持续未覆盖
            }
        } else {
            // 其他传感器的数据，也记录一下
        }
    }

    /**
     * 传感器精度变化回调（不需要处理）
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理
    }

    /**
     * 处理接近传感器覆盖事件 - 拉回主屏并停止Service
     */
    private void handleProximityCovered(LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity triggerOwner,
                                        boolean triggerHadControlledOwner) {
        if (virtualSessionId != null) { stopSelf(); return; }
        if (hasExclusiveDisplayLease()) return;
        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "🤚 PROXIMITY TRIGGER - Return to main display");
        Log.w(TAG, "═══════════════════════════════════════");

        try {
            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity controlled =
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(this, RearDisplay.requireId());
            if (LegacyDisplayOccupancy.hasControlledPhysicalProjectionRecord(this)) {
                if (!triggerHadControlledOwner || triggerOwner == null || controlled == null
                        || controlled.getTaskId() != triggerOwner.getTaskId()
                        || controlled.getGeneration() != triggerOwner.getGeneration()
                        || !controlled.getIdentity().equals(triggerOwner.getIdentity())) return;
                pendingControlledReturn = controlled;
                if (taskService == null) bindTaskService();
                else runControlledReturn(controlled, 0);
                return; // Controlled owners never fall through to the legacy root move.
            }
            if (triggerHadControlledOwner) return;
            if (taskService != null) {
                // URI and Tile starts both update this keeper's actual projection owner.
                String lastTask = monitoredTaskInfo;

                if (lastTask != null && lastTask.contains(":")) {
                    String[] parts = lastTask.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // 获取应用名
                    String appName = getAppName(packageName);

                    // 拉回主屏
                    boolean success = taskService.isTaskOnDisplay(taskId, RearDisplay.requireId())
                            && taskService.moveTaskToDisplay(taskId, 0);

                    if (success) {
                        // 延迟显示Toast
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(RearScreenKeeperService.this, appName + " 已返回主屏", Toast.LENGTH_SHORT).show();
                        }, 100);
                    } else {
                        Log.w(TAG, "⚠ Failed to return task (may already be on main display)");
                    }
                } else {
                    Log.w(TAG, "⚠ No active rear screen task found");
                }

                // 先移除前台通知
                stopForeground(Service.STOP_FOREGROUND_REMOVE);

                // 停止Service（会自动恢复系统Launcher）
                stopSelf();

            } else {
                Log.w(TAG, "⚠ TaskService not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error handling proximity event", e);
        }
    }
}
