package top.zainzh.rearscreen;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolve platform methods and exported launcher components on the running ROM. */
final class RearSystemBridge {
    private RearSystemBridge() {}
    private static volatile String lastTaskSnapshotQuery = "unavailable";
    static String lastTaskSnapshotQueryForDiagnostics() { return lastTaskSnapshotQuery; }
    static Object service(String name, String stub) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("Missing system service: " + name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    static final class TaskLocation {
        final int rootTask, displayId;
        TaskLocation(int rootTask, int displayId) { this.rootTask = rootTask; this.displayId = displayId; }
    }

    /** True only when the target is the sole well-formed task member of one exact root on display 0. */
    static boolean isSoleTaskRoot(String stacks, int task, int expectedDisplay) {
        if (stacks == null || stacks.trim().isEmpty() || task < 0 || expectedDisplay < 0) return false;
        Pattern headerPattern = Pattern.compile("^\\s*(?:RootTask|Stack)\\s+id=(\\d+)\\b.*$");
        Pattern displayPattern = Pattern.compile("\\bdisplayId=(\\d+)\\b");
        Pattern taskPattern = Pattern.compile("\\btaskId=(\\d+):");
        Pattern taskMarker = Pattern.compile("\\btaskId=");
        int root = -1, display = -1, members = 0, targetMembers = 0;
        boolean malformed = false;
        int matchingRoots = 0;
        boolean sole = false;
        for (String line : stacks.split("\\n")) {
            Matcher header = headerPattern.matcher(line);
            if (header.find()) {
                if (root >= 0 && display == expectedDisplay && targetMembers > 0) {
                    matchingRoots++;
                    sole = !malformed && members == 1 && targetMembers == 1;
                }
                root = Integer.parseInt(header.group(1));
                Matcher displayMatch = displayPattern.matcher(line);
                display = displayMatch.find() ? Integer.parseInt(displayMatch.group(1)) : -1;
                members = 0; targetMembers = 0; malformed = false;
                continue;
            }
            if (root < 0 || !taskMarker.matcher(line).find()) continue;
            Matcher taskMatch = taskPattern.matcher(line);
            int parsed = 0;
            while (taskMatch.find()) {
                parsed++;
                members++;
                if (Integer.parseInt(taskMatch.group(1)) == task) targetMembers++;
            }
            if (parsed == 0) malformed = true;
        }
        if (root >= 0 && display == expectedDisplay && targetMembers > 0) {
            matchingRoots++;
            sole = !malformed && members == 1 && targetMembers == 1;
        }
        return matchingRoots == 1 && sole;
    }

    /** Exact typed task base joined to the one task line in its sole root, without Android parsing helpers. */
    static boolean isExactSoleTaskRoot(String stacks, int task, String expectedBaseComponent,
            int expectedDisplay) {
        if (expectedBaseComponent == null || !isSoleTaskRoot(stacks, task, expectedDisplay)) return false;
        Pattern header = Pattern.compile("^\\s*(?:RootTask|Stack)\\s+id=(\\d+)\\b.*$");
        Pattern display = Pattern.compile("\\bdisplayId=(\\d+)\\b");
        Pattern row = Pattern.compile("\\btaskId=(\\d+):\\s*([^\\s]+)");
        int currentDisplay = -1, matches = 0;
        String actual = null;
        for (String line : stacks.split("\\n")) {
            Matcher root = header.matcher(line);
            if (root.find()) {
                Matcher displayMatch = display.matcher(line);
                currentDisplay = displayMatch.find() ? Integer.parseInt(displayMatch.group(1)) : -1;
                continue;
            }
            Matcher taskRow = row.matcher(line);
            while (taskRow.find()) {
                if (Integer.parseInt(taskRow.group(1)) == task && currentDisplay == expectedDisplay) {
                    matches++;
                    actual = taskRow.group(2);
                }
            }
        }
        return matches == 1 && canonicalComponent(actual).equals(canonicalComponent(expectedBaseComponent));
    }

    private static String canonicalComponent(String value) {
        if (value == null) return "";
        String component = value.trim();
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1) return "";
        String pkg = component.substring(0, slash);
        String cls = component.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        else if (!cls.contains(".")) cls = pkg + "." + cls;
        return pkg + "/" + cls;
    }

    /**
     * Uses ActivityTaskManager's existing task snapshot rather than starting `am` for every
     * final-window READY. The returned records contain only task id, stable base component,
     * current top component and display.
     */
    static List<SceneTaskIdentity.Record> runningTaskSnapshot() throws Exception {
        Object manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
        return runningTaskSnapshot(manager);
    }

