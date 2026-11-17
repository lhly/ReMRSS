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

import android.os.Build;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

/**
 * 运行在Shizuku进程中的服务，具有shell权限
 */
public class TaskService extends ITaskService.Stub {
    private static final String TAG = "TaskService";
    private static final java.util.regex.Pattern NOTIFICATION_LAUNCH = PrivilegedShellArguments.notificationLaunchPrefix(
            BuildConfig.APPLICATION_ID + "/" + RearScreenNotificationActivity.class.getName());
    static final int USER_SERVICE_CONTRACT_VERSION = 98;
    static final int RUNTIME_USER_SERVICE_VERSION = BuildConfig.DEBUG ? 102 : USER_SERVICE_CONTRACT_VERSION;
    private final Object mainTaskFocusLock = new Object();
    @Override public String getRearDisplayState() { return RearDisplay.state(); }
    @Override public String getCompatibilityDiagnostics() {
        try { return new org.json.JSONObject(CompatibilityDiagnostics.report())
                .put("virtualCast", new org.json.JSONObject(virtualCast.status()))
                .put("inputProxy", new org.json.JSONObject(inputProxy.status())).toString(); }
        catch (Exception e) { return CompatibilityDiagnostics.report(); }
    }
    @Override public String getRearPhysicalDisplayId() {
        try { return RearDisplay.physicalId(); }
        catch (Exception e) { throw new IllegalStateException("Rear physical display: " + e, e); }
    }
    private final SystemMetricsReader systemMetrics = new SystemMetricsReader();
    @Override public String readSystemMetrics() { return systemMetrics.read(); }
    @Override public String readSystemWeather() {
        return android.os.Build.VERSION.SDK_INT >= 26 ? SystemWeatherReader.read() : "{}";
    }
    private final RearInputProxy inputProxy = new RearInputProxy(this::isTaskOnDisplay);
    private final RearVirtualCast virtualCast = new RearVirtualCast(this, inputProxy);

