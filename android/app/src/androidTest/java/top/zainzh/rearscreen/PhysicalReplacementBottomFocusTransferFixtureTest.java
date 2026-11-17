package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import top.zainzh.rearscreen.platform.LegacyDisplayOccupancy;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Explicitly armed exact A/B test for B-bottom followed by one B-focus request; it never starts either app. */
@RunWith(AndroidJUnit4.class)
public final class PhysicalReplacementBottomFocusTransferFixtureTest {
    private static final String TAG = "RC5PhysicalBottomFocusTest";
    private static final long SERVICE_TIMEOUT_MS = 35_000L;
    private static final long ARM_TIMEOUT_MS = 60_000L;
    private static final long OUTCOME_TIMEOUT_MS = 120_000L;
    private static final long CLEANUP_TIMEOUT_MS = 90_000L;
    private static final String A_PACKAGE = "dev.remrss.sample.rc5physicala";
    private static final String A_BASE = A_PACKAGE + "/dev.remrss.physical.MainActivity";

    @Test public void exactPhysicalBFocusTransfer() throws Exception {
        assumeTrue("B bottom/focus experiment exists only in isolated Debug", BuildConfig.DEBUG
                && BuildConfig.APPLICATION_ID.endsWith(".debug"));
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("unsupported experiment version", "v1".equals(args.getString("rc5BottomFocusTransfer", "")));
        int expectedBTask = parseTaskId(args.getString("rc5FocusBTask", ""));
        String expectedIdentity = args.getString("rc5FocusRearIdentity", "");
        assumeTrue("exact B task and physical rear identity are required", expectedBTask > 0
                && expectedIdentity.matches("[1-9][0-9]*:.+"));

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
        DebugPhysicalBFocusTransferProbe.clear();
        TaskServiceManager.acquire(context, connection);
        TaskServiceManager.requestConnection(context);
        try {
            assertTrue("TaskService connection unavailable: " + TaskServiceManager.diagnostics(),
                    connected.await(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            Log.i(TAG, "STAGE=WAIT_A");
            long deadline = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS;
            boolean armed = false;
            int aTask = -1;
            long aGeneration = -1L;
            int rearDisplay = -1;
            String rearIdentity = null;
            while (!armed && SystemClock.elapsedRealtime() < deadline) {
                ITaskService service = remote[0];
                if (service != null) {
                    try {
                        LegacyDisplayOccupancy.ControlledPhysicalProjectionIdentity owner =
                                LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(
                                        context, RearDisplay.requireId());
                        boolean exactOwner = owner != null && A_PACKAGE.equals(owner.getPackageName())
                                && A_BASE.equals(owner.getBaseComponent()) && owner.getTaskId() > 0
                                && owner.getGeneration() > 0L && owner.getTaskId() != expectedBTask
                                && expectedIdentity.equals(owner.getIdentity())
                                && ControlledPhysicalCastOperations.selectedRearIdentityMatches(
                                        service, owner.getDisplayId(), owner.getIdentity());
                        if (exactOwner && ControlledPhysicalCastOperations.armDebugPhysicalBFocusTransfer(
                                context, service, expectedBTask)) {
                            aTask = owner.getTaskId(); aGeneration = owner.getGeneration();
                            rearDisplay = owner.getDisplayId(); rearIdentity = owner.getIdentity(); armed = true;
                        }
                    } catch (Exception unavailable) { armed = false; }
                }
                if (!armed) Thread.sleep(250L);
            }
            assertTrue("exact A owner and expected B MAIN task were not available", armed);
            Log.i(TAG, "STAGE=ARMED_B_BOTTOM_FOCUS v=1 a_task=" + aTask
                    + " b_task=" + expectedBTask + " rear_id=" + rearDisplay);

            String outcome = DebugPhysicalBFocusTransferProbe.awaitOutcome(OUTCOME_TIMEOUT_MS);
            assertTrue("physical A→B transaction did not reach a bounded terminal outcome", outcome != null);
            Log.i(TAG, "STAGE=PROBE_RESULT_PENDING_CLEANUP result=" + outcome);

            long cleanupDeadline = SystemClock.elapsedRealtime() + CLEANUP_TIMEOUT_MS;
            boolean cleanupVerified = false;
            while (!cleanupVerified && SystemClock.elapsedRealtime() < cleanupDeadline) {
                ITaskService service = remote[0];
                cleanupVerified = service != null
                        && DebugPhysicalBFocusTransferProbe.cleanupVerified(context, service);
                if (!cleanupVerified) Thread.sleep(1_000L);
            }
            assertTrue("SDK did not return exact A/B MAIN and clear physical ownership within 90 seconds",
                    cleanupVerified);
            Log.i(TAG, "STAGE=PROBE_COMPLETE cleanup=both_sdk_closed");
            android.os.Bundle status = new android.os.Bundle();
            status.putString("physicalBFocusTransferCleanup", "verified");
            status.putString("physicalBFocusTransferResult", outcome);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
        } finally {
            DebugPhysicalBFocusTransferProbe.clear();
            TaskServiceManager.release(connection);
        }
    }

    private static int parseTaskId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) return -1;
        try {
            long parsed = Long.parseLong(value);
            return parsed <= Integer.MAX_VALUE ? (int) parsed : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }
}