    /** Package-visible for deterministic task-query contract tests using a binder-shaped fake. */
    static List<SceneTaskIdentity.Record> runningTaskSnapshot(Object manager) throws Exception {
        Method query = null;
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getTasks")) continue;
            Class<?>[] types = method.getParameterTypes();
            // The Binder contract accepts a display selector. Always request all displays so
            // scene ownership and cleanup compare against the same global task identities.
            if (types.length == 4 && types[0] == int.class && types[1] == boolean.class
                    && types[2] == boolean.class && types[3] == int.class) {
                query = method; break;
            }
            if (types.length == 3 && types[0] == int.class && types[1] == boolean.class && types[2] == boolean.class) {
                if (query == null) query = method;
            }
            if (types.length == 1 && types[0] == int.class && query == null) query = method;
        }
        if (query == null) throw new NoSuchMethodException("IActivityTaskManager.getTasks");
        Class<?>[] parameters = query.getParameterTypes();
        lastTaskSnapshotQuery = "getTasks/" + parameters.length
                + " display=" + (parameters.length == 4 ? "INVALID_DISPLAY" : "legacy");
        Object raw;
        if (parameters.length == 4) raw = query.invoke(manager, 1000, false, false, -1); // INVALID_DISPLAY: all displays
        else if (parameters.length == 3) raw = query.invoke(manager, 1000, false, false);
        else raw = query.invoke(manager, 1000);
        CompatibilityDiagnostics.record("scene_task_snapshot", "method=" + lastTaskSnapshotQuery
                + " records=" + (raw instanceof List ? ((List<?>) raw).size() : "invalid"));
        if (!(raw instanceof List)) throw new IllegalStateException("task_snapshot_not_list");
        List<SceneTaskIdentity.Record> result = new ArrayList<>();
        for (Object info : (List<?>) raw) {
            if (info == null) throw new IllegalStateException("task_snapshot_null_row");
            int task = ((Number) info.getClass().getField("taskId").get(info)).intValue();
            int display = ((Number) info.getClass().getField("displayId").get(info)).intValue();
            Object base = info.getClass().getField("baseActivity").get(info);
            Object top = info.getClass().getField("topActivity").get(info);
            String baseComponent = base == null ? null : (String) base.getClass().getMethod("flattenToString").invoke(base);
            String component = top == null ? null : (String) top.getClass().getMethod("flattenToString").invoke(top);
            Boolean visible = null;
            try {
                Object value = info.getClass().getMethod("isVisible").invoke(info);
                if (value instanceof Boolean) visible = (Boolean) value;
            } catch (NoSuchMethodException oldApi) {
                // Older platform TaskInfo has no visibility bit. Scene task ownership remains
                // queryable, but foreground presentation will fail closed without this evidence.
            }
            result.add(new SceneTaskIdentity.Record(task, display, baseComponent, component, visible));
        }
        return result;
    }
    static TaskLocation taskLocation(String stacks, int task) {
        int root = -1, display = -1;
        for (String line : stacks.split("\n")) {
            Matcher header = Pattern.compile("^\\s*(?:RootTask|Stack)\\s+id=(\\d+)\\b").matcher(line);
            if (header.find()) {
                root = Integer.parseInt(header.group(1));
                Matcher id = Pattern.compile("\\bdisplayId=(\\d+)\\b").matcher(line);
                display = id.find() ? Integer.parseInt(id.group(1)) : -1;
            }
            if (root >= 0 && Pattern.compile("\\btaskId=" + task + ":").matcher(line).find())
                return new TaskLocation(root, display);
        }
        return null;
    }
    static int rootTask(String stacks, int task) {
        TaskLocation location = taskLocation(stacks, task);
        return location == null ? -1 : location.rootTask;
    }
    static boolean moveTask(int task, int display) throws Exception {
        if (task < 0 || display < 0) throw new IllegalArgumentException("Invalid task/display");
        DisplayRotationController.Result stacks = DisplayRotationController.runCommand("am", "stack", "list");
        if (stacks.exitCode != 0) throw new IllegalStateException("Cannot read root tasks: " + stacks.output);
        TaskLocation location = taskLocation(stacks.output, task);
        if (location == null) throw new IllegalStateException("Root task not found for task " + task);
        int root = location.rootTask;
        if (location.displayId == display) {
            // Moving a root to its current display is rejected; restore its foreground instead.
            DisplayRotationController.Result focused = DisplayRotationController.runCommand("am", "task", "focus", Integer.toString(task));
            boolean ok = commandSucceeded(focused);
            CompatibilityDiagnostics.record("task_move", "method=am_task_focus task=" + task + " display=" + display
                    + " success=" + ok + " response=" + focused.output);
            return ok;
        }
        try {
            Object manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
            manager.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(manager, root, display);
            CompatibilityDiagnostics.record("task_move", "method=moveRootTaskToDisplay root=" + root + " display=" + display);
            return true;
        } catch (ReflectiveOperationException e) {
            // The system command resolves its own Binder interface, including vendor transaction order.
            DisplayRotationController.Result result = DisplayRotationController.runCommand("am", "display", "move-stack", Integer.toString(root), Integer.toString(display));
            boolean ok = commandSucceeded(result);
            CompatibilityDiagnostics.record("task_move", "method=am_display_move_stack root=" + root + " display=" + display
                    + " success=" + ok + " reflection=" + e + " response=" + result.output);
            return ok;
        }
    }
    enum SoleRootMoveDispatch { REJECTED_BEFORE_SEND, SENT, MAY_HAVE_BEEN_SENT }
    static final class SoleRootMoveDiagnostic {
        final SoleRootMoveDispatch dispatch;
        final String reason;
        SoleRootMoveDiagnostic(SoleRootMoveDispatch dispatch, String reason) {
            this.dispatch = dispatch; this.reason = reason;
        }
    }

    /** Move only one verified root; distinguish a pre-send rejection from a lost Binder reply. */
    static SoleRootMoveDispatch moveSoleTaskRoot(int task, String expectedBaseComponent,
            int sourceDisplay, int targetDisplay) throws Exception {
        return moveSoleTaskRoot(task, expectedBaseComponent, sourceDisplay, targetDisplay, null);
    }

    static SoleRootMoveDispatch moveSoleTaskRoot(int task, String expectedBaseComponent,
            int sourceDisplay, int targetDisplay, java.util.function.BooleanSupplier dispatchGate) throws Exception {
        if (task <= 0 || expectedBaseComponent == null || sourceDisplay < 0
                || targetDisplay < 0 || sourceDisplay == targetDisplay)
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        DisplayRotationController.Result stacks = DisplayRotationController.runCommand("am", "stack", "list");
        if (stacks.exitCode != 0 || !isSoleTaskRoot(stacks.output, task, sourceDisplay))
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        SceneTaskIdentity.Result identity = SceneTaskIdentity.verifyOwner(
                SceneTaskIdentity.parseStackList(stacks.output), task, expectedBaseComponent,
                sourceDisplay, "am_stack_list");
        if (!identity.verified()) return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        TaskLocation location = taskLocation(stacks.output, task);
        if (location == null || location.displayId != sourceDisplay)
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        final Object manager;
        final Method move;
        try {
            manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
            move = manager.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
        } catch (Exception unavailableBeforeInvoke) {
            CompatibilityDiagnostics.record("controlled_physical_move", "dispatch=rejected_before_send");
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        }
        SoleRootMoveDispatch dispatch = dispatchRootMoveAfterGate(dispatchGate,
                () -> move.invoke(manager, location.rootTask, targetDisplay));
        if (dispatch == SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT) {
            // The remote may have applied the move before the Binder reply failed. The caller
            // must read the exact task back; it must not resend this side effect.
            CompatibilityDiagnostics.record("controlled_physical_move", "dispatch=maybe_sent");
        } else if (dispatch == SoleRootMoveDispatch.REJECTED_BEFORE_SEND) {
            // Method resolution/access failed before the remote method was invoked.
            CompatibilityDiagnostics.record("controlled_physical_move", "dispatch=rejected_before_send");
        }
        return dispatch;
    }

    /** One exact Debug experiment move: put the verified root at the bottom of the selected display. */
    static boolean supportsPhysicalReplacementBottomFocus() {
        try {
            Object manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
            manager.getClass().getMethod("moveRootTaskToDisplayOnTopOrBottom",
                    int.class, int.class, boolean.class);
            manager.getClass().getMethod("setFocusedTask", int.class);
            return true;
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    /** One exact Debug experiment move: put the verified root at the bottom of the selected display. */
    static SoleRootMoveDispatch moveSoleTaskRootToDisplayBottom(int task, String expectedBaseComponent,
            int sourceDisplay, int targetDisplay, java.util.function.BooleanSupplier dispatchGate) throws Exception {
        return moveSoleTaskRootToDisplayBottomDiagnosed(task, expectedBaseComponent, sourceDisplay,
                targetDisplay, dispatchGate).dispatch;
    }

    /** Debug probe variant of the same dispatch sequence; returns fixed reason codes only. */
    static SoleRootMoveDiagnostic moveSoleTaskRootToDisplayBottomDiagnosed(int task,
            String expectedBaseComponent, int sourceDisplay, int targetDisplay,
            java.util.function.BooleanSupplier dispatchGate) throws Exception {
        if (task <= 0 || expectedBaseComponent == null || sourceDisplay < 0
                || targetDisplay <= 0 || sourceDisplay == targetDisplay)
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "invalid_arguments");
        DisplayRotationController.Result stacks = DisplayRotationController.runCommand("am", "stack", "list");
        if (stacks.exitCode != 0)
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "root_snapshot_unavailable");
        if (!isExactSoleTaskRoot(stacks.output, task, expectedBaseComponent, sourceDisplay))
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "exact_root_mismatch");
        TaskLocation location = taskLocation(stacks.output, task);
        if (location == null || location.displayId != sourceDisplay)
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "source_location_mismatch");
        final Object manager;
        final Method move;
        try {
            manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
        } catch (Exception unavailable) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "atms_unavailable");
        }
        try {
            move = manager.getClass().getMethod("moveRootTaskToDisplayOnTopOrBottom",
                    int.class, int.class, boolean.class);
        } catch (NoSuchMethodException unavailable) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "move_method_unavailable");
        } catch (SecurityException denied) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "reflection_access_denied");
        } catch (Exception unavailable) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "move_method_unavailable");
        }
        return dispatchRootMoveAfterGateDiagnosed(dispatchGate,
                () -> move.invoke(manager, location.rootTask, targetDisplay, false));
    }

    static SoleRootMoveDiagnostic dispatchRootMoveAfterGateDiagnosed(
            java.util.function.BooleanSupplier dispatchGate, RootTaskMoveInvoker invoker) {
        try {
            if (invoker == null || (dispatchGate != null && !dispatchGate.getAsBoolean()))
                return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "final_gate_rejected");
        } catch (RuntimeException gateFailure) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "final_gate_rejected");
        }
        try {
            invoker.invoke();
            return diagnosed(SoleRootMoveDispatch.SENT, "invoked");
        } catch (java.lang.reflect.InvocationTargetException replyUnknown) {
            return diagnosed(SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT, "invoke_reply_unknown");
        } catch (IllegalAccessException accessDenied) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "reflection_access_denied");
        } catch (ReflectiveOperationException rejected) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "reflection_invoke_rejected");
        } catch (RuntimeException uncertain) {
            return diagnosed(SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT, "invoke_reply_unknown");
        } catch (Exception rejected) {
            return diagnosed(SoleRootMoveDispatch.REJECTED_BEFORE_SEND, "reflection_invoke_rejected");
        }
    }

    private static SoleRootMoveDiagnostic diagnosed(SoleRootMoveDispatch dispatch, String reason) {
        return new SoleRootMoveDiagnostic(dispatch, reason);
    }

    /** Request focus for one exact root already on its display; caller must use readback as outcome evidence. */
    static SoleRootMoveDispatch focusSoleTaskOnDisplay(int task, String expectedBaseComponent,
            int expectedDisplay, java.util.function.BooleanSupplier dispatchGate) throws Exception {
        if (task <= 0 || expectedBaseComponent == null || expectedDisplay <= 0)
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        DisplayRotationController.Result stacks = DisplayRotationController.runCommand("am", "stack", "list");
        if (stacks.exitCode != 0 || !isExactSoleTaskRoot(stacks.output, task, expectedBaseComponent, expectedDisplay))
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        final Object manager;
        final Method focus;
        try {
            manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
            focus = manager.getClass().getMethod("setFocusedTask", int.class);
        } catch (Exception unavailableBeforeInvoke) {
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        }
        return dispatchRootMoveAfterGate(dispatchGate, () -> focus.invoke(manager, task));
    }

    @FunctionalInterface interface RootTaskMoveInvoker { void invoke() throws Exception; }

    /** A failing authorization callback is pre-send; only exceptions from the actual invoke are ambiguous. */
    static SoleRootMoveDispatch dispatchRootMoveAfterGate(java.util.function.BooleanSupplier dispatchGate,
            RootTaskMoveInvoker invoker) {
        try {
            if (invoker == null || (dispatchGate != null && !dispatchGate.getAsBoolean()))
                return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        } catch (RuntimeException gateFailure) {
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        }
        try {
            invoker.invoke();
            return SoleRootMoveDispatch.SENT;
        } catch (java.lang.reflect.InvocationTargetException replyUnknown) {
            return SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT;
        } catch (ReflectiveOperationException notDispatched) {
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        } catch (RuntimeException uncertain) {
            return SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT;
        } catch (Exception unavailable) {
            return SoleRootMoveDispatch.REJECTED_BEFORE_SEND;
        }
    }
    static boolean commandSucceeded(DisplayRotationController.Result result) {
        return result.exitCode == 0 && !Pattern.compile("(?im)\\b(?:Error:|Exception|Permission Denial|does not exist|not found)").matcher(result.output).find();
    }
    static Context systemContext() throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("currentActivityThread").invoke(null);
        if (instance == null) throw new IllegalStateException("System context unavailable");
        return (Context) thread.getMethod("getSystemContext").invoke(instance);
    }
    static List<String> launcherCandidates() {
        List<String> result = new ArrayList<>();
        try {
            PackageManager pm = systemContext().getPackageManager();
            for (String pkg : new String[]{"com.xiaomi.subscreencenter", "com.xiaomi.mirror"}) {
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                    if (info.activities == null) continue;
                    for (ActivityInfo activity : info.activities) {
                        String name = activity.name.toLowerCase(java.util.Locale.ROOT);
                        if (activity.exported && activity.enabled && name.contains("subscreen") && name.contains("launcher"))
                            result.add(pkg + "/" + activity.name);
                    }
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
        } catch (Exception e) { CompatibilityDiagnostics.record("launcher_discovery", e.toString()); }
        if (result.isEmpty()) {
            // Some shell hosts lack an ActivityThread context. Resolve explicit platform entrances
            // through PackageManager's command before trying them; never report an absent component as restored.
            for (String component : new String[]{"com.xiaomi.subscreencenter/.SubScreenLauncher",
                    "com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity", "com.xiaomi.mirror/.SubscreenLauncher"}) {
                try {
                    DisplayRotationController.Result resolved = DisplayRotationController.runCommand("cmd", "package", "resolve-activity", "--brief", "-n", component);
                    String pkg = component.substring(0, component.indexOf('/'));
                    if (resolved.exitCode == 0 && resolved.output.contains(pkg + "/")) result.add(component);
                } catch (Exception e) { CompatibilityDiagnostics.record("launcher_resolve", component + ": " + e); }
            }
        }
        // Keep the established entrance first when the ROM still exposes it.
        result.sort((a, b) -> Boolean.compare(!a.endsWith(".SubScreenLauncher"), !b.endsWith(".SubScreenLauncher")));
        return result;
    }
    /** Validate the exact launcher task against PackageManager and the system user's task identity. */
    public static boolean isValidatedRearLauncher(String flattenedComponent, int userId) {
        if (flattenedComponent == null || flattenedComponent.isBlank() || userId < 0) return false;
        String packageName = flattenedComponent.contains("/")
                ? flattenedComponent.substring(0, flattenedComponent.indexOf('/')) : "";
        if (!packageName.equals("com.xiaomi.subscreencenter") && !packageName.equals("com.xiaomi.mirror"))
            return false;
        try {
            Context context = systemContext();
            // Android's multi-user UID range is 100000; this system context is the
            // authoritative user for launcher tasks, unlike the client process user.
            int systemUser = context.getApplicationInfo().uid / 100000;
            if (userId != systemUser) return false;
            android.content.ComponentName expected = android.content.ComponentName.unflattenFromString(flattenedComponent);
            if (expected == null) return false;
            if (!launcherCandidates().contains(expected.flattenToString())) return false;
            ActivityInfo info = context.getPackageManager().getActivityInfo(expected, 0);
            return info.enabled && info.exported && expected.getPackageName().equals(info.packageName)
                    && expected.getClassName().equals(info.name)
                    && info.applicationInfo != null
                    && ((info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        || (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    && (expected.getPackageName().equals("com.xiaomi.subscreencenter")
                        || expected.getPackageName().equals("com.xiaomi.mirror"));
        } catch (Exception unavailable) {
            CompatibilityDiagnostics.record("launcher_identity", "component=" + flattenedComponent + " unavailable=" + unavailable.getClass().getSimpleName());
            return false;
        }
    }
    static boolean restoreLauncher() throws Exception {
        int display = RearDisplay.requireId();
        List<String> candidates = launcherCandidates();
        for (String component : candidates) {
            DisplayRotationController.Result result = DisplayRotationController.runCommand("am", "start", "--display", Integer.toString(display), "-n", component);
            boolean ok = commandSucceeded(result);
            CompatibilityDiagnostics.record("launcher_restore", "component=" + component + " display=" + display + " success=" + ok + " response=" + result.output);
            if (ok) return true;
        }
        CompatibilityDiagnostics.record("launcher_restore", "NO_EXPORTED_REAR_LAUNCHER candidates=" + candidates.size());
        return false;
    }
}
