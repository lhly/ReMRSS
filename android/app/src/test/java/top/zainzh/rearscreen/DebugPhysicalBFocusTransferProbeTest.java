package top.zainzh.rearscreen;

import org.json.JSONObject;
import android.view.Display;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DebugPhysicalBFocusTransferProbeTest {
    private static final String A_PACKAGE = "dev.remrss.sample.rc5physicala";
    private static final String A_BASE = A_PACKAGE + "/dev.remrss.physical.MainActivity";
    private static final String B_PACKAGE = "dev.remrss.sample.rc5physicalb";
    private static final String B_BASE = B_PACKAGE + "/dev.remrss.physical.MainActivity";
    private static final String IDENTITY = "1:local:test";
    private static final String OP = "operation-exact";

    @Test public void oneShotSequenceRequiresBottomReadbackBeforeFocusAndReady() {
        DebugPhysicalBFocusTransferProbe.Gate gate = new DebugPhysicalBFocusTransferProbe.Gate();
        assertTrue(gate.arm(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, 101, 202));
        assertTrue(gate.claim(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, B_PACKAGE, B_BASE, OP));
        assertFalse(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.FOCUS));
        assertTrue(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.BOTTOM));
        assertArrayEquals(new int[]{101, 202}, gate.dispatchProcessPids(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.BOTTOM));
        assertTrue(gate.dispatchCurrent(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.BOTTOM));
        assertFalse(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.BOTTOM));
        assertTrue(gate.recordBottomReadback(21, 8L, 22, 1, IDENTITY, OP));
        assertTrue(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.FOCUS));
        assertTrue(gate.recordFocusRequest(21, 8L, 22, 1, IDENTITY, OP));
        assertTrue(gate.recordReady(21, 8L, 22, 1, IDENTITY, OP));
        assertFalse(gate.recordReady(21, 8L, 22, 1, IDENTITY, OP));
        assertFalse(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.FOCUS));
    }

    @Test public void mismatchedFixtureAndRevokedPreDispatchLicenseNeverReachFocus() {
        DebugPhysicalBFocusTransferProbe.Gate gate = new DebugPhysicalBFocusTransferProbe.Gate();
        assertFalse(gate.arm(21, 8L, 22, 1, IDENTITY,
                "dev.other.app", "dev.other.app/.MainActivity", 101, 202));
        assertFalse(gate.arm(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, 0, 202));
        assertTrue(gate.arm(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, 101, 202));
        assertFalse(gate.claim(21, 9L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, B_PACKAGE, B_BASE, OP));
        assertTrue(gate.claim(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, B_PACKAGE, B_BASE, OP));
        assertTrue(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.BOTTOM));
        assertTrue(gate.recordBottomReadback(21, 8L, 22, 1, IDENTITY, OP));
        gate.clear();
        assertFalse(gate.dispatchCurrent(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.FOCUS));
        assertFalse(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.FOCUS));
    }

    @Test public void interstageInventoryUsesProductionSerializerAndRejectsUnknownForeignAndDuplicates() throws Exception {
        String valid = rearSnapshot(task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false));
        JSONObject serialized = new JSONObject(valid);
        JSONObject aRow = serialized.getJSONArray("tasks").getJSONObject(0);
        assertEquals(A_PACKAGE, aRow.getString("base"));
        assertEquals(A_BASE, aRow.getString("baseComponent"));
        assertFalse(aRow.has("package"));
        assertFalse(aRow.has("component"));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                serialized, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        // The main-screen picker may own global focus; rear-local A visibility/presentation is decisive.
        serialized.put("focusedTask", 99);
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                serialized, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject extra = new JSONObject(valid);
        extra.getJSONArray("tasks").put(new JSONObject().put("task", 23).put("base", "foreign")
                .put("top", "foreign").put("baseComponent", "foreign/.Activity")
                .put("topComponent", "foreign/.Activity"));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                extra, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject aHidden = new JSONObject(valid);
        aHidden.getJSONArray("tasks").getJSONObject(0).put("visible", false);
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                aHidden, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject wrongB = new JSONObject(valid);
        wrongB.getJSONArray("tasks").getJSONObject(1).put("base", "dev.other")
                .put("baseComponent", "dev.other/.MainActivity");
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                wrongB, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject bUnknown = new JSONObject(rearSnapshot(task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, null, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                bUnknown, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject bWrongTop = new JSONObject(valid);
        bWrongTop.getJSONArray("tasks").getJSONObject(1).put("topComponent", "dev.other/.Foreign");
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                bWrongTop, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject duplicate = new JSONObject(valid);
        duplicate.getJSONArray("tasks").getJSONObject(1).put("task", 21);
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                duplicate, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
    }

    @Test public void interstageAllowsOnlySameTypedHiddenOemDesktopShape() throws Exception {
        String launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
        JSONObject retained = new JSONObject(rearSnapshot(task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(23, "com.xiaomi.subscreencenter", launcher, false, false, false)));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                retained, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        retained.getJSONArray("tasks").getJSONObject(2).put("visible", true);
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                retained, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject unknownDesktop = new JSONObject(rearSnapshot(task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                new RearDisplayCastOccupancy.Task(23, "com.xiaomi.subscreencenter",
                        "com.xiaomi.subscreencenter", launcher, launcher, 0, false, null, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                unknownDesktop, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
        JSONObject foreign = new JSONObject(rearSnapshot(task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(24, "foreign", "foreign/.MainActivity", false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearPairWithAFront(
                foreign, 21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1));
    }

    @Test public void strictRearFocusRequiresAFrontBUniquelyHiddenAndExactPidPair() {
        PhysicalReplacementBottomFocus.DesktopIdentity desktop = desktopIdentity();
        RearDisplayCastOccupancy.Snapshot valid = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false)));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(valid, 21, A_PACKAGE, A_BASE,
                22, B_PACKAGE, B_BASE, 1, desktop, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot mainPickerFocused = new RearDisplayCastOccupancy.Snapshot(
                1, Display.STATE_ON, Display.STATE_ON, Display.STATE_ON, 99,
                Arrays.asList(task(21, A_PACKAGE, A_BASE, true, true, false),
                        task(22, B_PACKAGE, B_BASE, false, false, false)), "activity_task", null);
        assertTrue("rear-local A/B state remains valid while the main-screen picker owns global focus",
                DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(mainPickerFocused,
                        21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, desktop, launcherMatcher()));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(valid, 21, A_PACKAGE, A_BASE,
                22, B_PACKAGE, B_BASE, 1, null, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot flipped = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, false, false, false),
                task(22, B_PACKAGE, B_BASE, true, true, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(flipped, 21, A_PACKAGE, A_BASE,
                22, B_PACKAGE, B_BASE, 1, desktop, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot unknownA = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, null, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(unknownA, 21, A_PACKAGE, A_BASE,
                22, B_PACKAGE, B_BASE, 1, desktop, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot unknown = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, null, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(unknown, 21, A_PACKAGE, A_BASE,
                22, B_PACKAGE, B_BASE, 1, desktop, launcherMatcher()));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactProcessPidPair(101, 101, 202, 202));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactProcessPidPair(101, 101, 202, 303));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactProcessPidPair(101, 202, 202, 101));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactProcessPidPair(101, 101, 101, 101));
    }

    @Test public void hiddenBFocusStageNeedsTypedIdentityAndBoundProcessButNoVisibleDispatcherWindow() {
        // A hidden B has no WMS/InputDispatcher presentation argument by design; its original PID is
        // tied to the same A/B process snapshot and exact package UIDs instead.
        RearDisplayCastOccupancy.Snapshot hidden = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false)));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactHiddenPairAndBoundPids(hidden,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, desktopIdentity(), launcherMatcher(),
                101, 101, 202, 202));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactHiddenPairAndBoundPids(hidden,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, desktopIdentity(), launcherMatcher(),
                101, 101, 202, 303));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactHiddenPairAndBoundPids(hidden,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, desktopIdentity(), launcherMatcher(),
                101, 303, 202, 202));
        RearDisplayCastOccupancy.Snapshot wrongUser = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                taskForUser(22, B_PACKAGE, B_BASE, 10, false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactHiddenPairAndBoundPids(wrongUser,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, desktopIdentity(), launcherMatcher(),
                101, 101, 202, 202));
    }

    @Test public void rearTopologyAllowsOnlySamePreboundHiddenOemDesktopToRemain() {
        String launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
        PhysicalReplacementBottomFocus.DesktopIdentity bound = desktopIdentity();
        RearDisplayCastOccupancy.Snapshot noDesktopAfterMove = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false)));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(noDesktopAfterMove,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, bound, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot sameDesktopAfterMove = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(23, "com.xiaomi.subscreencenter", launcher, false, false, false)));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(sameDesktopAfterMove,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, bound, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot otherDesktop = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(24, "com.xiaomi.subscreencenter", launcher, false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(otherDesktop,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, bound, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot foreign = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(25, "foreign", "foreign/.Activity", false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(foreign,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, bound, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot desktopVisible = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(23, "com.xiaomi.subscreencenter", launcher, true, true, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(desktopVisible,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, bound, launcherMatcher()));
    }

    @Test public void abandonedExactOperationCanReleaseSlotButWrongOperationCannot() {
        DebugPhysicalBFocusTransferProbe.BottomBindingSlot slot =
                new DebugPhysicalBFocusTransferProbe.BottomBindingSlot(30_000L);
        PhysicalReplacementBottomFocus.DesktopIdentity desktop = desktopIdentity();
        assertTrue(slot.availableForNew(100L));
        assertTrue(slot.store(21, 8L, 22, 1, IDENTITY, "op-1", desktop, 100L));
        assertFalse(slot.availableForNew(101L));
        assertFalse(slot.clearExact(21, 8L, 22, 1, IDENTITY, "wrong-op", 102L));
        assertNull(slot.take(21, 8L, 22, 1, IDENTITY, "wrong-op", 103L));
        assertFalse(slot.availableForNew(104L));
        assertTrue(slot.clearExact(21, 8L, 22, 1, IDENTITY, "op-1", 105L));
        assertTrue(slot.availableForNew(106L));
        assertTrue(slot.store(21, 8L, 22, 1, IDENTITY, "op-2", desktop, 107L));
        assertNull("cleared operation can never authorize its delayed focus request",
                slot.take(21, 8L, 22, 1, IDENTITY, "op-1", 108L));
        assertNotNull(slot.take(21, 8L, 22, 1, IDENTITY, "op-2", 109L));
        assertTrue(slot.availableForNew(110L));
    }

    @Test public void expiredBindingIsClearedAndCannotAuthorizeOldFocus() {
        DebugPhysicalBFocusTransferProbe.BottomBindingSlot slot =
                new DebugPhysicalBFocusTransferProbe.BottomBindingSlot(30_000L);
        assertTrue(slot.store(21, 8L, 22, 1, IDENTITY, "expired", desktopIdentity(), 100L));
        assertNull(slot.take(21, 8L, 22, 1, IDENTITY, "expired", 30_100L));
        assertTrue(slot.availableForNew(30_101L));
        assertTrue(slot.store(21, 9L, 22, 1, IDENTITY, "next", desktopIdentity(), 30_102L));
    }

    @Test public void bottomStageRequiresExactAAndValidatedHiddenSystemDesktopOnly() {
        String launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
        RearDisplayCastOccupancy.Snapshot valid = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(23, "com.xiaomi.subscreencenter", launcher, false, false, false)));
        RearDisplayCastOccupancy.ExactLauncherMatcher allowExactLauncher = row -> row != null
                && "com.xiaomi.subscreencenter".equals(row.basePackage)
                && launcher.equals(row.baseComponent) && row.userId == 0;
        assertTrue(DebugPhysicalBFocusTransferProbe.exactAWithValidatedSystemDesktop(valid, 1, 21,
                A_PACKAGE, A_BASE, allowExactLauncher));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactAWithValidatedSystemDesktop(valid, 2, 21,
                A_PACKAGE, A_BASE, allowExactLauncher));
        RearDisplayCastOccupancy.Snapshot unknownDesktop = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(23, "com.xiaomi.subscreencenter", launcher, false, null, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactAWithValidatedSystemDesktop(unknownDesktop, 1, 21,
                A_PACKAGE, A_BASE, allowExactLauncher));
        RearDisplayCastOccupancy.Snapshot foreign = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(23, "foreign", "foreign/.Activity", false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactAWithValidatedSystemDesktop(foreign, 1, 21,
                A_PACKAGE, A_BASE, allowExactLauncher));
    }

    @Test public void bottomStageBindsExplicitlyAbsentDesktopAndRejectsItsLaterAppearance() {
        RearDisplayCastOccupancy.Snapshot onlyA = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false)));
        assertTrue("exercise TaskService's actual A inventory predicate",
                TaskService.rc5BottomAInventoryReady(onlyA, 1, 21, launcherMatcher()));
        assertFalse(TaskService.rc5BottomAInventoryReady(onlyA, 2, 21, launcherMatcher()));
        PhysicalReplacementBottomFocus.DesktopIdentity absent =
                DebugPhysicalBFocusTransferProbe.exactSystemDesktopIdentity(onlyA, 1, 21,
                        A_PACKAGE, A_BASE, launcherMatcher());
        assertNotNull("a stable exact A-only inventory is explicit absence, not unknown", absent);
        assertFalse(absent.exists);

        RearDisplayCastOccupancy.Snapshot afterMove = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false)));
        assertTrue(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(afterMove,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, absent, launcherMatcher()));

        String launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
        RearDisplayCastOccupancy.Snapshot appeared = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(23, "com.xiaomi.subscreencenter", launcher, false, false, false)));
        assertFalse("no launcher may appear after the pre-move absence binding",
                DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(appeared,
                        21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, absent, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot foreign = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(22, B_PACKAGE, B_BASE, false, false, false),
                task(24, "foreign", "foreign/.MainActivity", false, false, false)));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactRearAFrontBHidden(foreign,
                21, A_PACKAGE, A_BASE, 22, B_PACKAGE, B_BASE, 1, absent, launcherMatcher()));

        RearDisplayCastOccupancy.Snapshot unknownA = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, null, false)));
        assertNull("unknown requested state cannot be converted into absence",
                DebugPhysicalBFocusTransferProbe.exactSystemDesktopIdentity(unknownA, 1, 21,
                        A_PACKAGE, A_BASE, launcherMatcher()));
        RearDisplayCastOccupancy.Snapshot duplicateA = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(21, A_PACKAGE, A_BASE, true, true, false)));
        assertNull(DebugPhysicalBFocusTransferProbe.exactSystemDesktopIdentity(duplicateA, 1, 21,
                A_PACKAGE, A_BASE, launcherMatcher()));
        assertFalse(TaskService.rc5BottomAInventoryReady(duplicateA, 1, 21, launcherMatcher()));
    }

    @Test public void bottomAndFocusUseFullAssembledSelectedRearIdentity() throws Exception {
        String unique = "local:4630946457447247252";
        org.json.JSONObject state = new org.json.JSONObject().put("selectedId", 1)
                .put("selectedUnique", unique);
        assertTrue(TaskService.rc5SelectedRearStateMatches(state, 1, "1:" + unique));
        ControlledPhysicalCastOperations.SelectedRearIdentity sharedParser =
                ControlledPhysicalCastOperations.parseSelectedRearIdentity(state.toString());
        assertNotNull(sharedParser);
        assertTrue(sharedParser.matches(1, "1:" + unique));

        assertFalse("raw unique without the display prefix is not the composite identity",
                TaskService.rc5SelectedRearStateMatches(state, 1, unique));
        assertFalse(TaskService.rc5SelectedRearStateMatches(state, 1, "1"));
        assertFalse(TaskService.rc5SelectedRearStateMatches(state, 2, "1:" + unique));
        assertFalse(TaskService.rc5SelectedRearStateMatches(state, 1, "1:local:other"));
        assertFalse(TaskService.rc5SelectedRearStateMatches(
                new org.json.JSONObject().put("selectedId", 1).put("selectedUnique", unique).put("error", true),
                1, "1:" + unique));
        assertFalse(TaskService.rc5SelectedRearStateMatches(
                new org.json.JSONObject().put("selectedId", "1").put("selectedUnique", unique),
                1, "1:" + unique));
        assertFalse(TaskService.rc5SelectedRearStateMatches(
                new org.json.JSONObject().put("selectedId", 1).put("selectedUnique", "unknown"),
                1, "1:unknown"));
        assertFalse(TaskService.rc5SelectedRearStateMatches(
                new org.json.JSONObject().put("selectedId", 1).put("selectedUnique", " unknown "),
                1, "1: unknown "));
        assertFalse(TaskService.rc5SelectedRearStateMatches(
                new org.json.JSONObject().put("selectedId", 1).put("selectedUnique", "x"),
                1, "1:x"));
        assertFalse(TaskService.rc5SelectedRearStateMatches(
                new org.json.JSONObject().put("selectedId", 2).put("selectedUnique", unique),
                1, "2:" + unique));
    }

    @Test public void callerUidMustBeExactDebugHostUid() {
        assertTrue(DebugPhysicalBFocusTransferProbe.exactDebugHostUid(true,
                "top.zainzh.rearscreen.debug", 10234, 10234));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactDebugHostUid(true,
                "top.zainzh.rearscreen.debug", 2000, 10234));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactDebugHostUid(true,
                "top.zainzh.rearscreen.debug", 10234, -1));
        assertFalse(DebugPhysicalBFocusTransferProbe.exactDebugHostUid(true,
                "top.zainzh.rearscreen.compat", 10234, 10234));
    }

    @Test public void remoteProbeEndpointsAreDebugSuffixOnlyAndUnknownBottomNeverAdvances() {
        assertTrue(TaskService.isRc5PhysicalFocusExperimentAvailable(true, "top.zainzh.rearscreen.debug"));
        assertFalse(TaskService.isRc5PhysicalFocusExperimentAvailable(false, "top.zainzh.rearscreen.debug"));
        assertFalse(TaskService.isRc5PhysicalFocusExperimentAvailable(true, "top.zainzh.rearscreen.compat"));
        assertFalse(TaskService.isRc5PhysicalFocusExperimentAvailable(true, null));

        DebugPhysicalBFocusTransferProbe.Gate gate = new DebugPhysicalBFocusTransferProbe.Gate();
        assertTrue(gate.arm(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, 101, 202));
        assertTrue(gate.claim(21, 8L, 22, 1, IDENTITY, A_PACKAGE, A_BASE, B_PACKAGE, B_BASE, OP));
        assertTrue(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.BOTTOM));
        // No exact bottom readback represents a rejected/ambiguous side effect: focus cannot be retried.
        assertFalse(gate.recordBottomReadback(21, 8L, 22, 1, "1:other", OP));
        assertFalse(gate.beginDispatch(21, 8L, 22, 1, IDENTITY, OP,
                DebugPhysicalBFocusTransferProbe.Stage.FOCUS));
        assertFalse(gate.recordFocusRequest(21, 8L, 22, 1, IDENTITY, OP));
    }

    private static RearDisplayCastOccupancy.Task task(int id, String pkg, String component,
            Boolean visible, Boolean requested, Boolean sleeping) {
        return taskForUser(id, pkg, component, 0, visible, requested, sleeping);
    }

    private static RearDisplayCastOccupancy.Task taskForUser(int id, String pkg, String component, int userId,
            Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(id, pkg, pkg, component, component, userId,
                visible, requested, sleeping);
    }

    private static String rearSnapshot(RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_ON, Display.STATE_ON,
                Display.STATE_ON, Arrays.asList(tasks), "activity_task", null).toJson();
    }

    private static RearDisplayCastOccupancy.Snapshot parseRear(String json) {
        return RearDisplayCastOccupancy.Snapshot.parse(json, 1);
    }

    private static PhysicalReplacementBottomFocus.DesktopIdentity desktopIdentity() {
        String launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
        RearDisplayCastOccupancy.Snapshot before = parseRear(rearSnapshot(
                task(21, A_PACKAGE, A_BASE, true, true, false),
                task(23, "com.xiaomi.subscreencenter", launcher, false, false, false)));
        return DebugPhysicalBFocusTransferProbe.exactSystemDesktopIdentity(before, 1, 21,
                A_PACKAGE, A_BASE, launcherMatcher());
    }

    private static RearDisplayCastOccupancy.ExactLauncherMatcher launcherMatcher() {
        String launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher";
        return row -> row != null && "com.xiaomi.subscreencenter".equals(row.basePackage)
                && launcher.equals(row.baseComponent) && row.userId == 0;
    }
}
