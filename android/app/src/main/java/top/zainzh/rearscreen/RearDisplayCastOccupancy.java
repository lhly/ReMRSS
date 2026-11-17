package top.zainzh.rearscreen;

import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Cast admission only. Task existence/legacy monitoring must not use this visibility filter. */
final class RearDisplayCastOccupancy {
    enum OwnerDisposition { EMPTY, CORE_SCENE, HOST, SYSTEM_LAUNCHER, UNKNOWN }
    enum ControlledLauncherRestoreDisposition { EMPTY, SYSTEM_LAUNCHER, UNKNOWN }
    enum ControlledLauncherRestoreAction { REJECT, NO_OP, START }
    @FunctionalInterface interface SceneTaskMatcher { boolean matches(String foreground, int displayId); }
    @FunctionalInterface interface ExactLauncherMatcher { boolean matches(Task task); }
    static final class Task {
        final int taskId;
        final String basePackage, topPackage, baseComponent, topComponent;
        final int userId;
        final Boolean visible, requested, sleeping;

        Task(int taskId, String basePackage, String topPackage, Boolean visible, Boolean requested, Boolean sleeping) {
            this(taskId, basePackage, topPackage, null, visible, requested, sleeping);
        }

        Task(int taskId, String basePackage, String topPackage, String baseComponent,
             Boolean visible, Boolean requested, Boolean sleeping) {
            this(taskId, basePackage, topPackage, baseComponent, null, visible, requested, sleeping);
        }

        Task(int taskId, String basePackage, String topPackage, String baseComponent, String topComponent,
             Boolean visible, Boolean requested, Boolean sleeping) {
            this(taskId, basePackage, topPackage, baseComponent, topComponent, -1, visible, requested, sleeping);
        }

        Task(int taskId, String basePackage, String topPackage, String baseComponent, String topComponent,
             int userId, Boolean visible, Boolean requested, Boolean sleeping) {
            this.taskId = taskId;
            this.basePackage = basePackage;
            this.topPackage = topPackage;
            this.baseComponent = baseComponent;
            this.topComponent = topComponent;
            this.userId = userId;
            this.visible = visible;
            this.requested = requested;
            this.sleeping = sleeping;
        }

        boolean clearlyHidden(int displayState) {
            // OFF/DOZE, a pending show, or missing evidence must never release another app's claim.
            return displayState == Display.STATE_ON && Boolean.FALSE.equals(visible)
                    && Boolean.FALSE.equals(requested) && Boolean.FALSE.equals(sleeping);
        }
    }

    static final class Snapshot {
        final int displayId, displayState, powerBefore, powerAfter;
        final int focusedTaskId;
        final List<Task> tasks;
        final String source, failure;

        Snapshot(int displayId, int displayState, List<Task> tasks, String source, String failure) {
            this(displayId, displayState, displayState, displayState, -1, tasks, source, failure);
        }

        Snapshot(int displayId, int displayState, int powerBefore, int powerAfter,
                 List<Task> tasks, String source, String failure) {
            this(displayId, displayState, powerBefore, powerAfter, -1, tasks, source, failure);
        }

        Snapshot(int displayId, int displayState, int powerBefore, int powerAfter, int focusedTaskId,
                 List<Task> tasks, String source, String failure) {
            this.displayId = displayId;
            this.displayState = displayState;
            this.powerBefore = powerBefore;
            this.powerAfter = powerAfter;
            this.focusedTaskId = focusedTaskId;
            this.tasks = tasks;
            this.source = source;
            this.failure = failure;
        }

        String toJson() {
            try {
                JSONObject value = new JSONObject().put("display", displayId).put("power", displayState)
                        .put("powerBefore", powerBefore).put("powerAfter", powerAfter)
                        .put("source", source).put("status", failure == null ? "ok" : "unavailable");
                if (focusedTaskId >= 0) value.put("focusedTask", focusedTaskId);
                if (failure != null) value.put("reason", failure);
                JSONArray rows = new JSONArray();
                for (Task task : tasks) rows.put(new JSONObject().put("task", task.taskId)
                        .put("base", task.basePackage).put("top", task.topPackage)
                        .put("baseComponent", task.baseComponent)
                        .put("topComponent", task.topComponent)
                        .put("userId", task.userId)
                        .put("visible", task.visible).put("requested", task.requested).put("sleeping", task.sleeping));
                return value.put("tasks", rows).toString();
            } catch (org.json.JSONException invalid) {
                return "{\"status\":\"unavailable\",\"reason\":\"encoding\"}";
            }
        }

