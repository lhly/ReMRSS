package top.zainzh.rearscreen;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A one-time focus handoff for an existing task, before the cast accepts its first gesture. */
final class RearImeStartupFocus {
    private static final long WINDOW_READY_MS = 700;
    private static final Pattern WINDOW = Pattern.compile(
            "(?m)^\\h*Window #\\d+ Window\\{[0-9a-fA-F]+ u\\d+ (\\S+)\\}[^\\r\\n]*$");
    private static final Pattern WM_ROW = Pattern.compile("(?m)^\\h*mDisplayId=(\\d+)\\b");
    private static final Pattern DRAWN = Pattern.compile("\\bmDrawState=HAS_DRAWN\\b");
    private static final Pattern VISIBLE = Pattern.compile("\\bisVisible=true\\b");
    private static final Pattern SURFACE = Pattern.compile("\\bmHasSurface=true\\b");

    private RearImeStartupFocus() { }

    static boolean prepare(int display, String displayName, int task, BooleanSupplier isCurrent) throws Exception {
        Object atm = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
        Class<?> atmType = Class.forName("android.app.IActivityTaskManager");
        Method getFocusedTask = atmType.getMethod("getFocusedRootTaskInfo");
        Method getTasks = atmType.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
        Object wm = RearSystemBridge.service("window", "android.view.IWindowManager$Stub");
        Method isLocked = Class.forName("android.view.IWindowManager").getMethod("isKeyguardLocked");

        long start = SystemClock.uptimeMillis();
        ComponentName top = null;
        while (isCurrent.getAsBoolean() && !(boolean) isLocked.invoke(wm)) {
            Object focused = getFocusedTask.invoke(atm);
            if (focused == null || focused.getClass().getField("displayId").getInt(focused) != display) return false;
            top = topActivity(getTasks, atm, display, task);
            if (top == null) return false;
            if (drawn(top, display, 220)) break;
            top = null;
            if (SystemClock.uptimeMillis() - start >= WINDOW_READY_MS) return false;
            Thread.sleep(25);
        }
        if (top == null || !isCurrent.getAsBoolean() || (boolean) isLocked.invoke(wm)) return false;
        Object focused = getFocusedTask.invoke(atm);
        if (focused == null || focused.getClass().getField("displayId").getInt(focused) != display
                || !top.equals(topActivity(getTasks, atm, display, task))) return false;

        CompatibilityDiagnostics.record("virtual_ime_focus_ready",
                "display=" + display + " wait_ms=" + (SystemClock.uptimeMillis() - start));
        String helper = new ComponentName(BuildConfig.APPLICATION_ID,
                RearImeFocusActivity.class.getName()).flattenToString();
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        if (!isCurrent.getAsBoolean()) return false;
        DisplayRotationController.Result result = DisplayRotationController.runCommand(1_200,
                "am", "start", "-W", "--display", Integer.toString(display), "--user", "current",
                "-f", "0x" + Integer.toHexString(flags), "-n", helper,
                "--ei", RearImeFocusActivity.EXTRA_DISPLAY_ID, Integer.toString(display),
                "--es", RearImeFocusActivity.EXTRA_DISPLAY_NAME, displayName);
        if (!RearSystemBridge.commandSucceeded(result) || !result.output.contains("Status: ok")) return false;
        long deadline = SystemClock.uptimeMillis() + 650;
        do {
            if (!isCurrent.getAsBoolean() || (boolean) isLocked.invoke(wm)) return false;
            Object current = getFocusedTask.invoke(atm);
            if (current == null || current.getClass().getField("displayId").getInt(current) != display) return false;
            if (top.equals(topActivity(getTasks, atm, display, task)) && drawn(top, display, 160)) return true;
            Thread.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static ComponentName topActivity(Method getTasks, Object atm, int display, int task)
            throws ReflectiveOperationException {
        Object result = getTasks.invoke(atm, 1, false, false, display);
        if (!(result instanceof List<?>) || ((List<?>) result).isEmpty()) return null;
        Object first = ((List<?>) result).get(0);
        if (!(first instanceof ActivityManager.RunningTaskInfo)) return null;
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) first;
        return info.taskId == task && info.getClass().getField("displayId").getInt(info) == display
                ? info.topActivity : null;
    }

    private static boolean drawn(ComponentName component, int display, long timeout) throws Exception {
        DisplayRotationController.Result result = DisplayRotationController.runCommand(timeout,
                "dumpsys", "window", "-a", component.getPackageName());
        return RearSystemBridge.commandSucceeded(result) && hasDrawnWindow(result.output, display,
                component.flattenToString());
    }

    static boolean hasDrawnWindow(String dump, int display, String expected) {
        String component = RearDisplayCastOccupancyReader.canonicalComponent(expected);
        if (display <= 0 || component == null) return false;
        Matcher window = WINDOW.matcher(dump);
        if (!window.find()) return false;
        do {
            String name = RearDisplayCastOccupancyReader.canonicalComponent(window.group(1));
            int start = window.end();
            boolean next = window.find();
            if (component.equals(name)) {
                String details = dump.substring(start, next ? window.start() : dump.length());
                Matcher row = WM_ROW.matcher(details);
                if (row.find() && row.group(1).equals(Integer.toString(display))
                        && DRAWN.matcher(details).find() && VISIBLE.matcher(details).find()
                        && SURFACE.matcher(details).find()) return true;
            }
            if (!next) return false;
        } while (true);
    }
}
