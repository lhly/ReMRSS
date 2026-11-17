package top.zainzh.rearscreen;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Waits for one manually confirmed real sample replacement; it never starts or authorizes the cast. */
@RunWith(AndroidJUnit4.class)
public class ControlledCastLateFailureFixtureTest {
    private static final String TAG = "RC5LateFailureTest";
    private static final long A_START_TIMEOUT_MS = 60_000L;
    private static final long B_START_TIMEOUT_MS = 98_000L;
    private static final long SECOND_TOUCH_TIMEOUT_MS = 30_000L;

    @Test public void lateFailureAfterCandidatePixelCopyPreservesConsumerAAndReleasesCandidateB() throws Exception {
        runFixture(true);
    }

    /** Automated acceptance stops at rollback evidence; the operator can then perform physical touch separately. */
    @Test public void lateFailureRollbackEvidenceWithoutPhysicalTouch() throws Exception {
        runFixture(false);
    }

    private void runFixture(boolean requirePhysicalTouch) throws Exception {
        assumeTrue("late-failure injection exists only in Debug", BuildConfig.DEBUG);
        Log.i(TAG, "STAGE=WAIT_A; open dev.remrss.sample.rc5consumer and use its real Start virtual cast flow; approve ReMRSS");
        long aDeadline = android.os.SystemClock.uptimeMillis() + A_START_TIMEOUT_MS;
        boolean aArmed = false;
        while (!aArmed && android.os.SystemClock.uptimeMillis() < aDeadline) {
            aArmed = VirtualRearHostActivity.armDebugLateFailureForKnownSampleReplacement();
            if (!aArmed) Thread.sleep(250L);
        }
        assertTrue("A did not become the exact live controlled sample session within 60 seconds",
                aArmed);
        DebugControlledCastLateFailure.FixtureSnapshot baseline = DebugControlledCastLateFailure.armedSnapshot();
        assertNotNull("exact A identity must be captured before waiting", baseline);
        Log.i(TAG, "STAGE=WAIT_B_READY A=dev.remrss.sample.rc5consumer B=dev.remrss.sample.rc5candidate "
                + "A-session=" + baseline.aSession + "; open B, press Start virtual cast, then approve the host confirmation");

        try {
            DebugControlledCastLateFailure.FixtureSnapshot result =
                    DebugControlledCastLateFailure.awaitCleanup(B_START_TIMEOUT_MS);
            assertNotNull("No exact approved B candidate reached the PixelCopy gate within 98 seconds", result);
            assertTrue("The injection must occur only after PixelCopy for the same operation/token", result.injected);
            assertTrue("B cleanup must finish before this selector returns", result.cleanupComplete);
            assertTrue("A session/reader/frame, B main task, or candidate virtual display did not verify: "
                    + result.cleanupReason, result.cleanupSucceeded);
            assertEquals("A session must be unchanged", baseline.aSession, result.aSession);
            assertEquals("A owner identity must be unchanged", baseline.aOwner, result.aOwner);
            assertEquals("A owner generation must be unchanged", baseline.aGeneration, result.aGeneration);
            assertEquals("A reader must be unchanged", baseline.aReader, result.aReader);
            assertEquals("Only the isolated B fixture component is accepted",
                    "dev.remrss.sample.rc5candidate/dev.remrss.sample.MainActivity", result.bComponent);
            assertTrue("B operation must be exact and non-empty", result.operationId.startsWith("cast-op-"));
            assertTrue("B candidate token must be captured from privileged prepare", !result.token.isEmpty());
            Log.i(TAG, "STAGE=PASS injected_after_pixelcopy=true A_same_session_reader_frame=true "
                    + "B_exact_task_on_main=true candidate_display_released=true op=" + result.operationId);
            if (!requirePhysicalTouch) {
                long observeMs = rollbackObserveWindowMs();
                Log.i(TAG, "STAGE=ROLLBACK_CAPTURE_READY observe_ms=" + observeMs
                        + "; backend rollback assertions passed; no reader input was sent");
                if (observeMs > 0L) Thread.sleep(observeMs);
                Log.i(TAG, "STAGE=AUTOMATED_ROLLBACK_VERIFIED_NO_TOUCH; backend rollback assertions passed; no reader input was sent");
                return;
            }
            Log.i(TAG, "STAGE=SECOND_TOUCH_PROMPT; physically tap A content once on the rear display; no test input is sent");
            assertTrue("No real touch advanced frames/events on the preserved A reader within 30 seconds",
                    VirtualRearHostActivity.awaitDebugLateFailurePhysicalTouch(result, SECOND_TOUCH_TIMEOUT_MS));
            Log.i(TAG, "STAGE=PASS second_physical_touch_same_A_reader=true");
        } finally { DebugControlledCastLateFailure.clearIfNotInjected(); }
    }

    private static long rollbackObserveWindowMs() {
        String value = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("rc5ObserveRollbackMs", "0");
        try {
            return Math.max(0L, Math.min(12_000L, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