        static Snapshot parse(String encoded, int expectedDisplay) {
            try {
                JSONObject value = new JSONObject(encoded);
                String source = safeSource(value.optString("source", "unavailable"));
                int power = value.optInt("power", 0);
                int powerBefore = value.optInt("powerBefore", power);
                int powerAfter = value.optInt("powerAfter", power);
                if (!"ok".equals(value.getString("status")))
                    return unavailable(expectedDisplay, source, safeFailure(value.optString("reason", "snapshot_unavailable")),
                            power, powerBefore, powerAfter);
                if (value.getInt("display") != expectedDisplay)
                    return unavailable(expectedDisplay, source, "wrong_display", power, powerBefore, powerAfter);
                JSONArray rows = value.getJSONArray("tasks");
                List<Task> tasks = new ArrayList<>();
                java.util.Set<Integer> ids = new java.util.HashSet<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    int id = row.getInt("task");
                    if (id < 0 || !ids.add(id)) throw new IllegalArgumentException("invalid_task_identity");
                    tasks.add(new Task(id, optionalPackage(row, "base"), optionalPackage(row, "top"),
                            optionalString(row, "baseComponent"), optionalString(row, "topComponent"),
                            row.optInt("userId", -1),
                            optionalBoolean(row, "visible"),
                            optionalBoolean(row, "requested"), optionalBoolean(row, "sleeping")));
                }
                return new Snapshot(expectedDisplay, power, powerBefore, powerAfter,
                        value.optInt("focusedTask", -1), tasks, source, null);
            } catch (Exception invalid) {
                return unavailable(expectedDisplay, "unavailable", "snapshot_unreadable");
            }
        }

        private static String safeSource(String source) {
            if ("activity_task".equals(source)) return source;
            if ("activity_task_focused_stack".equals(source)) return source;
            if ("activity_focused_stack".equals(source)) return source;
            if ("am_stack_list".equals(source)) return source;
            return "unavailable";
        }

        private static String safeFailure(String failure) {
            switch (failure) {
                case "invalid_display":
                case "query_failed":
                case "query_or_parse_failed":
                case "wrong_display":
                case "snapshot_unreadable":
                case "snapshot_unavailable":
                case "invalid_task_identity":
                case "invalid_visibility":
                case "encoding":
                    return failure;
                default:
                    return "snapshot_unavailable";
            }
        }

        private static String optionalPackage(JSONObject row, String key) throws org.json.JSONException {
            return row.isNull(key) ? null : VirtualCastRequest.packageName(row.getString(key));
        }

        private static String optionalString(JSONObject row, String key) throws org.json.JSONException {
            if (!row.has(key) || row.isNull(key)) return null;
            Object value = row.get(key);
            if (!(value instanceof String)) throw new IllegalArgumentException("invalid_component");
            return (String) value;
        }

