package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import top.zainzh.rearscreen.platform.LegacyDisplayOccupancy;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** One-shot, explicit R21 exact-owner close at home. It never launches or moves either sample app. */
@RunWith(AndroidJUnit4.class)
public final class R20PhysicalReplacementJournalRecoveryTest {
    private static final String TAG = "R21JournalClose";
    private static final int A_TASK_R20 = 57724;
    private static final int A_TASK_R22 = 57772;
    private static final int A_TASK_R26 = 57801;
    private static final int A_TASK_R28 = 57895;
    private static final int A_TASK_R29 = 57918;
    private static final int A_TASK_R30 = 57935;
    private static final int A_TASK_R49 = 58490;
    private static final int B_TASK_R49 = 58493;
    private static final int A_TASK_KJPUYU = 58745;
    private static final int B_TASK_KJPUYU = 58801;
    private static final int A_TASK_WNAILL = 58510;
    private static final int B_TASK_WNAILL = 58513;
    private static final int B_TASK = 57727;
    private static final int REAR_DISPLAY = 1;
    private static final String R49_REAR_IDENTITY = "1:local:4630946457447247252";
    private static final String A_PACKAGE = "dev.remrss.sample.rc5physicala";
    private static final String A_BASE = "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity";
    private static final String B_PACKAGE = "dev.remrss.sample.rc5physicalb";
    private static final String B_BASE = "dev.remrss.sample.rc5physicalb/dev.remrss.physical.MainActivity";
    private static final long SERVICE_TIMEOUT_MS = 35_000L;

