package top.zainzh.rearscreen;

import android.util.Log;
import android.view.Display;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Read-only diagnostics for the one fixture task; never initializes or reconnects TaskService. */
public final class Rc5TargetTaskSnapshotProbe {
    private static final String TARGET_PACKAGE = "dev.remrss.sample.rc5physicala";
    private static final String TARGET_MAIN_COMPONENT = TARGET_PACKAGE + "/dev.remrss.physical.MainActivity";
    private static final String TARGET_CONTROLLER_COMPONENT = TARGET_PACKAGE + "/dev.remrss.physical.ControllerActivity";

    private Rc5TargetTaskSnapshotProbe() {}


    /**
     * Requires the runner's exact Controller/Main pair to be the two eligible same-package
     * foreground-choice rows before the signed Mod Cast fixture is allowed to request a pick.
     * Reads the already-connected debug service only; it never initializes or reconnects it.
     */
    public static void requireFixtureTaskPair(String tag) {
        try {
            int controllerTask = fixtureTask("fixtureControllerTask");
            int targetTask = fixtureTask("fixtureTargetTask");
            if (controllerTask <= 0 || targetTask <= 0 || controllerTask == targetTask)
                throw pairFailure(tag, "pair_arguments_invalid");
            ITaskService service = TaskServiceManager.peekConnectedServiceForDebug();
            if (service == null || !isAlive(service)) throw pairFailure(tag, "service_unavailable");

            JSONObject snapshot = new JSONObject(service.getDisplayCastOccupancy(Display.DEFAULT_DISPLAY));
            int power = snapshot.optInt("power", Display.STATE_UNKNOWN);
            String source = snapshot.optString("source");
            if (!"ok".equals(snapshot.optString("status")) ||
                    snapshot.optInt("display", -1) != Display.DEFAULT_DISPLAY ||
                    power != Display.STATE_ON || snapshot.optInt("powerBefore", -1) != Display.STATE_ON ||
                    snapshot.optInt("powerAfter", -1) != Display.STATE_ON || !isKnownSource(source))
                throw pairFailure(tag, "snapshot_not_stable_main_display");

            JSONArray rows = snapshot.optJSONArray("tasks");
            if (rows == null) throw pairFailure(tag, "snapshot_tasks_missing");
            Map<Integer, Integer> taskCounts = new HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) throw pairFailure(tag, "snapshot_row_invalid");
                int task = row.optInt("task", -1);
                if (task > 0) taskCounts.put(task, taskCounts.getOrDefault(task, 0) + 1);
            }

            JSONObject controller = uniqueTaskRow(rows, taskCounts, controllerTask);
            JSONObject target = uniqueTaskRow(rows, taskCounts, targetTask);
            if (controller == null || target == null) throw pairFailure(tag, "pair_task_not_unique");
            if (!isExactChoiceCandidate(controller, taskCounts, service, controllerTask,
                    TARGET_CONTROLLER_COMPONENT) ||
                    !isExactChoiceCandidate(target, taskCounts, service, targetTask, TARGET_MAIN_COMPONENT))
                throw pairFailure(tag, "pair_identity_or_eligibility_mismatch");

