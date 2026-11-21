package top.zainzh.rearscreen;
import android.os.IBinder;
import android.view.Surface;
import android.os.ParcelFileDescriptor;
import top.zainzh.rearscreen.IRearGestureListener;
import top.zainzh.rearscreen.IRearWallpaperOutputReleased;
import top.zainzh.rearscreen.IRearNotificationTarget;
import top.zainzh.rearscreen.ISceneTaskActionGate;
import top.zainzh.rearscreen.ITaskFocusActionGate;
import top.zainzh.rearscreen.IPhysicalCastReaderLifecycle;
import top.zainzh.rearscreen.IPhysicalReplacementMoveGate;

interface ITaskService {
    const int CONTROLLED_CAST_WAKE_FAILED = 0;
    const int CONTROLLED_CAST_WAKE_ALREADY_ON = 2;
    const int CONTROLLED_CAST_WAKE_CONFIRMED = 3;
    const int TASK_FOCUS_FAILED = 0;
    const int TASK_FOCUS_CONFIRMED = 2;
    void destroy() = 16777114;  // Shizuku required
    
    /**
     * 获取当前前台应用的包名和taskId
     * @return "package:taskId" 格式
     */
    String getCurrentForegroundApp() = 1;
    
    /**
     * 通过包名获取taskId
     * @param packageName 包名
     * @return taskId，失败返回-1
     */
    int getTaskIdByPackage(String packageName) = 2;
    
    /**
     * 移动任务到指定显示屏
     * @param taskId 任务ID
     * @param displayId 显示屏ID (0=主屏, 1=背屏)
     * @return 是否成功
     */
    boolean moveTaskToDisplay(int taskId, int displayId) = 3;
    
    /**
     * 在指定显示屏启动Activity（尝试保持常亮）
     * 注意：已移除主动点亮功能，仅尝试设置FLAG_KEEP_SCREEN_ON
     * @param displayId 显示屏ID (0=主屏, 1=背屏)
     * @return 是否成功
     */
    boolean launchWakeActivity(int displayId) = 4;
    
    /**
     * 强制将SystemUI（状态栏）固定在主屏幕
     * @return 是否成功
     */
    boolean forceStatusBarToMainDisplay() = 5;
    
    /**
     * 禁用小米背屏Launcher（防止挤占应用）
     * @return 是否成功
     */
    boolean disableSubScreenLauncher() = 6;
    
    /**
     * 启用小米背屏Launcher（恢复系统功能）
     * @return 是否成功
     */
    boolean enableSubScreenLauncher() = 7;
    
    /**
     * V9新增：检查Launcher进程是否在运行
     * @return true=进程在运行, false=进程不存在
     */
    boolean isLauncherProcessRunning() = 8;
    
    /**
     * V9新增：杀掉Launcher进程（轻量级操作）
     * @return 是否成功
     */
    boolean killLauncherProcess() = 9;
    
    // 删除未使用的wakeUpDisplay方法声明
    
    /**
     * V14.4新增：收回状态栏/控制中心
     * @return 是否成功
     */
    boolean collapseStatusBar() = 11;
    
    /**
     * V15新增：获取当前背屏DPI
     * @return DPI值
     */
    int getCurrentRearDpi() = 12;
    
    /**
     * V15新增：设置背屏DPI
     * @param dpi DPI值
     * @return 是否成功
     */
    boolean setRearDpi(int dpi) = 13;
    
    /**
     * V15新增：还原背屏DPI到默认值
     * @return 是否成功
     */
    boolean resetRearDpi() = 14;
    
    /**
     * V15新增：截取背屏画面
     * @return 是否成功
     */
    boolean takeRearScreenshot() = 15;
    
    /**
     * V15.1新增：检查任务是否在指定显示屏上运行
     * @param taskId 任务ID
     * @param displayId 显示屏ID (0=主屏, 1=背屏)
     * @return true=任务在指定屏幕运行, false=任务不存在或在其他屏幕
     */
    boolean isTaskOnDisplay(int taskId, int displayId) = 16;
    
