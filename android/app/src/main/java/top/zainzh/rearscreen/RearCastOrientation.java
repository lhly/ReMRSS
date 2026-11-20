package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

/** Determines the orientation for a cast without an explicit or saved rotation. */
final class RearCastOrientation {
    private RearCastOrientation() {}

    static int initialRotation(int taskId, String component, RearCastViewport viewport) {
        ComponentName target = component == null ? null : ComponentName.unflattenFromString(component);
        int orientation = taskOrientation(taskId, target);
        if (orientation == Configuration.ORIENTATION_UNDEFINED && target != null) {
            orientation = declaredOrientation(target);
        }
        if (orientation == Configuration.ORIENTATION_UNDEFINED) {
            try {
                Object display = RearDisplay.info(0);
                Class<?> type = display.getClass();
                orientation = orientationOf(type.getField("logicalWidth").getInt(display),
                        type.getField("logicalHeight").getInt(display));
            } catch (Exception unavailable) {
                // Older/modified ROMs may not provide source geometry. Keep the original rear direction.
            }
        }
        return rotationFor(orientation, viewport.width(), viewport.height());
    }

    private static int taskOrientation(int taskId, ComponentName target) {
        if (taskId < 0) return Configuration.ORIENTATION_UNDEFINED;
        try {
            Object manager = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
            int orientation = Configuration.ORIENTATION_UNDEFINED;
            boolean found = false;
            for (Object row : RearDisplayCastOccupancyReader.taskInfos(manager)) {
                if (row == null) continue;
                Class<?> type = row.getClass();
                if (type.getField("taskId").getInt(row) != taskId
                        || type.getField("displayId").getInt(row) != 0) continue;
                // When launching a different Activity, the old task's orientation does not describe it.
                if (found || (target != null && !target.equals(type.getField("topActivity").get(row))))
                    return Configuration.ORIENTATION_UNDEFINED;
                found = true;
                Configuration config = (Configuration) type.getField("configuration").get(row);
                if (config != null && (config.orientation == Configuration.ORIENTATION_PORTRAIT
                        || config.orientation == Configuration.ORIENTATION_LANDSCAPE)) {
                    orientation = config.orientation;
                }
            }
            return orientation;
        } catch (Exception unavailable) {
            return Configuration.ORIENTATION_UNDEFINED;
        }
    }

    private static int declaredOrientation(ComponentName target) {
        try {
            return declaredOrientation(RearSystemBridge.systemContext().getPackageManager()
                    .getActivityInfo(target, 0).screenOrientation);
        } catch (Exception unavailable) {
            return Configuration.ORIENTATION_UNDEFINED;
        }
    }

    static int declaredOrientation(int request) {
        switch (request) {
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                return Configuration.ORIENTATION_PORTRAIT;
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                return Configuration.ORIENTATION_LANDSCAPE;
            default:
                return Configuration.ORIENTATION_UNDEFINED;
        }
    }

    static int rotationFor(int sourceOrientation, int rearWidth, int rearHeight) {
        int rearOrientation = orientationOf(rearWidth, rearHeight);
        if (sourceOrientation == Configuration.ORIENTATION_UNDEFINED
                || rearOrientation == Configuration.ORIENTATION_UNDEFINED) return 0;
        return sourceOrientation == rearOrientation ? 0 : 1;
    }

    private static int orientationOf(int width, int height) {
        if (width == height || width <= 0 || height <= 0) return Configuration.ORIENTATION_UNDEFINED;
        return width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
    }
}
