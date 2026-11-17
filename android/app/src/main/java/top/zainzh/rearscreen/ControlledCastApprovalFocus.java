package top.zainzh.rearscreen;

import android.view.Display;
import java.util.List;

/** Exact per-display approval check, used only before a signed Mod virtual cast moves its target. */
final class ControlledCastApprovalFocus {
    private static final String PICKER_CLASS =
            top.zainzh.rearscreen.platform.mods.ModCastTargetPickerActivity.class.getName();
    private ControlledCastApprovalFocus() { }

    enum Result {
        EXACT, REAR_IDENTITY_CHANGED, PRESENTER_NOT_FOCUSED, DISPLAY_DUMP_UNAVAILABLE,
        PICKER_NOT_VISIBLE, TARGET_CHANGED, DISPLAY_FOCUS_UNVERIFIED, QUERY_UNAVAILABLE
    }

    static Result verify(int pickerTask, int targetTask, String targetBase, String targetTop,
            int rearHostTask, int rearId, String rearIdentity) throws Exception {
        String picker = ControlledCastHostComponent.expected(BuildConfig.APPLICATION_ID, PICKER_CLASS);
        String host = ControlledCastHostComponent.expected(BuildConfig.APPLICATION_ID,
                VirtualRearHostActivity.class.getName());
        String expectedBase = RearDisplayCastOccupancyReader.canonicalComponent(targetBase);
        String expectedTop = RearDisplayCastOccupancyReader.canonicalComponent(targetTop);
        if (pickerTask <= 0 || targetTask <= 0 || rearHostTask <= 0 || rearId <= 0
                || pickerTask == rearHostTask || targetTask == pickerTask || targetTask == rearHostTask
                || expectedBase == null || expectedTop == null
                || rearIdentity == null || !rearIdentity.equals(RearDisplay.identity())
                || !RearDisplay.isRear(rearId)) return Result.REAR_IDENTITY_CHANGED;
        Object manager = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
        if (!isFocusedRearHost(manager, rearHostTask, rearId, host)) return Result.PRESENTER_NOT_FOCUSED;
        DisplayRotationController.Result evidence = DisplayRotationController.runCommand(
                350L, "dumpsys", "window", "displays");
        if (evidence.exitCode != 0) return Result.DISPLAY_DUMP_UNAVAILABLE;
        if (!isFocusedRearHost(manager, rearHostTask, rearId, host)) return Result.PRESENTER_NOT_FOCUSED;
        RearDisplayCastOccupancy.Snapshot finalMain = RearDisplayCastOccupancyReader.readTypedOnly(0);
        RearDisplayCastOccupancy.Task exactPicker = visiblePicker(finalMain, pickerTask, picker);
        if (exactPicker == null) return Result.PICKER_NOT_VISIBLE;
        if (!exactTarget(finalMain, targetTask, expectedBase, expectedTop, exactPicker.userId))
            return Result.TARGET_CHANGED;
        ControlledCastApprovalWindowEvidence.Result window = ControlledCastApprovalWindowEvidence.inspect(
                evidence.output, rearId, exactPicker.userId, pickerTask, picker, rearHostTask, host);
        if (window != ControlledCastApprovalWindowEvidence.Result.EXACT) {
            if (BuildConfig.DEBUG) android.util.Log.w("TaskService", "controlled_cast_picker_window result="
                    + window.name().toLowerCase(java.util.Locale.ROOT));
            return Result.DISPLAY_FOCUS_UNVERIFIED;
        }
        return rearIdentity.equals(RearDisplay.identity()) && RearDisplay.isRear(rearId)
                ? Result.EXACT : Result.REAR_IDENTITY_CHANGED;
    }

    private static boolean isFocusedRearHost(Object manager, int expectedTask, int display, String component)
            throws Exception {
        List<?> rows = RearDisplayCastOccupancyReader.taskInfos(manager);
        RearDisplayCastOccupancyReader.FocusedStack focus =
                RearDisplayCastOccupancyReader.focusedStack(manager, display, rows);
        return focus != null && !focus.fromStackInfo && focus.taskId == expectedTask
                && Boolean.TRUE.equals(focus.visible) && component.equals(focus.topComponent)
                && component.equals(RearDisplayCastOccupancyReader.exactTaskBaseComponent(rows, expectedTask, display));
    }

    static boolean exactTarget(RearDisplayCastOccupancy.Snapshot snapshot,
                               int taskId, String base, String top, int userId) {
        if (snapshot == null || base == null || top == null || taskId <= 0 || userId < 0) return false;
        boolean seen = false;
        for (RearDisplayCastOccupancy.Task row : snapshot.tasks) {
            if (row.taskId != taskId) continue;
            if (seen || row.userId != userId || !base.equals(row.baseComponent)
                    || !top.equals(row.topComponent)) return false;
            seen = true;
        }
        return seen;
    }

    static RearDisplayCastOccupancy.Task visiblePicker(RearDisplayCastOccupancy.Snapshot snapshot,
                                                       int taskId, String component) {
        if (snapshot == null || snapshot.failure != null || snapshot.displayId != Display.DEFAULT_DISPLAY
                || snapshot.displayState != Display.STATE_ON || snapshot.powerBefore != Display.STATE_ON
                || snapshot.powerAfter != Display.STATE_ON || taskId <= 0 || component == null) return null;
        RearDisplayCastOccupancy.Task exact = null;
        for (RearDisplayCastOccupancy.Task row : snapshot.tasks) {
            if (row.taskId != taskId) continue;
            if (exact != null || row.userId < 0 || !component.equals(row.baseComponent)
                    || !component.equals(row.topComponent) || !Boolean.TRUE.equals(row.visible)
                    || !Boolean.TRUE.equals(row.requested) || !Boolean.FALSE.equals(row.sleeping)) return null;
            exact = row;
        }
        return exact;
    }
}
