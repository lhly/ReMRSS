package top.zainzh.rearscreen;

import android.app.ActivityManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Routes the system IME to one owned virtual display; never configures a physical display. */
final class RearVirtualIme {
    static final String DISPLAY_NAME_PREFIX = "ReMRSS-virtual-";
    private static final int LOCAL = 0; // WindowManager.DISPLAY_IME_POLICY_LOCAL
    private static final int HIDE = 2; // WindowManager.DISPLAY_IME_POLICY_HIDE

    interface Platform {
        void setPolicy(int display, int policy) throws Exception;
        int getPolicy(int display) throws Exception;
        boolean keyguardLocked() throws Exception;
        int topTaskOnDisplay(int display) throws Exception;
        void focusTask(int task) throws Exception;
    }

    private final int display;
    private final Platform platform;

    private RearVirtualIme(int display, Platform platform) {
        this.display = display;
        this.platform = platform;
    }

    static RearVirtualIme enable(int display, int physicalRear) throws Exception {
        return enable(display, physicalRear, shell());
    }

    static RearVirtualIme enable(int display, int physicalRear, Platform platform) throws Exception {
        setOwnedPolicy(display, physicalRear, platform, LOCAL);
        return new RearVirtualIme(display, platform);
    }

    static void deferLockedHome(int display, int physicalRear) throws Exception {
        deferLockedHome(display, physicalRear, shell());
    }

    static void deferLockedHome(int display, int physicalRear, Platform platform) throws Exception {
        setOwnedPolicy(display, physicalRear, platform, HIDE);
    }

    private static void setOwnedPolicy(int display, int physicalRear, Platform platform, int policy) throws Exception {
        if (display <= 0 || physicalRear <= 0 || display == physicalRear) {
            throw new IllegalArgumentException("IME policy requires the owned virtual display");
        }
        platform.setPolicy(display, policy);
        if (platform.getPolicy(display) != policy) throw new IllegalStateException("Virtual display IME policy was not applied");
    }

    /** Runs synchronously before an app-bound DOWN, never on a timer, MOVE, or overlay gesture. */
    boolean beforeDown(BooleanSupplier isCurrent) throws Exception {
        if (!isCurrent.getAsBoolean() || platform.keyguardLocked()) return false;
        int task = platform.topTaskOnDisplay(display);
        // Query the foreground task now: the cast app may have launched a new task on this display.
        if (task < 0 || !isCurrent.getAsBoolean()) return false;
        platform.focusTask(task);
        return true;
    }

    private static Platform shell() throws Exception {
        Object window = RearSystemBridge.service("window", "android.view.IWindowManager$Stub");
        Object activity = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
        Class<?> wm = Class.forName("android.view.IWindowManager");
        Class<?> atm = Class.forName("android.app.IActivityTaskManager");
        Method setPolicy = wm.getMethod("setDisplayImePolicy", int.class, int.class);
        Method getPolicy = wm.getMethod("getDisplayImePolicy", int.class);
        Method keyguardLocked = wm.getMethod("isKeyguardLocked");
        Method getTasks = atm.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
        Method setFocusedTask = atm.getMethod("setFocusedTask", int.class);
        return new Platform() {
            @Override public void setPolicy(int display, int policy) throws Exception {
                setPolicy.invoke(window, display, policy);
            }
            @Override public int getPolicy(int display) throws Exception {
                return (int) getPolicy.invoke(window, display);
            }
            @Override public boolean keyguardLocked() throws Exception {
                return (boolean) keyguardLocked.invoke(window);
            }
            @Override public int topTaskOnDisplay(int display) throws Exception {
                Object result = getTasks.invoke(activity, 1, false, false, display);
                if (!(result instanceof List<?>) || ((List<?>) result).isEmpty()) return -1;
                Object task = ((List<?>) result).get(0);
                if (!(task instanceof ActivityManager.RunningTaskInfo)) return -1;
                ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) task;
                // A vendor implementation must not inadvertently let us focus a different display.
                if (info.getClass().getField("displayId").getInt(info) != display) return -1;
                return info.taskId;
            }
            @Override public void focusTask(int task) throws Exception { setFocusedTask.invoke(activity, task); }
        };
    }
}