            int samePackageCandidates = 0;
            int controllerMatches = 0;
            int targetMatches = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                int task = row.optInt("task", -1);
                if (isForegroundChoiceCandidate(row, taskCounts, service)) {
                    samePackageCandidates++;
                    String component = normalizeComponent(row.optString("baseComponent"));
                    if (TARGET_CONTROLLER_COMPONENT.equals(component)) controllerMatches++;
                    if (TARGET_MAIN_COMPONENT.equals(component)) targetMatches++;
                }
            }
            if (samePackageCandidates != 2 || controllerMatches != 1 || targetMatches != 1)
                throw pairFailure(tag, "same_package_candidate_set_mismatch");
            Log.i(tag, "stage=fixture_target_pair result=PASS connected=true snapshot_stable=true " +
                    "controller_exact=true target_exact=true controller_unique=true target_unique=true " +
                    "same_package_candidates=2 pair_distinct=true");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw pairFailure(tag, "snapshot_read_failed");
        }
    }

    private static int fixtureTask(String name) {
        try {
            String value = InstrumentationRegistry.getArguments().getString(name);
            return value == null ? -1 : Integer.parseInt(value);
        } catch (RuntimeException invalid) { return -1; }
    }

    private static JSONObject uniqueTaskRow(JSONArray rows, Map<Integer, Integer> counts, int task) {
        if (counts.getOrDefault(task, 0) != 1) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.optInt("task", -1) == task) return row;
        }
        return null;
    }

    private static boolean isExactChoiceCandidate(JSONObject row, Map<Integer, Integer> taskCounts,
            ITaskService service, int task, String expectedComponent) throws Exception {
        return isForegroundChoiceCandidate(row, taskCounts, service) &&
                expectedComponent.equals(normalizeComponent(row.optString("baseComponent"))) &&
                expectedComponent.equals(normalizeComponent(row.optString("topComponent"))) &&
                expectedComponent.equals(normalizeComponent(service.getTaskBaseComponent(task, Display.DEFAULT_DISPLAY)));
    }

    /** Mirrors the resolver's same-package, visible/requested/sleeping and exact-component admission. */
    private static boolean isForegroundChoiceCandidate(JSONObject row, Map<Integer, Integer> taskCounts,
            ITaskService service) {
        if (row == null) return false;
        int task = row.optInt("task", -1);
        if (task <= 0 || taskCounts.getOrDefault(task, 0) != 1 ||
                !TARGET_PACKAGE.equals(row.optString("base")) || !TARGET_PACKAGE.equals(row.optString("top"))) return false;
        Object visibleValue = row.opt("visible");
        Object requestedValue = row.opt("requested");
        Object sleepingValue = row.opt("sleeping");
        if (!(visibleValue instanceof Boolean) || !(requestedValue instanceof Boolean) || !(sleepingValue instanceof Boolean)) return false;
        boolean visible = (Boolean) visibleValue;
        boolean requested = (Boolean) requestedValue;
        boolean sleeping = (Boolean) sleepingValue;
        if (visible != requested || sleeping) return false;
        String baseFromSnapshot = normalizeComponent(row.optString("baseComponent"));
        String top = normalizeComponent(row.optString("topComponent"));
        if (baseFromSnapshot == null || top == null ||
                !TARGET_PACKAGE.equals(componentPackage(baseFromSnapshot)) ||
                !TARGET_PACKAGE.equals(componentPackage(top))) return false;
        try {
            String serviceBase = normalizeComponent(service.getTaskBaseComponent(task, Display.DEFAULT_DISPLAY));
            return baseFromSnapshot.equals(serviceBase) && service.isTaskOnDisplay(task, Display.DEFAULT_DISPLAY);
        } catch (Exception unavailable) { return false; }
    }

    private static String normalizeComponent(String value) {
        if (value == null) return null;
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('/', separator + 1) >= 0) return null;
        String packageName = value.substring(0, separator);
        String className = value.substring(separator + 1);
        if (packageName.trim().isEmpty() || className.trim().isEmpty() ||
                containsWhitespace(packageName) || containsWhitespace(className)) return null;
        if (className.startsWith(".")) className = packageName + className;
        if (!className.contains(".")) return null;
        return packageName + "/" + className;
    }

    private static String componentPackage(String value) {
        int separator = value == null ? -1 : value.indexOf('/');
        return separator <= 0 ? null : value.substring(0, separator);
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return true;
        return false;
    }

    private static IllegalStateException pairFailure(String tag, String reason) {
        Log.i(tag, "stage=fixture_target_pair result=FAIL reason=" + reason);
        return new IllegalStateException("受控 A MainActivity/ControllerActivity 任务对前置失败：" + reason);
    }

    public static void log(String tag) {
        final int targetTask;
        try {
            String value = InstrumentationRegistry.getArguments().getString("fixtureTargetTask");
            targetTask = value == null ? -1 : Integer.parseInt(value);
        } catch (RuntimeException invalidArgument) {
            return;
        }
        if (targetTask <= 0) {
            Log.i(tag, "stage=foreground_target_task_snapshot result=SKIP reason=target_argument_invalid");
            return;
        }

        ITaskService service = TaskServiceManager.peekConnectedServiceForDebug();
        if (service == null || !isAlive(service)) {
            Log.i(tag, "stage=foreground_target_task_snapshot target_argument_valid=true" +
                    " connected=false status_known=false power_known=false source_known=false" +
                    " target_task_unique=false base_exact=unknown top_exact=unknown" +
                    " base_component_exact=unknown top_component_exact=unknown" +
                    " visible=unknown requested=unknown sleeping=unknown");
            return;
        }

        String fields;
        try {
            JSONObject snapshot = new JSONObject(service.getDisplayCastOccupancy(Display.DEFAULT_DISPLAY));
            String status = snapshot.optString("status");
            int power = snapshot.optInt("power", Display.STATE_UNKNOWN);
            String source = snapshot.optString("source");
            JSONArray rows = snapshot.optJSONArray("tasks");
            JSONObject target = null;
            int matches = 0;
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null && row.optInt("task", -1) == targetTask) {
                        matches++;
                        target = row;
                    }
                }
            }
            boolean unique = matches == 1;
            if (!unique) target = null;
            fields = "status_known=" + ("ok".equals(status) || "unavailable".equals(status)) +
                    " status_ok=" + "ok".equals(status) +
                    " display_exact=" + (snapshot.optInt("display", -1) == Display.DEFAULT_DISPLAY) +
                    " power_known=" + (power >= Display.STATE_OFF && power <= Display.STATE_VR) +
                    " power_on=" + (power == Display.STATE_ON) +
                    " power_stable_on=" + (power == Display.STATE_ON &&
                    snapshot.optInt("powerBefore", -1) == Display.STATE_ON &&
                    snapshot.optInt("powerAfter", -1) == Display.STATE_ON) +
                    " source_known=" + isKnownSource(source) +
                    " target_task_unique=" + unique +
                    " base_exact=" + matches(target, "base", TARGET_PACKAGE) +
                    " top_exact=" + matches(target, "top", TARGET_PACKAGE) +
                    " base_component_exact=" + matches(target, "baseComponent", TARGET_MAIN_COMPONENT) +
                    " top_component_exact=" + matches(target, "topComponent", TARGET_MAIN_COMPONENT) +
                    " visible=" + state(target, "visible") +
                    " requested=" + state(target, "requested") +
                    " sleeping=" + state(target, "sleeping");
        } catch (Exception unavailable) {
            fields = "status_known=false power_known=false source_known=false target_task_unique=false" +
                    " base_exact=unknown top_exact=unknown base_component_exact=unknown top_component_exact=unknown" +
                    " visible=unknown requested=unknown sleeping=unknown";
        }
        Log.i(tag, "stage=foreground_target_task_snapshot target_argument_valid=true connected=true " + fields);
    }

    /** Logs one bounded, fixed-vocabulary cause from the existing service diagnostic after test failure. */
    public static void logControlledVirtualStartDiagnostic(String tag, long requestStartedAtMs) {
        ITaskService service = TaskServiceManager.peekConnectedServiceForDebug();
        if (service == null || !isAlive(service)) {
            Log.i(tag, "stage=controlled_virtual_start_diagnostic result=unavailable");
            return;
        }
        try {
            JSONObject diagnostics = new JSONObject(service.getCompatibilityDiagnostics());
            JSONArray events = diagnostics.optJSONArray("events");
            long sampleTimeMs = System.currentTimeMillis();
            String failure = null;
            long failureTimeMs = -1L;
            int matchingEvents = 0;
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    String event = events.optString(i, "");
                    EventStamp stamp = controlledStartFailureStamp(event);
                    if (stamp != null && stamp.timestampMs >= requestStartedAtMs
                            && stamp.timestampMs <= sampleTimeMs) {
                        matchingEvents++;
                        failure = event;
                        failureTimeMs = stamp.timestampMs;
                    }
                }
            }
            String result = matchingEvents == 1 ? "recorded" : matchingEvents == 0 ? "not_recorded" : "ambiguous";
            String stage = matchingEvents == 1 ? controlledStartFailureStage(failure) : "none";
            JSONObject virtualCast = diagnostics.optJSONObject("virtualCast");
            String state = virtualCast == null ? "unknown" : safeVirtualState(virtualCast.optString("state", ""));
            Log.i(tag, "stage=controlled_virtual_start_diagnostic result="
                    + result + " event_count=" + matchingEvents + " failure_stage=" + stage
                    + " event_age_ms=" + (matchingEvents == 1 ? sampleTimeMs - failureTimeMs : -1L)
                    + " virtual_state=" + state);
            if ("running".equals(state)) {
                int target = fixtureTask("fixtureTargetTask");
                int display = virtualCast.optInt("display", -1);
                int host = virtualCast.optInt("host", -1);
                String expectedHost = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()
                        + "/top.zainzh.rearscreen.VirtualRearHostActivity";
                boolean targetExact = target > 0 && display > 1 && virtualCast.optInt("task", -1) == target
                        && TARGET_MAIN_COMPONENT.equals(normalizeComponent(virtualCast.optString("baseComponent")))
                        && TARGET_MAIN_COMPONENT.equals(normalizeComponent(service.getTaskBaseComponent(target, display)));
                boolean hostExact = host > 0 && virtualCast.optInt("rearDisplayId", -1) == 1
                        && expectedHost.equals(normalizeComponent(service.getTaskBaseComponent(host, 1)));
                JSONObject input = virtualCast.optJSONObject("input");
                boolean output = virtualCast.optBoolean("outputAvailable");
                boolean reader = input != null && "running".equals(input.optString("state"))
                        && input.optBoolean("outputAvailable");
                Log.i(tag, "stage=controlled_virtual_running_readback target_exact=" + targetExact
                        + " host_exact=" + hostExact + " output=" + output + " reader=" + reader);
            }
        } catch (Exception unavailable) {
            Log.i(tag, "stage=controlled_virtual_start_diagnostic result=unavailable");
        }
    }

    private static final class EventStamp {
        final long timestampMs;
        EventStamp(long timestampMs) { this.timestampMs = timestampMs; }
    }

    private static EventStamp controlledStartFailureStamp(String event) {
        if (event == null) return null;
        String[] parts = event.split("\\s+", 3);
        if (parts.length < 2 || !"controlled_virtual_start_failed".equals(parts[1])
                || !parts[0].matches("[0-9]{1,16}")) return null;
        try { return new EventStamp(Long.parseLong(parts[0])); }
        catch (NumberFormatException invalidTime) { return null; }
    }

    private static String controlledStartFailureStage(String event) {
        if (event == null) return "not_recorded";
        String[] prefixes = {"controlled_start_target_gate_stale:", "controlled_start_gate_stale:"};
        String[] stages = {"begin", "before_virtual_display_create", "after_virtual_display_create",
                "before_rotation", "after_rotation", "before_freeze_guard", "after_freeze_guard",
                "before_target_move", "after_target_move", "before_target_launch", "after_target_launch",
                "before_reader_start", "after_reader_start", "ready"};
        for (String prefix : prefixes) for (String stage : stages) {
            if (event.contains(prefix + stage))
                return prefix.startsWith("controlled_start_target") ? "target_gate_" + stage : "owner_gate_" + stage;
        }
        return "recorded_unclassified";
    }

    private static String safeVirtualState(String state) {
        return "running".equals(state) || "stopped".equals(state) || "starting".equals(state)
                ? state : "other";
    }

    private static boolean isAlive(ITaskService service) {
        try { return service.asBinder() != null && service.asBinder().isBinderAlive(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static boolean isKnownSource(String source) {
        return "activity_task".equals(source) || "activity_task_focused_stack".equals(source) ||
                "activity_focused_stack".equals(source);
    }

    private static String state(JSONObject row, String key) {
        Object value = row == null ? null : row.opt(key);
        return value instanceof Boolean ? value.toString() : "unknown";
    }

    private static String matches(JSONObject row, String key, String expected) {
        Object value = row == null ? null : row.opt(key);
        return value instanceof String ? Boolean.toString(expected.equals(value)) : "unknown";
    }
}
