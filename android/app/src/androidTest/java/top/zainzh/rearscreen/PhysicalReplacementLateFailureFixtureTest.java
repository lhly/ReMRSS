package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
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

/** Arms one exact A→B physical late-failure and observes rollback; it never starts/stops either app. */
@RunWith(AndroidJUnit4.class)
public class PhysicalReplacementLateFailureFixtureTest {
    private static final String TAG = "RC5PhysicalReplaceTest";
    private static final long SERVICE_TIMEOUT_MS = 35_000L;
    private static final long A_START_TIMEOUT_MS = 60_000L;
    private static final long B_READY_TIMEOUT_MS = 120_000L;
    private static final long COOPERATIVE_CLEANUP_TIMEOUT_MS = 90_000L;
    private static final String A_PACKAGE = "dev.remrss.sample.rc5physicala";
    private static final String A_BASE = A_PACKAGE + "/dev.remrss.physical.MainActivity";
    private static final String B_PACKAGE = "dev.remrss.sample.rc5physicalb";
    private static final String B_BASE = B_PACKAGE + "/dev.remrss.physical.MainActivity";

    @Test public void lateFailureAfterPhysicalBVisibleRestoresExactAAndReturnsBHome() throws Exception {
        assumeTrue("physical replacement injection exists only in Debug", BuildConfig.DEBUG);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String cooperativeArg = args.getString("rc5PhysicalRollbackCooperative");
        assumeTrue("unsupported cooperative cleanup protocol version",
                cooperativeArg == null || "v1".equals(cooperativeArg));
        boolean cooperative = cooperativeArg != null;
        String stagedArg = args.getString("rc5PhysicalRollbackAfterBottomFocus");
        assumeTrue("unsupported staged bottom-focus rollback protocol version",
                stagedArg == null || "v1".equals(stagedArg));
        boolean stagedBottomFocus = stagedArg != null;
        int expectedBTask = cooperative ? parseTaskId(args.getString("rc5PhysicalRollbackBTask", "")) : -1;
        String expectedIdentity = cooperative ? args.getString("rc5PhysicalRollbackRearIdentity", "") : "";
        assumeTrue("cooperative cleanup requires explicit exact B task and rear identity",
                !cooperative || (BuildConfig.APPLICATION_ID.endsWith(".debug") && expectedBTask > 0
                        && expectedIdentity.matches("[1-9][0-9]*:.+")));
        assumeTrue("staged bottom-focus rollback requires cooperative v1 and an exact A/B/rear tuple",
                !stagedBottomFocus || (cooperative && expectedBTask > 0 && expectedIdentity.matches("[1-9][0-9]*:.+")));
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
            Log.i(TAG, "STAGE=WAIT_A; start exact controlled physical A dev.remrss.sample.rc5physicala via its existing SDK flow");
            long deadline = SystemClock.elapsedRealtime() + A_START_TIMEOUT_MS;
            boolean armed = false;
            boolean stagedProbeArmed = false;
            int expectedATask = -1;
            long expectedAGeneration = -1L;
            int rearDisplay = -1;
            String armedIdentity = null;
            while (!armed && SystemClock.elapsedRealtime() < deadline) {
                ITaskService current = remote[0];
                if (current != null) {
                    if (cooperative) {
                        LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity owner =
                                LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(
                                        context, RearDisplay.requireId());
                        boolean exactOwner = owner != null && A_PACKAGE.equals(owner.getPackageName())
                                && A_BASE.equals(owner.getBaseComponent())
                                && owner.getTaskId() > 0 && owner.getGeneration() > 0L
                                && owner.getTaskId() != expectedBTask
                                && expectedIdentity.equals(owner.getIdentity())
                                && ControlledPhysicalCastOperations.selectedRearIdentityMatches(
                                        current, owner.getDisplayId(), owner.getIdentity());
                        if (exactOwner) {
                            boolean failureArmed = false;
                            boolean focusArmed = false;
                            if (stagedBottomFocus) {
                                focusArmed = ControlledPhysicalCastOperations
                                        .armDebugPhysicalBFocusTransfer(context, current, expectedBTask);
                                if (focusArmed) {
                                    failureArmed = ControlledPhysicalCastOperations
                                            .armDebugPhysicalReplacementLateFailure(context, current,
                                                    expectedBTask, expectedIdentity);
                                }
                                if (focusArmed && failureArmed) {
                                    LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity currentOwner =
                                            LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(
                                                    context, owner.getDisplayId());
                                    boolean sameOwner = currentOwner != null
                                            && currentOwner.getTaskId() == owner.getTaskId()
                                            && currentOwner.getGeneration() == owner.getGeneration()
                                            && currentOwner.getDisplayId() == owner.getDisplayId()
                                            && expectedIdentity.equals(currentOwner.getIdentity())
                                            && (A_PACKAGE + ":" + owner.getTaskId()).equals(
                                                    RearScreenKeeperService.physicalCastTask())
                                            && RearScreenKeeperService.physicalCastOwnerGeneration()
                                                    == owner.getGeneration()
                                            && LegacyDisplayOccupancy.physicalKeeperStartClaimed(context,
                                                    A_PACKAGE + ":" + owner.getTaskId(), owner.getDisplayId(),
                                                    expectedIdentity, owner.getGeneration())
                                            && ControlledPhysicalCastOperations.selectedRearIdentityMatches(
                                                    current, owner.getDisplayId(), expectedIdentity);
                                    if (sameOwner) {
                                        armed = true;
                                        expectedATask = owner.getTaskId();
                                        expectedAGeneration = owner.getGeneration();
                                        rearDisplay = owner.getDisplayId();
                                        armedIdentity = owner.getIdentity();
                                        stagedProbeArmed = true;
                                    }
                                }
                                if (!armed && focusArmed) DebugPhysicalBFocusTransferProbe.clear();
                                if (!armed && (focusArmed || failureArmed))
                                    DebugPhysicalReplacementLateFailure.clearIfNotConsumed();
                            } else if (ControlledPhysicalCastOperations
                                    .armDebugPhysicalReplacementLateFailure(context, current,
                                            expectedBTask, expectedIdentity)) {
                                armed = true;
                                expectedATask = owner.getTaskId();
                                expectedAGeneration = owner.getGeneration();
                                rearDisplay = owner.getDisplayId();
                                armedIdentity = owner.getIdentity();
                            }
                        }
                    } else {
                        armed = ControlledPhysicalCastOperations
                                .armDebugPhysicalReplacementLateFailure(context, current);
                    }
                }
                if (!armed) Thread.sleep(250L);
            }
            assertTrue("Exact controlled physical A Keeper was not available within 60 seconds", armed);
            assertTrue("staged mode requires both one-shot gates to be armed together",
                    !stagedBottomFocus || stagedProbeArmed);
            Log.i(TAG, "STAGE=ARMED; start physical B dev.remrss.sample.rc5physicalb, approve '替换：RC5 Physical A（现有受控投放）' in ReMRSS");
            if (cooperative) Log.i(TAG, "STAGE=COOPERATIVE_ROLLBACK_ARMED v=1 a_task=" + expectedATask
                    + " b_task=" + expectedBTask + " rear_id=" + rearDisplay);
            if (stagedBottomFocus) Log.i(TAG, "STAGE=COOPERATIVE_ROLLBACK_STAGED_ARMED v=1 a_task="
                    + expectedATask + " b_task=" + expectedBTask + " rear_id=" + rearDisplay);
            boolean rollbackVerified = cooperative
                    ? DebugPhysicalReplacementLateFailure.awaitRollbackVerified(B_READY_TIMEOUT_MS,
                            expectedATask, expectedAGeneration, expectedBTask, rearDisplay, armedIdentity)
                    : DebugPhysicalReplacementLateFailure.awaitRollbackVerified(B_READY_TIMEOUT_MS);
            assertTrue("B never reached the visible-before-A-return injection and exact rollback within 120 seconds",
                    rollbackVerified);
            if (stagedBottomFocus) {
                String stagedOutcome = DebugPhysicalBFocusTransferProbe.awaitOutcome(0L);
                assertEquals("staged two-step focus did not reach exact B READY before injected rollback",
                        "ready", stagedOutcome);
            }
            Log.i(TAG, "STAGE=PASS injected_after_B_visible=true A_exact_owner_keeper_frame_restored=true B_exact_main=true");
            long observeMs = rollbackObserveWindowMs();
            Log.i(TAG, "STAGE=ROLLBACK_CAPTURE_READY observe_ms=" + observeMs
                    + "; exact A rollback and B main placement verified; test will not send input or close either app");
            if (cooperative) {
                assertTrue("captured exact A/B transaction identity is unavailable",
                        expectedATask > 0 && expectedAGeneration > 0L && expectedBTask > 0
                                && rearDisplay > 0 && armedIdentity != null);
                Log.i(TAG, "STAGE=COOPERATIVE_ROLLBACK_CLEANUP_PENDING v=1 b_task=" + expectedBTask
                        + " rear_id=" + rearDisplay);
                long cleanupDeadline = SystemClock.elapsedRealtime() + COOPERATIVE_CLEANUP_TIMEOUT_MS;
                boolean cleanupVerified = false;
                while (!cleanupVerified && SystemClock.elapsedRealtime() < cleanupDeadline) {
                    ITaskService current = remote[0];
                    cleanupVerified = current != null && cooperativeCleanupVerified(context, current,
                            expectedATask, expectedAGeneration, expectedBTask, rearDisplay, armedIdentity);
                    if (!cleanupVerified) Thread.sleep(1_000L);
                }
                if (!cleanupVerified) {
                    Log.i(TAG, "STAGE=COOPERATIVE_ROLLBACK_CLEANUP_TIMEOUT v=1 cleanup=unverified");
                    fail("SDK cleanup did not return the exact A/B tasks to MAIN and clear owner state within 90 seconds");
                }
                Log.i(TAG, "STAGE=COOPERATIVE_ROLLBACK_CLEANUP_VERIFIED v=1");
                android.os.Bundle status = new android.os.Bundle();
                status.putString("physicalRollbackCleanup", "verified");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
            } else {
                if (observeMs > 0L) Thread.sleep(observeMs);
                Log.i(TAG, "STAGE=AUTOMATED_ROLLBACK_VERIFIED; no physical input sent; A/B lifecycle left to operator");
            }
        } finally {
            DebugPhysicalReplacementLateFailure.clearIfNotConsumed();
            if (stagedBottomFocus) DebugPhysicalBFocusTransferProbe.clear();
            TaskServiceManager.release(connection);
        }
    }

    private static long rollbackObserveWindowMs() {
        String value = InstrumentationRegistry.getArguments().getString("rc5ObserveRollbackMs", "0");
        try { return Math.max(0L, Math.min(30_000L, Long.parseLong(value))); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static int parseTaskId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) return -1;
        try {
            long parsed = Long.parseLong(value);
            return parsed <= Integer.MAX_VALUE ? (int) parsed : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    private static boolean cooperativeCleanupVerified(Context context, ITaskService service,
            int aTask, long aGeneration, int bTask, int display, String identity) {
        try {
            if (LegacyDisplayOccupancy.physicalReplacementJournal(context) != null
                    || LegacyDisplayOccupancy.physicalProjectionGeneration(context, aTask, display) != 0L
                    || LegacyDisplayOccupancy.physicalProjectionGeneration(context, bTask, display) != 0L
                    || LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(context, display) != null
                    || LegacyDisplayOccupancy.physicalKeeperStartPending(context,
                            A_PACKAGE + ":" + aTask, display, identity, aGeneration)
                    || LegacyDisplayOccupancy.physicalKeeperStartClaimed(context,
                            A_PACKAGE + ":" + aTask, display, identity, aGeneration)
                    || RearScreenKeeperService.physicalCastTask() != null
                    || RearScreenKeeperService.physicalCastOwnerGeneration() != 0L
                    || RearScreenKeeperService.hasAnyKeeperInstance()
                    || !ControlledPhysicalCastOperations.selectedRearIdentityMatches(service, display, identity)) return false;
            android.content.SharedPreferences owner = context.getSharedPreferences(
                    "platform_display_ownership", Context.MODE_PRIVATE);
            if (owner.contains("physical_projection_task")
                    || owner.getBoolean("physical_projection_controlled", false)
                    || owner.getBoolean("physical_projection_keeper_start_pending", false)
                    || owner.getBoolean("physical_projection_keeper_start_claimed", false)
                    || owner.getBoolean("physical_projection_closing", false)
                    || owner.contains("physical_replacement_token")) return false;
            if (!exactMainPlacement(service, aTask, A_PACKAGE, A_BASE, display, identity)
                    || !exactMainPlacement(service, bTask, B_PACKAGE, B_BASE, display, identity)) return false;
            return "stopped".equals(new JSONObject(service.getRearVirtualCastStatus()).optString("state"))
                    && "stopped".equals(new JSONObject(service.getRearInputProxyStatus()).optString("state"));
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private static boolean exactMainPlacement(ITaskService service, int task, String packageName,
            String base, int display, String identity) throws Exception {
        JSONObject row = new JSONObject(service.queryExactTaskPlacement(task));
        int separator = identity.indexOf(':');
        if (separator <= 0 || separator == identity.length() - 1) return false;
        int selectedId = Integer.parseInt(identity.substring(0, separator));
        return "present".equals(row.optString("status")) && row.optInt("task", -1) == task
                && row.optInt("display", -1) == Display.DEFAULT_DISPLAY
                && packageName.equals(row.optString("package")) && base.equals(row.optString("base"))
                && base.equals(row.optString("top")) && row.optBoolean("rootSnapshotKnown", false)
                && row.optBoolean("rootSole", false) && row.optInt("selectedId", -1) == selectedId
                && selectedId == display && identity.substring(separator + 1)
                        .equals(row.optString("selectedUnique", ""));
    }
}