    // Transactions 52-56 remain reserved for wire compatibility; theme operations are not part of RC1.
    @Override public String getOfficialThemeStatus() { return "{\"availability\":\"UNSUPPORTED\",\"reason\":\"not_in_rc1\"}"; }
    @Override public String installOfficialTheme(String id, int systemId, String title, byte[] compiledResource) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }
    @Override public String removeOfficialTheme(String id) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }
    @Override public void prepareOfficialThemeHost(int task, String component, android.os.IBinder owner) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }
    @Override public void cancelOfficialThemeHost(android.os.IBinder owner) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }

    @Override public String getRearCastViewport() {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        try { return RearCastGeometry.encode(RearCastGeometry.read()); }
        catch (Exception e) { throw new IllegalStateException("Rear safe area: " + e, e); }
    }
    @Override public String getRearCastRenderPlan(int task, String component, int density, boolean compatibility, boolean full) {
        try { return virtualCast.renderPlan(task, component, density, compatibility, full); }
        catch (Exception e) { throw new IllegalStateException("Rear render plan: " + e, e); }
    }
    @Override public String startRearVirtualCast(android.view.Surface surface, String path, int task, int host, int density, String component, android.os.IBinder owner, IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full) {
        try { return virtualCast.start(surface, path, task, host, density, component, owner, gestures, viewport, compatibility, rotation, full); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast: " + e, e); }
    }
    @Override public String prepareRearVirtualCastReplacement(String previousId, long previousGeneration,
            long previousOwnerGeneration, String previousOwnerIdentity, int previousTask, int previousHost,
            String previousInputId,
            String previousBaseComponent, String expectedRearIdentity,
            String expectedCandidateBaseComponent, int rearDisplayId, String operationId,
            long ownerGeneration, String ownerIdentity,
            android.view.Surface candidateSurface, String readerPath, int task, int host, int density,
            String component, android.os.IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full) {
        try { return virtualCast.prepareReplacement(previousId, previousGeneration, previousOwnerGeneration,
                previousOwnerIdentity,
                previousTask, previousHost, previousInputId, previousBaseComponent, expectedRearIdentity,
                expectedCandidateBaseComponent, rearDisplayId, operationId, ownerGeneration, ownerIdentity,
                candidateSurface, readerPath, task, host, density, component,
                owner, gestures, viewport, compatibility, rotation, full, null); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast replacement prepare: " + e, e); }
    }
    @Override public String prepareRearVirtualCastReplacementWithTargetGate(String previousId, long previousGeneration,
            long previousOwnerGeneration, String previousOwnerIdentity, int previousTask, int previousHost,
            String previousInputId, String previousBaseComponent, String expectedRearIdentity,
            String expectedCandidateBaseComponent, int rearDisplayId, String operationId,
            long ownerGeneration, String ownerIdentity, android.view.Surface candidateSurface,
            String readerPath, int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation,
            boolean full, ISceneTaskActionGate targetActionGate) {
        if (targetActionGate == null) throw new SecurityException("explicit replacement target gate is required");
        try { return virtualCast.prepareReplacement(previousId, previousGeneration, previousOwnerGeneration,
                previousOwnerIdentity, previousTask, previousHost, previousInputId, previousBaseComponent,
                expectedRearIdentity, expectedCandidateBaseComponent, rearDisplayId, operationId,
                ownerGeneration, ownerIdentity, candidateSurface, readerPath, task, host, density,
                component, owner, gestures, viewport, compatibility, rotation, full, targetActionGate); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast replacement prepare: " + e, e); }
    }
    @Override public boolean reportRearVirtualCastReplacementOutputFrame(String token) {
        return virtualCast.replacementOutputFrameReady(token);
    }
    @Override public boolean beginRearVirtualCastReplacementHandoff(String token, ISceneTaskActionGate gate) {
        try { return virtualCast.beginReplacementHandoff(token, gate); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast replacement pause: " + e, e); }
    }
    @Override public boolean bindRearVirtualCastReplacementInput(String token, ISceneTaskActionGate gate) {
        try { return virtualCast.bindReplacementInput(token, gate); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast replacement input: " + e, e); }
    }
    @Override public String finishRearVirtualCastReplacement(String token, boolean commit, ISceneTaskActionGate gate) {
        try { return virtualCast.finishReplacementHandoff(token, commit, gate); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast replacement finish: " + e, e); }
    }
    @Override public boolean abortRearVirtualCastReplacement(String token) {
        try { return virtualCast.abortReplacement(token); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast replacement abort: " + e, e); }
    }
    @Override public String startControlledRearVirtualCast(android.view.Surface surface, String readerPath,
            int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity) {
        try { return virtualCast.startControlled(surface, readerPath, task, host, density, component, owner,
                gestures, viewport, compatibility, rotation, full, expectedBaseComponent,
                expectedRearDisplayId, expectedRearIdentity, operationId, ownerGeneration, ownerIdentity); }
        catch (Exception e) { throw new IllegalStateException("Controlled rear virtual cast: " + e, e); }
    }
    @Override public String getTaskBaseComponent(int taskId, int displayId) {
        try {
            return RearDisplayCastOccupancyReader.exactTaskBaseComponent(
                    RearDisplayCastOccupancyReader.taskInfos(RearSystemBridge.service(
                            "activity_task", "android.app.IActivityTaskManager$Stub")), taskId, displayId);
        } catch (Exception unavailable) { return null; }
    }
    @Override public void dismissRearCastMenu(String id) {
        try { virtualCast.dismissMenu(id); }
        catch (Exception e) { throw new IllegalStateException("Rear menu: " + e, e); }
    }
    @Override public String rotateRearVirtualCast(String id, int direction) {
        try { return virtualCast.rotate(id, direction); }
        catch (Exception e) { throw new IllegalStateException("Rear content rotation: " + e, e); }
    }
    @Override public String beginRearCastViewport(String id, boolean full) {
        try { return virtualCast.beginViewport(id, full); }
        catch (Exception e) { throw new IllegalStateException("Rear viewport begin: " + e, e); }
    }
    @Override public String finishRearCastViewport(String id, android.view.Surface surface) {
        try { return virtualCast.finishViewport(id, surface); }
        catch (Exception e) { throw new IllegalStateException("Rear viewport finish: " + e, e); }
    }
    @Override public String getRearVirtualCastStatus() { return virtualCast.status(); }
    @Override public boolean renewRearVirtualCastLease(String id) { return virtualCast.renew(id); }
    @Override public void stopRearVirtualCast(String id) { virtualCast.stop(id); }
    @Override public boolean setRearCastNotificationBounds(String id, long token, int left, int top, int right, int bottom) {
        return virtualCast.notification(id, token, left, top, right, bottom);
    }
    @Override public boolean updateRearVirtualCastSurface(String id, android.view.Surface surface) {
        try { return virtualCast.updateSurface(id, surface); }
        catch (Exception e) { throw new IllegalStateException("Rear output reconnect: " + e, e); }
    }
    @Override public String setRearVirtualCastOutputAvailable(String id, boolean available, android.view.Surface replacement,
                                                               ISceneTaskActionGate actionGate) {
        try { return virtualCast.setOutputAvailable(id, available, replacement, actionGate); }
        catch (Exception e) { throw new IllegalStateException("Rear output power state: " + e, e); }
    }
    @Override public String recoverRearVirtualCastHost(String id, int host, int rearDisplayId, int action,
                                                       ISceneTaskActionGate actionGate) {
        try { return virtualCast.recoverHost(id, host, rearDisplayId, action, actionGate); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual host recovery: " + e, e); }
    }

    @Override public boolean reportOwnedNotificationActivity(int displayId, long notificationSessionId,
                                                              long expiresAtElapsedRealtimeMs,
                                                              long drawEventUptime,
                                                              ISceneTaskActionGate currentGate) {
        String result = "unavailable";
        try {
            if (notificationSessionId <= 0 || displayId < 1 || expiresAtElapsedRealtimeMs <= 0 ||
                    drawEventUptime < 0 || currentGate == null ||
                    android.os.SystemClock.elapsedRealtime() >= expiresAtElapsedRealtimeMs) {
                result = "invalid_request";
                return false;
            }
            RearDisplayUserActivity.Result report = RearDisplayUserActivity.reportOwnedNotification(
                    this::rearUserActivitySink,
                    targetDisplayId -> {
                        org.json.JSONObject rear = new org.json.JSONObject(RearDisplay.state());
                        int selectedRearId = rear.optInt("selectedId", -1);
                        String selectedRearUniqueId = rear.optString("selectedUnique", "");
                        Object info = RearDisplay.info(targetDisplayId);
                        String actualDisplayUniqueId = String.valueOf(info.getClass().getField("uniqueId").get(info));
                        boolean displayOn = info.getClass().getField("state").getInt(info) == android.view.Display.STATE_ON;
                        return new RearDisplayUserActivity.DisplaySnapshot(
                                selectedRearId, selectedRearUniqueId, actualDisplayUniqueId, displayOn);
                    },
                    currentGate::isCurrent, android.os.SystemClock::elapsedRealtime,
                    displayId, notificationSessionId, expiresAtElapsedRealtimeMs, drawEventUptime);
            result = report.name().toLowerCase(java.util.Locale.ROOT);
            return report == RearDisplayUserActivity.Result.REPORTED;
        } catch (Exception failure) {
            result = "failed_" + failure.getClass().getSimpleName();
            synchronized (rearUserActivitySinkLock) { rearUserActivitySink = null; }
            return false;
        } finally {
            CompatibilityDiagnostics.record("owned_notification_activity",
                    "session=" + notificationSessionId + " display=" + displayId
                            + " draw_uptime=" + drawEventUptime + " result=" + result);
            Log.i(TAG, "owned_notification_activity session=" + notificationSessionId +
                    " display=" + displayId + " draw_uptime=" + drawEventUptime + " result=" + result);
        }
    }

    private RearDisplayUserActivity.Sink rearUserActivitySink() throws Exception {
        RearDisplayUserActivity.Sink sink = rearUserActivitySink;
        if (sink != null) return sink;
        synchronized (rearUserActivitySinkLock) {
            if (rearUserActivitySink == null) rearUserActivitySink = RearDisplayUserActivity.shell();
            return rearUserActivitySink;
        }
    }

    @Override public String startRearInputProxy(String path, int task, android.os.IBinder owner) {
        if (!BuildConfig.DEBUG || !BuildConfig.INPUT_PROXY_PROTOTYPE) throw new SecurityException("Direct input probe is debug-only");
        try { return inputProxy.start(path, task, owner); }
        catch (Exception e) { throw new IllegalStateException("Rear input proxy: " + e, e); }
    }
    @Override public String getRearInputProxyStatus() { return inputProxy.status(); }
    @Override public boolean renewRearInputProxyLease(String id) { return inputProxy.renew(id); }
    @Override public void stopRearInputProxy(String id) { inputProxy.stop(id); }
    private final DisplayRotationController rotationController = new DisplayRotationController(
            DisplayRotationController::runCommand, Thread::sleep, message -> { Log.i(TAG, message); CompatibilityDiagnostics.record("rotation", message); });

    private final int ownerUid;
    private volatile RearDisplayUserActivity.Sink rearUserActivitySink;
    private final Object rearUserActivitySinkLock = new Object();
    @Keep
    public TaskService() { this(-1); }
    TaskService(int ownerUid) {
        this.ownerUid = ownerUid;
        RearDisplay.initializeShell();
        if (ownerUid < 0) RearAppFreezeGuard.recoverStale();
        CompatibilityDiagnostics.record("task_service_start", "user_service_contract=" + RUNTIME_USER_SERVICE_VERSION);
    }

    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws RemoteException {
        int caller = android.os.Binder.getCallingUid();
        if (ownerUid >= 0 && caller != ownerUid && caller != android.os.Process.myUid())
            throw new SecurityException("Caller is not the Root service owner");
        long startedAt = android.os.SystemClock.elapsedRealtime();
        try {
            return super.onTransact(code, data, reply, flags);
        } finally {
            long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
            if (elapsed >= 1000L) Log.w(TAG, "binder_slow method=" + binderMethodName(code)
                    + " code=" + code + " elapsed_ms=" + elapsed);
        }
    }

    /** Uses generated AIDL transaction constants; Android's runtime code includes FIRST_CALL_TRANSACTION. */
    private static String binderMethodName(int code) {
        if (code == ITaskService.Stub.TRANSACTION_launchSceneActivity) return "launchSceneActivity";
        if (code == ITaskService.Stub.TRANSACTION_launchSceneActivityWithPlacement) return "launchSceneActivityWithPlacement";
        if (code == ITaskService.Stub.TRANSACTION_verifySceneActivity) return "verifySceneActivity";
        if (code == ITaskService.Stub.TRANSACTION_inspectSceneActivityTask) return "inspectSceneActivityTask";
        if (code == ITaskService.Stub.TRANSACTION_finishSceneActivityTask) return "finishSceneActivityTask";
        if (code == ITaskService.Stub.TRANSACTION_presentSceneActivityTask) return "presentSceneActivityTask";
        return "unknown";
    }

    void closeSessions() {
        try { virtualCast.stop(null); } finally { inputProxy.stop(null); }
    }
    @Override
    public void destroy() { closeSessions(); System.exit(0); }

    @Override
    public String getCurrentForegroundApp() throws RemoteException {
        try {

            // 执行am stack list，在Shizuku进程中具有shell权限
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inDisplayZero = false;
            String line;
            while ((line = reader.readLine()) != null) {
                // RootTask行：判断displayId
                if (line.startsWith("RootTask")) {
                    inDisplayZero = line.contains("displayId=0");
                    continue;
                }
                
                // taskId行（缩进的子行）
                if (inDisplayZero && line.contains("taskId=") && line.contains("/")) {
                    // 解析:   taskId=1471: com.example.display_switcher/com.example.display_switcher.MainActivity
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    // 跳过Launcher和应用自己
                    if (packageName.contains("launcher") || 
                        packageName.contains("miui.home") ||
                        packageName.equals(BuildConfig.APPLICATION_ID)) {
                        continue;
                    }
                    
                    reader.close();
                    process.destroy();
                    
                    String result = packageName + ":" + taskId;

                    return result;
                }
            }
            
            int exitCode = process.waitFor();
            reader.close();

            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    @Override
    public int getTaskIdByPackage(String packageName) throws RemoteException {
        try {

            // 执行am stack list，在Shizuku进程中具有shell权限
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=") && line.contains(packageName + "/")) {
                    // 解析: taskId=1434: com.android.camera/...
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    String taskId = line.substring(start, end).trim();
                    int tid = Integer.parseInt(taskId);
                    
                    reader.close();
                    process.destroy();

                    return tid;
                }
            }
            
            reader.close();
            process.waitFor();

            return -1;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    @Override
    public boolean moveTaskToDisplay(int taskId, int displayId) throws RemoteException {
        if (!RearDisplay.isRear(displayId)) inputProxy.taskLeaving(taskId);
        try {
            long startTime = System.currentTimeMillis();

            // 先获取包名
            String packageName = getPackageNameFromTaskId(taskId);

            boolean invoked = RearSystemBridge.moveTask(taskId, displayId);
            boolean success = false;
            for (int attempt = 0; invoked && attempt < 10; attempt++) {
                if (isTaskOnDisplay(taskId, displayId)) { success = true; break; }
                try { Thread.sleep(50); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            }
            CompatibilityDiagnostics.record("move_result", "task_id=" + taskId + " display_id=" + displayId
                    + " verified=" + success + " elapsed_ms=" + (System.currentTimeMillis() - startTime));
            Log.i(TAG, "move_result task_id=" + taskId + " display_id=" + displayId + " verified=" + success);

            // 如果成功移动到当前背屏，保存任务信息
            if (success && RearDisplay.isRear(displayId)) {
                try {
                    if (packageName != null) {
                        // 保存到广播接收器，以便系统事件后恢复
                        RearScreenBroadcastReceiver.saveLastTask(packageName, taskId);

                    } else {

                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to save task info", e);
                }
            }

            return success;
            
        } catch (Exception e) {
            CompatibilityDiagnostics.record("task_move_failed", e.toString());
            Log.e(TAG, "❌ EXCEPTION in moveTaskToDisplay", e);
            return false;
        }
    }

    /**
     * Focuses one already-existing, exact main-display task for the explicit Mod target chooser.
     * Unlike moveTaskToDisplay this never touches RearInputProxy, legacy last-task state, or a rear owner.
     */
    @Override public int focusExactMainTask(int taskId, String expectedBaseComponent,
            String expectedTopComponent, ITaskFocusActionGate actionGate) {
        if (taskId <= 0 || expectedBaseComponent == null || expectedTopComponent == null || actionGate == null)
            return ITaskService.TASK_FOCUS_FAILED;
        synchronized (mainTaskFocusLock) {
            try {
                if (!isTaskFocusGateCurrent(actionGate, taskId, expectedBaseComponent, expectedTopComponent))
                    return failMainTaskFocus(FocusFailure.GATE_BEFORE);
                RearDisplayCastOccupancy.Snapshot before = RearDisplayCastOccupancyReader.readTypedOnly(0);
                if (!isExactMainTaskFocusSnapshot(before, taskId, expectedBaseComponent, expectedTopComponent, false))
                    return failMainTaskFocus(FocusFailure.TYPED_BEFORE);
                DisplayRotationController.Result rootsBefore = DisplayRotationController.runCommand("am", "stack", "list");
                if (rootsBefore.exitCode != 0) return failMainTaskFocus(FocusFailure.ROOTS_BEFORE_COMMAND);
                if (!RearSystemBridge.isSoleTaskRoot(rootsBefore.output, taskId, 0))
                    return failMainTaskFocus(FocusFailure.ROOTS_BEFORE_SHAPE);
                if (!isTaskFocusGateCurrent(actionGate, taskId, expectedBaseComponent, expectedTopComponent))
                    return failMainTaskFocus(FocusFailure.GATE_BEFORE_COMMAND);

                DisplayRotationController.Result focus = DisplayRotationController.runCommand("am", "task", "focus",
                        Integer.toString(taskId));
                if (!RearSystemBridge.commandSucceeded(focus)) return failMainTaskFocus(FocusFailure.FOCUS_COMMAND);
                if (!isTaskFocusGateCurrent(actionGate, taskId, expectedBaseComponent, expectedTopComponent))
                    return failMainTaskFocus(FocusFailure.GATE_AFTER_COMMAND);

                RearDisplayCastOccupancy.Snapshot after = RearDisplayCastOccupancyReader.readTypedOnly(0);
                DisplayRotationController.Result rootsAfter = DisplayRotationController.runCommand("am", "stack", "list");
                if (!isExactMainTaskFocusSnapshot(after, taskId, expectedBaseComponent, expectedTopComponent, true))
                    return failMainTaskFocus(FocusFailure.TYPED_AFTER);
                if (rootsAfter.exitCode != 0) return failMainTaskFocus(FocusFailure.ROOTS_AFTER_COMMAND);
                if (!RearSystemBridge.isSoleTaskRoot(rootsAfter.output, taskId, 0))
                    return failMainTaskFocus(FocusFailure.ROOTS_AFTER_SHAPE);
                if (!isTaskFocusGateCurrent(actionGate, taskId, expectedBaseComponent, expectedTopComponent))
                    return failMainTaskFocus(FocusFailure.GATE_AFTER_READBACK);
                return ITaskService.TASK_FOCUS_CONFIRMED;
            } catch (Exception unavailable) {
                return failMainTaskFocus(FocusFailure.EXCEPTION);
            }
        }
    }

    /** Fixed, debug-only failure categories for the RC5 cast focus probe; never includes task data. */
    private enum FocusFailure {
        GATE_BEFORE, TYPED_BEFORE, ROOTS_BEFORE_COMMAND, ROOTS_BEFORE_SHAPE, GATE_BEFORE_COMMAND,
        FOCUS_COMMAND, GATE_AFTER_COMMAND, TYPED_AFTER, ROOTS_AFTER_COMMAND, ROOTS_AFTER_SHAPE,
        GATE_AFTER_READBACK, EXCEPTION
    }

    private static int failMainTaskFocus(FocusFailure failure) {
        if (BuildConfig.DEBUG) {
            Log.i("ModCastFocusProbe", "stage=focus_service_failure value=E_FOCUS_" + failure.name());
        }
        return ITaskService.TASK_FOCUS_FAILED;
    }

    @Override public String moveControlledPhysicalTask(int taskId, String packageName, String baseComponent,
            int sourceDisplayId, int targetDisplayId, String expectedRearIdentity) {
        return moveControlledPhysicalTaskInternal(taskId, packageName, baseComponent,
                sourceDisplayId, targetDisplayId, expectedRearIdentity, null);
    }

    /** Debug-only two-stage RC5 experiment; exact fixtures only, release always rejects. */
    @Override public String moveRc5PhysicalFixtureBToRearBottom(int aTaskId, long aGeneration, int bTaskId,
            int aProcessPid, int bProcessPid, int rearDisplayId, String expectedRearIdentity,
            String operationId, ITaskFocusActionGate actionGate) {
        final String aPackage = "dev.remrss.sample.rc5physicala";
        final String aBase = aPackage + "/dev.remrss.physical.MainActivity";
        final String bPackage = "dev.remrss.sample.rc5physicalb";
        final String bBase = bPackage + "/dev.remrss.physical.MainActivity";
        if (!isRc5PhysicalFocusExperimentAvailable())
            return controlledPhysicalMoveResult("rejected", "debug_disabled");
        if (!rc5PhysicalFocusCallerAllowed())
            return controlledPhysicalMoveResult("rejected", "caller_rejected");
        if (aTaskId <= 0 || bTaskId <= 0
                || aTaskId == bTaskId || aGeneration <= 0L || rearDisplayId <= 0
                || aProcessPid <= 0 || bProcessPid <= 0 || aProcessPid == bProcessPid
                || expectedRearIdentity == null || operationId == null || operationId.isBlank() || actionGate == null)
            return controlledPhysicalMoveResult("rejected", "invalid_arguments");
        synchronized (controlledPhysicalMoveLock) {
            try {
                // Refuse before any read/dispatch if a previous operation still owns the slot.
                if (!rc5BottomDesktopBindings.availableForNew(android.os.SystemClock.elapsedRealtime()))
                    return controlledPhysicalMoveResult("rejected", "bottom_binding_busy");
                if (!rc5SelectedRearStateMatches(new org.json.JSONObject(RearDisplay.state()),
                        rearDisplayId, expectedRearIdentity))
                    return controlledPhysicalMoveResult("rejected", "rear_identity_mismatch");
                RearDisplayCastOccupancy.Snapshot aRear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                RearDisplayCastOccupancy.Snapshot bMain = RearDisplayCastOccupancyReader.readTypedOnly(0);
                if (aRear == null || aRear.displayId != rearDisplayId || bMain == null || bMain.displayId != 0)
                    return controlledPhysicalMoveResult("rejected", "typed_snapshot_unavailable");
                String inventoryFailure = rc5BottomInventoryFailure(aRear, bMain, aTaskId, bTaskId,
                        rearDisplayId, expectedRearIdentity, aProcessPid, bProcessPid);
                if (inventoryFailure != null)
                    return controlledPhysicalMoveResult("rejected", inventoryFailure);
                final PhysicalReplacementBottomFocus.DesktopIdentity[] boundDesktop = {null};
                final String[] finalGateFailure = {"final_gate_rejected"};
                RearSystemBridge.SoleRootMoveDiagnostic result = RearSystemBridge.moveSoleTaskRootToDisplayBottomDiagnosed(
                        bTaskId, bBase, 0, rearDisplayId, () -> {
                            RearDisplayCastOccupancy.Snapshot currentA = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                            RearDisplayCastOccupancy.Snapshot currentB = RearDisplayCastOccupancyReader.readTypedOnly(0);
                            if (currentA == null || currentA.displayId != rearDisplayId) {
                                finalGateFailure[0] = "typed_snapshot_unavailable"; return false;
                            }
                            if (currentB == null || currentB.displayId != 0) {
                                finalGateFailure[0] = "typed_snapshot_unavailable"; return false;
                            }
                            if (!rc5PhysicalFocusCallerAllowed()) {
                                finalGateFailure[0] = "caller_rejected"; return false;
                            }
                            String failure = rc5BottomInventoryFailure(currentA, currentB, aTaskId, bTaskId,
                                    rearDisplayId, expectedRearIdentity, aProcessPid, bProcessPid);
                            if (failure != null) { finalGateFailure[0] = failure; return false; }
                            if (!rc5SelectedRearStateMatches(safeRearState(), rearDisplayId, expectedRearIdentity)) {
                                finalGateFailure[0] = "rear_identity_mismatch"; return false;
                            }
                            if (!isTaskFocusGateCurrent(actionGate, bTaskId, bBase, bBase)) {
                                finalGateFailure[0] = "dispatch_gate_rejected"; return false;
                            }
                            boundDesktop[0] = DebugPhysicalBFocusTransferProbe.exactSystemDesktopIdentity(
                                    currentA, rearDisplayId, aTaskId, aPackage, aBase,
                                    RearDisplayCastOccupancy::isExactSystemLauncher);
                            if (boundDesktop[0] == null) {
                                finalGateFailure[0] = "a_topology_mismatch"; return false;
                            }
                            return boundDesktop[0] != null;
                        });
                if (result.dispatch == RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND)
                    return controlledPhysicalMoveResult("rejected", "final_gate_rejected".equals(result.reason)
                            ? finalGateFailure[0] : result.reason);
                if (result.dispatch != RearSystemBridge.SoleRootMoveDispatch.SENT)
                    return controlledPhysicalMoveResult("unknown", result.reason);

                // A single typed/root readback is decisive; no retry can repeat the move.
                RearDisplayCastOccupancy.Snapshot afterRear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                if (afterRear == null || afterRear.displayId != rearDisplayId
                        || !rc5RearPairAFrontBHidden(afterRear, aTaskId, bTaskId, rearDisplayId,
                            expectedRearIdentity, boundDesktop[0], aProcessPid, bProcessPid))
                    return controlledPhysicalMoveResult("unknown", "bottom_pair_readback_unknown");
                if (!rc5SelectedRearStateMatches(safeRearState(), rearDisplayId, expectedRearIdentity))
                    return controlledPhysicalMoveResult("unknown", "bottom_root_readback_unknown");
                if (!storeRc5BottomDesktopBinding(aTaskId, aGeneration, bTaskId, rearDisplayId,
                        expectedRearIdentity, operationId, boundDesktop[0]))
                    return controlledPhysicalMoveResult("unknown", "bottom_binding_store_failed");
                return controlledPhysicalMoveResult("moved", "exact_pair_rear_readback");
            } catch (Exception unavailable) {
                return controlledPhysicalMoveResult("unknown", "bottom_operation_unknown");
            }
        }
    }

    /** Debug-only one-shot setFocusedTask for exact B already attached to the selected rear display. */
    @Override public String focusRc5PhysicalFixtureBOnRear(int aTaskId, long aGeneration, int bTaskId,
            int aProcessPid, int bProcessPid, int rearDisplayId, String expectedRearIdentity,
            String operationId, ITaskFocusActionGate actionGate) {
        final String aPackage = "dev.remrss.sample.rc5physicala";
        final String aBase = aPackage + "/dev.remrss.physical.MainActivity";
        final String bPackage = "dev.remrss.sample.rc5physicalb";
        final String bBase = bPackage + "/dev.remrss.physical.MainActivity";
        if (!isRc5PhysicalFocusExperimentAvailable() || !rc5PhysicalFocusCallerAllowed()
                || aTaskId <= 0 || bTaskId <= 0
                || aTaskId == bTaskId || aGeneration <= 0L || rearDisplayId <= 0
                || aProcessPid <= 0 || bProcessPid <= 0 || aProcessPid == bProcessPid
                || expectedRearIdentity == null || operationId == null || operationId.isBlank() || actionGate == null)
            return controlledPhysicalMoveResult("rejected", "debug_fixture_unavailable");
        synchronized (controlledPhysicalMoveLock) {
            try {
                PhysicalReplacementBottomFocus.DesktopIdentity boundDesktop =
                        takeRc5BottomDesktopBinding(aTaskId, aGeneration, bTaskId, rearDisplayId,
                                expectedRearIdentity, operationId);
                if (boundDesktop == null)
                    return controlledPhysicalMoveResult("rejected", "bottom_desktop_binding_unavailable");
                if (!rc5SelectedRearStateMatches(safeRearState(), rearDisplayId, expectedRearIdentity))
                    return controlledPhysicalMoveResult("rejected", "rear_identity_mismatch");
                RearDisplayCastOccupancy.Snapshot rear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                if (rear == null || rear.displayId != rearDisplayId
                        || !rc5RearPairAFrontBHidden(rear, aTaskId, bTaskId, rearDisplayId,
                            expectedRearIdentity, boundDesktop, aProcessPid, bProcessPid))
                    return controlledPhysicalMoveResult("rejected", "exact_pair_placement_mismatch");
                DisplayRotationController.Result roots = DisplayRotationController.runCommand("am", "stack", "list");
                if (roots.exitCode != 0
                        || !RearSystemBridge.isExactSoleTaskRoot(roots.output, aTaskId, aBase, rearDisplayId)
                        || !RearSystemBridge.isExactSoleTaskRoot(roots.output, bTaskId, bBase, rearDisplayId))
                    return controlledPhysicalMoveResult("rejected", "exact_pair_root_mismatch");
                RearSystemBridge.SoleRootMoveDispatch dispatch = RearSystemBridge.focusSoleTaskOnDisplay(
                        bTaskId, bBase, rearDisplayId, () -> {
                            RearDisplayCastOccupancy.Snapshot currentRear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                            return currentRear != null && currentRear.displayId == rearDisplayId
                                    && rc5PhysicalFocusCallerAllowed()
                                    && rc5RearPairAFrontBHidden(currentRear, aTaskId, bTaskId,
                                        rearDisplayId, expectedRearIdentity, boundDesktop, aProcessPid, bProcessPid)
                                    && rc5SelectedRearStateMatches(safeRearState(), rearDisplayId, expectedRearIdentity)
                                    && isTaskFocusGateCurrent(actionGate, bTaskId, bBase, bBase);
                        });
                if (dispatch == RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND)
                    return controlledPhysicalMoveResult("rejected", "focus_dispatch_rejected");
                if (dispatch != RearSystemBridge.SoleRootMoveDispatch.SENT)
                    return controlledPhysicalMoveResult("unknown", "focus_dispatch_reply_unknown");
                return controlledPhysicalMoveResult("requested", "focus_binder_returned");
            } catch (Exception unavailable) {
                return controlledPhysicalMoveResult("unknown", "focus_operation_unknown");
            }
        }
    }

    /** Best-effort exact-operation release after transaction compensation/termination. */
    @Override public boolean clearRc5PhysicalFixtureBottomBinding(int aTaskId, long aGeneration,
            int bTaskId, int rearDisplayId, String expectedRearIdentity, String operationId) {
        if (!isRc5PhysicalFocusExperimentAvailable() || !rc5PhysicalFocusCallerAllowed()
                || aTaskId <= 0 || bTaskId <= 0 || aTaskId == bTaskId || aGeneration <= 0L
                || rearDisplayId <= 0 || expectedRearIdentity == null || operationId == null
                || operationId.isBlank()) return false;
        synchronized (controlledPhysicalMoveLock) {
            return rc5BottomDesktopBindings.clearExact(aTaskId, aGeneration, bTaskId,
                    rearDisplayId, expectedRearIdentity, operationId, android.os.SystemClock.elapsedRealtime());
        }
    }

    /**
     * Production P→P handoff for the verified popsicle route. The entire bottom→readback→focus
     * sequence stays inside one privileged call so no unbound inter-stage state can authorize a
     * later request. An uncertain post-bottom result is reported with bMoved=true for exact host
     * compensation; this method never repeats either system action.
     */
    @Override public String moveControlledPhysicalReplacementBWithBottomFocus(int oldTaskId,
            String oldPackage, String oldBase, long oldGeneration, int newTaskId, String newPackage,
            String newBase, long newGeneration, long newCoordinatorGeneration, String newOwnerIdentity,
            int rearDisplayId, String rearIdentity, String operationId,
            IPhysicalReplacementMoveGate bottomGate, IPhysicalReplacementMoveGate focusGate) {
        if (!validPhysicalReplacementMoveRequest(oldTaskId, oldPackage, oldBase, rearDisplayId,
                rearIdentity, operationId, oldGeneration, newTaskId, newPackage, newBase,
                newGeneration, newCoordinatorGeneration, newOwnerIdentity)
                || bottomGate == null || focusGate == null
                || !oldPackage.equals(packageOfComponent(oldBase))
                || !newPackage.equals(packageOfComponent(newBase)))
            return physicalBottomFocusResult("rejected", "invalid_replacement_authority", false);
        if (!physicalBottomFocusCallerAllowed())
            return physicalBottomFocusResult("rejected", "caller_rejected", false);
        if (!PhysicalReplacementBottomFocus.supportedDevice(Build.DEVICE, Build.MODEL)
                || !RearSystemBridge.supportsPhysicalReplacementBottomFocus())
            return physicalBottomFocusResult("unsupported", "capability_unavailable", false);

        final boolean[] possiblyMoved = {false};
        synchronized (controlledPhysicalMoveLock) {
            try {
                if (!isPhysicalReplacementMoveGateCurrent(bottomGate, operationId, oldTaskId,
                        oldPackage, oldBase, oldGeneration, newTaskId, newPackage, newBase,
                        newGeneration, newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity))
                    return physicalBottomFocusResult("rejected", "dispatch_gate_rejected", false);
                boolean firstRejectDiagnostic = PhysicalReplacementFirstReject.enabled(
                        BuildConfig.DEBUG, Build.DEVICE, Build.MODEL);
                String[] sourceReason = firstRejectDiagnostic ? new String[] {"snapshot_unknown"} : null;
                PresentationProcessPair processPair = exactPhysicalReplacementSource(oldTaskId,
                        oldPackage, oldBase, newTaskId, newPackage, newBase, rearDisplayId, rearIdentity,
                        sourceReason);
                if (processPair == null)
                    return physicalBottomFocusResult("rejected", sourceReason == null
                            ? "source_evidence_unverified" : sourceReason[0], false);
                PhysicalReplacementBottomFocus.BottomCheck[] firstBottom = {null};
                PhysicalReplacementBottomFocus.BottomCheck[] lastBottom = {null};
                java.util.function.LongFunction<PhysicalReplacementBottomFocus.BottomCheck> observeBottom =
                        deadline -> physicalReplacementBottomCheck(focusGate, operationId,
                                oldTaskId, oldPackage, oldBase, oldGeneration, newTaskId,
                                newPackage, newBase, newGeneration, newCoordinatorGeneration,
                                newOwnerIdentity, rearDisplayId, rearIdentity, processPair, deadline);
                PhysicalReplacementBottomFocus.Outcome outcome = PhysicalReplacementBottomFocus.run(true,
                        new PhysicalReplacementBottomFocus.Actions() {
                    @Override public PhysicalReplacementBottomFocus.Dispatch moveBottom() {
                        try {
                            RearSystemBridge.SoleRootMoveDiagnostic result =
                                    RearSystemBridge.moveSoleTaskRootToDisplayBottomDiagnosed(newTaskId,
                                            newBase, 0, rearDisplayId, () ->
                                            physicalBottomFocusDispatchReady(bottomGate, operationId,
                                                    oldTaskId, oldPackage, oldBase, oldGeneration,
                                                    newTaskId, newPackage, newBase, newGeneration,
                                                    newCoordinatorGeneration, newOwnerIdentity, rearDisplayId,
                                                    rearIdentity, processPair));
                            if (result.dispatch != RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND)
                                possiblyMoved[0] = true;
                            return physicalBottomFocusDispatch(result.dispatch);
                        } catch (Exception preSendFailure) {
                            return PhysicalReplacementBottomFocus.Dispatch.REJECTED_BEFORE_SEND;
                        }
                    }
                    @Override public boolean verifyBottom() {
                        lastBottom[0] = PhysicalReplacementBottomFocus.awaitBottomReady(deadline -> {
                            PhysicalReplacementBottomFocus.BottomCheck observed = observeBottom.apply(deadline);
                            if (firstBottom[0] == null) firstBottom[0] = observed;
                            return observed;
                        }, new PhysicalReplacementBottomFocus.SettlementClock() {
                            @Override public long nanoTime() { return System.nanoTime(); }
                            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                        });
                        return lastBottom[0] == PhysicalReplacementBottomFocus.BottomCheck.READY;
                    }
                    @Override public PhysicalReplacementBottomFocus.Dispatch requestFocus() {
                        try {
                            RearSystemBridge.SoleRootMoveDispatch result =
                                    RearSystemBridge.focusSoleTaskOnDisplay(newTaskId, newBase,
                                            rearDisplayId, () -> {
                                                lastBottom[0] = observeBottom.apply(Long.MAX_VALUE);
                                                return lastBottom[0] == PhysicalReplacementBottomFocus.BottomCheck.READY;
                                            });
                            return physicalBottomFocusDispatch(result);
                        } catch (Exception preSendFailure) {
                            if (preSendFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                            return PhysicalReplacementBottomFocus.Dispatch.REJECTED_BEFORE_SEND;
                        }
                    }
                });
                possiblyMoved[0] |= outcome.bMoved;
                if (firstRejectDiagnostic && firstBottom[0] != null && lastBottom[0] != null) {
                    Log.i(TAG, "physical_bottom_observation v=1 first=" + firstBottom[0].name()
                            + " last=" + lastBottom[0].name());
                }
                return physicalBottomFocusResult(outcome.status, outcome.reason, possiblyMoved[0]);
            } catch (Exception | LinkageError unavailable) {
                return physicalBottomFocusResult(possiblyMoved[0] ? "unknown" : "rejected",
                        possiblyMoved[0] ? "post_bottom_proof_unknown" : "source_evidence_unverified",
                        possiblyMoved[0]);
            }
        }
    }

    private boolean physicalBottomFocusCallerAllowed() {
        try {
            int expectedUid = RearSystemBridge.systemContext().getPackageManager()
                    .getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
            return expectedUid > 0 && android.os.Binder.getCallingUid() == expectedUid;
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private static PhysicalReplacementBottomFocus.Dispatch physicalBottomFocusDispatch(
            RearSystemBridge.SoleRootMoveDispatch dispatch) {
        if (dispatch == RearSystemBridge.SoleRootMoveDispatch.SENT)
            return PhysicalReplacementBottomFocus.Dispatch.SENT;
        if (dispatch == RearSystemBridge.SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT)
            return PhysicalReplacementBottomFocus.Dispatch.MAY_HAVE_BEEN_SENT;
        return PhysicalReplacementBottomFocus.Dispatch.REJECTED_BEFORE_SEND;
    }

    private static String physicalBottomFocusResult(String status, String reason, boolean bMoved) {
        try { return new org.json.JSONObject().put("status", status).put("reason", reason)
                .put("bMoved", bMoved).toString(); }
        catch (org.json.JSONException impossible) { return "{\"status\":\"unknown\",\"bMoved\":true}"; }
    }

    private static final class PresentationProcessPair {
        final int aPid, aUid, bPid, bUid;
        final PhysicalReplacementBottomFocus.DesktopIdentity desktop;
        PresentationProcessPair(int aPid, int aUid, int bPid, int bUid,
                PhysicalReplacementBottomFocus.DesktopIdentity desktop) {
            this.aPid = aPid; this.aUid = aUid; this.bPid = bPid; this.bUid = bUid; this.desktop = desktop;
        }
    }

    private PresentationProcessPair exactPhysicalReplacementSource(int aTask, String aPackage, String aBase,
            int bTask, String bPackage, String bBase, int rearDisplay, String rearIdentity,
            String[] firstFailure) throws Exception {
        boolean diagnose = firstFailure != null;
        if (!rc5SelectedRearStateMatches(safeRearState(), rearDisplay, rearIdentity)) {
            if (diagnose) firstFailure[0] = "rear_identity";
            return null;
        }
        RearDisplayCastOccupancy.Snapshot rear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplay);
        RearDisplayCastOccupancy.Snapshot main = RearDisplayCastOccupancyReader.readTypedOnly(0);
        PhysicalReplacementBottomFocus.DesktopIdentity desktop =
                PhysicalReplacementBottomFocus.exactSystemDesktopIdentity(rear, rearDisplay, aTask,
                        aPackage, aBase, RearDisplayCastOccupancy::isExactSystemLauncher);
        if (desktop == null) {
            if (diagnose) firstFailure[0] = PhysicalReplacementFirstReject.firstInventoryFailure(
                    rear, rearDisplay, aTask, aPackage, aBase, false, main, bTask, bPackage, bBase,
                    null, null);
            return null;
        }
        boolean exactB = exactMainReplacementCandidate(main, bTask, bPackage, bBase);
        if (!exactB) {
            if (diagnose) firstFailure[0] = PhysicalReplacementFirstReject.firstInventoryFailure(
                    rear, rearDisplay, aTask, aPackage, aBase, true, main, bTask, bPackage, bBase,
                    false, null);
            return null;
        }
        if (containsTaskIdStrict(main, aTask)) {
            if (diagnose) firstFailure[0] = PhysicalReplacementFirstReject.firstInventoryFailure(
                    rear, rearDisplay, aTask, aPackage, aBase, true, main, bTask, bPackage, bBase,
                    true, true);
            return null;
        }
        DisplayRotationController.Result roots = DisplayRotationController.runCommand("am", "stack", "list");
        if (roots.exitCode != 0
                || !RearSystemBridge.isExactSoleTaskRoot(roots.output, aTask, aBase, rearDisplay)
                || !RearSystemBridge.isExactSoleTaskRoot(roots.output, bTask, bBase, 0)
                || (desktop.exists && !RearSystemBridge.isExactSoleTaskRoot(roots.output,
                        desktop.taskId, desktop.baseComponent, rearDisplay))) {
            if (diagnose) firstFailure[0] = "root_inventory";
            return null;
        }
        JSONObject aPresentation = new JSONObject(queryExactPhysicalPresentationInternal(
                aTask, aPackage, aBase, rearDisplay, rearIdentity, false, true));
        JSONObject bPresentation = new JSONObject(queryExactPhysicalPresentationInternal(
                bTask, bPackage, bBase, 0, rearIdentity, true, true));
        if (!exactInternalPhysicalPresentation(aPresentation, aTask, aPackage, aBase, rearDisplay, rearIdentity)) {
            if (diagnose) firstFailure[0] = physicalPresentationFailure(aPresentation,
                    aTask, aPackage, aBase, rearDisplay, rearIdentity, "a");
            return null;
        }
        if (!exactInternalPhysicalPresentation(bPresentation, bTask, bPackage, bBase, 0, rearIdentity)) {
            if (diagnose) firstFailure[0] = physicalPresentationFailure(bPresentation,
                    bTask, bPackage, bBase, 0, rearIdentity, "b");
            return null;
        }
        int aPid = aPresentation.optInt("internalDispatcherOwnerPid", -1);
        int aUid = aPresentation.optInt("internalDispatcherOwnerUid", -1);
        int bPid = bPresentation.optInt("internalDispatcherOwnerPid", -1);
        int bUid = bPresentation.optInt("internalDispatcherOwnerUid", -1);
        if (!exactAppProcessPair(aPackage, aPid, aUid, bPackage, bPid, bUid)) {
            if (diagnose) firstFailure[0] = "process_identity";
            return null;
        }
        return new PresentationProcessPair(aPid, aUid, bPid, bUid, desktop);
    }

    private static boolean exactInternalPhysicalPresentation(org.json.JSONObject evidence, int task,
            String packageName, String base, int display, String rearIdentity) {
        return ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                evidence, task, packageName, base, display, rearIdentity)
                && evidence.optInt("internalDispatcherOwnerPid", -1) > 0
                && evidence.optInt("internalDispatcherOwnerUid", -1) > 0;
    }

    private static String physicalPresentationFailure(org.json.JSONObject evidence, int task,
            String packageName, String base, int display, String rearIdentity, String side) {
        if (!ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                evidence, task, packageName, base, display, rearIdentity))
            return PhysicalReplacementFirstReject.presentationReason(evidence.optString("reason", ""), side);
        return "a".equals(side) ? "a_presentation_owner_missing" : "b_presentation_owner_missing";
    }

    private static boolean exactAppProcessPair(String aPackage, int aPid, int aUid,
            String bPackage, int bPid, int bUid) {
        if (aPid <= 0 || bPid <= 0 || aPid == bPid || aUid <= 0 || bUid <= 0 || aUid == bUid)
            return false;
        try {
            android.content.pm.PackageManager pm = RearSystemBridge.systemContext().getPackageManager();
            if (pm.getApplicationInfo(aPackage, 0).uid != aUid
                    || pm.getApplicationInfo(bPackage, 0).uid != bUid) return false;
            android.app.ActivityManager am = (android.app.ActivityManager)
                    RearSystemBridge.systemContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes == null) return false;
            int aMatches = 0, bMatches = 0;
            for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                if (process == null) continue;
                if (process.pid == aPid && process.uid == aUid && processNameBelongsTo(process.processName, aPackage)) aMatches++;
                if (process.pid == bPid && process.uid == bUid && processNameBelongsTo(process.processName, bPackage)) bMatches++;
            }
            return aMatches == 1 && bMatches == 1;
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private static boolean processNameBelongsTo(String processName, String packageName) {
        return processName != null && (packageName.equals(processName) || processName.startsWith(packageName + ":"));
    }

    private PhysicalReplacementBottomFocus.BottomCheck physicalReplacementBottomCheck(
            IPhysicalReplacementMoveGate gate, String operationId,
            int aTask, String aPackage, String aBase, long aGeneration,
            int bTask, String bPackage, String bBase, long bGeneration, long coordinatorGeneration,
            String ownerIdentity, int rearDisplay, String rearIdentity, PresentationProcessPair processPair,
            long deadlineNanos) {
        try {
            requireBottomObservationTime(deadlineNanos);
            if (!isPhysicalReplacementMoveGateCurrent(gate, operationId, aTask, aPackage, aBase,
                    aGeneration, bTask, bPackage, bBase, bGeneration, coordinatorGeneration,
                    ownerIdentity, rearDisplay, rearIdentity)) return PhysicalReplacementBottomFocus.BottomCheck.GATE_CHANGED;
            requireBottomObservationTime(deadlineNanos);
            if (!rc5SelectedRearStateMatches(safeRearState(), rearDisplay, rearIdentity))
                return PhysicalReplacementBottomFocus.BottomCheck.REAR_IDENTITY;
            requireBottomObservationTime(deadlineNanos);
            RearDisplayCastOccupancy.Snapshot rear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplay);
            requireBottomObservationTime(deadlineNanos);
            RearDisplayCastOccupancy.Snapshot main = RearDisplayCastOccupancyReader.readTypedOnly(0);
            if (!usableTypedSnapshot(main, 0)) return PhysicalReplacementBottomFocus.BottomCheck.MAIN_SNAPSHOT;
            if (containsTaskIdStrict(main, aTask)) return PhysicalReplacementBottomFocus.BottomCheck.A_STILL_MAIN;
            if (containsTaskIdStrict(main, bTask)) return PhysicalReplacementBottomFocus.BottomCheck.B_STILL_MAIN;
            PhysicalReplacementBottomFocus.BottomCheck observed = PhysicalReplacementBottomFocus.rearPairCheck(
                    rear, aTask, aPackage, aBase, bTask, bPackage, bBase, rearDisplay,
                    processPair.desktop, RearDisplayCastOccupancy::isExactSystemLauncher);
            if (observed != PhysicalReplacementBottomFocus.BottomCheck.READY && !observed.maySettle())
                return observed;
            requireBottomObservationTime(deadlineNanos);
            DisplayRotationController.Result roots = deadlineNanos == Long.MAX_VALUE
                    ? DisplayRotationController.runCommand("am", "stack", "list")
                    : runCommandWithin(deadlineNanos, "am", "stack", "list");
            if (roots.exitCode != 0
                    || !RearSystemBridge.isExactSoleTaskRoot(roots.output, aTask, aBase, rearDisplay)
                    || !RearSystemBridge.isExactSoleTaskRoot(roots.output, bTask, bBase, rearDisplay)
                    || (processPair.desktop.exists && processPair.desktop.present(rear)
                        && !RearSystemBridge.isExactSoleTaskRoot(roots.output, processPair.desktop.taskId,
                            processPair.desktop.baseComponent, rearDisplay)))
                return PhysicalReplacementBottomFocus.BottomCheck.ROOT_INVENTORY;
            requireBottomObservationTime(deadlineNanos);
            if (observed == PhysicalReplacementBottomFocus.BottomCheck.READY) {
                JSONObject aPresentation = new JSONObject(queryExactPhysicalPresentationInternal(
                        aTask, aPackage, aBase, rearDisplay, rearIdentity, false, true, deadlineNanos));
                if (!exactInternalPhysicalPresentation(aPresentation, aTask, aPackage, aBase, rearDisplay, rearIdentity)) {
                    if (!"window_not_drawn_and_shown".equals(aPresentation.optString("reason")))
                        return PhysicalReplacementBottomFocus.BottomCheck.A_PRESENTATION;
                    observed = PhysicalReplacementBottomFocus.BottomCheck.A_REDRAWING;
                } else if (aPresentation.optInt("internalDispatcherOwnerPid", -1) != processPair.aPid
                        || aPresentation.optInt("internalDispatcherOwnerUid", -1) != processPair.aUid) {
                    return PhysicalReplacementBottomFocus.BottomCheck.PROCESS_IDENTITY;
                }
            }
            requireBottomObservationTime(deadlineNanos);
            if (!exactAppProcessPair(aPackage, processPair.aPid, processPair.aUid,
                    bPackage, processPair.bPid, processPair.bUid))
                return PhysicalReplacementBottomFocus.BottomCheck.PROCESS_IDENTITY;
            requireBottomObservationTime(deadlineNanos);
            if (!isPhysicalReplacementMoveGateCurrent(gate, operationId, aTask, aPackage, aBase,
                    aGeneration, bTask, bPackage, bBase, bGeneration, coordinatorGeneration,
                    ownerIdentity, rearDisplay, rearIdentity)) return PhysicalReplacementBottomFocus.BottomCheck.GATE_CHANGED;
            requireBottomObservationTime(deadlineNanos);
            if (!rc5SelectedRearStateMatches(safeRearState(), rearDisplay, rearIdentity))
                return PhysicalReplacementBottomFocus.BottomCheck.REAR_IDENTITY;
            requireBottomObservationTime(deadlineNanos);
            return observed;
        } catch (Exception | LinkageError unknown) {
            if (unknown instanceof InterruptedException) Thread.currentThread().interrupt();
            return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos
                    ? PhysicalReplacementBottomFocus.BottomCheck.SETTLEMENT_EXPIRED
                    : PhysicalReplacementBottomFocus.BottomCheck.QUERY_FAILED;
        }
    }

    private static void requireBottomObservationTime(long deadlineNanos) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos)
            throw new java.io.IOException("Physical bottom observation deadline expired");
    }

    private boolean physicalBottomFocusDispatchReady(IPhysicalReplacementMoveGate gate, String operationId,
            int aTask, String aPackage, String aBase, long aGeneration, int bTask, String bPackage,
            String bBase, long bGeneration, long coordinatorGeneration, String ownerIdentity,
            int rearDisplay, String rearIdentity, PresentationProcessPair processPair) {
        try {
            if (!isPhysicalReplacementMoveGateCurrent(gate, operationId, aTask, aPackage, aBase,
                    aGeneration, bTask, bPackage, bBase, bGeneration, coordinatorGeneration,
                    ownerIdentity, rearDisplay, rearIdentity)
                    || !rc5SelectedRearStateMatches(safeRearState(), rearDisplay, rearIdentity)) return false;
            RearDisplayCastOccupancy.Snapshot rear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplay);
            RearDisplayCastOccupancy.Snapshot main = RearDisplayCastOccupancyReader.readTypedOnly(0);
            PhysicalReplacementBottomFocus.DesktopIdentity desktop =
                    PhysicalReplacementBottomFocus.exactSystemDesktopIdentity(rear, rearDisplay,
                            aTask, aPackage, aBase, RearDisplayCastOccupancy::isExactSystemLauncher);
            if (desktop == null || !desktop.sameAs(processPair.desktop)
                    || !exactMainReplacementCandidate(main, bTask, bPackage, bBase)
                    || containsTaskIdStrict(main, aTask)) return false;
            DisplayRotationController.Result roots = DisplayRotationController.runCommand("am", "stack", "list");
            if (roots.exitCode != 0
                    || !RearSystemBridge.isExactSoleTaskRoot(roots.output, aTask, aBase, rearDisplay)
                    || !RearSystemBridge.isExactSoleTaskRoot(roots.output, bTask, bBase, 0)
                    || (desktop.exists
                        && !RearSystemBridge.isExactSoleTaskRoot(roots.output,
                            desktop.taskId, desktop.baseComponent, rearDisplay))) return false;
            JSONObject aPresentation = new JSONObject(queryExactPhysicalPresentationInternal(
                    aTask, aPackage, aBase, rearDisplay, rearIdentity, false, true));
            JSONObject bPresentation = new JSONObject(queryExactPhysicalPresentationInternal(
                    bTask, bPackage, bBase, 0, rearIdentity, true, true));
            return exactInternalPhysicalPresentation(aPresentation, aTask, aPackage, aBase, rearDisplay, rearIdentity)
                    && exactInternalPhysicalPresentation(bPresentation, bTask, bPackage, bBase, 0, rearIdentity)
                    && aPresentation.optInt("internalDispatcherOwnerPid", -1) == processPair.aPid
                    && aPresentation.optInt("internalDispatcherOwnerUid", -1) == processPair.aUid
                    && bPresentation.optInt("internalDispatcherOwnerPid", -1) == processPair.bPid
                    && bPresentation.optInt("internalDispatcherOwnerUid", -1) == processPair.bUid
                    && exactAppProcessPair(aPackage, processPair.aPid, processPair.aUid,
                        bPackage, processPair.bPid, processPair.bUid)
                    && isPhysicalReplacementMoveGateCurrent(gate, operationId, aTask, aPackage, aBase,
                        aGeneration, bTask, bPackage, bBase, bGeneration, coordinatorGeneration,
                        ownerIdentity, rearDisplay, rearIdentity)
                    && rc5SelectedRearStateMatches(safeRearState(), rearDisplay, rearIdentity);
        } catch (Exception | LinkageError unknown) { return false; }
    }

    private static boolean exactMainReplacementCandidate(RearDisplayCastOccupancy.Snapshot snapshot,
            int task, String packageName, String base) {
        return PhysicalReplacementBottomFocus.exactVisibleMainTask(snapshot, task, packageName, base);
    }

    private static boolean containsTaskIdStrict(RearDisplayCastOccupancy.Snapshot snapshot, int taskId) {
        if (snapshot == null || snapshot.failure != null || snapshot.tasks == null) return true;
        for (RearDisplayCastOccupancy.Task row : snapshot.tasks)
            if (row == null || row.taskId == taskId) return true;
        return false;
    }

    private static boolean isRc5PhysicalFocusExperimentAvailable() {
        return isRc5PhysicalFocusExperimentAvailable(BuildConfig.DEBUG, BuildConfig.APPLICATION_ID);
    }

    static boolean isRc5PhysicalFocusExperimentAvailable(boolean debugBuild, String applicationId) {
        return debugBuild && applicationId != null && applicationId.endsWith(".debug");
    }

    private boolean rc5PhysicalFocusCallerAllowed() {
        if (!isRc5PhysicalFocusExperimentAvailable()) return false;
        try {
            int hostUid = RearSystemBridge.systemContext().getPackageManager()
                    .getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
            return DebugPhysicalBFocusTransferProbe.exactDebugHostUid(true, BuildConfig.APPLICATION_ID,
                    android.os.Binder.getCallingUid(), hostUid);
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private String rc5BottomInventoryFailure(RearDisplayCastOccupancy.Snapshot rear,
            RearDisplayCastOccupancy.Snapshot main, int aTask, int bTask, int rearDisplay,
            String identity, int aPid, int bPid) {
        try {
            if (!rc5BottomAInventoryReady(rear, rearDisplay, aTask,
                    RearDisplayCastOccupancy::isExactSystemLauncher))
                return "a_topology_mismatch";
            if (!exactMainFixtureTask(main, bTask)) return "b_main_identity_mismatch";
            if (!rc5PresentationPidMatches(aTask, "dev.remrss.sample.rc5physicala",
                    "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity",
                    rearDisplay, identity, aPid, true)) return "a_presentation_mismatch";
            if (!rc5PresentationPidMatches(bTask, "dev.remrss.sample.rc5physicalb",
                    "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity",
                    0, identity, bPid, true)) return "b_presentation_mismatch";
            if (!rc5ProcessPairCurrent(aPid, bPid)) return "process_pair_mismatch";
            return null;
        } catch (Exception | LinkageError unavailable) { return "inventory_check_failed"; }
    }

    static boolean rc5BottomAInventoryReady(RearDisplayCastOccupancy.Snapshot rear, int rearDisplay,
            int aTask, RearDisplayCastOccupancy.ExactLauncherMatcher desktopPermit) {
        return rear != null && rear.displayId == rearDisplay
                && DebugPhysicalBFocusTransferProbe.exactAWithValidatedSystemDesktop(rear, rearDisplay, aTask,
                    "dev.remrss.sample.rc5physicala",
                    "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity", desktopPermit);
    }

    static boolean rc5SelectedRearStateMatches(org.json.JSONObject selected, int expectedDisplay,
            String expectedIdentity) {
        if (selected == null) return false;
        ControlledPhysicalCastOperations.SelectedRearIdentity parsed =
                ControlledPhysicalCastOperations.parseSelectedRearIdentity(selected.toString());
        if (parsed == null || !parsed.selectedUnique.equals(parsed.selectedUnique.trim())
                || parsed.selectedUnique.length() < 3
                || "unknown".equalsIgnoreCase(parsed.selectedUnique)
                || "unavailable".equalsIgnoreCase(parsed.selectedUnique)
                || "error".equalsIgnoreCase(parsed.selectedUnique)
                || "null".equalsIgnoreCase(parsed.selectedUnique)) return false;
        return parsed.matches(expectedDisplay, expectedIdentity);
    }

    private boolean rc5RearPairAFrontBHidden(RearDisplayCastOccupancy.Snapshot rear,
            int aTask, int bTask, int rearDisplay, String identity,
            PhysicalReplacementBottomFocus.DesktopIdentity boundDesktop, int aPid, int bPid) {
        try {
            if (rear == null || rear.displayId != rearDisplay || boundDesktop == null) return false;
            DisplayRotationController.Result roots = DisplayRotationController.runCommand("am", "stack", "list");
            if (roots.exitCode != 0
                    || !RearSystemBridge.isExactSoleTaskRoot(roots.output, aTask,
                        "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity", rearDisplay)
                    || !RearSystemBridge.isExactSoleTaskRoot(roots.output, bTask,
                        "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity", rearDisplay)
                    || !rc5PresentationPidMatches(aTask, "dev.remrss.sample.rc5physicala",
                        "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity",
                        rearDisplay, identity, aPid, true)) return false;
            if (boundDesktop.present(rear)
                    && !RearSystemBridge.isExactSoleTaskRoot(roots.output, boundDesktop.taskId,
                        boundDesktop.baseComponent, rearDisplay)) return false;
            int[] currentPids = rc5CurrentProcessPidPair();
            return currentPids != null && DebugPhysicalBFocusTransferProbe.exactHiddenPairAndBoundPids(
                    rear, aTask, "dev.remrss.sample.rc5physicala",
                    "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity",
                    bTask, "dev.remrss.sample.rc5physicalb",
                    "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity",
                    rearDisplay, boundDesktop, RearDisplayCastOccupancy::isExactSystemLauncher,
                    aPid, currentPids[0], bPid, currentPids[1]);
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private static boolean rc5ProcessPairCurrent(int aPid, int bPid) throws Exception {
        int[] current = rc5CurrentProcessPidPair();
        return current != null && DebugPhysicalBFocusTransferProbe.exactProcessPidPair(
                aPid, current[0], bPid, current[1]);
    }

    /** One process snapshot binds both exact fixture processes to their resolved package UIDs. */
    private static int[] rc5CurrentProcessPidPair() throws Exception {
        android.content.Context context = RearSystemBridge.systemContext();
        android.content.pm.PackageManager pm = context.getPackageManager();
        int aUid = pm.getApplicationInfo("dev.remrss.sample.rc5physicala", 0).uid;
        int bUid = pm.getApplicationInfo("dev.remrss.sample.rc5physicalb", 0).uid;
        if (aUid <= 0 || bUid <= 0 || aUid == bUid) return null;
        android.app.ActivityManager activityManager = (android.app.ActivityManager)
                context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (activityManager == null) return null;
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                activityManager.getRunningAppProcesses();
        if (processes == null) return null;
        int aCount = 0, bCount = 0, actualA = -1, actualB = -1;
        boolean aMatches = false, bMatches = false;
        for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
            if (process == null) continue;
            if (process.uid == aUid) {
                aCount++;
                if ("dev.remrss.sample.rc5physicala".equals(process.processName)) {
                    aMatches = true; actualA = process.pid;
                }
            } else if (process.uid == bUid) {
                bCount++;
                if ("dev.remrss.sample.rc5physicalb".equals(process.processName)) {
                    bMatches = true; actualB = process.pid;
                }
            }
        }
        return aCount == 1 && bCount == 1 && aMatches && bMatches && actualA > 0 && actualB > 0
                ? new int[]{actualA, actualB} : null;
    }

    private static boolean exactMainFixtureTask(RearDisplayCastOccupancy.Snapshot main, int taskId) {
        if (main == null || main.displayId != 0 || main.failure != null || main.tasks == null) return false;
        int matches = 0;
        for (RearDisplayCastOccupancy.Task task : main.tasks) {
            if (task != null && task.taskId == taskId && task.userId == 0
                    && "dev.remrss.sample.rc5physicalb".equals(task.basePackage)
                    && "dev.remrss.sample.rc5physicalb".equals(task.topPackage)
                    && "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(task.baseComponent)
                    && "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(task.topComponent)
                    && Boolean.TRUE.equals(task.visible) && Boolean.TRUE.equals(task.requested)
                    && Boolean.FALSE.equals(task.sleeping)) matches++;
        }
        return matches == 1;
    }

    private boolean rc5PresentationPidMatches(int task, String packageName, String base, int display,
            String identity, int expectedPid, boolean requireReady) throws Exception {
        String queryIdentity = display == 0 && "dev.remrss.sample.rc5physicalb".equals(packageName)
                ? identity + "|rc5-main-focus-mode=observe" : identity;
        org.json.JSONObject result = new org.json.JSONObject(queryExactPhysicalPresentation(
                task, packageName, base, display, queryIdentity));
        return result.optInt("task", -1) == task && packageName.equals(result.optString("package"))
                && base.equals(result.optString("base")) && result.optInt("display", -1) == display
                && (result.optInt("selectedId", -1) + ":" + result.optString("selectedUnique")).equals(identity)
                && !result.optString("windowId", "").isBlank()
                && result.optInt("dispatcherOwnerPid", -1) == expectedPid
                && (!requireReady || ("ready".equals(result.optString("status"))
                    && result.optBoolean("drawn") && result.optBoolean("inputReady")));
    }

    private static org.json.JSONObject safeRearState() {
        try { return new org.json.JSONObject(RearDisplay.state()); }
        catch (Exception invalid) { return null; }
    }

    @Override public String returnPhysicalAForVirtualReplacement(int oldTaskId, String oldPackage,
            String oldBase, int rearDisplayId, String rearIdentity, String operationId, long ownerGeneration,
            String ownerIdentity, String sessionId, long sessionGeneration, int newTaskId, String newBase,
            String readerId, long readerGeneration, ISceneTaskActionGate actionGate) {
        if (actionGate == null || oldTaskId == newTaskId || rearDisplayId <= 0)
            return controlledPhysicalMoveResult("rejected", "invalid_replacement_authority");
        return moveControlledPhysicalTaskInternal(oldTaskId, oldPackage, oldBase, rearDisplayId, 0,
                rearIdentity, () -> {
                    if (!isExactRear(rearDisplayId, rearIdentity) || !isGateCurrent(actionGate)) return false;
                    try {
                        return VirtualCastTerminalStatus.matchesRunningPhysicalReplacement(
                                new org.json.JSONObject(virtualCast.status()), rearDisplayId, rearIdentity,
                                operationId, ownerGeneration, ownerIdentity, sessionId, sessionGeneration,
                                newTaskId, newBase, readerId, readerGeneration)
                                && isGateCurrent(actionGate);
                    } catch (Exception stale) { return false; }
                });
    }

    @Override public String returnPhysicalAAfterVirtualRollback(int oldTaskId, String oldPackage,
            String oldBase, int rearDisplayId, String rearIdentity, int newTaskId, String newPackage,
            String newBase, ISceneTaskActionGate actionGate) {
        if (actionGate == null || oldTaskId == newTaskId || rearDisplayId <= 0 || newTaskId <= 0
                || newPackage == null || newPackage.isBlank() || newBase == null || newBase.isBlank())
            return controlledPhysicalMoveResult("rejected", "invalid_replacement_authority");
        return moveControlledPhysicalTaskInternal(oldTaskId, oldPackage, oldBase, rearDisplayId, 0, rearIdentity,
                () -> {
                    if (!isExactRear(rearDisplayId, rearIdentity) || !isGateCurrent(actionGate)) return false;
                    try {
                        return canDispatchVirtualRollbackReturn(
                                RearDisplayCastOccupancyReader.readTypedOnly(0),
                                RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId), rearDisplayId,
                                oldTaskId, oldPackage, oldBase, newTaskId, newPackage, newBase,
                                new org.json.JSONObject(virtualCast.status()))
                                && isExactRear(rearDisplayId, rearIdentity) && isGateCurrent(actionGate);
                    } catch (Exception stale) { return false; }
                });
    }

    static boolean canDispatchVirtualRollbackReturn(RearDisplayCastOccupancy.Snapshot main,
            RearDisplayCastOccupancy.Snapshot rear, int rearDisplayId, int oldTaskId, String oldPackage,
            String oldBase, int newTaskId, String newPackage, String newBase, org.json.JSONObject virtual) {
        if (main == null || main.displayId != 0 || rear == null || rear.displayId != rearDisplayId
                || !ControlledPhysicalCastOperations.exactVirtualResourcesStopped(virtual)) return false;
        RearDisplayCastOccupancy.Task candidate = exactControlledPhysicalTask(main, newTaskId, newPackage, newBase);
        return candidate != null && newPackage.equals(candidate.topPackage)
                && RearDisplayCastOccupancy.containsOnlyExactTask(rear.toJson(), rearDisplayId, oldTaskId, oldPackage, oldBase);
    }

    @Override public String returnPhysicalAForPhysicalReplacement(int oldTaskId, String oldPackage,
            String oldBase, int rearDisplayId, String rearIdentity, String operationId, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity,
            IPhysicalReplacementMoveGate actionGate) {
        if (!validPhysicalReplacementMoveRequest(oldTaskId, oldPackage, oldBase, rearDisplayId, rearIdentity,
                operationId, oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                newCoordinatorGeneration, newOwnerIdentity) || actionGate == null)
            return controlledPhysicalMoveResult("rejected", "invalid_replacement_authority");
        return moveControlledPhysicalTaskInternal(oldTaskId, oldPackage, oldBase, rearDisplayId, 0,
                rearIdentity, () -> {
                    if (!isPhysicalReplacementMoveGateCurrent(actionGate, operationId, oldTaskId,
                            oldPackage, oldBase, oldGeneration, newTaskId, newPackage, newBase,
                            newGeneration, newCoordinatorGeneration, newOwnerIdentity,
                            rearDisplayId, rearIdentity)) return false;
                    RearDisplayCastOccupancy.Snapshot rear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                    RearDisplayCastOccupancy.Snapshot main = RearDisplayCastOccupancyReader.readTypedOnly(0);
                    return canDispatchPhysicalReplacementAReturn(rear, main, rearDisplayId,
                            oldTaskId, oldPackage, oldBase, newTaskId, newPackage, newBase)
                            && isExactRear(rearDisplayId, rearIdentity)
                            && isPhysicalReplacementMoveGateAuthorize(actionGate, operationId, oldTaskId,
                                oldPackage, oldBase, oldGeneration, newTaskId, newPackage, newBase,
                                newGeneration, newCoordinatorGeneration, newOwnerIdentity,
                                rearDisplayId, rearIdentity);
                }, () -> rejectPhysicalReplacementDispatch(actionGate, operationId, oldTaskId, oldPackage,
                        oldBase, oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                        newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity),
                        () -> recordPhysicalReplacementDispatch(actionGate, operationId, oldTaskId, oldPackage,
                            oldBase, oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                            newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity));
    }

    @Override public String restorePhysicalAForPhysicalReplacement(int oldTaskId, String oldPackage,
            String oldBase, int rearDisplayId, String rearIdentity, String operationId, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity,
            IPhysicalReplacementMoveGate actionGate) {
        if (oldTaskId <= 0 || newTaskId <= 0 || oldTaskId == newTaskId || oldGeneration <= 0L
                || newGeneration <= 0L || oldPackage == null || oldPackage.isBlank()
                || oldBase == null || oldBase.isBlank() || newPackage == null || newPackage.isBlank()
                || newBase == null || newBase.isBlank() || operationId == null || operationId.isBlank()
                || newCoordinatorGeneration <= 0L || newOwnerIdentity == null || newOwnerIdentity.isBlank()
                || rearDisplayId <= 0 || rearIdentity == null || rearIdentity.isBlank() || actionGate == null)
            return controlledPhysicalMoveResult("rejected", "invalid_replacement_authority");
        return moveControlledPhysicalTaskInternal(oldTaskId, oldPackage, oldBase, 0, rearDisplayId,
                rearIdentity, () -> {
                    if (!isPhysicalReplacementMoveGateCurrent(actionGate, operationId, oldTaskId,
                            oldPackage, oldBase, oldGeneration, newTaskId, newPackage, newBase,
                            newGeneration, newCoordinatorGeneration, newOwnerIdentity,
                            rearDisplayId, rearIdentity)) return false;
                    RearDisplayCastOccupancy.Snapshot main = RearDisplayCastOccupancyReader.readTypedOnly(0);
                    RearDisplayCastOccupancy.Snapshot rear = RearDisplayCastOccupancyReader.readTypedOnly(rearDisplayId);
                    return canDispatchPhysicalReplacementRestore(main, rear, rearDisplayId,
                            oldTaskId, oldPackage, oldBase, newTaskId, newPackage, newBase)
                            && isExactRear(rearDisplayId, rearIdentity)
                            && isPhysicalReplacementMoveGateAuthorize(actionGate, operationId, oldTaskId,
                                oldPackage, oldBase, oldGeneration, newTaskId, newPackage, newBase,
                                newGeneration, newCoordinatorGeneration, newOwnerIdentity,
                                rearDisplayId, rearIdentity);
                }, () -> rejectPhysicalReplacementDispatch(actionGate, operationId, oldTaskId,
                        oldPackage, oldBase, oldGeneration, newTaskId, newPackage, newBase,
                        newGeneration, newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity),
                        () -> recordPhysicalReplacementDispatch(actionGate, operationId, oldTaskId, oldPackage,
                            oldBase, oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                            newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity));
    }

    private static boolean validPhysicalReplacementMoveRequest(int oldTaskId, String oldPackage, String oldBase,
            int rearDisplayId, String rearIdentity, String operationId, long oldGeneration, int newTaskId,
            String newPackage, String newBase, long newGeneration, long newCoordinatorGeneration,
            String newOwnerIdentity) {
        return oldTaskId > 0 && newTaskId > 0 && oldTaskId != newTaskId && oldGeneration > 0L
                && newGeneration > 0L && newCoordinatorGeneration > 0L
                && oldPackage != null && !oldPackage.isBlank() && oldBase != null && !oldBase.isBlank()
                && newPackage != null && !newPackage.isBlank() && newBase != null && !newBase.isBlank()
                && operationId != null && !operationId.isBlank() && newOwnerIdentity != null
                && !newOwnerIdentity.isBlank() && rearDisplayId > 0 && rearIdentity != null && !rearIdentity.isBlank();
    }

    static boolean canDispatchPhysicalReplacementAReturn(RearDisplayCastOccupancy.Snapshot rear,
            RearDisplayCastOccupancy.Snapshot main, int rearDisplayId, int oldTaskId, String oldPackage,
            String oldBase, int newTaskId, String newPackage, String newBase) {
        if (!usableTypedSnapshot(rear, rearDisplayId) || !usableTypedSnapshot(main, 0)) return false;
        try {
            return "ready".equals(ControlledPhysicalCastOperations.replacementCandidateReadinessReason(
                    new org.json.JSONObject(rear.toJson()), oldTaskId, oldPackage, oldBase,
                    newTaskId, newPackage, newBase, rearDisplayId))
                    && !containsTaskId(main, oldTaskId) && !containsTaskId(main, newTaskId);
        } catch (org.json.JSONException malformed) { return false; }
    }

    static boolean canDispatchPhysicalReplacementRestore(RearDisplayCastOccupancy.Snapshot main,
            RearDisplayCastOccupancy.Snapshot rear, int rearDisplayId, int oldTaskId, String oldPackage,
            String oldBase, int newTaskId, String newPackage, String newBase) {
        RearDisplayCastOccupancy.Task a = exactControlledPhysicalTask(main, oldTaskId, oldPackage, oldBase);
        RearDisplayCastOccupancy.Task b = exactControlledPhysicalTask(main, newTaskId, newPackage, newBase);
        return usableTypedSnapshot(main, 0) && usableTypedSnapshot(rear, rearDisplayId)
                && a != null && b != null && newPackage.equals(b.topPackage)
                && RearDisplayCastOccupancy.emptyOrOnlySystemLauncher(rear.toJson(), rearDisplayId);
    }

    private static boolean usableTypedSnapshot(RearDisplayCastOccupancy.Snapshot snapshot, int expectedDisplay) {
        return snapshot != null && snapshot.failure == null && snapshot.displayId == expectedDisplay
                && snapshot.displayState == android.view.Display.STATE_ON
                && snapshot.powerBefore == android.view.Display.STATE_ON
                && snapshot.powerAfter == android.view.Display.STATE_ON && snapshot.tasks != null;
    }

    private static boolean containsTaskId(RearDisplayCastOccupancy.Snapshot snapshot, int taskId) {
        if (snapshot == null || snapshot.tasks == null) return true;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks)
            if (task == null || task.taskId == taskId) return true;
        return false;
    }

    private static boolean isPhysicalReplacementMoveGateCurrent(IPhysicalReplacementMoveGate gate,
            String operationId, int oldTaskId, String oldPackage, String oldBase, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity) {
        try { return gate != null && gate.isCurrent(operationId, oldTaskId, oldPackage, oldBase,
                oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity); }
        catch (RemoteException unavailable) { return false; }
    }

    private static boolean isPhysicalReplacementMoveGateAuthorize(IPhysicalReplacementMoveGate gate,
            String operationId, int oldTaskId, String oldPackage, String oldBase, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity) {
        try { return gate != null && gate.authorizeDispatch(operationId, oldTaskId, oldPackage, oldBase,
                oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity); }
        catch (RemoteException unavailable) { return false; }
    }

    private static boolean rejectPhysicalReplacementDispatch(IPhysicalReplacementMoveGate gate,
            String operationId, int oldTaskId, String oldPackage, String oldBase, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity) {
        try { return gate != null && gate.onRejectedBeforeSend(operationId, oldTaskId, oldPackage, oldBase,
                oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity); }
        catch (RemoteException ignored) { return false; }
    }

    private static boolean recordPhysicalReplacementDispatch(IPhysicalReplacementMoveGate gate,
            String operationId, int oldTaskId, String oldPackage, String oldBase, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity) {
        try { return gate != null && gate.onMoveMayHaveBeenSent(operationId, oldTaskId, oldPackage, oldBase,
                oldGeneration, newTaskId, newPackage, newBase, newGeneration,
                newCoordinatorGeneration, newOwnerIdentity, rearDisplayId, rearIdentity); }
        catch (RemoteException ignored) { return false; }
    }

    private String moveControlledPhysicalTaskInternal(int taskId, String packageName, String baseComponent,
            int sourceDisplayId, int targetDisplayId, String expectedRearIdentity,
            java.util.function.BooleanSupplier dispatchGate) {
        return moveControlledPhysicalTaskInternal(taskId, packageName, baseComponent,
                sourceDisplayId, targetDisplayId, expectedRearIdentity, dispatchGate, null, null);
    }

    private String moveControlledPhysicalTaskInternal(int taskId, String packageName, String baseComponent,
            int sourceDisplayId, int targetDisplayId, String expectedRearIdentity,
            java.util.function.BooleanSupplier dispatchGate, java.util.function.BooleanSupplier onRejectedBeforeSend,
            java.util.function.BooleanSupplier onMayHaveBeenSent) {
        try {
            if (taskId <= 0 || packageName == null || packageName.isBlank() || baseComponent == null
                    || baseComponent.isBlank() || expectedRearIdentity == null || expectedRearIdentity.isBlank())
                return controlledPhysicalMoveResult("rejected", "invalid_arguments");
            synchronized (controlledPhysicalMoveLock) {
                org.json.JSONObject selected = new org.json.JSONObject(RearDisplay.state());
                int selectedId = selected.optInt("selectedId", -1);
                String unique = selected.optString("selectedUnique", "");
                if (selected.has("error") || selectedId <= 0 || unique.isBlank()
                        || !expectedRearIdentity.equals(selectedId + ":" + unique)
                        || !((sourceDisplayId == 0 && targetDisplayId == selectedId)
                            || (sourceDisplayId == selectedId && targetDisplayId == 0)))
                    return controlledPhysicalMoveResult("rejected", "rear_role_or_identity_mismatch");
                RearDisplayCastOccupancy.Snapshot before = RearDisplayCastOccupancyReader.readTypedOnly(sourceDisplayId);
                RearDisplayCastOccupancy.Task task = exactControlledPhysicalTask(before, taskId, packageName, baseComponent);
                if (task == null) return controlledPhysicalMoveResult("rejected", "source_snapshot_mismatch");
                RearSystemBridge.SoleRootMoveDispatch dispatch = RearSystemBridge.moveSoleTaskRoot(
                        taskId, baseComponent, sourceDisplayId, targetDisplayId, dispatchGate);
                if (dispatch == RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND) {
                    boolean cleanupConfirmed = onRejectedBeforeSend == null || onRejectedBeforeSend.getAsBoolean();
                    Log.i(TAG, "controlled_physical_move dispatch=rejected_before_send marker_cleanup="
                            + (cleanupConfirmed ? "confirmed" : "unconfirmed"));
                    return controlledPhysicalMoveResult("rejected", cleanupConfirmed
                            ? "root_precheck_rejected" : "rejected_marker_cleanup_unconfirmed");
                }
                if (onMayHaveBeenSent != null && !onMayHaveBeenSent.getAsBoolean()) {
                    Log.w(TAG, "controlled_physical_move dispatch=" + dispatch.name().toLowerCase(java.util.Locale.ROOT)
                            + " durable_receipt=unconfirmed");
                    return controlledPhysicalMoveResult("unknown", "dispatch_receipt_unconfirmed");
                }
                ControlledPhysicalMoveReadback.Dispatch readbackDispatch = dispatch
                        == RearSystemBridge.SoleRootMoveDispatch.SENT
                        ? ControlledPhysicalMoveReadback.Dispatch.SENT
                        : ControlledPhysicalMoveReadback.Dispatch.MAY_HAVE_BEEN_SENT;
                ControlledPhysicalMoveReadback.Outcome readback = ControlledPhysicalMoveReadback.confirm(
                        readbackDispatch,
                        () -> readControlledPhysicalMovePlacement(taskId, packageName, baseComponent,
                                sourceDisplayId, targetDisplayId, selectedId, unique),
                        new ControlledPhysicalMoveReadback.Clock() {
                            @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
                            @Override public void sleep(long millis) throws InterruptedException {
                                Thread.sleep(millis);
                            }
                        }, 350L, 3, 100L);
                Log.i(TAG, "controlled_physical_move dispatch=" + dispatch.name().toLowerCase(java.util.Locale.ROOT)
                        + " readback=" + readback.name().toLowerCase(java.util.Locale.ROOT));
                if (readback == ControlledPhysicalMoveReadback.Outcome.CONFIRMED)
                    return controlledPhysicalMoveResult("moved", "exact_target_readback");
                return controlledPhysicalMoveResult("unknown", readback == ControlledPhysicalMoveReadback.Outcome.REJECTED
                        ? "move_not_dispatched" : "exact_target_readback_unverified");
            }
        } catch (Exception failure) {
            return controlledPhysicalMoveResult("unknown", "privileged_query_failed");
        }
    }

    private final Object controlledPhysicalMoveLock = new Object();
    private final DebugPhysicalBFocusTransferProbe.BottomBindingSlot rc5BottomDesktopBindings =
            new DebugPhysicalBFocusTransferProbe.BottomBindingSlot(30_000L);

    private boolean storeRc5BottomDesktopBinding(int aTask, long generation, int bTask, int display,
            String identity, String operation, PhysicalReplacementBottomFocus.DesktopIdentity desktop) {
        return rc5BottomDesktopBindings.store(aTask, generation, bTask, display, identity, operation,
                desktop, android.os.SystemClock.elapsedRealtime());
    }

    private PhysicalReplacementBottomFocus.DesktopIdentity takeRc5BottomDesktopBinding(int aTask,
            long generation, int bTask, int display, String identity, String operation) {
        return rc5BottomDesktopBindings.take(aTask, generation, bTask, display, identity, operation,
                android.os.SystemClock.elapsedRealtime());
    }

    private static RearDisplayCastOccupancy.Task exactControlledPhysicalTask(
            RearDisplayCastOccupancy.Snapshot snapshot, int taskId, String packageName, String baseComponent) {
        if (snapshot == null || snapshot.failure != null) return null;
        RearDisplayCastOccupancy.Task found = null;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task.taskId == taskId) {
                if (found != null || !packageName.equals(task.basePackage)
                        || !baseComponent.equals(task.baseComponent)) return null;
                found = task;
            }
        }
        return found;
    }
    private static String controlledPhysicalMoveResult(String status, String reason) {
        try { return new org.json.JSONObject().put("status", status).put("reason", reason).toString(); }
        catch (org.json.JSONException impossible) { return "{\"status\":\"unknown\"}"; }
    }

    private ControlledPhysicalMoveReadback.Placement readControlledPhysicalMovePlacement(int taskId,
            String packageName, String baseComponent, int sourceDisplayId, int targetDisplayId,
            int expectedSelectedId, String expectedUnique) throws Exception {
        org.json.JSONObject selected = new org.json.JSONObject(RearDisplay.state());
        if (!selectedRearStateMatches(selected, expectedSelectedId, expectedUnique))
            return ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN;
        java.util.List<SceneTaskIdentity.Record> rows = RearSystemBridge.runningTaskSnapshot();
        if (!"present".equals(exactTaskSnapshotDisposition(rows, taskId)))
            return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
        SceneTaskIdentity.Record exact = null;
        for (SceneTaskIdentity.Record row : rows) {
            if (row.taskId != taskId) continue;
            if (exact != null) return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
            exact = row;
        }
        if (exact == null) return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
        String actualPackage = packageOfComponent(exact.baseComponent);
        if (!packageName.equals(actualPackage) || !baseComponent.equals(exact.baseComponent)
                || (exact.displayId != sourceDisplayId && exact.displayId != targetDisplayId))
            return ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN;
        // Source is only a pending observation; its exact root was verified immediately before dispatch.
        // Do not spend a shell query until the typed task is actually observed on the target.
        if (exact.displayId == sourceDisplayId) {
            org.json.JSONObject selectedAfterSource = new org.json.JSONObject(RearDisplay.state());
            return finalizeControlledMoveReadback(ControlledPhysicalMoveReadback.Placement.SOURCE_EXACT,
                    selectedAfterSource, expectedSelectedId, expectedUnique);
        }
        DisplayRotationController.Result roots = DisplayRotationController.runCommand("am", "stack", "list");
        ControlledPhysicalMoveReadback.Placement classified = classifyControlledPhysicalMovePlacement(
                rows, roots.output, roots.exitCode == 0,
                taskId, packageName, baseComponent, sourceDisplayId, targetDisplayId);
        // Selected-display identity is the final read in the sample, after root exclusivity.
        org.json.JSONObject selectedAfter = new org.json.JSONObject(RearDisplay.state());
        return finalizeControlledMoveReadback(classified, selectedAfter, expectedSelectedId, expectedUnique);
    }

    static ControlledPhysicalMoveReadback.Placement finalizeControlledMoveReadback(
            ControlledPhysicalMoveReadback.Placement observed, org.json.JSONObject selected,
            int expectedSelectedId, String expectedUnique) {
        if (observed == null || observed == ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN
                || !selectedRearStateMatches(selected, expectedSelectedId, expectedUnique))
            return ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN;
        return observed;
    }

    private static boolean selectedRearStateMatches(org.json.JSONObject selected,
            int expectedSelectedId, String expectedUnique) {
        return selected != null && !selected.has("error")
                && selected.optInt("selectedId", -1) == expectedSelectedId
                && expectedUnique.equals(selected.optString("selectedUnique", ""));
    }

    static ControlledPhysicalMoveReadback.Placement classifyControlledPhysicalMovePlacement(
            java.util.List<SceneTaskIdentity.Record> rows, String rootSnapshot, boolean rootSnapshotKnown,
            int taskId, String packageName, String baseComponent, int sourceDisplayId, int targetDisplayId) {
        if (!"present".equals(exactTaskSnapshotDisposition(rows, taskId)))
            return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
        SceneTaskIdentity.Record exact = null;
        for (SceneTaskIdentity.Record row : rows) {
            if (row.taskId != taskId) continue;
            if (exact != null) return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
            exact = row;
        }
        if (exact == null) return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
        String actualPackage = packageOfComponent(exact.baseComponent);
        if (!packageName.equals(actualPackage) || !baseComponent.equals(exact.baseComponent)
                || (exact.displayId != sourceDisplayId && exact.displayId != targetDisplayId))
            return ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN;
        if (exact.displayId == sourceDisplayId) return ControlledPhysicalMoveReadback.Placement.SOURCE_EXACT;
        if (!rootSnapshotKnown || rootSnapshot == null)
            return ControlledPhysicalMoveReadback.Placement.UNKNOWN;
        if (!RearSystemBridge.isExactSoleTaskRoot(rootSnapshot, taskId, baseComponent, exact.displayId))
            return ControlledPhysicalMoveReadback.Placement.OTHER_OR_FOREIGN;
        return ControlledPhysicalMoveReadback.Placement.TARGET_EXACT;
    }

    @Override public String queryExactTaskPlacement(int taskId) {
        try {
            if (taskId <= 0) return exactTaskPlacementResult("unknown", -1, null, null, -1, null);
            org.json.JSONObject selected = new org.json.JSONObject(RearDisplay.state());
            int selectedId = selected.optInt("selectedId", -1);
            String unique = selected.optString("selectedUnique", "");
            if (selected.has("error") || selectedId <= 0 || unique.isBlank())
                return exactTaskPlacementResult("unknown", -1, null, null, -1, null);
            java.util.List<SceneTaskIdentity.Record> rows = RearSystemBridge.runningTaskSnapshot();
            if (!"present".equals(exactTaskSnapshotDisposition(rows, taskId)))
                return exactTaskPlacementResult("unknown", -1, null, null, selectedId, unique);
            SceneTaskIdentity.Record found = null;
            for (SceneTaskIdentity.Record row : rows) {
                if (row.taskId != taskId) continue;
                if (found != null) return exactTaskPlacementResult("unknown", -1, null, null, selectedId, unique);
                found = row;
            }
            // getTasks has no completeness signal on older platform contracts. A miss may be an
            // empty/truncated/filtered snapshot, so never promote it to proof of task absence.
            if (found == null) return exactTaskPlacementResult("unknown", -1, null, null, selectedId, unique);
            String pkg = packageOfComponent(found.baseComponent);
            if (pkg == null || found.baseComponent == null)
                return exactTaskPlacementResult("unknown", -1, null, null, selectedId, unique);
            DisplayRotationController.Result roots = DisplayRotationController.runCommand("am", "stack", "list");
            boolean rootSole = roots.exitCode == 0
                    && RearSystemBridge.isSoleTaskRoot(roots.output, taskId, found.displayId);
            org.json.JSONObject out = new org.json.JSONObject().put("status", "present")
                    .put("task", taskId).put("display", found.displayId)
                    .put("package", pkg).put("base", found.baseComponent).put("top", found.topComponent)
                    .put("selectedId", selectedId).put("selectedUnique", unique)
                    .put("rootSole", rootSole).put("rootSnapshotKnown", roots.exitCode == 0);
            return out.toString();
        } catch (Exception unavailable) { return exactTaskPlacementResult("unknown", -1, null, null, -1, null); }
    }

    /** A single bounded WMS/InputDispatcher sample; no polling, user-content capture, or fallback. */
    @Override public String queryExactPhysicalPresentation(int taskId, String packageName,
            String baseComponent, int displayId, String expectedRearIdentity) {
        return queryExactPhysicalPresentationInternal(taskId, packageName, baseComponent,
                displayId, expectedRearIdentity, false, false);
    }

    /** Private transaction proof: MAIN is allowed only inside the one authenticated P→P RPC. */
    private String queryExactPhysicalPresentationInternal(int taskId, String packageName,
            String baseComponent, int displayId, String expectedRearIdentity,
            boolean allowMainForInternalTransaction, boolean includeInternalOwnerPid) {
        return queryExactPhysicalPresentationInternal(taskId, packageName, baseComponent, displayId,
                expectedRearIdentity, allowMainForInternalTransaction, includeInternalOwnerPid, Long.MAX_VALUE);
    }

    private String queryExactPhysicalPresentationInternal(int taskId, String packageName,
            String baseComponent, int displayId, String expectedRearIdentity,
            boolean allowMainForInternalTransaction, boolean includeInternalOwnerPid, long outerDeadlineNanos) {
        try {
            final boolean mainFocusProbe = BuildConfig.DEBUG && displayId == 0
                    && "dev.remrss.sample.rc5physicalb".equals(packageName)
                    && "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity".equals(baseComponent);
            String sampleRearIdentity = expectedRearIdentity;
            String focusProbeMode = null;
            final String focusModeMarker = "|rc5-main-focus-mode=";
            if (mainFocusProbe && expectedRearIdentity != null) {
                int marker = expectedRearIdentity.lastIndexOf(focusModeMarker);
                if (marker >= 0) {
                    String requestedMode = expectedRearIdentity.substring(marker + focusModeMarker.length());
                    if (!"observe".equals(requestedMode) && !"gated".equals(requestedMode))
                        return physicalPresentationUnknown("invalid_request");
                    focusProbeMode = requestedMode;
                    sampleRearIdentity = expectedRearIdentity.substring(0, marker);
                }
            }
            if (taskId <= 0 || packageName == null || packageName.isBlank() || baseComponent == null
                    || baseComponent.isBlank() || displayId < 0
                    || (displayId == 0 && !mainFocusProbe && !allowMainForInternalTransaction)
                    || sampleRearIdentity == null || sampleRearIdentity.isBlank())
                return physicalPresentationUnknown("invalid_request");
            // One presentation sample shares its bounded budget across shell probes. The
            // preceding platform task snapshot/Binder reads are synchronous and cannot be
            // preempted here; their actual call duration remains part of the caller's bound.
            final long sampleDeadlineNanos = Math.min(outerDeadlineNanos,
                    System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    "gated".equals(focusProbeMode)
                            ? DebugPhysicalReplacementMainFocusGate.TIMEOUT_MS : 4_000L));
            if (outerDeadlineNanos != Long.MAX_VALUE) requireBottomObservationTime(sampleDeadlineNanos);
            org.json.JSONObject selectedBefore = new org.json.JSONObject(RearDisplay.state());
            if (selectedBefore.has("error") || !sampleRearIdentity.equals(selectedBefore.optInt("selectedId", -1)
                    + ":" + selectedBefore.optString("selectedUnique")))
                return physicalPresentationUnknown("rear_identity_changed");
            if (outerDeadlineNanos != Long.MAX_VALUE) requireBottomObservationTime(sampleDeadlineNanos);
            java.util.List<SceneTaskIdentity.Record> rows = RearSystemBridge.runningTaskSnapshot();
            if (!"present".equals(exactTaskSnapshotDisposition(rows, taskId)))
                return physicalPresentationUnknown("exact_task_identity_unverified");
            SceneTaskIdentity.Record exact = null;
            for (SceneTaskIdentity.Record row : rows) if (row.taskId == taskId) exact = row;
            if (exact == null || exact.displayId != displayId || !baseComponent.equals(exact.baseComponent)
                    || !packageName.equals(packageOfComponent(exact.baseComponent)))
                return physicalPresentationUnknown("exact_task_identity_unverified");
            DisplayRotationController.Result roots = runCommandWithin(sampleDeadlineNanos, "am", "stack", "list");
            if (roots.exitCode != 0 || !RearSystemBridge.isExactSoleTaskRoot(roots.output,
                    taskId, baseComponent, displayId))
                return physicalPresentationUnknown("exact_task_identity_unverified");
            DisplayRotationController.Result uid = runCommandWithin(sampleDeadlineNanos,
                    "pm", "list", "packages", "-U", packageName);
            if (uid.exitCode != 0) return physicalPresentationUnknown("package_uid_unavailable");
            java.util.regex.Matcher uidMatch = java.util.regex.Pattern.compile(
                    "(?m)^package:" + java.util.regex.Pattern.quote(packageName) + "\\s+uid:(\\d+)\\s*$").matcher(uid.output);
            if (!uidMatch.find()) return physicalPresentationUnknown("package_uid_unavailable");
            String uidValue = uidMatch.group(1);
            if (uidMatch.find()) return physicalPresentationUnknown("package_uid_ambiguous");
            int expectedUid = Integer.parseInt(uidValue);
            DisplayRotationController.Result windows = runCommandWithin(sampleDeadlineNanos,
                    "dumpsys", "-t", "2", "window", "-a");
            boolean samplePhysicalBClock = false;
            try {
                samplePhysicalBClock = BuildConfig.DEBUG && displayId > 0
                        && "b".equals(PhysicalTaskPresentationEvidence.fixtureLane(packageName, baseComponent));
            } catch (RuntimeException | LinkageError diagnosticUnavailable) { }
            long inputEpochStartMs = 0L, inputEpochEndMs = 0L;
            long inputElapsedStartMs = 0L, inputElapsedEndMs = 0L;
            long inputUptimeStartMs = 0L, inputUptimeEndMs = 0L;
            if (samplePhysicalBClock) {
                try {
                    inputEpochStartMs = System.currentTimeMillis();
                    inputElapsedStartMs = android.os.SystemClock.elapsedRealtime();
                    inputUptimeStartMs = android.os.SystemClock.uptimeMillis();
                } catch (RuntimeException | LinkageError diagnosticUnavailable) {
                    samplePhysicalBClock = false;
                }
            }
            DisplayRotationController.Result input = runCommandWithin(sampleDeadlineNanos,
                    "dumpsys", "-t", "2", "input");
            if (samplePhysicalBClock) {
                try {
                    inputElapsedEndMs = android.os.SystemClock.elapsedRealtime();
                    inputUptimeEndMs = android.os.SystemClock.uptimeMillis();
                    inputEpochEndMs = System.currentTimeMillis();
                } catch (RuntimeException | LinkageError diagnosticUnavailable) {
                    samplePhysicalBClock = false;
                }
            }
            if (windows.exitCode != 0 || input.exitCode != 0) return physicalPresentationUnknown("window_probe_unavailable");
            if (outerDeadlineNanos != Long.MAX_VALUE) requireBottomObservationTime(sampleDeadlineNanos);
            org.json.JSONObject selectedAfter = new org.json.JSONObject(RearDisplay.state());
            if (selectedAfter.has("error") || !sampleRearIdentity.equals(selectedAfter.optInt("selectedId", -1)
                    + ":" + selectedAfter.optString("selectedUnique")))
                return physicalPresentationUnknown("rear_identity_changed");
            final PhysicalTaskPresentationEvidence.Result evidence;
            try {
                PhysicalTaskPresentationEvidence.InputSampleClock sampleClock = samplePhysicalBClock
                        && displayId == selectedAfter.optInt("selectedId", -1)
                        ? new PhysicalTaskPresentationEvidence.InputSampleClock(inputEpochStartMs,
                            inputEpochEndMs, inputElapsedStartMs, inputElapsedEndMs,
                            inputUptimeStartMs, inputUptimeEndMs) : null;
                evidence = PhysicalTaskPresentationEvidence.evaluate(
                        windows.output, input.output, taskId, packageName, baseComponent, displayId, expectedUid,
                        BuildConfig.DEBUG, (mainFocusProbe || allowMainForInternalTransaction)
                                ? selectedAfter.optInt("selectedId", -1) : displayId,
                        sampleClock, includeInternalOwnerPid);
                if (BuildConfig.DEBUG && !evidence.ready && evidence.debugStructure != null)
                    Log.d(TAG, "physical_presentation_structure reason=" + evidence.reason
                            + " " + evidence.debugStructure);
            } catch (LinkageError parserUnavailable) {
                // ICU may reject a regex at class initialization on a specific Android build.
                // Fail this proof closed, but keep the privileged service alive for compensation.
                Log.w(TAG, "Exact presentation parser unavailable; rejecting this sample");
                return physicalPresentationUnknown("presentation_parser_unavailable");
            }
            org.json.JSONObject out = new org.json.JSONObject().put("status", evidence.ready ? "ready" : "unknown")
                    .put("reason", evidence.reason).put("task", taskId).put("package", packageName)
                    .put("base", baseComponent).put("display", displayId)
                    .put("selectedId", selectedAfter.optInt("selectedId", -1))
                    .put("selectedUnique", selectedAfter.optString("selectedUnique"))
                    .put("windowId", evidence.windowId).put("drawn", evidence.ready).put("inputReady", evidence.ready);
            if (BuildConfig.DEBUG && evidence.dispatcherOwnerPid > 0
                    && PhysicalTaskPresentationEvidence.fixtureLane(packageName, baseComponent) != null)
                out.put("dispatcherOwnerPid", evidence.dispatcherOwnerPid);
            if (includeInternalOwnerPid && evidence.dispatcherOwnerPid > 0)
                out.put("internalDispatcherOwnerPid", evidence.dispatcherOwnerPid)
                        .put("internalDispatcherOwnerUid", expectedUid);
            if (mainFocusProbe) out.put("rootSnapshotKnown", roots.exitCode == 0).put("rootSole", true)
                    .put("topExact", baseComponent.equals(exact.topComponent));
            if (BuildConfig.DEBUG && evidence.routeStructure != null) {
                try {
                    String lane = PhysicalTaskPresentationEvidence.fixtureLane(packageName, baseComponent);
                    if (lane != null) out.put("routeProbeV1", "lane=" + lane
                            + " service_contract=" + RUNTIME_USER_SERVICE_VERSION
                            + " " + evidence.routeStructure);
                } catch (org.json.JSONException | RuntimeException | LinkageError diagnosticUnavailable) {
                    // Optional diagnostics must not change the exact presentation decision.
                }
            }
            if (BuildConfig.DEBUG && evidence.presentationSampleV1 != null) {
                try { out.put("presentationSampleV1", evidence.presentationSampleV1); }
                catch (org.json.JSONException | RuntimeException | LinkageError diagnosticUnavailable) { }
            }
            if (BuildConfig.DEBUG && evidence.presentationLayerBindingV2 != null) {
                try { out.put("presentationLayerBindingV2", evidence.presentationLayerBindingV2); }
                catch (org.json.JSONException | RuntimeException | LinkageError diagnosticUnavailable) { }
            }
            return out.toString();
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return physicalPresentationUnknown("probe_unavailable");
        }
    }
    private static String physicalPresentationUnknown(String reason) {
        try { return new org.json.JSONObject().put("status", "unknown").put("reason", reason).toString(); }
        catch (org.json.JSONException impossible) { return "{\"status\":\"unknown\"}"; }
    }

    private static DisplayRotationController.Result runCommandWithin(long deadlineNanos,
            String... command) throws Exception {
        if (System.nanoTime() >= deadlineNanos)
            throw new java.io.IOException("Presentation sample budget expired before command start");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        java.io.InputStream stream = process.getInputStream();
        java.util.concurrent.FutureTask<String> output = new java.util.concurrent.FutureTask<>(() -> {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int size;
            while ((size = stream.read(buffer)) != -1) {
                if (bytes.size() + size > 1024 * 1024) throw new java.io.IOException("Probe output too large");
                bytes.write(buffer, 0, size);
            }
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        });
        Thread reader = new Thread(output, "physical-presentation-output");
        reader.setDaemon(true);
        reader.start();
        try {
            while (true) {
                try {
                    int exit = process.exitValue();
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) throw new java.io.IOException("Presentation sample budget expired");
                    return new DisplayRotationController.Result(exit, output.get(remaining,
                            java.util.concurrent.TimeUnit.NANOSECONDS));
                } catch (IllegalThreadStateException running) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) throw new java.io.IOException("Presentation sample budget expired");
                    Thread.sleep(Math.min(10L, Math.max(1L,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
                }
            }
        } finally {
            process.destroy();
            try { stream.close(); } catch (Exception ignored) { }
            output.cancel(true);
        }
    }
    private static String exactTaskPlacementResult(String status, int displayId, String pkg, String base,
            int selectedId, String selectedUnique) {
        try { return new org.json.JSONObject().put("status", status).put("display", displayId)
                .put("package", pkg).put("base", base).put("selectedId", selectedId)
                .put("selectedUnique", selectedUnique).toString(); }
        catch (org.json.JSONException impossible) { return "{\"status\":\"unknown\"}"; }
    }
    /** A found exact row proves presence; a miss is never proof of absence without an API completeness bit. */
    static String exactTaskSnapshotDisposition(java.util.List<SceneTaskIdentity.Record> rows, int taskId) {
        if (rows == null || taskId <= 0) return "unknown";
        SceneTaskIdentity.Record found = null;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (SceneTaskIdentity.Record row : rows) {
            if (row == null || row.taskId <= 0 || row.displayId < 0) return "unknown";
            if (!seen.add(row.taskId)) return "unknown";
            if (row.taskId == taskId) found = row;
        }
        return found != null && found.baseComponent != null && packageOfComponent(found.baseComponent) != null
                ? "present" : "unknown";
    }
    private static String packageOfComponent(String component) {
        if (component == null) return null;
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : null;
    }

    private static boolean isTaskFocusGateCurrent(ITaskFocusActionGate gate, int taskId,
            String expectedBaseComponent, String expectedTopComponent) {
        try { return gate.isCurrent(taskId, expectedBaseComponent, expectedTopComponent); }
        catch (Exception stale) { return false; }
    }

    static boolean isExactMainTaskFocusSnapshot(RearDisplayCastOccupancy.Snapshot snapshot, int taskId,
            String expectedBaseComponent, String expectedTopComponent, boolean requireVisible) {
        if (snapshot == null || snapshot.failure != null || snapshot.displayId != 0
                || snapshot.displayState != android.view.Display.STATE_ON
                || snapshot.powerBefore != android.view.Display.STATE_ON
                || snapshot.powerAfter != android.view.Display.STATE_ON || snapshot.tasks == null) return false;
        RearDisplayCastOccupancy.Task selected = null;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task == null || task.taskId != taskId) continue;
            if (selected != null) return false;
            selected = task;
        }
        if (selected == null || !sameCanonicalComponent(selected.baseComponent, expectedBaseComponent)
                || !sameCanonicalComponent(selected.topComponent, expectedTopComponent)
                || selected.visible == null || selected.requested == null || selected.sleeping == null) return false;
        if (Boolean.TRUE.equals(selected.sleeping)) return false;
        if (requireVisible)
            return Boolean.TRUE.equals(selected.visible) && Boolean.TRUE.equals(selected.requested);
        // The host's authenticated Mod chooser may focus a clearly hidden task, but a mixed
        // or unknown visibility/requested pair is never sufficient evidence of that task.
        return selected.visible.equals(selected.requested);
    }

    /**
     * 根据taskId获取包名（辅助方法）
     */
    @Override public String getPackageNameFromTaskId(int taskId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=" + taskId + ":") && line.contains("/")) {
                    // 解析: taskId=1471: com.example.app/...
                    int pkgStart = line.indexOf(':') + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd > pkgStart) {
                        String packageName = line.substring(pkgStart, pkgEnd).trim();
                        reader.close();
                        process.destroy();
                        return packageName;
                    }
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting package name from taskId", e);
            return null;
        }
    }

    @Override
    public boolean launchWakeActivity(int displayId) throws RemoteException {
        try {
            long startTime = System.currentTimeMillis();

            // 使用am start命令在指定display上启动RearScreenWakeupActivity
            // --display参数指定目标display
            // 注意：RearScreenWakeupActivity使用FLAG_TURN_SCREEN_ON点亮屏幕
            String cmd = "am start --display " + displayId + 
                        " -n " + BuildConfig.APPLICATION_ID + "/" + RearScreenWakeupActivity.class.getName();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 读取输出
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

            }
            reader.close();
            
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in launchWakeActivity", e);
            return false;
        }
    }
    
    @Override
    public boolean disableSubScreenLauncher() throws RemoteException {
        try {

            // 强制停止进程（进程可能会自动重启，需要持续杀死）
            String killCmd = "am force-stop com.xiaomi.subscreencenter";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", killCmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in disableSubScreenLauncher", e);
            return false;
        }
    }
    
    /**
     * V12杀进程法：检查Launcher进程是否在运行
     */
    @Override
    public boolean isLauncherProcessRunning() throws RemoteException {
        try {
            // 检查进程是否在运行
            String cmd = "ps -A | grep com.xiaomi.subscreencenter";
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            
            // 如果有输�?�?进程在运�?�?返回true（需要杀�?
            // 如果无输�?�?进程不在运行 �?返回false（不需要处理）
            boolean isRunning = (line != null && !line.isEmpty());
            
            if (isRunning) {

            }
            
            return isRunning;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in isLauncherProcessRunning", e);
            return false;
        }
    }
    
    /**
     * V12杀进程法：尝试杀掉Launcher进程
     * 返回true = 成功杀掉（说明进程在运行）
     * 返回false = 失败（说明进程不在运行）
     */
    @Override
    public boolean killLauncherProcess() throws RemoteException {
        try {
            // 强制停止进程
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            // force-stop 总是返回0，所以需要检查进程是否真的被杀死
            // 简单起见，如果命令成功就返回true
            return (exitCode == 0);
            
        } catch (Exception e) {
            // 异常也返回false（静默）
            return false;
        }
    }
    
    @Override
    public boolean enableSubScreenLauncher() throws RemoteException {
        virtualCast.stop(null);
        inputProxy.stop(null);
        try {

            return RearSystemBridge.restoreLauncher();
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in enableSubScreenLauncher", e);
            return false;
        }
    }

    @Override
    public boolean restoreSubScreenLauncherOnly() throws RemoteException {
        try {
            return RearSystemBridge.restoreLauncher();
        } catch (Exception e) {
            Log.e(TAG, "Rear Launcher restore failed", e);
            return false;
        }
    }

    @Override public boolean restoreSubScreenLauncherForControlledReplacement(int displayId,
            String expectedRearIdentity) {
        try {
            if (!isExactRear(displayId, expectedRearIdentity)) return false;
            RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(this,
                    BuildConfig.APPLICATION_ID, displayId);
            if (occupancy.disposition == RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER) return true;
            if (occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.EMPTY
                    || !isExactRear(displayId, expectedRearIdentity)) return false;
            occupancy = RearDisplayCastOccupancy.query(this, BuildConfig.APPLICATION_ID, displayId);
            if (occupancy.disposition != RearDisplayCastOccupancy.OwnerDisposition.EMPTY
                    || !isExactRear(displayId, expectedRearIdentity)) return false;
            if (!RearSystemBridge.restoreLauncher()) return false;
            return RearDisplayCastOccupancy.query(this, BuildConfig.APPLICATION_ID, displayId).disposition
                    == RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER;
        } catch (Exception unavailable) { return false; }
    }

    @Override public boolean restoreSubScreenLauncherAfterNormalControlledClose(int displayId,
            String expectedRearIdentity) {
        try {
            if (!isExactRear(displayId, expectedRearIdentity)) return false;
            RearDisplayCastOccupancy.Snapshot first = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
            RearDisplayCastOccupancy.Snapshot second = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
            RearDisplayCastOccupancy.ControlledLauncherRestoreAction action =
                    RearDisplayCastOccupancy.controlledLauncherRestoreAction(first, second, displayId,
                            RearDisplayCastOccupancy::isExactSystemLauncher);
            if (action == RearDisplayCastOccupancy.ControlledLauncherRestoreAction.REJECT
                    || !isExactRear(displayId, expectedRearIdentity)) return false;
            if (action == RearDisplayCastOccupancy.ControlledLauncherRestoreAction.NO_OP) return true;
            if (action != RearDisplayCastOccupancy.ControlledLauncherRestoreAction.START
                    || !isExactRear(displayId, expectedRearIdentity)) return false;
            if (!RearSystemBridge.restoreLauncher()) return false;
            RearDisplayCastOccupancy.Snapshot afterFirst = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
            RearDisplayCastOccupancy.Snapshot afterSecond = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
            return RearDisplayCastOccupancy.controlledLauncherRestoreAction(afterFirst, afterSecond, displayId,
                    RearDisplayCastOccupancy::isExactSystemLauncher)
                    == RearDisplayCastOccupancy.ControlledLauncherRestoreAction.NO_OP
                    && isExactRear(displayId, expectedRearIdentity);
        } catch (Exception unavailable) { return false; }
    }
    
    // 删除未使用的wakeUpDisplay方法
    
    @Override
    public boolean forceStatusBarToMainDisplay() throws RemoteException {
        try {

            // 新策略：直接展开主屏状态栏，而不是移动或重启SystemUI
            // 这会强制主屏显示SystemUI，从而保持焦点在主屏
            
            // 方法1: 展开主屏状态栏（不完全展开，只是激活）
            String expandCmd = "cmd statusbar expand-settings";

            ProcessBuilder pb1 = new ProcessBuilder("sh", "-c", expandCmd);
            Process process1 = pb1.start();
            int exitCode1 = process1.waitFor();
            
            if (exitCode1 == 0) {

                Thread.sleep(30);  // 短暂延迟
                
                // 立即收起
                String collapseCmd = "cmd statusbar collapse";
                ProcessBuilder pb2 = new ProcessBuilder("sh", "-c", collapseCmd);
                Process process2 = pb2.start();
                int exitCode2 = process2.waitFor();
                
                if (exitCode2 == 0) {

                } else {

                }
            } else {

            }
            
            // 方法2: 强制主屏SystemUI可见（通过wm命令�?
            // 设置主屏display为默�?
            String wmCmd = "wm set-display-type 0 home";

            ProcessBuilder pb3 = new ProcessBuilder("sh", "-c", wmCmd);
            Process process3 = pb3.start();
            
            BufferedReader reader3 = new BufferedReader(
                new InputStreamReader(process3.getInputStream()), 8192
            );
            String line;
            while ((line = reader3.readLine()) != null) {

            }
            reader3.close();
            
            int exitCode3 = process3.waitFor();
            if (exitCode3 == 0) {

            } else {

            }
            
            // 方法3: 检查当前状态栏位置

            ProcessBuilder pb4 = new ProcessBuilder("sh", "-c", "dumpsys window displays | grep -A20 'Display: 0'");
            Process process4 = pb4.start();
            
            BufferedReader reader4 = new BufferedReader(
                new InputStreamReader(process4.getInputStream()), 8192
            );
            while ((line = reader4.readLine()) != null) {
                if (line.contains("StatusBar") || line.contains("systemui")) {

                }
            }
            reader4.close();
            process4.waitFor();

            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in forceStatusBarToMainDisplay", e);
            return false;
        }
    }
    
    /**
     * 收回状态栏/控制中心
     * @return 是否成功
     */
    @Override
    public boolean collapseStatusBar() throws RemoteException {
        try {

            // 使用 cmd statusbar collapse 命令
            String cmd = "cmd statusbar collapse";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in collapseStatusBar", e);
            return false;
        }
    }
    
    /**
     * 获取当前背屏DPI
     * @return DPI值
     */
    @Override
    public int getCurrentRearDpi() throws RemoteException {
        try {

            // 使用 wm density 命令获取当前背屏的DPI
            String cmd = "wm density -d " + RearDisplay.requireId();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            int dpi = 0;
            while ((line = reader.readLine()) != null) {

                // 解析输出: "Physical density: 450" 或 "Override density: 300"
                if (line.contains("density:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        try {
                            String dpiStr = parts[1].trim();
                            // 如果是 "Override density: 300"，优先使用
                            if (line.contains("Override density")) {
                                dpi = Integer.parseInt(dpiStr);

                                break; // 找到override就不继续找了
                            } else if (dpi == 0) {
                                // 如果还没找到override，先记录physical
                                dpi = Integer.parseInt(dpiStr);

                            }
                        } catch (NumberFormatException e) {

                        }
                    }
                }
            }
            reader.close();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && dpi > 0) {

            } else {

            }

            return dpi;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in getCurrentRearDpi", e);
            return 0;
        }
    }
    
    /**
     * 设置背屏DPI
     * @param dpi DPI值
     * @return 是否成功
     */
    @Override
    public boolean setRearDpi(int dpi) throws RemoteException {
        try {

            // 使用 wm density 命令设置display 1的DPI
            String cmd = "wm density " + dpi + " -d " + RearDisplay.requireId();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in setRearDpi", e);
            return false;
        }
    }
    
    /**
     * 还原背屏DPI到默认值
     * @return 是否成功
     */
    @Override
    public boolean resetRearDpi() throws RemoteException {
        try {

            // 使用 wm density reset 命令还原display 1的DPI
            String cmd = "wm density reset -d " + RearDisplay.requireId();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in resetRearDpi", e);
            return false;
        }
    }
    
    /**
     * 截取背屏画面
     * @return 是否成功
     */
    @Override
    public boolean takeRearScreenshot() throws RemoteException {
        try {
            return RearScreenshotShell.saveLegacy(RearScreenshotShell.capture(new int[3]));
        } catch (Exception e) {
            Log.e(TAG, "Rear screenshot failed", e);
            return false;
        }
    }

    @Override public android.os.ParcelFileDescriptor captureRearScreenshot(int[] metadata) {
        try { return RearScreenshotShell.capture(metadata); }
        catch (Exception e) { CompatibilityDiagnostics.record("screenshot_failed", e.toString()); throw new IllegalStateException("Rear screenshot capture failed", e); }
    }

    @Override public boolean saveRearScreenshotLegacy(android.os.ParcelFileDescriptor source) {
        return source != null && RearScreenshotShell.saveLegacy(source);
    }

    @Override
    public int launchSceneActivity(String component, int displayId, String launchTicket) throws RemoteException {
        String validated = VirtualCastRequest.component(component);
        if (displayId < 1 || !RearDisplay.isRear(displayId)) throw new IllegalArgumentException("Scene display is not the selected rear display");
        if (launchTicket == null || !launchTicket.matches("[A-Za-z0-9_-]{24,64}")) throw new IllegalArgumentException("Invalid scene ticket");
        SceneTaskLaunchFlow.Result result = SceneTaskLaunchFlow.launch(validated, displayId, launchTicket,
                SceneTaskSnapshotReader::read, SceneTaskShellRunner::run,
                (taskId, sceneComponent, actualDisplayId) -> {
                    SceneTaskTaskRemover.Result removal = SceneTaskTaskRemover.remove(taskId, sceneComponent, actualDisplayId);
                    return new SceneTaskLaunchFlow.CleanupResult(removal.removed, removal.reason);
                }, new SceneTaskLaunchFlow.Time() {
                    @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                    @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                });
        CompatibilityDiagnostics.record("scene_launch_attempt", "component=" + validated + " display=" + displayId + " " + result.diagnostic());
        if (result.taskId < 0) CompatibilityDiagnostics.record("scene_launch_failed", "reason=" + result.failure);
        CompatibilityDiagnostics.record("scene_launch", "component=" + validated + " task=" + result.taskId + " display=" + displayId);
        return result.taskId;
    }

    @Override
    public int launchSceneActivityWithPlacement(String component, int displayId, String launchTicket,
                                                boolean fullRegion, int contentRotation,
                                                boolean explicitRotation) throws RemoteException {
        String validated = VirtualCastRequest.component(component);
        if (displayId < 1 || !RearDisplay.isRear(displayId)) throw new IllegalArgumentException("Scene display is not the selected rear display");
        if (launchTicket == null || !launchTicket.matches("[A-Za-z0-9_-]{24,64}")) throw new IllegalArgumentException("Invalid scene ticket");
        if (contentRotation < 0 || contentRotation > 3 || (!explicitRotation && contentRotation != 0))
            throw new IllegalArgumentException("Invalid Scene quarter-turn placement");
        SceneTaskLaunchFlow.Result result = SceneTaskLaunchFlow.launchWithPlacement(validated, displayId, launchTicket,
                fullRegion, contentRotation, explicitRotation,
                SceneTaskSnapshotReader::read, SceneTaskShellRunner::run,
                (taskId, sceneComponent, actualDisplayId) -> {
                    SceneTaskTaskRemover.Result removal = SceneTaskTaskRemover.remove(taskId, sceneComponent, actualDisplayId);
                    return new SceneTaskLaunchFlow.CleanupResult(removal.removed, removal.reason);
                }, new SceneTaskLaunchFlow.Time() {
                    @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                    @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                });
        CompatibilityDiagnostics.record("scene_launch_attempt", "component=" + validated + " display=" + displayId + " placement=explicit " + result.diagnostic());
        if (result.taskId < 0) CompatibilityDiagnostics.record("scene_launch_failed", "reason=" + result.failure);
        CompatibilityDiagnostics.record("scene_launch", "component=" + validated + " task=" + result.taskId + " display=" + displayId);
        return result.taskId;
    }

    @Override
    public boolean wakeRearDisplayForScene(int displayId) throws RemoteException {
        if (displayId < 1 || !RearDisplay.isRear(displayId)) return false;
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(displayId, id -> {
            Object info = RearDisplay.info(id);
            return info.getClass().getField("state").getInt(info);
        }, id -> SceneTaskShellRunner.run("input -d " + id + " keyevent KEYCODE_WAKEUP", 1_500L),
                new SceneDisplayWakeup.Time() {
                    @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                    @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                });
        CompatibilityDiagnostics.record("scene_display_wake", "display=" + displayId + " status=" + result.status
                + " command_exit=" + result.commandExit + " awake=" + result.awake);
        return result.awake;
    }

    @Override public int wakeRearDisplayForControlledCast(int displayId, String expectedRearIdentity,
            ISceneTaskActionGate actionGate) {
        try {
            if (!isGateCurrent(actionGate) || !isExactRear(displayId, expectedRearIdentity)) return ITaskService.CONTROLLED_CAST_WAKE_FAILED;
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
            if (!safeIdleStandbySnapshot(snapshot) || !isGateCurrent(actionGate)
                    || !isExactRear(displayId, expectedRearIdentity)) return ITaskService.CONTROLLED_CAST_WAKE_FAILED;
            if (ownsActiveVirtualOrInput(virtualCast.status(), inputProxy.status()) || !isGateCurrent(actionGate)) return ITaskService.CONTROLLED_CAST_WAKE_FAILED;
            SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(displayId, id -> {
                Object info = RearDisplay.info(id);
                return info.getClass().getField("state").getInt(info);
            }, id -> {
                if (!isGateCurrent(actionGate) || !isExactRear(id, expectedRearIdentity))
                    return new SceneTaskShellRunner.Result("", -1, false, false, "action_gate_stale");
                RearDisplayCastOccupancy.Snapshot fresh = RearDisplayCastOccupancyReader.readTypedOnly(id);
                if (!safeIdleStandbySnapshot(fresh) || !isGateCurrent(actionGate)
                        || !isExactRear(id, expectedRearIdentity)
                        || ownsActiveVirtualOrInput(virtualCast.status(), inputProxy.status()))
                    return new SceneTaskShellRunner.Result("", -1, false, false, "occupancy_changed");
                if (fresh.displayState == android.view.Display.STATE_ON)
                    return new SceneTaskShellRunner.Result("", 0, false, false, "already_on");
                return SceneTaskShellRunner.run("input -d " + id + " keyevent KEYCODE_WAKEUP", 1_500L);
            }, new SceneDisplayWakeup.Time() {
                @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
            });
            if (!result.awake) return ITaskService.CONTROLLED_CAST_WAKE_FAILED;
            return result.wakeIssued ? ITaskService.CONTROLLED_CAST_WAKE_CONFIRMED
                    : ITaskService.CONTROLLED_CAST_WAKE_ALREADY_ON;
        } catch (Exception unavailable) { return ITaskService.CONTROLLED_CAST_WAKE_FAILED; }
    }

    @Override public String startControlledRearVirtualCastWithGate(android.view.Surface surface, String readerPath,
            int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate) {
        return startControlledRearVirtualCastWithGateInternal(surface, readerPath, task, host, density, component,
                owner, gestures, viewport, compatibility, rotation, full, expectedBaseComponent,
                expectedRearDisplayId, expectedRearIdentity, operationId, ownerGeneration, ownerIdentity,
                actionGate, false, null);
    }

    @Override public String startControlledRearVirtualCastForPhysicalReplacement(android.view.Surface surface,
            String readerPath, int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            IPhysicalCastReaderLifecycle readerLifecycle) {
        return startControlledRearVirtualCastWithGateInternal(surface, readerPath, task, host, density, component,
                owner, gestures, viewport, compatibility, rotation, full, expectedBaseComponent,
                expectedRearDisplayId, expectedRearIdentity, operationId, ownerGeneration, ownerIdentity,
                actionGate, true, readerLifecycle);
    }

    @Override public String startControlledRearVirtualCastForPhysicalReplacementWithA(android.view.Surface surface,
            String readerPath, int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            int expectedOldTaskId, String expectedOldPackage, String expectedOldBaseComponent,
            IPhysicalCastReaderLifecycle readerLifecycle) {
        if (expectedOldTaskId <= 0 || expectedOldTaskId == task || expectedOldTaskId == host
                || expectedOldPackage == null || !expectedOldPackage.equals(packageFromCanonicalComponent(expectedOldBaseComponent))
                || readerLifecycle == null)
            throw new IllegalArgumentException("physical_replacement_old_owner_invalid");
        return startControlledRearVirtualCastWithGateInternal(surface, readerPath, task, host, density, component,
                owner, gestures, viewport, compatibility, rotation, full, expectedBaseComponent,
                expectedRearDisplayId, expectedRearIdentity, operationId, ownerGeneration, ownerIdentity,
                actionGate, true, readerLifecycle, expectedOldTaskId, expectedOldPackage, expectedOldBaseComponent);
    }

    private String startControlledRearVirtualCastWithGateInternal(android.view.Surface surface, String readerPath,
            int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            boolean deferLauncherRestore, IPhysicalCastReaderLifecycle readerLifecycle) {
        return startControlledRearVirtualCastWithGateInternal(surface, readerPath, task, host, density, component,
                owner, gestures, viewport, compatibility, rotation, full, expectedBaseComponent,
                expectedRearDisplayId, expectedRearIdentity, operationId, ownerGeneration, ownerIdentity,
                actionGate, deferLauncherRestore, readerLifecycle, -1, null, null);
    }

    private String startControlledRearVirtualCastWithGateInternal(android.view.Surface surface, String readerPath,
            int task, int host, int density, String component, android.os.IBinder owner,
            IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            boolean deferLauncherRestore, IPhysicalCastReaderLifecycle readerLifecycle,
            int expectedOldTaskId, String expectedOldPackage, String expectedOldBaseComponent) {
        String rejectStage = "pre_action_gate";
        int snapshotRows = -1;
        String snapshotDetail = "host_exact=unknown host_rows=unknown oem_exact=unknown oem_rows=unknown other_rows=unknown duplicate_rows=unknown oem_visible=? oem_requested=? oem_sleeping=?";
        try {
            if (!isGateCurrent(actionGate))
                throw new IllegalStateException("controlled_start_gate_rejected");
            rejectStage = "pre_rear_identity";
            if (!isExactRear(expectedRearDisplayId, expectedRearIdentity))
                throw new IllegalStateException("controlled_start_gate_rejected");
            rejectStage = "target_display";
            if (task <= 0 || !isTaskOnDisplay(task, 0))
                throw new IllegalStateException("controlled_start_gate_rejected");
            rejectStage = "target_base_identity";
            if (!sameCanonicalComponent(expectedBaseComponent, getTaskBaseComponent(task, 0)))
                throw new IllegalStateException("controlled_start_gate_rejected");
            rejectStage = "typed_snapshot_read";
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.readTypedOnly(expectedRearDisplayId);
            snapshotRows = snapshot == null || snapshot.tasks == null ? -1 : snapshot.tasks.size();
            String expectedHostComponent = ControlledCastHostComponent.expected(BuildConfig.APPLICATION_ID,
                    VirtualRearHostActivity.class.getName());
            snapshotDetail = controlledStartSnapshotDiagnostic(snapshot, host, expectedHostComponent);
            boolean replacementWithA = expectedOldTaskId > 0;
            rejectStage = replacementWithA
                    ? physicalReplacementStartSnapshotRejectStage(snapshot, host, expectedHostComponent,
                        expectedRearDisplayId, expectedOldTaskId, expectedOldPackage, expectedOldBaseComponent).name()
                            .toLowerCase(java.util.Locale.ROOT)
                    : controlledStartSnapshotRejectStage(snapshot, host, expectedHostComponent).name()
                            .toLowerCase(java.util.Locale.ROOT);
            if (!"ready".equals(rejectStage))
                throw new IllegalStateException("controlled_start_identity_changed");
            rejectStage = "host_task_display";
            if (!isTaskOnDisplay(host, expectedRearDisplayId))
                throw new IllegalStateException("controlled_start_identity_changed");
            rejectStage = "host_base_identity";
            if (!sameCanonicalComponent(expectedHostComponent, getTaskBaseComponent(host, expectedRearDisplayId)))
                throw new IllegalStateException("controlled_start_identity_changed");
            rejectStage = "post_snapshot_action_gate";
            if (!isGateCurrent(actionGate))
                throw new IllegalStateException("controlled_start_identity_changed");
            rejectStage = "post_snapshot_rear_identity";
            if (!isExactRear(expectedRearDisplayId, expectedRearIdentity))
                throw new IllegalStateException("controlled_start_identity_changed");
            rejectStage = "target_display_recheck";
            if (!isTaskOnDisplay(task, 0))
                throw new IllegalStateException("controlled_start_identity_changed");
            rejectStage = "target_base_recheck";
            if (!sameCanonicalComponent(expectedBaseComponent, getTaskBaseComponent(task, 0)))
                throw new IllegalStateException("controlled_start_identity_changed");
            if (replacementWithA) {
                rejectStage = "physical_a_recheck";
                if (!isTaskOnDisplay(expectedOldTaskId, expectedRearDisplayId)
                        || !expectedOldPackage.equals(packageFromCanonicalComponent(expectedOldBaseComponent))
                        || !sameCanonicalComponent(expectedOldBaseComponent,
                                getTaskBaseComponent(expectedOldTaskId, expectedRearDisplayId)))
                    throw new IllegalStateException("controlled_start_identity_changed");
            }
            rejectStage = "active_owner_check";
            if (ownsActiveVirtualOrInput(virtualCast.status(), inputProxy.status()))
                throw new IllegalStateException("controlled_start_owner_changed");
            rejectStage = "final_action_gate";
            if (!isGateCurrent(actionGate)) throw new IllegalStateException("controlled_start_owner_changed");
            if (replacementWithA && (!isExactRear(expectedRearDisplayId, expectedRearIdentity)
                    || !isTaskOnDisplay(expectedOldTaskId, expectedRearDisplayId)
                    || !sameCanonicalComponent(expectedOldBaseComponent,
                            getTaskBaseComponent(expectedOldTaskId, expectedRearDisplayId))))
                throw new IllegalStateException("controlled_start_identity_changed");
            // The same owned A/host inventory is required at the target move, after B's allocation and rotation.
            ISceneTaskActionGate engineGate = replacementWithA ? new ISceneTaskActionGate.Stub() {
                @Override public boolean isCurrent() { return isGateCurrent(actionGate); }

                @Override public boolean isCurrentBeforeTargetMove() throws RemoteException {
                    if (!actionGate.isCurrentBeforeTargetMove()
                            || !isExactRear(expectedRearDisplayId, expectedRearIdentity)) return false;
                    RearDisplayCastOccupancy.Snapshot fresh =
                            RearDisplayCastOccupancyReader.readTypedOnly(expectedRearDisplayId);
                    return physicalReplacementStartSnapshotRejectStage(fresh, host, expectedHostComponent,
                            expectedRearDisplayId, expectedOldTaskId, expectedOldPackage, expectedOldBaseComponent)
                                == PhysicalReplacementStartSnapshotStage.READY
                            && isExactRear(expectedRearDisplayId, expectedRearIdentity)
                            && isGateCurrent(actionGate);
                }
            } : actionGate;
            // Keep the final pre-action attestation distinct from failures raised by the Cast engine.
            rejectStage = "cast_backend_start";
            return virtualCast.startControlled(surface, readerPath, task, host, density, component, owner,
                    gestures, viewport, compatibility, rotation, full, expectedBaseComponent,
                    expectedRearDisplayId, expectedRearIdentity, operationId, ownerGeneration, ownerIdentity,
                    engineGate, deferLauncherRestore, readerLifecycle);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "controlled_start_reject stage=" + rejectStage + " rows=" + snapshotRows
                    + " " + snapshotDetail);
            throw new IllegalStateException("Controlled rear virtual cast: " + e, e);
        }
    }

    private static boolean isGateCurrent(ISceneTaskActionGate gate) {
        try { return gate != null && gate.isCurrent(); } catch (Exception stale) { return false; }
    }

    private static boolean isExactRear(int displayId, String expectedIdentity) {
        try {
            org.json.JSONObject state = new org.json.JSONObject(RearDisplay.state());
            String unique = state.optString("selectedUnique", "");
            return state.optInt("selectedId", -1) == displayId && !unique.isEmpty()
                    && (displayId + ":" + unique).equals(expectedIdentity);
        } catch (Exception unavailable) { return false; }
    }

    static boolean safeIdleStandbySnapshot(RearDisplayCastOccupancy.Snapshot snapshot) {
        if (snapshot == null || snapshot.failure != null || snapshot.tasks == null || snapshot.displayId <= 0
                || snapshot.powerBefore != snapshot.powerAfter || snapshot.displayState != snapshot.powerBefore
                || (snapshot.displayState != android.view.Display.STATE_ON
                    && snapshot.displayState != android.view.Display.STATE_OFF
                    && snapshot.displayState != android.view.Display.STATE_DOZE
                    && snapshot.displayState != android.view.Display.STATE_DOZE_SUSPEND)) return false;
        boolean sawExactLauncher = false;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            boolean exactLauncher = "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && "com.xiaomi.subscreencenter".equals(task.topPackage)
                    && sameCanonicalComponent(task.baseComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && sameCanonicalComponent(task.topComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher");
            if (exactLauncher && sawExactLauncher) return false;
            if (exactLauncher) sawExactLauncher = true;
            boolean exactHiddenLauncher = "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && "com.xiaomi.subscreencenter".equals(task.topPackage)
                    && sameCanonicalComponent(task.baseComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && sameCanonicalComponent(task.topComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && Boolean.FALSE.equals(task.visible) && Boolean.FALSE.equals(task.requested)
                    && Boolean.FALSE.equals(task.sleeping);
            boolean exactVisibleLauncher = snapshot.displayState == android.view.Display.STATE_ON
                    && "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && "com.xiaomi.subscreencenter".equals(task.topPackage)
                    && sameCanonicalComponent(task.baseComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && sameCanonicalComponent(task.topComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && Boolean.TRUE.equals(task.visible) && Boolean.TRUE.equals(task.requested)
                    && Boolean.FALSE.equals(task.sleeping);
            boolean asleepVisibleLauncher = exactLauncher
                    && (snapshot.displayState == android.view.Display.STATE_OFF
                        || snapshot.displayState == android.view.Display.STATE_DOZE
                        || snapshot.displayState == android.view.Display.STATE_DOZE_SUSPEND)
                    && task.visible != null && Boolean.FALSE.equals(task.requested)
                    && Boolean.TRUE.equals(task.sleeping);
            if (!exactHiddenLauncher && !exactVisibleLauncher && !asleepVisibleLauncher) return false;
        }
        return true;
    }

    static boolean safeControlledStartSnapshot(RearDisplayCastOccupancy.Snapshot snapshot,
            int expectedHostTaskId, String expectedHostComponent) {
        return controlledStartSnapshotRejectStage(snapshot, expectedHostTaskId, expectedHostComponent)
                == ControlledStartSnapshotStage.READY;
    }

    enum ControlledStartSnapshotStage {
        READY, SNAPSHOT_UNKNOWN, POWER_NOT_STABLE_ON, HOST_MISSING, HOST_DUPLICATE, OEM_DUPLICATE,
        HOST_COMPONENT_MISMATCH, HOST_FLAGS_MISMATCH, OEM_COMPONENT_MISMATCH,
        OEM_FLAGS_MISMATCH, OTHER_ROW
    }

    enum PhysicalReplacementStartSnapshotStage {
        READY, SNAPSHOT_UNKNOWN, POWER_NOT_STABLE_ON, INVALID_OLD_A, HOST_MISSING, HOST_DUPLICATE,
        OLD_A_MISSING, OLD_A_DUPLICATE, OLD_A_IDENTITY_MISMATCH, OLD_A_FLAGS_MISMATCH,
        OEM_DUPLICATE, HOST_COMPONENT_MISMATCH, HOST_FLAGS_MISMATCH, OEM_COMPONENT_MISMATCH,
        OEM_FLAGS_MISMATCH, OTHER_ROW
    }

    /** Txn80 adds only the exact, awake physical A, which the new host may now cover. */
    static PhysicalReplacementStartSnapshotStage physicalReplacementStartSnapshotRejectStage(
            RearDisplayCastOccupancy.Snapshot snapshot, int expectedHostTaskId, String expectedHostComponent,
            int expectedRearDisplayId, int expectedOldTaskId, String expectedOldPackage,
            String expectedOldBaseComponent) {
        if (snapshot == null || snapshot.failure != null || snapshot.displayId <= 0
                || expectedRearDisplayId <= 0 || snapshot.displayId != expectedRearDisplayId)
            return PhysicalReplacementStartSnapshotStage.SNAPSHOT_UNKNOWN;
        if (snapshot.displayState != android.view.Display.STATE_ON
                || snapshot.powerBefore != android.view.Display.STATE_ON
                || snapshot.powerAfter != android.view.Display.STATE_ON)
            return PhysicalReplacementStartSnapshotStage.POWER_NOT_STABLE_ON;
        if (expectedHostTaskId <= 0 || expectedOldTaskId <= 0 || expectedHostTaskId == expectedOldTaskId
                || expectedOldPackage == null || expectedOldPackage.isBlank()
                || expectedOldBaseComponent == null || expectedOldBaseComponent.isBlank())
            return PhysicalReplacementStartSnapshotStage.INVALID_OLD_A;
        boolean hostSeen = false, oldASeen = false, oemSeen = false;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task.taskId == expectedHostTaskId) {
                if (hostSeen) return PhysicalReplacementStartSnapshotStage.HOST_DUPLICATE;
                if (!sameCanonicalComponent(task.baseComponent, expectedHostComponent)
                        || !sameCanonicalComponent(task.topComponent, expectedHostComponent))
                    return PhysicalReplacementStartSnapshotStage.HOST_COMPONENT_MISMATCH;
                if (!Boolean.TRUE.equals(task.visible) || !Boolean.TRUE.equals(task.requested)
                        || !Boolean.FALSE.equals(task.sleeping))
                    return PhysicalReplacementStartSnapshotStage.HOST_FLAGS_MISMATCH;
                hostSeen = true; continue;
            }
            if (task.taskId == expectedOldTaskId) {
                if (oldASeen) return PhysicalReplacementStartSnapshotStage.OLD_A_DUPLICATE;
                if (!expectedOldPackage.equals(task.basePackage)
                        || !sameCanonicalComponent(task.baseComponent, expectedOldBaseComponent)
                        || !expectedOldPackage.equals(task.topPackage)
                        || !expectedOldPackage.equals(packageFromCanonicalComponent(task.topComponent)))
                    return PhysicalReplacementStartSnapshotStage.OLD_A_IDENTITY_MISMATCH;
                // Before host launch A was verified as the active controlled owner. While the host
                // is above it, A may legitimately be hidden; missing flags or sleep are still unknown.
                if (task.visible == null || task.requested == null || !Boolean.FALSE.equals(task.sleeping))
                    return PhysicalReplacementStartSnapshotStage.OLD_A_FLAGS_MISMATCH;
                oldASeen = true; continue;
            }
            boolean exactOem = "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && "com.xiaomi.subscreencenter".equals(task.topPackage)
                    && sameCanonicalComponent(task.baseComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && sameCanonicalComponent(task.topComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher");
            if (!exactOem) {
                boolean oemRow = "com.xiaomi.subscreencenter".equals(task.basePackage)
                        || "com.xiaomi.subscreencenter".equals(task.topPackage);
                return oemRow ? PhysicalReplacementStartSnapshotStage.OEM_COMPONENT_MISMATCH
                        : PhysicalReplacementStartSnapshotStage.OTHER_ROW;
            }
            if (oemSeen) return PhysicalReplacementStartSnapshotStage.OEM_DUPLICATE;
            oemSeen = true;
            if (task.visible == null || !Boolean.FALSE.equals(task.requested)
                    || !Boolean.FALSE.equals(task.sleeping))
                return PhysicalReplacementStartSnapshotStage.OEM_FLAGS_MISMATCH;
        }
        if (!hostSeen) return PhysicalReplacementStartSnapshotStage.HOST_MISSING;
        if (!oldASeen) return PhysicalReplacementStartSnapshotStage.OLD_A_MISSING;
        return PhysicalReplacementStartSnapshotStage.READY;
    }

    /** Sanitized facts derived only from the typed snapshot already read by txn72. */
    static String controlledStartSnapshotDiagnostic(RearDisplayCastOccupancy.Snapshot snapshot,
            int expectedHostTaskId, String expectedHostComponent) {
        if (snapshot == null || snapshot.tasks == null)
            return "host_exact=unknown host_rows=unknown oem_exact=unknown oem_rows=unknown other_rows=unknown duplicate_rows=unknown oem_visible=? oem_requested=? oem_sleeping=?";
        int hostRows = 0, exactHostRows = 0, oemRows = 0, otherRows = 0;
        String firstVisible = null, firstRequested = null, firstSleeping = null;
        boolean oemFlagsConsistent = true;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            boolean hostRow = task.taskId == expectedHostTaskId;
            boolean exactHost = hostRow && sameCanonicalComponent(task.baseComponent, expectedHostComponent)
                    && sameCanonicalComponent(task.topComponent, expectedHostComponent);
            boolean exactOem = "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && "com.xiaomi.subscreencenter".equals(task.topPackage)
                    && sameCanonicalComponent(task.baseComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && sameCanonicalComponent(task.topComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher");
            if (hostRow) hostRows++;
            if (exactHost) exactHostRows++;
            if (exactOem) {
                oemRows++;
                String visible = triState(task.visible), requested = triState(task.requested), sleeping = triState(task.sleeping);
                if (firstVisible == null) {
                    firstVisible = visible; firstRequested = requested; firstSleeping = sleeping;
                } else if (!firstVisible.equals(visible) || !firstRequested.equals(requested)
                        || !firstSleeping.equals(sleeping)) oemFlagsConsistent = false;
            }
            if (!hostRow && !exactOem) otherRows++;
        }
        int duplicates = Math.max(0, hostRows - 1) + Math.max(0, oemRows - 1);
        return "host_exact=" + (hostRows == 1 && exactHostRows == 1 ? "true" : "false")
                + " host_rows=" + hostRows + " oem_exact=" + (oemRows == 1 ? "true" : "false")
                + " oem_rows=" + oemRows + " other_rows=" + otherRows + " duplicate_rows=" + duplicates
                + " oem_visible=" + (firstVisible == null ? "?" : firstVisible)
                + " oem_requested=" + (firstRequested == null ? "?" : firstRequested)
                + " oem_sleeping=" + (firstSleeping == null ? "?" : firstSleeping)
                + " oem_flags_consistent=" + (firstVisible == null ? "unknown" : oemFlagsConsistent ? "true" : "false");
    }

    private static String triState(Boolean value) {
        return value == null ? "?" : value ? "1" : "0";
    }

    static ControlledStartSnapshotStage controlledStartSnapshotRejectStage(
            RearDisplayCastOccupancy.Snapshot snapshot, int expectedHostTaskId, String expectedHostComponent) {
        if (snapshot == null || snapshot.failure != null || snapshot.displayId <= 0
                || expectedHostTaskId <= 0) return ControlledStartSnapshotStage.SNAPSHOT_UNKNOWN;
        if (snapshot.displayState != android.view.Display.STATE_ON
                || snapshot.powerBefore != android.view.Display.STATE_ON
                || snapshot.powerAfter != android.view.Display.STATE_ON)
            return ControlledStartSnapshotStage.POWER_NOT_STABLE_ON;
        boolean hostSeen = false, oemSeen = false;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task.taskId == expectedHostTaskId) {
                if (hostSeen) return ControlledStartSnapshotStage.HOST_DUPLICATE;
                if (!sameCanonicalComponent(task.baseComponent, expectedHostComponent)
                        || !sameCanonicalComponent(task.topComponent, expectedHostComponent))
                    return ControlledStartSnapshotStage.HOST_COMPONENT_MISMATCH;
                if (!Boolean.TRUE.equals(task.visible) || !Boolean.TRUE.equals(task.requested)
                        || !Boolean.FALSE.equals(task.sleeping))
                    return ControlledStartSnapshotStage.HOST_FLAGS_MISMATCH;
                hostSeen = true;
                continue;
            }
            boolean oemPackage = "com.xiaomi.subscreencenter".equals(task.basePackage)
                    && "com.xiaomi.subscreencenter".equals(task.topPackage)
                    && sameCanonicalComponent(task.baseComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher")
                    && sameCanonicalComponent(task.topComponent,
                        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher");
            if (!oemPackage) {
                boolean oemRow = "com.xiaomi.subscreencenter".equals(task.basePackage)
                        || "com.xiaomi.subscreencenter".equals(task.topPackage);
                return oemRow ? ControlledStartSnapshotStage.OEM_COMPONENT_MISMATCH
                        : ControlledStartSnapshotStage.OTHER_ROW;
            }
            if (oemSeen) return ControlledStartSnapshotStage.OEM_DUPLICATE;
            oemSeen = true;
            if (task.visible == null || !Boolean.FALSE.equals(task.requested)
                    || !Boolean.FALSE.equals(task.sleeping)) return ControlledStartSnapshotStage.OEM_FLAGS_MISMATCH;
        }
        return hostSeen ? ControlledStartSnapshotStage.READY : ControlledStartSnapshotStage.HOST_MISSING;
    }

    private static boolean sameCanonicalComponent(String left, String right) {
        String a = RearDisplayCastOccupancyReader.canonicalComponent(left);
        String b = RearDisplayCastOccupancyReader.canonicalComponent(right);
        return a != null && a.equals(b);
    }

    private static String packageFromCanonicalComponent(String component) {
        String canonical = RearDisplayCastOccupancyReader.canonicalComponent(component);
        if (canonical == null) return null;
        int slash = canonical.indexOf('/');
        return slash <= 0 ? null : canonical.substring(0, slash);
    }

    private static boolean ownsActiveVirtualOrInput(String virtual, String input) {
        try {
            String state = new org.json.JSONObject(virtual).optString("state");
            if ("running".equals(state) || "starting".equals(state)) return true;
            String inputState = new org.json.JSONObject(input).optString("state");
            return "running".equals(inputState) || "starting".equals(inputState);
        } catch (Exception unknown) { return true; }
    }

    @Override
    public String presentSceneActivityTask(int taskId, String component, int displayId,
            ISceneTaskActionGate actionGate) throws RemoteException {
        final String expected;
        try {
            expected = android.content.ComponentName.unflattenFromString(VirtualCastRequest.component(component)).flattenToString();
        } catch (Exception invalid) {
            return new SceneTaskPresentationFlow.Result("invalid_component", "input", taskId, displayId,
                    null, null, -1).toJson();
        }
        if (taskId < 0 || displayId < 1 || !RearDisplay.isRear(displayId)) {
            return new SceneTaskPresentationFlow.Result("invalid_task_or_display", "input", taskId, displayId,
                    null, null, -1).toJson();
        }
        SceneRecoveryPresentationAuthority.RearStateReader rearState = RearDisplay::state;
        SceneRecoveryPresentationAuthority.CurrentGate currentGate = () ->
                SceneTaskPresentationFlow.invokeRemoteActionGate(actionGate == null ? null : actionGate::isCurrent);
        String rearPreflight = SceneRecoveryPresentationAuthority.preflightRear(displayId, rearState);
        if (rearPreflight != null) {
            SceneTaskPresentationFlow.Result rejected = new SceneTaskPresentationFlow.Result(
                    rearPreflight, "physical_rear", taskId, displayId, expected, null, -1);
            CompatibilityDiagnostics.record("scene_task_present", rejected.diagnostic());
            return rejected.toJson();
        }
        SceneTaskPresentationFlow.Result result = SceneTaskPresentationFlow.present(taskId, expected, displayId,
                SceneTaskSnapshotReader::read,
                id -> {
                    try {
                        DisplayRotationController.Result command = DisplayRotationController.runCommand(
                                "am", "task", "focus", Integer.toString(id));
                        return new SceneTaskPresentationFlow.FocusResult(
                                RearSystemBridge.commandSucceeded(command), command.exitCode, null);
                    } catch (Exception failure) {
                        return new SceneTaskPresentationFlow.FocusResult(false, -1,
                                failure.getClass().getSimpleName());
                    }
                }, top -> SceneTaskPresentationFlow.matchesKnownRearHome(RearSystemBridge.launcherCandidates(), top),
                () -> SceneTaskPresentationFlow.invokeRemoteActionGate(
                        actionGate == null ? null : actionGate::isCurrent),
                presentation -> SceneRecoveryPresentationAuthority.confirm(taskId, expected, displayId,
                        rearState, SceneTaskSnapshotReader::read, currentGate));
        CompatibilityDiagnostics.record("scene_task_present", result.diagnostic());
        return result.toJson();
    }

    /** Returns only a single task created by this launch attempt; never guesses among existing/user tasks. */
    public static int uniqueFreshSceneTaskId(java.util.Map<Integer, Integer> before,
            java.util.Map<Integer, Integer> observed, int expectedDisplayId) {
        int candidate = -1;
        for (java.util.Map.Entry<Integer, Integer> entry : observed.entrySet()) {
            int task = entry.getKey();
            if (before.containsKey(task)) continue;
            if (candidate >= 0) return -1;
            candidate = task;
            if (entry.getValue() != expectedDisplayId) return -1;
        }
        return candidate;
    }

    @Override
    public boolean verifySceneActivity(int taskId, String component, int displayId) throws RemoteException {
        try { return new org.json.JSONObject(inspectSceneTaskIdentity(taskId, component, displayId, false)).optString("status").equals("VERIFIED"); }
        catch (Exception e) { return false; }
    }

    @Override
    public String inspectSceneActivityTask(int taskId, String component, int displayId) throws RemoteException {
        return inspectSceneTaskIdentity(taskId, component, displayId, true);
    }

    private String inspectSceneTaskIdentity(int taskId, String component, int displayId, boolean readyReport) {
        if (taskId < 0 || displayId < 1) return new SceneTaskIdentity.Result(
                SceneTaskIdentity.Status.PARSE_ERROR, "input", "invalid_task_or_display", null).toJson();
        final String expected;
        try { expected = android.content.ComponentName.unflattenFromString(VirtualCastRequest.component(component)).flattenToString(); }
        catch (Exception e) { return new SceneTaskIdentity.Result(SceneTaskIdentity.Status.PARSE_ERROR, "input", "invalid_component", null).toJson(); }

        try {
            java.util.List<SceneTaskIdentity.Record> records = RearSystemBridge.runningTaskSnapshot();
            SceneTaskIdentity.Result result = readyReport
                    ? SceneTaskIdentity.verifyReady(records, taskId, expected, displayId, "activity_task")
                    : SceneTaskIdentity.verifyOwner(records, taskId, expected, displayId, "activity_task");
            CompatibilityDiagnostics.record("scene_verify", "task=" + taskId + " expected_display=" + displayId + " purpose=" + (readyReport ? "ready" : "owner") + " source=activity_task status=" + result.status
                    + (result.detail == null ? "" : " detail=" + result.detail));
            return result.toJson();
        } catch (Exception binderError) {
            // Reflection/API/permission failures are distinguished from an authoritative task
            // mismatch. Only this unavailable-query path falls back to the bounded legacy shell.
            CompatibilityDiagnostics.record("scene_verify_query_unavailable", "type=" + binderError.getClass().getSimpleName());
        }

        SceneTaskShellRunner.Result shell = SceneTaskShellRunner.run("am stack list", 3000);
        SceneTaskIdentity.Result result = SceneTaskIdentity.verifyShellSnapshot(shell.output, shell.exitCode,
                shell.timedOut, shell.truncated, taskId, expected, displayId, readyReport);
        CompatibilityDiagnostics.record("scene_verify", "task=" + taskId + " expected_display=" + displayId
                + " purpose=" + (readyReport ? "ready" : "owner") + " source=am_stack_list status=" + result.status
                + (result.detail == null ? "" : " detail=" + result.detail));
        return result.toJson();
    }

    @Override
    public boolean finishSceneActivityTask(int taskId, String component, int displayId) throws RemoteException {
        SceneTaskTaskRemover.Result result = SceneTaskTaskRemover.remove(taskId, component, displayId);
        CompatibilityDiagnostics.record("scene_close", "component=" + component + " task=" + taskId
                + " removed=" + result.removed + " reason=" + result.reason);
        return result.removed;
    }

    @Override
    public boolean isTaskOnDisplay(int taskId, int displayId) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.matches(".*\\bdisplayId=" + displayId + "\\b.*");
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=" + taskId + ":")) {
                    reader.close();
                    process.destroy();
                    return true;
                }
            }
            
            reader.close();
            process.waitFor();
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking task on display", e);
            return false;
        }
    }
    
    @Override
    public String getForegroundAppOnDisplay(int displayId) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.matches(".*\\bdisplayId=" + displayId + "\\b.*");
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    reader.close();
                    process.destroy();
                    
                    return packageName + ":" + taskId;
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app on display", e);
            return null;
        }
    }

    @Override public String getDisplayCastOccupancy(int displayId) {
        return RearDisplayCastOccupancyReader.read(displayId).toJson();
    }

    @Override public boolean isControlledCastPickerStillOnMain(int pickerTaskId, int targetTaskId,
            String expectedBaseComponent, String expectedTopComponent, int hostTaskId,
            int rearDisplayId, String rearIdentity) {
        ControlledCastApprovalFocus.Result result = ControlledCastApprovalFocus.Result.QUERY_UNAVAILABLE;
        try {
            result = ControlledCastApprovalFocus.verify(pickerTaskId, targetTaskId,
                    expectedBaseComponent, expectedTopComponent, hostTaskId, rearDisplayId, rearIdentity);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "controlled_cast_main_picker_proof result="
                + result.name().toLowerCase(java.util.Locale.ROOT));
        return result == ControlledCastApprovalFocus.Result.EXACT;
    }


    /**
     * V2.1: 设置显示器旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @param rotation 旋转角度 (0=0°, 1=90°, 2=180°, 3=270°)
     * @return 是否成功
     */
    @Override
    public boolean setDisplayRotation(int displayId, int rotation) throws RemoteException {
        if (displayId < 0 || rotation < 0 || rotation > 3) return false;
        try {
            // 获取当前背屏前台应用（如果有）
            String currentApp = null;
            int currentTaskId = -1;
            if (RearDisplay.isRear(displayId)) {
                currentApp = getForegroundAppOnDisplay(RearDisplay.requireId());
                if (currentApp != null && currentApp.contains(":")) {
                    String[] parts = currentApp.split(":");
                    try {
                        currentTaskId = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            
            boolean applied = rotationController.setRotation(displayId, rotation);

            // 如果是背屏且有应用在运行，等待500ms后检查并复活
            if (RearDisplay.isRear(displayId) && applied && currentTaskId > 0) {
                Thread.sleep(500);
                
                // 检查应用是否还在背屏
                boolean stillOnRear = isTaskOnDisplay(currentTaskId, RearDisplay.requireId());
                
                if (!stillOnRear) {
                    // 应用被关闭了，重新投放
                    moveTaskToDisplay(currentTaskId, RearDisplay.requireId());
                }
            }
            
            return applied;
            
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "设置旋转异常", e);
            return false;
        }
    }
    
    /**
     * V2.1: 获取显示器当前旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @return 旋转角度 (0-3)，-1表示失败
     */
    @Override
    public int getDisplayRotation(int displayId) throws RemoteException {
        return rotationController.getRotation(displayId);
    }
    
    @Override
    public boolean executeShellCommand(String cmd) throws RemoteException {
        boolean notificationLaunch = cmd != null && NOTIFICATION_LAUNCH.matcher(cmd).lookingAt();
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            reader.close();
            errorReader.close();
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0 && cmd.startsWith("input -d ") && cmd.endsWith(" keyevent KEYCODE_WAKEUP"))
                CompatibilityDiagnostics.record("wake_failed", "exit=" + exitCode + " response=" + errorOutput);
            if (notificationLaunch) {
                // Intent extras carry notification content; even shell output can echo it.
                if (exitCode != 0) Log.w(TAG, "notification_launch_command_failed exit=" + exitCode);
            } else {
                if (output.length() > 0) {
                    Log.d(TAG, "Command stdout: " + output.toString().trim());
                }
                if (errorOutput.length() > 0) {
                    Log.w(TAG, "Command stderr: " + errorOutput.toString().trim());
                }
            }
            
            return (exitCode == 0);
            
        } catch (Exception e) {
            if (notificationLaunch) {
                Log.e(TAG, "notification_launch_command_error type=" + e.getClass().getSimpleName());
            } else {
                Log.e(TAG, "执行命令失败: " + cmd, e);
            }
            return false;
        }
    }
    
    @Override
    public String executeShellCommandWithResult(String cmd) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            reader.close();
            process.waitFor();
            
            return output.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return "";
        }
    }
    
}