    /**
     * V15.2新增：获取指定显示屏的前台应用
     * @param displayId 显示屏ID (0=主屏, 1=背屏)
     * @return "package:taskId" 格式，失败返回null
     */
    String getForegroundAppOnDisplay(int displayId) = 17;
    
    /**
     * V2.1新增：设置显示器旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @param rotation 旋转角度 (0=0°, 1=90°, 2=180°, 3=270°)
     * @return 是否成功
     */
    boolean setDisplayRotation(int displayId, int rotation) = 18;
    
    /**
     * V2.1新增：获取显示器当前旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @return 旋转角度 (0-3)，-1表示失败
     */
    int getDisplayRotation(int displayId) = 19;
    
    /**
     * V2.3新增：执行shell命令
     * @param cmd 要执行的命令
     * @return 是否成功
     */
    boolean executeShellCommand(String cmd) = 20;
    String executeShellCommandWithResult(String cmd) = 21;
    String startRearInputProxy(String readerPath, int taskId, IBinder owner) = 22;
    String getRearInputProxyStatus() = 23;
    boolean renewRearInputProxyLease(String sessionId) = 24;
    void stopRearInputProxy(String sessionId) = 25;
    String startRearVirtualCast(in Surface surface, String readerPath, int taskId, int hostTaskId, int density, String component, IBinder owner, IRearGestureListener gestures, String viewport, boolean densityCompatibility, int contentRotation, boolean fullViewport) = 26;
    String getRearVirtualCastStatus() = 27;
    boolean renewRearVirtualCastLease(String sessionId) = 28;
    void stopRearVirtualCast(String sessionId) = 29;
    boolean updateRearVirtualCastSurface(String sessionId, in Surface surface) = 30;
    String getRearCastViewport() = 31;
    void dismissRearCastMenu(String sessionId) = 32;
    String rotateRearVirtualCast(String sessionId, int direction) = 33;
    String beginRearCastViewport(String sessionId, boolean full) = 34;
    String finishRearCastViewport(String sessionId, in Surface surface) = 35;
    String getRearCastRenderPlan(int taskId, String component, int density, boolean densityCompatibility, boolean fullViewport) = 36;
    // Metadata is [display rotation, logical width, logical height]. PNG travels through an fd.
    ParcelFileDescriptor captureRearScreenshot(out int[] metadata) = 37;
    boolean saveRearScreenshotLegacy(in ParcelFileDescriptor source) = 38;
    // Fixed CPU/GPU counters only; future display packages do not receive a shell interface.
    String readSystemMetrics() = 39;
    String getRearDisplayState() = 40;
    String getCompatibilityDiagnostics() = 41;
    String getRearPhysicalDisplayId() = 42;
    // Only temperature, condition and update time; no location or arbitrary query access.
    String readSystemWeather() = 43;
    boolean setRearCastNotificationBounds(String sessionId, long token, int left, int top, int right, int bottom) = 44;

    // Narrow privileged scene operations; never exported through the third-party SDK service.
    int launchSceneActivity(String component, int displayId, String launchTicket) = 45;
    boolean verifySceneActivity(int taskId, String component, int displayId) = 46;
    boolean finishSceneActivityTask(int taskId, String component, int displayId) = 47;
    /** Internal ownership verification for a previously registered legacy cast task. */
    String getPackageNameFromTaskId(int taskId) = 48;
    /** Final Scene window check with bounded, task-local diagnostics; never returns other task records. */
    String inspectSceneActivityTask(int taskId, String component, int displayId) = 49;
    /** One-shot, target-rear-only wake for an authorized Scene activation; never a loop or global wake. */
    boolean wakeRearDisplayForScene(int displayId) = 50;
    /** Focus an already-owned Scene task only when the rear OEM launcher is top; never launches or moves. */
    String presentSceneActivityTask(int taskId, String component, int displayId, ISceneTaskActionGate actionGate) = 51;

