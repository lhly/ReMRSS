package top.zainzh.rearscreen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WMS display-local evidence for the brief handoff from a confirmed Mod picker to its rear host. */
final class ControlledCastApprovalWindowEvidence {
    private static final Pattern DISPLAY = Pattern.compile("^Display: mDisplayId=(\\d+)(?: \\(organized\\))?$");
    // The existing rotation parser also sees WMS attach the next header to the IME row.
    // Never split arbitrary window titles or other text on an embedded "Display:" string.
    private static final Pattern IME_DISPLAY = Pattern.compile(
            "^mImeWindow=(?:null|Window\\{[0-9a-fA-F]+ u[0-9]+ [^{}\\r\\n]+\\})[ \\t]+"
            + "(Display: mDisplayId=\\d+(?: \\(organized\\))?)$");
    private static final Pattern WINDOW = Pattern.compile("^Window\\{[0-9a-fA-F]+ u(\\d+) (\\S+)\\}$");
    private static final Pattern ACTIVITY = Pattern.compile(
            "^ActivityRecord\\{[0-9a-fA-F]+ u(\\d+) (\\S+) t(\\d+)\\}$");
    private static final String HEADER = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)";
    private static final String WINDOW_KEY = "mCurrentFocus=";
    private static final String ACTIVITY_KEY = "mFocusedApp=";

    private ControlledCastApprovalWindowEvidence() { }

    enum Result {
        EXACT, INVALID_INPUT, DISPLAY_HEADER_MISSING, DISPLAY_HEADER_INVALID,
        DISPLAY_DUPLICATE, FOCUS_DUPLICATE, MAIN_FIELDS_MISSING, REAR_FIELDS_MISSING,
        MAIN_ACTIVITY_MISMATCH, MAIN_WINDOW_MISMATCH, REAR_ACTIVITY_MISMATCH, REAR_WINDOW_MISMATCH
    }

    /** Call only with fresh independent proof that the global root is the exact transaction's rear host. */
    static boolean matches(String dump, int rearId, int userId, int pickerTask, String pickerComponent,
                           int rearHostTask, String rearHostComponent) {
        return inspect(dump, rearId, userId, pickerTask, pickerComponent, rearHostTask, rearHostComponent) == Result.EXACT;
    }

    static Result inspect(String dump, int rearId, int userId, int pickerTask, String pickerComponent,
                          int rearHostTask, String rearHostComponent) {
        if (dump == null || dump.length() > 1024 * 1024 || rearId <= 0 || userId < 0
                || pickerTask <= 0 || rearHostTask <= 0 || pickerTask == rearHostTask) return Result.INVALID_INPUT;
        String expectedPicker = RearDisplayCastOccupancyReader.canonicalComponent(pickerComponent);
        String expectedHost = RearDisplayCastOccupancyReader.canonicalComponent(rearHostComponent);
        if (expectedPicker == null || expectedHost == null || expectedPicker.equals(expectedHost)) return Result.INVALID_INPUT;

        Focus main = new Focus(), rear = new Focus();
        Focus active = null;
        boolean sawHeader = false;
        int displayIndent = -1;
        for (String untrimmed : dump.split("\\r?\\n")) {
            String line = untrimmed.trim();
            if (line.isEmpty()) continue;
            if (!sawHeader) {
                if (!HEADER.equals(line)) return Result.DISPLAY_HEADER_MISSING;
                sawHeader = true;
                continue;
            }
            Matcher attached = IME_DISPLAY.matcher(line);
            boolean afterIme = attached.matches();
            if (afterIme) {
                if (displayIndent < 0) return Result.DISPLAY_HEADER_INVALID;
                line = attached.group(1);
            }
            if (line.startsWith("Display:")) {
                if (!afterIme) {
                    int indent = untrimmed.indexOf("Display:");
                    if (displayIndent < 0) displayIndent = indent;
                    else if (indent != displayIndent) return Result.DISPLAY_HEADER_INVALID;
                }
                Matcher display = DISPLAY.matcher(line);
                if (!display.matches()) return Result.DISPLAY_HEADER_INVALID;
                String id = display.group(1);
                active = id.equals("0") ? main : id.equals(Integer.toString(rearId)) ? rear : null;
                if (active != null) {
                    if (active.seen) return Result.DISPLAY_DUPLICATE;
                    active.seen = true;
                }
            } else if (active != null && line.startsWith(WINDOW_KEY)) {
                if (active.window != null) return Result.FOCUS_DUPLICATE;
                active.window = line.substring(WINDOW_KEY.length()).trim();
            } else if (active != null && line.startsWith(ACTIVITY_KEY)) {
                if (active.activity != null) return Result.FOCUS_DUPLICATE;
                active.activity = line.substring(ACTIVITY_KEY.length()).trim();
            }
        }
        if (!sawHeader) return Result.DISPLAY_HEADER_MISSING;
        if (!main.complete()) return Result.MAIN_FIELDS_MISSING;
        if (!rear.complete()) return Result.REAR_FIELDS_MISSING;
        if (!exactActivity(main.activity, userId, pickerTask, expectedPicker)) return Result.MAIN_ACTIVITY_MISMATCH;
        // A non-null main window must still belong to the same picker, not a system overlay.
        if (!"null".equals(main.window) && !exactWindow(main.window, userId, expectedPicker))
            return Result.MAIN_WINDOW_MISMATCH;
        if (!exactActivity(rear.activity, userId, rearHostTask, expectedHost)) return Result.REAR_ACTIVITY_MISMATCH;
        return exactWindow(rear.window, userId, expectedHost) ? Result.EXACT : Result.REAR_WINDOW_MISMATCH;
    }

    private static boolean exactActivity(String row, int userId, int taskId, String expected) {
        Matcher value = ACTIVITY.matcher(row);
        return value.matches() && value.group(1).equals(Integer.toString(userId))
                && expected.equals(RearDisplayCastOccupancyReader.canonicalComponent(value.group(2)))
                && value.group(3).equals(Integer.toString(taskId));
    }

    private static boolean exactWindow(String row, int userId, String expected) {
        Matcher value = WINDOW.matcher(row);
        return value.matches() && value.group(1).equals(Integer.toString(userId))
                && expected.equals(RearDisplayCastOccupancyReader.canonicalComponent(value.group(2)));
    }

    private static final class Focus {
        boolean seen;
        String window;
        String activity;
        boolean complete() { return seen && window != null && activity != null; }
    }
}