        private static Boolean optionalBoolean(JSONObject row, String key) throws org.json.JSONException {
            if (row.isNull(key)) return null;
            Object value = row.get(key);
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("invalid_visibility");
            return (Boolean) value;
        }
    }

    static final class Result {
        final OwnerDisposition disposition;
        final String foreground;
        final int hiddenTasks;
        Result(OwnerDisposition disposition, String foreground, int hiddenTasks) {
            this.disposition = disposition;
            this.foreground = foreground;
            this.hiddenTasks = hiddenTasks;
        }
        boolean allowed() { return disposition != OwnerDisposition.UNKNOWN; }
    }

    private RearDisplayCastOccupancy() {}

    static Snapshot unavailable(int displayId, String source, String reason) {
        return unavailable(displayId, source, reason, 0, 0, 0);
    }

    static Snapshot unavailable(int displayId, String source, String reason,
                                int power, int powerBefore, int powerAfter) {
        return new Snapshot(displayId, power, powerBefore, powerAfter,
                java.util.Collections.emptyList(), Snapshot.safeSource(source), Snapshot.safeFailure(reason));
    }

    static Result query(ITaskService service, String hostPackage, int displayId) throws android.os.RemoteException {
        return query(service, hostPackage, displayId,
                top.zainzh.rearscreen.platform.RearCapabilityCore::isActiveSceneTaskForeground);
    }

    static Result query(ITaskService service, String hostPackage, int displayId,
                        SceneTaskMatcher scenes) throws android.os.RemoteException {
        Snapshot snapshot = Snapshot.parse(service.getDisplayCastOccupancy(displayId), displayId);
        if (snapshot.failure != null) throw new IllegalStateException("无法确认背屏占用状态，请稍后重试");
        return evaluate(snapshot, hostPackage, scenes);
    }

    static Result evaluate(Snapshot snapshot, String hostPackage, SceneTaskMatcher scenes) {
        return evaluate(snapshot, hostPackage, scenes, RearDisplayCastOccupancy::isExactSystemLauncher);
    }

    static Result evaluate(Snapshot snapshot, String hostPackage, SceneTaskMatcher scenes,
            ExactLauncherMatcher launcherMatcher) {
        Result selected = new Result(OwnerDisposition.EMPTY, null, 0);
        int hidden = 0;
        if (snapshot.failure != null) return new Result(OwnerDisposition.UNKNOWN, null, 0);
        for (Task task : snapshot.tasks) {
            if (task.clearlyHidden(snapshot.displayState)) { hidden++; continue; }
            String owner = task.basePackage != null ? task.basePackage : task.topPackage;
            if (owner == null) return new Result(OwnerDisposition.UNKNOWN, null, hidden);
            String foreground = owner + ":" + task.taskId;
            OwnerDisposition disposition = classify(foreground, task, hostPackage, snapshot.displayId, scenes, launcherMatcher);
            if (disposition == OwnerDisposition.UNKNOWN)
                return new Result(disposition, foreground, hidden);
            // An unrelated Activity hosted inside an otherwise permitted task is still protected.
            if (task.topPackage != null && !owner.equals(task.topPackage)) {
                String top = task.topPackage + ":" + task.taskId;
                if (classify(top, task, hostPackage, snapshot.displayId, scenes, launcherMatcher) == OwnerDisposition.UNKNOWN)
                    return new Result(OwnerDisposition.UNKNOWN, top, hidden);
            }
            if (selected.foreground == null) selected = new Result(disposition, foreground, hidden);
        }
        return new Result(selected.disposition, selected.foreground, hidden);
    }

    /** True only for a complete one-row snapshot of the exact task allowed to remain on the rear. */
    static boolean containsOnlyExactTask(String encoded, int displayId, int taskId,
            String packageName, String baseComponent) {
        Snapshot snapshot = Snapshot.parse(encoded, displayId);
        if (!stableSnapshot(snapshot) || taskId <= 0 || packageName == null || baseComponent == null
                || snapshot.tasks.size() != 1) return false;
        Task task = snapshot.tasks.get(0);
        return task.taskId == taskId && packageName.equals(task.basePackage)
                && packageName.equals(task.topPackage) && baseComponent.equals(task.baseComponent);
    }

    /** Rear restoration may target only an empty display, the exact still-owned A task, or its launcher. */
    static boolean safeForExactPhysicalRestore(String encoded, int displayId, int taskId,
            String packageName, String baseComponent) {
        return safeForExactPhysicalRestore(encoded, displayId, taskId, packageName, baseComponent,
                RearDisplayCastOccupancy::isExactSystemLauncher);
    }

    static boolean safeForExactPhysicalRestore(String encoded, int displayId, int taskId,
            String packageName, String baseComponent, ExactLauncherMatcher launcherMatcher) {
        Snapshot snapshot = Snapshot.parse(encoded, displayId);
        if (!stableSnapshot(snapshot) || taskId <= 0 || packageName == null || baseComponent == null) return false;
        if (snapshot.tasks.isEmpty()) return true;
        if (containsOnlyExactTask(encoded, displayId, taskId, packageName, baseComponent)) return true;
        if (snapshot.tasks.size() != 1) return false;
        Task task = snapshot.tasks.get(0);
        return launcherMatcher.matches(task);
    }

    static boolean emptyOrOnlySystemLauncher(String encoded, int displayId) {
        return emptyOrOnlySystemLauncher(encoded, displayId, RearDisplayCastOccupancy::isExactSystemLauncher);
    }

    static boolean emptyOrOnlySystemLauncher(String encoded, int displayId, ExactLauncherMatcher launcherMatcher) {
        Snapshot snapshot = Snapshot.parse(encoded, displayId);
        if (!stableSnapshot(snapshot)) return false;
        if (snapshot.tasks.isEmpty()) return true;
        if (snapshot.tasks.size() != 1) return false;
        Task task = snapshot.tasks.get(0);
        return launcherMatcher.matches(task);
    }

    /** Strict typed topology for controlled launcher restoration; hidden foreign rows stay UNKNOWN. */
    static ControlledLauncherRestoreDisposition controlledLauncherRestoreDisposition(Snapshot snapshot,
            int expectedDisplay, ExactLauncherMatcher launcherMatcher) {
        if (!stableSnapshot(snapshot) || snapshot.displayId != expectedDisplay
                || snapshot.source == null || !"activity_task".equals(snapshot.source)
                || launcherMatcher == null) return ControlledLauncherRestoreDisposition.UNKNOWN;
        int power = snapshot.displayState;
        if (power != Display.STATE_ON && power != Display.STATE_OFF
                && power != Display.STATE_DOZE && power != Display.STATE_DOZE_SUSPEND)
            return ControlledLauncherRestoreDisposition.UNKNOWN;
        if (snapshot.tasks.isEmpty()) return ControlledLauncherRestoreDisposition.EMPTY;
        if (snapshot.tasks.size() != 1) return ControlledLauncherRestoreDisposition.UNKNOWN;
        Task task = snapshot.tasks.get(0);
        if (task.taskId <= 0 || !launcherMatcher.matches(task)
                || task.visible == null || task.requested == null || task.sleeping == null)
            return ControlledLauncherRestoreDisposition.UNKNOWN;
        boolean activeVisible = power == Display.STATE_ON
                && Boolean.TRUE.equals(task.visible) && Boolean.TRUE.equals(task.requested)
                && Boolean.FALSE.equals(task.sleeping);
        boolean hiddenIdle = power == Display.STATE_ON
                && Boolean.FALSE.equals(task.visible) && Boolean.FALSE.equals(task.requested)
                && Boolean.FALSE.equals(task.sleeping);
        boolean asleep = power != Display.STATE_ON
                && Boolean.FALSE.equals(task.requested) && Boolean.TRUE.equals(task.sleeping);
        return activeVisible || hiddenIdle || asleep
                ? ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER
                : ControlledLauncherRestoreDisposition.UNKNOWN;
    }

    static ControlledLauncherRestoreAction controlledLauncherRestoreAction(Snapshot first, Snapshot second,
            int expectedDisplay, ExactLauncherMatcher launcherMatcher) {
        if (!sameStableTypedSnapshot(first, second)) return ControlledLauncherRestoreAction.REJECT;
        ControlledLauncherRestoreDisposition before = controlledLauncherRestoreDisposition(
                first, expectedDisplay, launcherMatcher);
        ControlledLauncherRestoreDisposition after = controlledLauncherRestoreDisposition(
                second, expectedDisplay, launcherMatcher);
        if (before != after) return ControlledLauncherRestoreAction.REJECT;
        if (after == ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER)
            return ControlledLauncherRestoreAction.NO_OP;
        if (after == ControlledLauncherRestoreDisposition.EMPTY) return ControlledLauncherRestoreAction.START;
        return ControlledLauncherRestoreAction.REJECT;
    }

    /** Two observations must be the same stable topology before a no-op or launcher dispatch. */
    static boolean sameStableTypedSnapshot(Snapshot first, Snapshot second) {
        if (!stableSnapshot(first) || !stableSnapshot(second)
                || first.displayId != second.displayId || first.displayState != second.displayState
                || first.powerBefore != second.powerBefore || first.powerAfter != second.powerAfter
                || first.source == null || second.source == null || !first.source.equals(second.source)
                || first.tasks.size() != second.tasks.size()) return false;
        for (int i = 0; i < first.tasks.size(); i++) {
            Task a = first.tasks.get(i), b = second.tasks.get(i);
            if (a.taskId != b.taskId || a.userId != b.userId
                    || !java.util.Objects.equals(a.basePackage, b.basePackage)
                    || !java.util.Objects.equals(a.topPackage, b.topPackage)
                    || !java.util.Objects.equals(a.baseComponent, b.baseComponent)
                    || !java.util.Objects.equals(a.topComponent, b.topComponent)
                    || !java.util.Objects.equals(a.visible, b.visible)
                    || !java.util.Objects.equals(a.requested, b.requested)
                    || !java.util.Objects.equals(a.sleeping, b.sleeping)) return false;
        }
        return true;
    }

    private static boolean stableSnapshot(Snapshot snapshot) {
        return snapshot != null && snapshot.failure == null && snapshot.tasks != null
                && snapshot.displayState != Display.STATE_UNKNOWN
                && snapshot.powerBefore == snapshot.powerAfter;
    }

    static boolean isExactSystemLauncher(Task task) {
        return task != null && task.userId >= 0 && task.baseComponent != null
                && task.baseComponent.equals(task.topComponent)
                && task.basePackage != null && task.basePackage.equals(task.topPackage)
                && RearSystemBridge.isValidatedRearLauncher(task.baseComponent, task.userId);
    }

    private static OwnerDisposition classify(String foreground, Task task, String hostPackage,
            int displayId, SceneTaskMatcher scenes, ExactLauncherMatcher launcherMatcher) {
        if (scenes != null && scenes.matches(foreground, displayId)) return OwnerDisposition.CORE_SCENE;
        if (hostPackage != null && foreground.startsWith(hostPackage + ":")) return OwnerDisposition.HOST;
        if (launcherMatcher.matches(task)) return OwnerDisposition.SYSTEM_LAUNCHER;
        return OwnerDisposition.UNKNOWN;
    }
}
