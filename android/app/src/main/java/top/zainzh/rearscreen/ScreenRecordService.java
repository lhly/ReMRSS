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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 背屏录屏服务
 * 功能：
 * 1. 显示悬浮窗（录制/停止按钮+关闭按钮）
 * 2. 录制背屏画面（screenrecord --display-id 1）
 * 3. 前台Service保活
 */
public class ScreenRecordService extends Service {
    private static final String TAG = "ScreenRecordService";
    private static final String CHANNEL_ID = "rear_screen_keeper"; // 使用ReMRSS内核服务通道
    private static final int NOTIFICATION_ID = 10004; // 避免与KeeperService冲突
    
    private static volatile ScreenRecordService instance = null;
    private WindowManager windowManager;
    private View floatingView;
    private View recordButton;
    private ImageView closeButton;
    private TextView statusLabel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile RecordingPhase phase = RecordingPhase.IDLE;
    private boolean closeAfterSave;
    private volatile boolean isRecording;
    // Finish stop/cleanup before releasing the privileged connection, even if the overlay is gone.
    private final ExecutorService recorder = Executors.newSingleThreadExecutor();
    private String currentVideoPath;
    private int recordPid = -1; // 录屏进程ID
    private Handler wakeupHandler = new Handler(android.os.Looper.getMainLooper());
    private static final long WAKEUP_INTERVAL_MS = 100; // 每100ms唤醒一次背屏
    