    @Test public void closeRetainedExactJournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(A_TASK_R20, B_TASK, "approved", "r21Result",
                "R21_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R21");
    }

    @Test public void closeRetainedR22JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(A_TASK_R22, B_TASK, "approved-r22", "r22Result",
                "R22_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R22");
    }

    @Test public void closeRetainedR26JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(A_TASK_R26, B_TASK, "approved-r26", "r26Result",
                "R26_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R26");
    }

    @Test public void closeRetainedR28JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(A_TASK_R28, 57898, "approved-r28", "r28Result",
                "R28_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R28");
    }

    @Test public void closeRetainedR29JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(A_TASK_R29, 57898, "approved-r29", "r29Result",
                "R29_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R29");
    }

    @Test public void closeRetainedR30JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(A_TASK_R30, 57898, "approved-r30", "r30Result",
                "R30_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R30");
    }

    @Test public void closeRetainedR31JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(57953, 57898, "approved-r31", "r31Result",
                "R31_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R31");
    }

    @Test public void closeRetainedR32JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(57971, 57898, "approved-r32", "r32Result",
                "R32_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R32");
    }

    @Test public void closeRetainedR34JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(58047, 58050, "approved-r34", "r34Result",
                "R34_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R34");
    }

    @Test public void closeRetainedR35JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(58067, 58050, "approved-r35", "r35Result",
                "R35_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R35");
    }

    @Test public void closeRetainedR36JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(58085, 58050, "approved-r36", "r36Result",
                "R36_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R36");
    }

    @Test public void closeRetainedR37JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(58105, 58050, "approved-r37", "r37Result",
                "R37_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R37");
    }

    @Test public void closeRetainedR39JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(58149, 58050, "approved-r39", "r39Result",
                "R39_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R39");
    }

    @Test public void closeRetainedR52JournalWithoutMovingA() throws Exception {
        runExactRetainedJournalStop(58592, 58595, "approved-r52", "r52Result",
                "R52_PRODUCT_JOURNAL_CLOSED_AT_HOME", "R52");
    }

    /** Close only the exact durable R49 A owner through the Debug no-move product close path. */
    @Test public void stopExactR49PhysicalOwnerAtHome() throws Exception {
        runExactRetainedPhysicalOwnerAtHome(A_TASK_R49, B_TASK_R49, "rc5R49OwnerStop",
                "rc5R49OwnerGeneration", "r49Result", "R49_EXACT_PHYSICAL_OWNER_STOPPED_AT_HOME", "R49");
    }

    /** Close only the exact durable WnailL A owner; B is a read-only identity/placement check. */
    @Test public void stopExactWnailLPhysicalOwnerAtHome() throws Exception {
        runExactRetainedPhysicalOwnerAtHome(A_TASK_WNAILL, B_TASK_WNAILL, "rc5WnailLOwnerStop",
                "rc5WnailLOwnerGeneration", "wnailLResult",
                "WNAILL_EXACT_PHYSICAL_OWNER_STOPPED_AT_HOME", "WNAILL");
    }

    /** Close the exact KJpYuu B lease through the ordinary product stop; never move either task. */
    @Test public void closeKJpYuuBThroughNormalPhysicalStop() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("product close is restricted to the isolated .debug host",
                BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".debug"));
        long generation = parsePositiveGeneration(args.getString("rc5KjpProductCloseGeneration", ""));
        assumeTrue("requires explicit product-close approval and a positive exact generation",
                "approved".equals(args.getString("rc5KjpProductClose")) && generation > 0L);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        ITaskService[] remote = new ITaskService[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote[0] = ITaskService.Stub.asInterface(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { remote[0] = null; }
        };
        TaskServiceManager.acquire(context, connection);
        TaskServiceManager.requestConnection(context);
        try {
            assertTrue("TaskService connection unavailable: " + TaskServiceManager.diagnostics(),
                    connected.await(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            ITaskService service = remote[0];
            assertNotNull(service);

            String exactB = B_PACKAGE + ":" + B_TASK_KJPUYU;
            LegacyDisplayOccupancy.PhysicalReplacementJournal journal =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            assertNull("no replacement journal may be present", journal);
            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity owner =
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, REAR_DISPLAY);
            assertNotNull("exact retained B owner is required", owner);
            assertEquals(B_TASK_KJPUYU, owner.getTaskId());
            assertEquals(B_PACKAGE, owner.getPackageName());
            assertEquals(B_BASE, owner.getBaseComponent());
            assertEquals(REAR_DISPLAY, owner.getDisplayId());
            assertEquals(R49_REAR_IDENTITY, owner.getIdentity());
            assertEquals(generation, owner.getGeneration());
            assertTrue("B remains the exact durable owner", LegacyDisplayOccupancy.physicalProjectionMatches(
                    context, B_TASK_KJPUYU, B_PACKAGE, B_BASE, REAR_DISPLAY, R49_REAR_IDENTITY, generation));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, A_TASK_KJPUYU, REAR_DISPLAY));
            assertEquals(generation, LegacyDisplayOccupancy.physicalProjectionGeneration(context, B_TASK_KJPUYU, REAR_DISPLAY));

            // Read-only direct preference assertions; this fixture never writes ownership state.
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    "platform_display_ownership", Context.MODE_PRIVATE);
            assertTrue("the exact owner must still be in closing state",
                    prefs.getBoolean("physical_projection_closing", false));
            assertTrue("this close must exercise the recorded launcher-disabled restore path",
                    prefs.getBoolean("physical_projection_launcher_disabled", false));
            assertTrue("the exact stale Keeper claim is the state under test",
                    LegacyDisplayOccupancy.physicalKeeperStartClaimed(context, exactB, REAR_DISPLAY,
                            R49_REAR_IDENTITY, generation));
            assertFalse("no start may remain pending", LegacyDisplayOccupancy.physicalKeeperStartPending(
                    context, exactB, REAR_DISPLAY, R49_REAR_IDENTITY, generation));
            assertTrue("no live or different Keeper may be present", !RearScreenKeeperService.hasAnyKeeperInstance()
                    && RearScreenKeeperService.physicalCastTask() == null
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L);
            assertTrue("selected physical rear identity changed", ControlledPhysicalCastOperations
                    .selectedRearIdentityMatches(service, REAR_DISPLAY, R49_REAR_IDENTITY));
            assertExactMainTaskAtRear(service, A_TASK_KJPUYU, A_PACKAGE, A_BASE);
            assertExactMainTaskAtRear(service, B_TASK_KJPUYU, B_PACKAGE, B_BASE);
            assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
            assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
            assertExactOemLauncherPresent(service.getDisplayCastOccupancy(REAR_DISPLAY));
            Log.i(TAG, "stage=KJPUYU_PRODUCT_CLOSE_PREFLIGHT_VERIFIED");

            // Reconfirm the conditions closest to the normal product stop call.
            assertNull("replacement journal appeared before stop",
                    LegacyDisplayOccupancy.physicalReplacementJournal(context));
            assertTrue("B owner changed before stop", LegacyDisplayOccupancy.physicalProjectionMatches(
                    context, B_TASK_KJPUYU, B_PACKAGE, B_BASE, REAR_DISPLAY, R49_REAR_IDENTITY, generation));
            assertTrue("B close/claim state changed before stop",
                    prefs.getBoolean("physical_projection_closing", false)
                            && prefs.getBoolean("physical_projection_launcher_disabled", false)
                            && LegacyDisplayOccupancy.physicalKeeperStartClaimed(context, exactB, REAR_DISPLAY,
                                    R49_REAR_IDENTITY, generation)
                            && !LegacyDisplayOccupancy.physicalKeeperStartPending(context, exactB, REAR_DISPLAY,
                                    R49_REAR_IDENTITY, generation));
            assertTrue("a Keeper appeared before stop", !RearScreenKeeperService.hasAnyKeeperInstance());
            assertTrue("selected rear identity changed before stop", ControlledPhysicalCastOperations
                    .selectedRearIdentityMatches(service, REAR_DISPLAY, R49_REAR_IDENTITY));
            assertExactMainTaskAtRear(service, A_TASK_KJPUYU, A_PACKAGE, A_BASE);
            assertExactMainTaskAtRear(service, B_TASK_KJPUYU, B_PACKAGE, B_BASE);
            assertExactOemLauncherPresent(service.getDisplayCastOccupancy(REAR_DISPLAY));

            ControlledPhysicalCastOperations.ReturnResult stopped = ControlledPhysicalCastOperations.stop(
                    context, service, "physical:" + B_TASK_KJPUYU + ":" + generation,
                    B_TASK_KJPUYU, B_PACKAGE, B_BASE, REAR_DISPLAY, R49_REAR_IDENTITY, generation);
            assertTrue("normal product stop did not complete: " + stopped.result, stopped.stopped);
            assertEquals("STOPPED", stopped.result);

            assertNull("normal stop must leave no replacement journal",
                    LegacyDisplayOccupancy.physicalReplacementJournal(context));
            assertNull("normal stop must clear the exact B owner",
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, A_TASK_KJPUYU, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, B_TASK_KJPUYU, REAR_DISPLAY));
            assertFalse("no stale Keeper start may remain claimed", LegacyDisplayOccupancy.physicalKeeperStartClaimed(
                    context, exactB, REAR_DISPLAY, R49_REAR_IDENTITY, generation));
            assertFalse("no Keeper start may remain pending", LegacyDisplayOccupancy.physicalKeeperStartPending(
                    context, exactB, REAR_DISPLAY, R49_REAR_IDENTITY, generation));
            assertTrue("no Keeper may remain", !RearScreenKeeperService.hasAnyKeeperInstance()
                    && RearScreenKeeperService.physicalCastTask() == null
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L);
            assertTrue("selected rear identity changed during close", ControlledPhysicalCastOperations
                    .selectedRearIdentityMatches(service, REAR_DISPLAY, R49_REAR_IDENTITY));
            assertExactMainTaskAtRear(service, A_TASK_KJPUYU, A_PACKAGE, A_BASE);
            assertExactMainTaskAtRear(service, B_TASK_KJPUYU, B_PACKAGE, B_BASE);
            assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
            assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
            assertExactOemLauncherPresent(service.getDisplayCastOccupancy(REAR_DISPLAY));
            Log.i(TAG, "stage=KJPUYU_PRODUCT_CLOSE_VERIFIED");
            Bundle result = new Bundle();
            result.putString("rc5KjpProductClose", "STOPPED");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        } finally {
            TaskServiceManager.release(connection);
        }
    }

    private static void assertExactOemLauncherPresent(String encoded) {
        RearDisplayCastOccupancy.Snapshot snapshot =
                RearDisplayCastOccupancy.Snapshot.parse(encoded, REAR_DISPLAY);
        assertEquals("rear snapshot must be stable typed TaskManager data", null, snapshot.failure);
        assertEquals(REAR_DISPLAY, snapshot.displayId);
        assertEquals("activity_task", snapshot.source);
        assertEquals("the singleton exact OEM launcher must be present",
                RearDisplayCastOccupancy.ControlledLauncherRestoreDisposition.SYSTEM_LAUNCHER,
                RearDisplayCastOccupancy.controlledLauncherRestoreDisposition(snapshot, REAR_DISPLAY,
                        RearDisplayCastOccupancy::isExactSystemLauncher));
    }

    private static void runExactRetainedPhysicalOwnerAtHome(int aTask, int bTask,
            String authorizationKey, String generationKey, String resultKey, String resultValue,
            String label) throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue(label + " owner close is available only in the app's isolated .debug build",
                BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".debug"));
        String generationArg = args.getString(generationKey, "");
        long expectedGeneration = parsePositiveGeneration(generationArg);
        assumeTrue("requires explicit " + label + " owner-stop authorization and a positive durable generation",
                "approved".equals(args.getString(authorizationKey)) && expectedGeneration > 0L);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        ITaskService[] remote = new ITaskService[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote[0] = ITaskService.Stub.asInterface(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { remote[0] = null; }
        };
        TaskServiceManager.acquire(context, connection);
        TaskServiceManager.requestConnection(context);
        try {
            assertTrue("Existing TaskService connection unavailable: " + TaskServiceManager.diagnostics(),
                    connected.await(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            ITaskService service = remote[0];
            assertNotNull(service);

            LegacyDisplayOccupancy.PhysicalReplacementJournal journal =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            assertNull(label + " owner stop must not touch a replacement journal", journal);
            LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity owner =
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, REAR_DISPLAY);
            assertNotNull("the exact durable " + label + " owner must still be present", owner);
            assertEquals(aTask, owner.getTaskId());
            assertEquals(A_PACKAGE, owner.getPackageName());
            assertEquals(A_BASE, owner.getBaseComponent());
            assertEquals(REAR_DISPLAY, owner.getDisplayId());
            assertEquals(R49_REAR_IDENTITY, owner.getIdentity());
            assertEquals(expectedGeneration, owner.getGeneration());
            assertTrue("durable owner must remain the exact controlled " + label + " A owner",
                    LegacyDisplayOccupancy.physicalProjectionMatches(context, aTask, A_PACKAGE,
                            A_BASE, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration));
            assertEquals(expectedGeneration,
                    LegacyDisplayOccupancy.physicalProjectionGeneration(context, aTask, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, bTask, REAR_DISPLAY));
            assertTrue("no Keeper may own the exact " + label + " task", RearScreenKeeperService.physicalCastTask() == null
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L
                    && !RearScreenKeeperService.hasAnyKeeperInstance());
            assertFalse("no Keeper start may remain pending", LegacyDisplayOccupancy.physicalKeeperStartPending(
                    context, A_PACKAGE + ":" + aTask, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration));
            assertTrue("selected rear identity changed", ControlledPhysicalCastOperations
                    .selectedRearIdentityMatches(service, REAR_DISPLAY, R49_REAR_IDENTITY));
            assertExactMainTaskAtRear(service, aTask, A_PACKAGE, A_BASE);
            assertExactMainTaskAtRear(service, bTask, B_PACKAGE, B_BASE);
            assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
            assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
            String beforeSnapshotRaw = service.getDisplayCastOccupancy(REAR_DISPLAY);
            assertStableKnownPowerAndIdleRear(beforeSnapshotRaw);
            Log.i(label + "OwnerStop", "stage=EXACT_OWNER_PREFLIGHT_VERIFIED A=" + aTask + " MAIN=true B=" + bTask + " MAIN=true"
                    + " journal=absent owner=exact generation=matched keeper=absent virtual=input=stopped");

            // All display snapshots above are observational only; repeat the exact guards immediately
            // before dispatch so a changed task, selected display, journal, or owner cannot be stopped.
            assertNull("replacement journal appeared before stop",
                    LegacyDisplayOccupancy.physicalReplacementJournal(context));
            assertTrue("durable " + label + " owner changed before stop",
                    LegacyDisplayOccupancy.physicalProjectionMatches(context, aTask, A_PACKAGE,
                            A_BASE, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration));
            assertEquals(expectedGeneration,
                    LegacyDisplayOccupancy.physicalProjectionGeneration(context, aTask, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, bTask, REAR_DISPLAY));
            assertFalse("Keeper start became pending before stop", LegacyDisplayOccupancy.physicalKeeperStartPending(
                    context, A_PACKAGE + ":" + aTask, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration));
            assertTrue("Keeper appeared before stop", RearScreenKeeperService.physicalCastTask() == null
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L
                    && !RearScreenKeeperService.hasAnyKeeperInstance());
            assertTrue("selected rear identity changed before stop", ControlledPhysicalCastOperations
                    .selectedRearIdentityMatches(service, REAR_DISPLAY, R49_REAR_IDENTITY));
            assertExactMainTaskAtRear(service, aTask, A_PACKAGE, A_BASE);
            assertExactMainTaskAtRear(service, bTask, B_PACKAGE, B_BASE);

            ControlledPhysicalCastOperations.ReturnResult stopped =
                    ControlledPhysicalCastOperations.stopRetainedPhysicalOwnerAtHomeForDebug(
                    context, service, "physical:" + aTask + ":" + expectedGeneration,
                    aTask, A_PACKAGE, A_BASE, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration);
            assertTrue("debug no-move product close failed: " + stopped.result, stopped.stopped);
            assertEquals("STOPPED", stopped.result);

            assertNull("product stop must leave no replacement journal",
                    LegacyDisplayOccupancy.physicalReplacementJournal(context));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, aTask, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, bTask, REAR_DISPLAY));
            assertNull("no durable controlled owner may remain",
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, REAR_DISPLAY));
            assertFalse("no Keeper start may remain pending", LegacyDisplayOccupancy.physicalKeeperStartPending(
                    context, A_PACKAGE + ":" + aTask, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration));
            assertFalse("no Keeper start may remain claimed", LegacyDisplayOccupancy.physicalKeeperStartClaimed(
                    context, A_PACKAGE + ":" + aTask, REAR_DISPLAY, R49_REAR_IDENTITY, expectedGeneration));
            assertTrue("no Keeper may remain", RearScreenKeeperService.physicalCastTask() == null
                    && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L
                    && !RearScreenKeeperService.hasAnyKeeperInstance());
            assertTrue("selected rear identity changed during stop", ControlledPhysicalCastOperations
                    .selectedRearIdentityMatches(service, REAR_DISPLAY, R49_REAR_IDENTITY));
            assertExactMainTaskAtRear(service, aTask, A_PACKAGE, A_BASE);
            assertExactMainTaskAtRear(service, bTask, B_PACKAGE, B_BASE);
            assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
            assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
            String afterSnapshotRaw = service.getDisplayCastOccupancy(REAR_DISPLAY);
            assertStableKnownPowerAndIdleRear(afterSnapshotRaw);
            RearDisplayCastOccupancy.Snapshot afterSnapshot =
                    RearDisplayCastOccupancy.Snapshot.parse(afterSnapshotRaw, REAR_DISPLAY);
            assertEquals("the exact real rear display changed", REAR_DISPLAY, afterSnapshot.displayId);
            Log.i(label + "OwnerStop", "stage=EXACT_OWNER_STOP_VERIFIED A=" + aTask + " MAIN=true B=" + bTask + " MAIN=true"
                    + " journal=absent owner=absent keeper=absent virtual=input=stopped");
            Bundle result = new Bundle();
            result.putString(resultKey, resultValue);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        } finally {
            TaskServiceManager.release(connection);
        }
    }

    private static void assertExactMainTaskAtRear(ITaskService service, int taskId,
            String packageName, String baseComponent) throws Exception {
        JSONObject placement = new JSONObject(service.queryExactTaskPlacement(taskId));
        assertEquals("present", placement.getString("status"));
        assertEquals(taskId, placement.getInt("task"));
        assertEquals(0, placement.getInt("display"));
        assertEquals(packageName, placement.getString("package"));
        assertEquals(baseComponent, placement.getString("base"));
        assertEquals("exact task component must remain at the top", baseComponent, placement.getString("top"));
        assertEquals(REAR_DISPLAY, placement.getInt("selectedId"));
        assertEquals(R49_REAR_IDENTITY,
                placement.getInt("selectedId") + ":" + placement.getString("selectedUnique"));
        assertTrue("task root snapshot must be complete and unique",
                placement.getBoolean("rootSnapshotKnown") && placement.getBoolean("rootSole"));
        assertTrue("TaskService display query disagrees with exact MAIN placement",
                service.isTaskOnDisplay(taskId, 0));
    }

    private static long parsePositiveGeneration(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return -1L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return -1L; }
    }

    private void runExactRetainedJournalStop(int expectedOldTask, int expectedNewTask,
            String approvedValue, String resultKey, String resultValue, String stagePrefix) throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        boolean mixedRollback = "R39".equals(stagePrefix);
        String expectedDecision = mixedRollback ? "ROLLBACK" : "NONE";
        assumeTrue(stagePrefix + " journal close is available only in the app's .debug build",
                BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".debug"));
        assumeTrue("requires the dedicated " + stagePrefix + " selector and exact retained-journal arguments",
                approvedValue.equals(args.getString("rc5R20JournalRecovery"))
                        && Integer.toString(expectedOldTask).equals(args.getString("rc5R20OldTask"))
                        && Integer.toString(expectedNewTask).equals(args.getString("rc5R20NewTask"))
                        && "PREPARING".equals(args.getString("rc5R20Phase"))
                        && expectedDecision.equals(args.getString("rc5R20Decision"))
                        && A_PACKAGE.equals(args.getString("rc5R20OldPackage"))
                        && A_BASE.equals(args.getString("rc5R20OldBase"))
                        && B_PACKAGE.equals(args.getString("rc5R20NewPackage"))
                        && B_BASE.equals(args.getString("rc5R20NewBase"))
                        && args.getString("rc5R20RearIdentity") != null
                        && args.getString("rc5R20RearIdentity").startsWith(REAR_DISPLAY + ":"));

        String rearIdentity = args.getString("rc5R20RearIdentity");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        ITaskService[] remote = new ITaskService[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote[0] = ITaskService.Stub.asInterface(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { remote[0] = null; }
        };
        TaskServiceManager.acquire(context, connection);
        TaskServiceManager.requestConnection(context);
        try {
            assertTrue("Existing TaskService connection unavailable: " + TaskServiceManager.diagnostics(),
                    connected.await(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            ITaskService service = remote[0];
            assertNotNull(service);

            LegacyDisplayOccupancy.PhysicalReplacementJournal journal =
                    LegacyDisplayOccupancy.physicalReplacementJournal(context);
            assertNotNull("the exact retained journal must still be present", journal);
            assertEquals(expectedOldTask, journal.getOldTask());
            assertEquals(A_PACKAGE, journal.getOldPackage());
            assertEquals(A_BASE, journal.getOldBase());
            assertEquals(expectedNewTask, journal.getNewTask());
            assertEquals(B_PACKAGE, journal.getNewPackage());
            assertEquals(B_BASE, journal.getNewBase());
            assertEquals(REAR_DISPLAY, journal.getDisplayId());
            assertEquals(rearIdentity, journal.getIdentity());
            assertEquals("PREPARING", journal.getPhase());
            assertEquals(expectedDecision, journal.getDecision());
            assertTrue("saved A generation must be exact", journal.getOldGeneration() > 0L);
            assertTrue("the exact journal records its disabled OEM launcher", journal.getOldLauncherDisabled());
            assertEquals(mixedRollback, journal.getNewVirtual());
            if (mixedRollback) {
                assertTrue("the exact candidate must be unallocated or fully released",
                        ControlledPhysicalCastOperations.hasTerminalCandidateForExplicitRollbackClose(
                                journal, new JSONObject(service.getRearVirtualCastStatus())));
            } else assertTrue("no physical candidate session may have been accepted", journal.getNewSessionId().isEmpty());

            assertTrue("selected rear display identity changed",
                    ControlledPhysicalCastOperations.selectedRearIdentityMatches(service, REAR_DISPLAY, rearIdentity));
            assertTrue("saved A must remain the sole controlled projection owner",
                    LegacyDisplayOccupancy.physicalProjectionMatches(context, expectedOldTask, A_PACKAGE, A_BASE,
                            REAR_DISPLAY, rearIdentity, journal.getOldGeneration()));
            assertEquals(A_PACKAGE + ":" + expectedOldTask,
                    LegacyDisplayOccupancy.physicalProjectionTask(context, service, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, expectedNewTask, REAR_DISPLAY));

            assertExactTask(service, expectedOldTask, A_PACKAGE, A_BASE, 0, REAR_DISPLAY, rearIdentity);
            assertExactTask(service, expectedNewTask, B_PACKAGE, B_BASE, 0, REAR_DISPLAY, rearIdentity);
            assertTrue("a live Keeper owner would be unrelated/stale authority",
                    RearScreenKeeperService.physicalCastTask() == null
                            && RearScreenKeeperService.physicalCastOwnerGeneration() == 0L);
            assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
            assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
            String beforeStopRearSnapshot = service.getDisplayCastOccupancy(REAR_DISPLAY);
            assertStableKnownPowerAndIdleRear(beforeStopRearSnapshot);
            Log.i(TAG, "stage=" + stagePrefix + "_PREFLIGHT_VERIFIED A=" + expectedOldTask + " MAIN=true B="
                    + expectedNewTask + " MAIN=true journal=PREPARING/" + expectedDecision
                    + " owner=exact keeper=absent virtual=input=stopped");

            ControlledPhysicalCastOperations.ReturnResult stopped = ControlledPhysicalCastOperations.stop(
                    context, service, "physical:" + expectedOldTask + ":" + journal.getOldGeneration(),
                    expectedOldTask, A_PACKAGE, A_BASE, REAR_DISPLAY, rearIdentity, journal.getOldGeneration());
            assertTrue("exact saved A return failed: " + stopped.result, stopped.stopped);

            assertNull("product stop must close the exact R20 journal", LegacyDisplayOccupancy.physicalReplacementJournal(context));
            assertNull("no controlled physical owner may remain",
                    LegacyDisplayOccupancy.physicalProjectionTask(context, service, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, expectedOldTask, REAR_DISPLAY));
            assertEquals(0L, LegacyDisplayOccupancy.physicalProjectionGeneration(context, expectedNewTask, REAR_DISPLAY));
            assertNull("no controlled physical owner recovery record may remain",
                    LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, REAR_DISPLAY));
            assertFalse("no Keeper start may remain pending", LegacyDisplayOccupancy.physicalKeeperStartPending(
                    context, A_PACKAGE + ":" + expectedOldTask, REAR_DISPLAY, rearIdentity, journal.getOldGeneration()));
            assertFalse("no Keeper start may remain claimed", LegacyDisplayOccupancy.physicalKeeperStartClaimed(
                    context, A_PACKAGE + ":" + expectedOldTask, REAR_DISPLAY, rearIdentity, journal.getOldGeneration()));
            assertNull("Keeper must release the exact A session", RearScreenKeeperService.physicalCastTask());
            assertEquals(0L, RearScreenKeeperService.physicalCastOwnerGeneration());
            assertTrue("rear identity changed during return",
                    ControlledPhysicalCastOperations.selectedRearIdentityMatches(service, REAR_DISPLAY, rearIdentity));
            assertExactTask(service, expectedOldTask, A_PACKAGE, A_BASE, 0, REAR_DISPLAY, rearIdentity);
            assertExactTask(service, expectedNewTask, B_PACKAGE, B_BASE, 0, REAR_DISPLAY, rearIdentity);
            assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
            assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
            String afterStopRearSnapshot = service.getDisplayCastOccupancy(REAR_DISPLAY);
            assertStableKnownPowerAndIdleRear(afterStopRearSnapshot);
            Log.i(TAG, "stage=" + stagePrefix + "_STOP_VERIFIED A=" + expectedOldTask + " MAIN=true B="
                    + expectedNewTask + " MAIN=true journal=absent owner=absent keeper=absent virtual=input=stopped");
            Log.i(TAG, resultValue);
            Bundle result = new Bundle();
            result.putString(resultKey, resultValue);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        } finally {
            TaskServiceManager.release(connection);
        }
    }

    private static void assertExactTask(ITaskService service, int taskId, String packageName,
            String baseComponent, int expectedDisplay, int rearDisplayId, String rearIdentity) throws Exception {
        JSONObject placement = new JSONObject(service.queryExactTaskPlacement(taskId));
        assertEquals("present", placement.getString("status"));
        assertEquals(taskId, placement.getInt("task"));
        assertEquals(expectedDisplay, placement.getInt("display"));
        assertEquals(packageName, placement.getString("package"));
        assertEquals(baseComponent, placement.getString("base"));
        assertTrue("top component must remain inside the exact fixture package",
                placement.getString("top").startsWith(packageName + "/"));
        assertEquals(rearDisplayId, placement.getInt("selectedId"));
        assertEquals(rearIdentity, placement.getInt("selectedId") + ":" + placement.getString("selectedUnique"));
        assertTrue("task root snapshot must be complete and unique", placement.getBoolean("rootSnapshotKnown")
                && placement.getBoolean("rootSole"));
        assertTrue("TaskService display query disagrees with exact placement", service.isTaskOnDisplay(taskId, expectedDisplay));
    }

    /** One fresh TaskService snapshot must prove stable known power and empty/singleton validated OEM occupancy. */
    private static void assertStableKnownPowerAndIdleRear(String encoded) {
        RearDisplayCastOccupancy.Snapshot snapshot =
                RearDisplayCastOccupancy.Snapshot.parse(encoded, REAR_DISPLAY);
        assertNull("rear occupancy snapshot must be available and exact", snapshot.failure);
        assertEquals(REAR_DISPLAY, snapshot.displayId);
        assertTrue("rear snapshot must use a known ActivityTask source",
                "activity_task".equals(snapshot.source)
                        || "activity_task_focused_stack".equals(snapshot.source)
                        || "activity_focused_stack".equals(snapshot.source));
        int power = snapshot.displayState;
        assertTrue("rear power must be a known stable state",
                power == Display.STATE_OFF || power == Display.STATE_ON
                        || power == Display.STATE_DOZE || power == Display.STATE_DOZE_SUSPEND);
        assertEquals("rear power changed across the same occupancy snapshot", power, snapshot.powerBefore);
        assertEquals(power, snapshot.powerAfter);
        assertTrue("rear must be empty or contain only the unique validated OEM launcher",
                RearDisplayCastOccupancy.emptyOrOnlySystemLauncher(encoded, REAR_DISPLAY));
    }
}
