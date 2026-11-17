package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ControlledCastApprovalWindowEvidenceTest {
    private static final String PICKER = "test.host/test.host.ModPicker";
    private static final String REAR = "test.host/test.host.VirtualRearHost";
    private static final String DUMP =
            "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
            + "  Display: mDisplayId=1\n"
            + " mCurrentFocus=Window{ca2 u0 test.host/.VirtualRearHost}\n"
            + " mFocusedApp=ActivityRecord{44f u0 test.host/.VirtualRearHost t45}\n"
            + "  Display: mDisplayId=0\n"
            + " mCurrentFocus=null\n"
            + " mFocusedApp=ActivityRecord{55f u0 test.host/.ModPicker t18}\n";

    @Test public void exactPickerRetainedWhenOwnRearHostHasFocus() {
        assertTrue(matches(DUMP));
        assertTrue(matches(DUMP.replace("mCurrentFocus=null", "mCurrentFocus=Window{b6 u0 test.host/.ModPicker}")));
    }

    @Test public void foreignWindowsAndTasksNeverCount() {
        assertFalse(matches(DUMP.replace("mCurrentFocus=null", "mCurrentFocus=Window{b6 u0 NotificationShade}")));
        assertFalse(matches(DUMP.replace("ModPicker t18", "ModPicker t19")));
        assertFalse(matches(DUMP.replace("ModPicker t18", "OtherActivity t18")));
        assertFalse(matches(DUMP.replace("VirtualRearHost t45", "VirtualRearHost t46")));
        assertFalse(matches(DUMP.replace("Window{ca2 u0 test.host/.VirtualRearHost}", "null")));
        assertFalse(matches(DUMP.replace("u0 test.host/.ModPicker", "u10 test.host/.ModPicker")));
        assertFalse(matches(DUMP.replace("u0 test.host/.VirtualRearHost", "u10 test.host/.VirtualRearHost")));
    }

    @Test public void missingDuplicatedOrWrongDisplayEvidenceFailsClosed() {
        assertFalse(matches(DUMP.replace(" mCurrentFocus=null\n", "")));
        assertFalse(matches(DUMP.replace("mCurrentFocus=null", "mCurrentFocus=null\n mCurrentFocus=null")));
        assertFalse(matches(DUMP.replace("Display: mDisplayId=0", "Display: mDisplayId=8")));
        assertFalse(matches(DUMP + "  Display: mDisplayId=0\n"));
        assertFalse(matches(DUMP.replace("mFocusedApp=ActivityRecord{55f", "other=ActivityRecord{55f")));
        assertFalse(matches("Permission denial\n" + DUMP));
    }

    @Test public void nextDisplayAttachedToImeRowKeepsItsOwnFocusSection() {
        String split = "  Display: mDisplayId=0";
        assertTrue(matches(DUMP.replace(split, "    mImeWindow=null  Display: mDisplayId=0 (organized)")));
        String attached = DUMP.replace(split, "    mImeWindow=Window{ac7 u0 Test input method} Display: mDisplayId=0");
        assertTrue(matches(attached));
        assertFalse(matches(attached.replace("ModPicker t18", "ModPicker t19")));
        assertFalse(matches(attached.replace("mCurrentFocus=null", "mCurrentFocus=Window{af1 u0 NotificationShade}")));
        assertFalse(matches(attached + " mImeWindow=null Display: mDisplayId=0\n"));
        assertFalse(matches(DUMP.replace(split, " title=Window{ac7 u0 Text Display: mDisplayId=0}")));
        assertFalse(matches(DUMP.replace(split, " mImeWindow=bad Display: mDisplayId=0")));
        assertFalse(matches(DUMP.replace(split, " mImeWindow=null Display: mDisplayId=8")));
    }

    @Test public void diagnosticsSeparateAbsentFieldsFromForeignFocus() {
        assertEquals(ControlledCastApprovalWindowEvidence.Result.MAIN_FIELDS_MISSING,
                inspect(DUMP.replace(" mCurrentFocus=null\n", "")));
        assertEquals(ControlledCastApprovalWindowEvidence.Result.MAIN_WINDOW_MISMATCH,
                inspect(DUMP.replace("mCurrentFocus=null", "mCurrentFocus=Window{b6 u0 NotificationShade}")));
        assertEquals(ControlledCastApprovalWindowEvidence.Result.REAR_ACTIVITY_MISMATCH,
                inspect(DUMP.replace("VirtualRearHost t45", "VirtualRearHost t46")));
    }

    private static ControlledCastApprovalWindowEvidence.Result inspect(String dump) {
        return ControlledCastApprovalWindowEvidence.inspect(dump, 1, 0, 18, PICKER, 45, REAR);
    }

    private static boolean matches(String dump) {
        return ControlledCastApprovalWindowEvidence.matches(dump, 1, 0, 18, PICKER, 45, REAR);
    }
}