    // TaskService
    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService;
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            Log.d(TAG, "✓ TaskService connected");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };
    
    public static boolean isRunning() {
        return instance != null;
    }
    
    static RecordingPhase currentPhase() {
        return instance == null ? null : instance.phase;
    }

    static boolean closeFromTile() {
        ScreenRecordService service = instance;
        if (service == null) return false;
        if (service.phase.canStart()) service.stopSelf();
        else {
            service.closeAfterSave = true;
            if (service.phase.canStop()) service.requestStop();
        }
        return true;
    }

    static int phaseLabel(RecordingPhase phase) {
        switch (phase) {
            case PREPARING: return R.string.ui_recording_preparing;
            case RECORDING: return R.string.ui_recording_running;
            case SAVING: return R.string.ui_recording_saving;
            case SAVED: return R.string.ui_recording_saved;
            case START_FAILED: return R.string.ui_recording_start_failed;
            case STOP_FAILED: return R.string.ui_recording_stop_failed;
            case SAVE_FAILED: return R.string.ui_recording_save_failed;
            default: return R.string.ui_recording_idle;
        }
    }

    private void updatePhase(RecordingPhase value) {
        if (taskServiceReleased) return;
        phase = value;
        renderControls();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        refreshTile();
        if (closeAfterSave && value.canStart()) stopSelf();
        else if (closeAfterSave && value == RecordingPhase.RECORDING) requestStop();
        else if (value == RecordingPhase.STOP_FAILED) closeAfterSave = false;
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, RearScreenRecordTileService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "📹 ScreenRecordService onCreate");
        
        // 启动前台通知
        RearScreenKeeperService.createServiceNotification(this); // Ensure the shared channel exists on a fresh install.
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.d(TAG, "✓ 前台Service已启动");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (floatingView != null) return START_STICKY;
        // Permission can be revoked between the tile click and service creation, or before a restart.
        if (Settings.canDrawOverlays(this)) {
            try {
                showFloatingWindow();
                if (floatingView != null) {
                    bindTaskService();
                    refreshTile();
                    return START_STICKY;
                }
            } catch (RuntimeException error) {
                floatingView = null;
                Log.w(TAG, "Recording controls unavailable", error);
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
        return START_NOT_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }
    
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );
        
        // 统一使用ReMRSS内核服务的通知样式
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tile_record_label))
            .setContentText(getString(phaseLabel(phase)))
            .setSmallIcon(R.drawable.ic_stat_mrss)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    /**
     * 显示悬浮窗
     */
    private void showFloatingWindow() {
        Log.d(TAG, "→ 准备显示悬浮窗");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "❌ 无法获取WindowManager");
            return;
        }
        Log.d(TAG, "✓ WindowManager已获取");
        
        // 创建悬浮窗布局
        Log.d(TAG, "→ 创建悬浮窗视图");
        floatingView = createFloatingView();
        if (floatingView == null) {
            Log.e(TAG, "❌ 创建视图失败");
            return;
        }
        Log.d(TAG, "✓ 视图已创建");
        
        // 设置悬浮窗参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(16);
        params.y = dp(80);
        
        Log.d(TAG, "→ 参数设置完成，准备添加视图");
        Log.d(TAG, "  TYPE: " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "TYPE_APPLICATION_OVERLAY" : "TYPE_PHONE"));
        
        try {
            windowManager.addView(floatingView, params);
            Log.d(TAG, "✅ 悬浮窗已成功添加到WindowManager");
        } catch (Exception e) {
            Log.e(TAG, "❌ 添加悬浮窗失败", e);
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 创建悬浮窗视图
     */
    private View createFloatingView() {
        Log.d(TAG, "→ 开始创建悬浮窗布局");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.setGravity(android.view.Gravity.CENTER); // 上下左右居中
        
        Log.d(TAG, "✓ LinearLayout已创建");
        
        // 关闭按钮（×）- 先声明
        closeButton = new ImageView(this);
        closeButton.setImageResource(R.drawable.ic_recording_close);
        closeButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        closeButton.setTag("recording_close");
        buttonSemantics(closeButton);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            dp(48), dp(48)
        );
        closeParams.gravity = android.view.Gravity.CENTER; // 上下居中
        closeParams.leftMargin = dp(8);
        closeButton.setLayoutParams(closeParams);
        
        closeButton.setOnClickListener(v -> { if (phase.canStart()) stopSelf(); });

        recordButton = new View(this);
        recordButton.setTag("recording_toggle");
        buttonSemantics(recordButton);
        recordButton.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        recordButton.setOnClickListener(v -> {
            if (phase.canStart()) {
                updatePhase(RecordingPhase.PREPARING);
                recorder.execute(this::startRecording);
            } else if (phase.canStop()) requestStop();
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(recordButton);
        controls.addView(closeButton);
        layout.addView(controls);
        statusLabel = new TextView(this);
        statusLabel.setTag("recording_status");
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setTextSize(12);
        statusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(statusLabel, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT));
        styleFloatingWindow(layout);
        renderControls();

        Log.d(TAG, "✓ 按钮已添加到布局");
        
        // 拖动功能
        final WindowManager.LayoutParams[] params = new WindowManager.LayoutParams[1];
        layout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params[0] == null) {
                    params[0] = (WindowManager.LayoutParams) floatingView.getLayoutParams();
                }
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params[0].x;
                        initialY = params[0].y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        params[0].x = initialX + (int) (initialTouchX - event.getRawX());
                        params[0].y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params[0]);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
        
        Log.d(TAG, "✓ 悬浮窗布局创建完成");
        return layout;
    }
    
    private void renderControls() {
        if (recordButton == null) return;
        boolean stop = phase.canStop();
        boolean pending = phase.isBusy();
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(stop ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
        if (stop) shape.setCornerRadius(dp(4));
        if (pending) {
            shape.setColor(android.graphics.Color.TRANSPARENT);
            shape.setStroke(dp(2), getColor(R.color.remrss_overlay_foreground));
        } else shape.setColor(getColor(R.color.remrss_recording_red));
        LayerDrawable content = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                controlBackground(), new InsetDrawable(shape, dp(stop || pending ? 14 : 8))});
        recordButton.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.remrss_overlay_ripple)),
                content, controlBackground()));
        recordButton.setActivated(stop);
        recordButton.setEnabled(!pending);
        recordButton.setContentDescription(getString(pending ? phaseLabel(phase)
                : stop ? R.string.ui_recording_stop : R.string.ui_recording_start));
        closeButton.setVisibility(phase.canStart() ? View.VISIBLE : View.INVISIBLE);
        statusLabel.setTextColor(getColor(R.color.remrss_overlay_foreground));
        statusLabel.setText(phaseLabel(phase));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable controlBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(getColor(R.color.remrss_overlay_control));
        return shape;
    }

    private void styleFloatingWindow(View panel) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.remrss_overlay_surface));
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), getColor(R.color.remrss_overlay_outline));
        panel.setBackground(background);
        panel.setElevation(dp(6));
        closeButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.remrss_overlay_foreground)));
        closeButton.setContentDescription(getString(R.string.ui_recording_close));
        closeButton.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.remrss_overlay_ripple)),
                controlBackground(), controlBackground()));
    }

    private void buttonSemantics(View button) {
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (floatingView != null && closeButton != null && recordButton != null) {
            styleFloatingWindow(floatingView);
            renderControls();
        }
    }
    
    /**
     * 确保TaskService连接
     */
    private boolean ensureTaskServiceConnected() {
        if (taskService != null) {
            Log.d(TAG, "✓ TaskService已连接");
            return true;
        }
        
        Log.w(TAG, "⚠ TaskService未连接，尝试重新绑定...");
        
        // 尝试绑定
        bindTaskService();
        
        // 等待连接（最多3秒）
        int attempts = 0;
        while (taskService == null && !taskServiceReleased && attempts < 30) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            attempts++;
        }
        
        if (taskService != null) {
            Log.d(TAG, "✅ TaskService重连成功");
            return true;
        } else {
            Log.e(TAG, "❌ TaskService重连失败（超时3秒）");
            return false;
        }
    }
    
    /**
     * 持续唤醒背屏任务 - 录制期间防止背屏熄屏
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskServiceReleased) return;
            if (isRecording && taskService != null) {
                try {
                    // 向当前背屏发送WAKEUP唤醒信号
                    taskService.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
                    // 不输出日志以减少刷屏
                } catch (Exception e) {
                    Log.w(TAG, "背屏唤醒失败: " + e.getMessage());
                }
            }
            
            // 持续发送，每100ms执行一次
            if (isRecording) {
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        }
    };
    
    /**
     * 启动背屏持续唤醒
     */
    private void startRearScreenWakeup() {
        if (wakeupHandler != null) {
            // 立即执行第一次唤醒，然后开始持续发送
            wakeupHandler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ 背屏持续唤醒已启动 (100ms间隔)");
        }
    }
    
    /**
     * 停止背屏持续唤醒
     */
    private void stopRearScreenWakeup() {
        if (wakeupHandler != null) {
            wakeupHandler.removeCallbacks(wakeupRearScreenRunnable);
            Log.d(TAG, "⏸️ 背屏持续唤醒已停止");
        }
    }
    
    private void startRecording() {
        if (taskServiceReleased) return;
        String pidFile = null, logFile = null;
        ITaskService remote = null;
        try {
            if (!ensureTaskServiceConnected() || taskServiceReleased) {
                completePhase(RecordingPhase.START_FAILED);
                return;
            }
            remote = taskService;
            if (remote == null) throw new IllegalStateException("Control service disconnected");
            try {
                remote.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
                Thread.sleep(200);
            } catch (Exception error) {
                Log.w(TAG, "Unable to wake rear display before recording", error);
            }
            if (taskServiceReleased) return;
            String displayId = remote.getRearPhysicalDisplayId();
            if (displayId == null || !displayId.matches("[0-9]+"))
                throw new IllegalStateException("Rear physical display unavailable");
            String executable = remote.executeShellCommandWithResult("command -v screenrecord").trim();
            if (!executable.matches("/[^\\s]+/screenrecord"))
                throw new IllegalStateException("Screen recording not available");
            String token = java.util.UUID.randomUUID().toString();
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
            currentVideoPath = "/storage/emulated/0/Movies/ReMRSS_" + timestamp + "_" + token.substring(0, 8) + ".mp4";
            String scratch = "/data/local/tmp/" + getPackageName() + "-record-" + token;
            pidFile = scratch + ".pid";
            logFile = scratch + ".log";
            if (!remote.executeShellCommand("mkdir -p /storage/emulated/0/Movies || exit 1; "
                    + PrivilegedShellArguments.quote(executable) + " --display-id " + displayId
                    + " --bit-rate 20000000 " + PrivilegedShellArguments.quote(currentVideoPath)
                    + " > " + PrivilegedShellArguments.quote(logFile) + " 2>&1 & echo $! > "
                    + PrivilegedShellArguments.quote(pidFile)))
                throw new IllegalStateException("Could not launch the recorder");

            // Read the PID for this recording, not other screenrecord processes on the device.
            String pid = remote.executeShellCommandWithResult("cat " + PrivilegedShellArguments.quote(pidFile)).trim();
            recordPid = Integer.parseInt(pid);
            Thread.sleep(800);
            if (!recordingAlive(remote)) {
                String startupLog = remote.executeShellCommandWithResult("cat " + PrivilegedShellArguments.quote(logFile));
                Log.w(TAG, "Recorder exited during startup: " + startupLog);
                completePhase(completeVideo(remote, RecordingPhase.START_FAILED));
                return;
            }
            isRecording = true;
            main.post(() -> {
                if (taskServiceReleased) return;
                updatePhase(RecordingPhase.RECORDING);
                startRearScreenWakeup();
                scheduleProcessCheck();
            });
        } catch (Exception error) {
            Log.w(TAG, "Could not start rear recording", error);
            // If the launch got as far as a known PID, keep a stop/retry path on ambiguous readback.
            completePhase(recordPid > 0 ? RecordingPhase.STOP_FAILED : RecordingPhase.START_FAILED);
        } finally {
            if (remote != null && pidFile != null) {
                try {
                    remote.executeShellCommand("rm -f " + PrivilegedShellArguments.quote(pidFile)
                            + " " + PrivilegedShellArguments.quote(logFile));
                } catch (Exception error) { Log.w(TAG, "Could not remove recorder temp files", error); }
            }
        }
    }

    private boolean recordingAlive(ITaskService remote) throws Exception {
        String result = remote.executeShellCommandWithResult(PrivilegedShellArguments.recordingStatus(recordPid, currentVideoPath));
        if ("active".equals(result.trim())) return true;
        if ("stopped".equals(result.trim())) return false;
        throw new IllegalStateException("Recording process status unavailable");
    }

    private void requestStop() {
        updatePhase(RecordingPhase.SAVING);
        main.removeCallbacks(checkProcess);
        recorder.execute(this::finishRecording);
    }

    /** A screenrecord process may end by itself (including the platform time limit). */
    private final Runnable checkProcess = () -> {
        if (taskServiceReleased || phase != RecordingPhase.RECORDING) return;
        recorder.execute(() -> {
            try {
                ITaskService remote = taskService;
                if (recordPid > 0 && remote != null && !recordingAlive(remote)) {
                    completePhase(completeVideo(remote, RecordingPhase.SAVE_FAILED));
                    return;
                }
            } catch (Exception error) {
                Log.w(TAG, "Could not confirm recording status", error);
            }
            main.post(this::scheduleProcessCheck);
        });
    };

    private void scheduleProcessCheck() {
        if (!taskServiceReleased && phase == RecordingPhase.RECORDING) main.postDelayed(checkProcess, 2000);
    }

    private void finishRecording() {
        if (recordPid <= 0) return;  // Already finalized by the process check.
        try {
            if (!ensureTaskServiceConnected()) throw new IllegalStateException("Control service disconnected");
            ITaskService remote = taskService;
            if (remote == null) throw new IllegalStateException("Control service disconnected");
            if (recordingAlive(remote)) {
                boolean stopped = remote.executeShellCommand(
                        PrivilegedShellArguments.stopRecording(recordPid, currentVideoPath, 2));
                if (!stopped) remote.executeShellCommand(
                        PrivilegedShellArguments.stopRecording(recordPid, currentVideoPath, 15));
                for (int i = 0; i < 12; i++) {
                    Thread.sleep(250);
                    if (!recordingAlive(remote)) {
                        completePhase(completeVideo(remote, RecordingPhase.SAVE_FAILED));
                        return;
                    }
                }
                throw new IllegalStateException("Recorder still running after stop");
            }
            completePhase(completeVideo(remote, RecordingPhase.SAVE_FAILED));
        } catch (Exception error) {
            Log.w(TAG, "Could not stop rear recording", error);
            completePhase(RecordingPhase.STOP_FAILED);
        }
    }

    private RecordingPhase completeVideo(ITaskService remote, RecordingPhase ifUnavailable) {
        isRecording = false;
        recordPid = -1;
        stopRearScreenWakeup();
        try {
            if (remote.executeShellCommand("test -s " + PrivilegedShellArguments.quote(currentVideoPath))) {
                try {
                    remote.executeShellCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + currentVideoPath);
                } catch (Exception error) { Log.w(TAG, "Saved video could not be indexed", error); }
                return RecordingPhase.SAVED;
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not verify saved video", error);
        }
        return ifUnavailable;
    }

    private void completePhase(RecordingPhase value) {
        main.post(() -> {
            if (taskServiceReleased) {
                if (value == RecordingPhase.SAVED) Toast.makeText(this, R.string.ui_recording_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            updatePhase(value);
            if (closeAfterSave && value == RecordingPhase.SAVED)
                Toast.makeText(this, R.string.ui_recording_saved, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroy() {
        taskServiceReleased = true;
        super.onDestroy();
        
        // 停止背屏持续唤醒
        stopRearScreenWakeup();
        main.removeCallbacks(checkProcess);
        
        recorder.execute(() -> {
            try {
                finishRecording();
            } finally {
                TaskServiceManager.release(taskServiceConnection);
                taskService = null;
            }
        });
        recorder.shutdown();
        
        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
                Log.d(TAG, "✓ 悬浮窗已移除");
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败", e);
            }
        }
        
        instance = null;
        refreshTile();
        Log.d(TAG, "⚠ Service已销毁");
    }
}