    // Internal host-only theme operations. No raw shell/MAML interface is exported to clients.
    String getOfficialThemeStatus() = 52;
    String installOfficialTheme(String registration, int systemId, String title, in byte[] compiledResource) = 53;
    String removeOfficialTheme(String registration) = 54;
    void prepareOfficialThemeHost(int hostTaskId, String component, IBinder owner) = 55;
    void cancelOfficialThemeHost(IBinder owner) = 56;
    /** Present/wake only the currently owned virtual-cast host at a gated recovery edge; action 1=wake, 2=present. */
    String recoverRearVirtualCastHost(String sessionId, int hostTaskId, int rearDisplayId,
            int action, ISceneTaskActionGate actionGate) = 57;
    /** Pauses/resumes this owned cast's existing Surface without releasing it during display power edges. */
    String setRearVirtualCastOutputAvailable(String sessionId, boolean available, in Surface replacement,
            ISceneTaskActionGate actionGate) = 58;
    /** Read-only cast admission snapshot; legacy foreground/ownership queries retain their semantics. */
    String getDisplayCastOccupancy(int displayId) = 59;
    /** One-shot non-touch idle-deadline reset after a verified owned rear notification draw. */
    boolean reportOwnedNotificationActivity(int displayId, long notificationSessionId,
            long expiresAtElapsedRealtimeMs, long drawEventUptime,
            ISceneTaskActionGate currentGate) = 60;
    /** Restore only the rear Launcher through the privileged backend; leaves virtual casting/input ownership untouched. */
    boolean restoreSubScreenLauncherOnly() = 61;
    /** Internal host-only prepare: exact old owner/session/task/reader plus new target; allocates B without stopping A. */
    String prepareRearVirtualCastReplacement(String previousSessionId, long previousGeneration,
            long previousOwnerGeneration, String previousOwnerIdentity,
            int previousTaskId, int previousHostTaskId,
            String previousInputSessionId, String previousBaseComponent, String expectedRearIdentity,
            String expectedCandidateBaseComponent, int rearDisplayId, String operationId,
            long ownerGeneration, String ownerIdentity,
            in Surface candidateSurface, String readerPath, int taskId, int hostTaskId, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean densityCompatibility, int contentRotation, boolean fullViewport) = 62;
    /** One-shot host acknowledgment after a real candidate Surface buffer was observed. */
    boolean reportRearVirtualCastReplacementOutputFrame(String candidateToken) = 63;
    /** Pause the original reader on its native PAUSED acknowledgment before swapping host layers. */
    boolean beginRearVirtualCastReplacementHandoff(String candidateToken, ISceneTaskActionGate actionGate) = 64;
    /** Rebind the same reader while paused; does not resume until finish. */
    boolean bindRearVirtualCastReplacementInput(String candidateToken, ISceneTaskActionGate actionGate) = 65;
    /** Resume/commit, or restore A route and abort B. */
    String finishRearVirtualCastReplacement(String candidateToken, boolean commit,
            ISceneTaskActionGate actionGate) = 66;
    /** Abort a prepared candidate before or during the reversible handoff. */
    boolean abortRearVirtualCastReplacement(String candidateToken) = 67;
    /** Exact base activity for one task on one display; null means unavailable or ambiguous. */
    String getTaskBaseComponent(int taskId, int displayId) = 68;
    /** Ticket-bound typed placement for a new explicit Scene layout; legacy launchSceneActivity remains unchanged. */
    int launchSceneActivityWithPlacement(String component, int displayId, String launchTicket,
            boolean fullRegion, int contentRotation, boolean explicitRotation) = 69;
    /** Internal controlled empty-cast start; refuses all existing virtual/input owners and never calls legacy stop(null). */
    String startControlledRearVirtualCast(in Surface surface, String readerPath, int taskId, int hostTaskId,
            int density, String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean densityCompatibility, int contentRotation, boolean fullViewport,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity) = 70;
    /** One-shot authorized idle-panel wake; returns FAILED, ALREADY_ON, or command-issued-and-confirmed. */
    int wakeRearDisplayForControlledCast(int displayId, String expectedRearIdentity,
            ISceneTaskActionGate actionGate) = 71;
    /** Same replacement prepare contract with an optional explicit target gate for the pre-move phase. */
    String prepareRearVirtualCastReplacementWithTargetGate(String previousSessionId, long previousGeneration,
            long previousOwnerGeneration, String previousOwnerIdentity,
            int previousTaskId, int previousHostTaskId, String previousInputSessionId,
            String previousBaseComponent, String expectedRearIdentity, String expectedCandidateBaseComponent,
            int rearDisplayId, String operationId, long ownerGeneration, String ownerIdentity,
            in Surface candidateSurface, String readerPath, int taskId, int hostTaskId, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean densityCompatibility, int contentRotation, boolean fullViewport,
            ISceneTaskActionGate targetActionGate) = 79;
    /** Same typed empty-start contract as transaction 70, with a final host-owned action gate. */
    String startControlledRearVirtualCastWithGate(in Surface surface, String readerPath, int taskId, int hostTaskId,
            int density, String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean densityCompatibility, int contentRotation, boolean fullViewport,
            String expectedBaseComponent, int expectedRearDisplayId, String expectedRearIdentity,
            String operationId, long ownerGeneration, String ownerIdentity,
            ISceneTaskActionGate actionGate) = 72;
    /** Focuses one exact sole-task root already on display 0, without touching input-proxy or rear ownership. */
    int focusExactMainTask(int taskId, String expectedBaseComponent, String expectedTopComponent,
            ITaskFocusActionGate actionGate) = 73;
    /** Controlled physical-cast move; validates exact task/root and selected rear identity at the privileged move point. */
    String moveControlledPhysicalTask(int taskId, String packageName, String baseComponent,
            int sourceDisplayId, int targetDisplayId, String expectedRearIdentity) = 74;
    /** All-display authoritative exact task identity for legacy owner reconciliation; unavailable is distinct from absent. */
    String queryExactTaskPlacement(int taskId) = 75;
    /** One-shot bounded exact drawn-window plus InputDispatcher touchability proof; unavailable is unknown. */
    String queryExactPhysicalPresentation(int taskId, String packageName, String baseComponent,
            int displayId, String expectedRearIdentity) = 76;
    /** Controlled physical→virtual handoff: defer OEM launcher start until exact B cleanup. */
    String startControlledRearVirtualCastForPhysicalReplacement(in Surface surface, String readerPath,
            int taskId, int hostTaskId, int density, String component, IBinder owner,
            IRearGestureListener gestures, String viewport, boolean densityCompatibility,
            int contentRotation, boolean fullViewport, String expectedBaseComponent,
            int expectedRearDisplayId, String expectedRearIdentity, String operationId,
            long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            IPhysicalCastReaderLifecycle readerLifecycle) = 77;
    /** Restores OEM launcher only after an exact selected-display and empty/system-launcher recheck. */
    boolean restoreSubScreenLauncherForControlledReplacement(int displayId, String expectedRearIdentity) = 78;
    /** txn77-compatible physical→virtual start; admits only the exact journaled physical A beside the exact host. */
    String startControlledRearVirtualCastForPhysicalReplacementWithA(in Surface surface, String readerPath,
            int taskId, int hostTaskId, int density, String component, IBinder owner,
            IRearGestureListener gestures, String viewport, boolean densityCompatibility,
            int contentRotation, boolean fullViewport, String expectedBaseComponent,
            int expectedRearDisplayId, String expectedRearIdentity, String operationId,
            long ownerGeneration, String ownerIdentity, ISceneTaskActionGate actionGate,
            int expectedOldTaskId, String expectedOldPackage, String expectedOldBaseComponent,
            IPhysicalCastReaderLifecycle readerLifecycle) = 80;
    /** One-shot read of an already-confirmed Mod picker while its exact rear presenter owns global focus. */
    boolean isControlledCastPickerStillOnMain(int pickerTaskId, int targetTaskId,
            String expectedBaseComponent, String expectedTopComponent,
            int hostTaskId, int rearDisplayId, String rearIdentity) = 81;
    /** P→V's first return of A: re-attest the exact live B and the host's durable journal at dispatch. */
    String returnPhysicalAForVirtualReplacement(int oldTaskId, String oldPackage, String oldBase,
            int rearDisplayId, String rearIdentity, String operationId, long ownerGeneration,
            String ownerIdentity, String sessionId, long sessionGeneration, int newTaskId, String newBase,
            String readerId, long readerGeneration, ISceneTaskActionGate actionGate) = 82;
    /** Explicit close after a failed mixed handoff: only A on rear, B on main, and no virtual resources. */
    String returnPhysicalAAfterVirtualRollback(int oldTaskId, String oldPackage, String oldBase,
            int rearDisplayId, String rearIdentity, int newTaskId, String newPackage, String newBase,
            ISceneTaskActionGate actionGate) = 83;
    /** P→P A return: exact two-task rear snapshot plus live journal gate at the physical dispatch boundary. */
    String returnPhysicalAForPhysicalReplacement(int oldTaskId, String oldPackage, String oldBase,
            int rearDisplayId, String rearIdentity, String operationId, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity,
            IPhysicalReplacementMoveGate actionGate) = 84;
    /** P→P compensation: only restore A after B is MAIN and rear is empty or only the exact OEM launcher. */
    String restorePhysicalAForPhysicalReplacement(int oldTaskId, String oldPackage, String oldBase,
            int rearDisplayId, String rearIdentity, String operationId, long oldGeneration,
            int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity,
            IPhysicalReplacementMoveGate actionGate) = 85;
    /** Debug-only exact RC5 A/B experiment: attach exact fixture B at the bottom of selected rear display. */
    String moveRc5PhysicalFixtureBToRearBottom(int aTaskId, long aGeneration, int bTaskId,
            int aProcessPid, int bProcessPid, int rearDisplayId, String expectedRearIdentity,
            String operationId, ITaskFocusActionGate actionGate) = 86;
    /** Debug-only exact RC5 A/B experiment: request focus for exact B already attached on that rear display. */
    String focusRc5PhysicalFixtureBOnRear(int aTaskId, long aGeneration, int bTaskId,
            int aProcessPid, int bProcessPid, int rearDisplayId, String expectedRearIdentity,
            String operationId, ITaskFocusActionGate actionGate) = 87;
    /** Debug-only exact-operation cleanup for an abandoned bottom-stage desktop binding. */
    boolean clearRc5PhysicalFixtureBottomBinding(int aTaskId, long aGeneration, int bTaskId,
            int rearDisplayId, String expectedRearIdentity, String operationId) = 88;
    /** One product P→P transaction: exact B bottom move, strict interstage readback, then one focus request. */
    String moveControlledPhysicalReplacementBWithBottomFocus(int oldTaskId, String oldPackage, String oldBase,
            long oldGeneration, int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity,
            String operationId, IPhysicalReplacementMoveGate bottomGate,
            IPhysicalReplacementMoveGate focusGate) = 89;
    /** Normal controlled physical close: no-op for the exact hidden/asleep OEM launcher; restore only from empty. */
    boolean restoreSubScreenLauncherAfterNormalControlledClose(int displayId, String expectedRearIdentity) = 90;
    /** Host-only, primary user's existing AI wallpaper slots. */
    String listAiRearWallpapers(String languageTag) = 91;
    String copyAiRearWallpaper(String id, boolean thumbnail, in ParcelFileDescriptor destination) = 92;
    void replaceAiRearWallpaper(String id, in ParcelFileDescriptor original, in ParcelFileDescriptor replacement) = 93;
    boolean restoreAiRearWallpaper(String id, in ParcelFileDescriptor applied, in ParcelFileDescriptor original) = 94;
    /** Isolated, Shizuku-only shortcut cast. Never replaces an existing cast or target task. */
    String startParkedWallpaperCast(in Surface surface, String readerPath, int host, int density,
            String component, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, ISceneTaskActionGate launchGate) = 95;
    /** Releases the exact wallpaper cast. The callback is not a terminal cleanup acknowledgement. */
    void stopParkedWallpaperCast(String sessionId, IRearWallpaperOutputReleased receipt) = 96;
    String getRearNotificationRenderPlan(String packageName, int density, boolean compatibility, boolean full) = 97;
    String startRearNotificationCast(in Surface surface, String readerPath, int host, int density,
            String packageName, IBinder owner, IRearGestureListener gestures, String viewport,
            boolean compatibility, int rotation, boolean full, IRearNotificationTarget target) = 98;
}

