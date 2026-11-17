package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RearSystemBridgeDispatchGateTest {
    @Test public void revokedAuthorityRejectsBeforeTheMoveInvokerCanRun() {
        AtomicInteger invokes = new AtomicInteger();
        RearSystemBridge.SoleRootMoveDispatch result = RearSystemBridge.dispatchRootMoveAfterGate(
                () -> false, invokes::incrementAndGet);
        assertEquals(RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND, result);
        assertEquals(0, invokes.get());
    }

    @Test public void gateExceptionRejectsBeforeTheMoveInvokerCanRun() {
        AtomicInteger invokes = new AtomicInteger();
        RearSystemBridge.SoleRootMoveDispatch result = RearSystemBridge.dispatchRootMoveAfterGate(
                () -> { throw new IllegalStateException("stale_action_gate"); }, invokes::incrementAndGet);
        assertEquals(RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND, result);
        assertEquals(0, invokes.get());
    }

    @Test public void invokeReplyExceptionIsAmbiguousBecauseTheMoveMayHaveBeenApplied() {
        AtomicInteger invokes = new AtomicInteger();
        RearSystemBridge.SoleRootMoveDispatch result = RearSystemBridge.dispatchRootMoveAfterGate(
                () -> true, () -> {
                    invokes.incrementAndGet();
                    throw new InvocationTargetException(new IllegalStateException("reply_lost"));
                });
        assertEquals(RearSystemBridge.SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT, result);
        assertEquals(1, invokes.get());
    }

    @Test public void successfulInvokeIsReportedSent() {
        AtomicInteger invokes = new AtomicInteger();
        RearSystemBridge.SoleRootMoveDispatch result = RearSystemBridge.dispatchRootMoveAfterGate(
                () -> true, invokes::incrementAndGet);
        assertEquals(RearSystemBridge.SoleRootMoveDispatch.SENT, result);
        assertEquals(1, invokes.get());
    }

    @Test public void diagnosedGateRejectionIsPreSendAndDoesNotInvoke() {
        AtomicInteger invokes = new AtomicInteger();
        RearSystemBridge.SoleRootMoveDiagnostic result = RearSystemBridge.dispatchRootMoveAfterGateDiagnosed(
                () -> false, invokes::incrementAndGet);
        assertEquals(RearSystemBridge.SoleRootMoveDispatch.REJECTED_BEFORE_SEND, result.dispatch);
        assertEquals("final_gate_rejected", result.reason);
        assertEquals(0, invokes.get());
    }

    @Test public void diagnosedInvocationFailureIsMarkedAmbiguousWithoutRetry() {
        AtomicInteger invokes = new AtomicInteger();
        RearSystemBridge.SoleRootMoveDiagnostic result = RearSystemBridge.dispatchRootMoveAfterGateDiagnosed(
                () -> true, () -> {
                    invokes.incrementAndGet();
                    throw new InvocationTargetException(new IllegalStateException("private detail"));
                });
        assertEquals(RearSystemBridge.SoleRootMoveDispatch.MAY_HAVE_BEEN_SENT, result.dispatch);
        assertEquals("invoke_reply_unknown", result.reason);
        assertEquals(1, invokes.get());
    }

    @Test public void bottomReasonProjectionRejectsMissingAndUntrustedText() throws Exception {
        assertEquals("a_topology_mismatch",
                DebugPhysicalBFocusTransferProbe.safeBottomDispatchReason("a_topology_mismatch"));
        assertEquals("invoke_reply_unknown",
                DebugPhysicalBFocusTransferProbe.safeBottomDispatchReason("invoke_reply_unknown"));
        assertEquals("unknown", DebugPhysicalBFocusTransferProbe.safeBottomDispatchReason(null));
        assertEquals("unknown", DebugPhysicalBFocusTransferProbe.safeBottomDispatchReason("private detail"));
        assertEquals("unknown", DebugPhysicalBFocusTransferProbe.safeBottomDispatchReason(
                "package=com.foreign uid=12345 stacktrace"));
        assertEquals("process_pair_mismatch", DebugPhysicalBFocusTransferProbe.bottomReasonFromResult(
                new org.json.JSONObject().put("status", "rejected").put("reason", "process_pair_mismatch")));
        assertEquals("unknown", DebugPhysicalBFocusTransferProbe.bottomReasonFromResult(
                new org.json.JSONObject().put("status", "rejected").put("reason", "private detail")));
        assertEquals("unknown", DebugPhysicalBFocusTransferProbe.bottomReasonFromResult(new org.json.JSONObject()));
    }
}
