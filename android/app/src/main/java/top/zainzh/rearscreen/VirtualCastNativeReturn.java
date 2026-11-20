package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies that Android can keep every ordinary fullscreen task when a virtual display is removed. */
final class VirtualCastNativeReturn {
    private final Object activityManager;
    private final Map<Integer, String> tasks;

    private VirtualCastNativeReturn(Object activityManager, Map<Integer, String> tasks) {
        this.activityManager = activityManager;
        this.tasks = tasks;
    }

    static VirtualCastNativeReturn prepare(int display, int originalTask) throws Exception {
        Object wm = RearSystemBridge.service("window", "android.view.IWindowManager$Stub");
        int mode = (int) Class.forName("android.view.IWindowManager")
                .getMethod("getRemoveContentMode", int.class).invoke(wm, display);
        int moveToPrimary = WindowManager.class.getField("REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY").getInt(null);
        if (mode != moveToPrimary) return null;

        Object activity = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
        Object result = Class.forName("android.app.IActivityTaskManager")
                .getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(activity, display);
        if (!(result instanceof List<?>)) return null;
        Class<?> windowConfig = Class.forName("android.app.WindowConfiguration");
        int standard = windowConfig.getField("ACTIVITY_TYPE_STANDARD").getInt(null);
        int full = windowConfig.getField("WINDOWING_MODE_FULLSCREEN").getInt(null);
        List<RootTask> roots = new ArrayList<>();
        for (Object row : (List<?>) result) {
            if (row == null) return null;
            Class<?> type = row.getClass();
            ComponentName base = (ComponentName) type.getField("baseActivity").get(row);
            boolean ordinary = (int) type.getMethod("getActivityType").invoke(row) == standard
                    && (int) type.getMethod("getWindowingMode").invoke(row) == full;
            roots.add(new RootTask(type.getField("taskId").getInt(row), type.getField("displayId").getInt(row),
                    base == null ? null : base.flattenToString(), ordinary,
                    (int[]) type.getField("childTaskIds").get(row)));
        }
        Map<Integer, String> tasks = inventory(roots, display, originalTask);
        return tasks == null ? null : new VirtualCastNativeReturn(activity, tasks);
    }

    static final class RootTask {
        final int id, display;
        final String base;
        final boolean ordinary;
        final int[] childTaskIds;
        RootTask(int id, int display, String base, boolean ordinary, int[] childTaskIds) {
            this.id = id; this.display = display; this.base = base; this.ordinary = ordinary;
            this.childTaskIds = childTaskIds;
        }
    }

    static Map<Integer, String> inventory(List<RootTask> roots, int display, int originalTask) {
        if (display <= Display.DEFAULT_DISPLAY || originalTask <= 0) return null;
        Map<Integer, String> tasks = new LinkedHashMap<>();
        for (RootTask root : roots) {
            String base = RearDisplayCastOccupancyReader.canonicalComponent(root.base);
            // Organized/nested, pinned and home roots do not have this ordinary leaf contract.
            if (root.display != display || root.id <= 0 || !root.ordinary || base == null
                    || root.childTaskIds == null || root.childTaskIds.length != 1
                    || root.childTaskIds[0] != root.id || tasks.put(root.id, base) != null) return null;
        }
        return tasks.containsKey(originalTask) ? tasks : null;
    }

    int awaitOnMain() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 1_500L;
        do {
            if (returned(tasks, RearDisplayCastOccupancyReader.taskInfos(activityManager))) return tasks.size();
            if (SystemClock.uptimeMillis() >= deadline) break;
            Thread.sleep(40);
        } while (true);
        throw new IllegalStateException("virtual_native_task_return_unverified");
    }

    static boolean returned(Map<Integer, String> expected, List<?> rows) {
        for (Map.Entry<Integer, String> task : expected.entrySet()) {
            String base = RearDisplayCastOccupancyReader.exactTaskBaseComponent(
                    rows, task.getKey(), Display.DEFAULT_DISPLAY);
            if (!task.getValue().equals(base)) return false;
        }
        return true;
    }
}
