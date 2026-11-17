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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/** Explicit opt-in exact RC5 A/B MAIN-focus comparison; no task moves or cleanup are issued here. */
@RunWith(AndroidJUnit4.class)
public final class PhysicalReplacementMainFocusGateFixtureTest {
    private static final String TAG = "RC5PhysicalMainFocusGateTest";
    private static final long OUTCOME_TIMEOUT_MS = 120_000L;
    private static final long CLEANUP_TIMEOUT_MS = 90_000L;

    @Test public void mainFocusBeforeBMove() throws Exception {
        assumeTrue("main-focus comparison exists only in Debug", BuildConfig.DEBUG);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String modeArg = args.getString("rc5MainFocusMode", "");
        String expectedIdentity = args.getString("rc5FocusRearIdentity", "");
        int expectedBTask = parseTaskId(args.getString("rc5FocusBTask", ""));
        DebugPhysicalReplacementMainFocusGate.Mode mode = "observe".equals(modeArg)
                ? DebugPhysicalReplacementMainFocusGate.Mode.OBSERVE
                : "gated".equals(modeArg) ? DebugPhysicalReplacementMainFocusGate.Mode.GATED : null;
        assumeTrue("invalid explicit mode, exact B task, or rear identity argument", mode != null
                && expectedBTask > 0 && expectedIdentity.matches("[1-9][0-9]*:.+"));

        CountDownLatch connected = new CountDownLatch(1);
        ITaskService[] remote = new ITaskService[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote[0] = ITaskService.Stub.asInterface(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { remote[0] = null; }
        };
        DebugPhysicalReplacementMainFocusProbe.clear();
        TaskServiceManager.acquire(context, connection);
        TaskServiceManager.requestConnection(context);
        try {
            assertNotNull("TaskService connection unavailable: " + TaskServiceManager.diagnostics(),
                    connected.await(35_000L, TimeUnit.MILLISECONDS) ? remote[0] : null);
            assumeTrue("TaskService does not expose the exact Debug MAIN-focus capability",
                    ControlledPhysicalCastOperations.debugMainFocusProbeAvailable(
                        remote[0], expectedBTask, expectedIdentity));
            Log.i(TAG, "STAGE=WAIT_A");
            long deadline = SystemClock.elapsedRealtime() + 60_000L;
            boolean armed = false;
            while (!armed && SystemClock.elapsedRealtime() < deadline) {
                ITaskService current = remote[0];
                if (current != null) {
                    try { armed = ControlledPhysicalCastOperations
                            .armDebugPhysicalReplacementMainFocusGate(context, current, expectedBTask, mode); }
                    catch (Exception unavailable) { armed = false; }
                }
                if (!armed) Thread.sleep(250L);
            }
            assertTrue("Exact controlled physical A owner was not available within 60 seconds", armed);
            Log.i(TAG, "STAGE=ARMED_EXACT_B_MAIN_FOCUS v=1 mode=" + modeArg + " lane=b");
            String result = DebugPhysicalReplacementMainFocusProbe.awaitDecision(OUTCOME_TIMEOUT_MS);
            String outcome = result == null ? "outcome_timeout" : result;
            Log.i(TAG, "STAGE=PROBE_RESULT_PENDING_CLEANUP mode=" + modeArg + " result=" + outcome);
            long cleanupDeadline = SystemClock.elapsedRealtime() + CLEANUP_TIMEOUT_MS;
            boolean cleanupVerified = false;
            while (!cleanupVerified && SystemClock.elapsedRealtime() < cleanupDeadline) {
                ITaskService current = remote[0];
                cleanupVerified = current != null
                        && DebugPhysicalReplacementMainFocusProbe.cleanupVerified(context, current);
                if (!cleanupVerified) Thread.sleep(1_000L);
            }
            if (cleanupVerified) {
                Log.i(TAG, "STAGE=PROBE_COMPLETE mode=" + modeArg + " cleanup=both_sdk_closed");
            } else {
                Log.i(TAG, "STAGE=PROBE_CLEANUP_TIMEOUT mode=" + modeArg + " cleanup=unverified");
                fail("Exact A/B were not both returned to MAIN with the physical owner cleared "
                        + "within the bounded cleanup observation window");
            }
        } finally {
            DebugPhysicalReplacementMainFocusProbe.clear();
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
