package top.zainzh.rearscreen.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

import top.zainzh.rearscreen.ControlledCastTaskIdentity

class ControlledCastBackendEdgePolicyTest {
    @Test fun canonicalTaskIdentityAcceptsShortAndFullButRejectsWrongTargetBeforeMove() {
        assertTrue(ControlledCastTaskIdentity.matchesExact(
            41, "example.target/.Main", 41, "example.target/example.target.Main", true))
        assertFalse(ControlledCastTaskIdentity.matchesExact(
            41, "example.target/.Main", 41, "example.target/example.target.Other", true))
        assertFalse(ControlledCastTaskIdentity.matchesExact(
            41, "example.target/.Main", 42, "example.target/example.target.Main", true))
        assertFalse(ControlledCastTaskIdentity.matchesExact(
            41, "example.target/.Main", 41, "example.target/example.target.Main", false))
        assertEquals("example.target/example.target.VirtualRearHostActivity",
            ControlledCastBackendPolicy.canonicalComponent("example.target/.VirtualRearHostActivity"))
    }

    @Test fun wrongBIdentityIsRejectedBeforeThePrivilegedMoveIsInvoked() {
        var moved = false
        val result = ControlledCastTaskIdentity.moveIfExact(41, "example.target/.Main", 41,
            "example.target/.Other", true) { moved = true; true }
        assertFalse(result)
        assertFalse("identity recheck runs before moveTaskToDisplay", moved)
    }

    @Test fun staleStoppedSnapshotIsPendingUntilThisOperationHasTerminalIdentity() {
        val historical = JSONObject().put("state", "stopped").put("coordinatorOperationId", "old")
            .put("ownerGeneration", 2).put("ownerIdentity", "owner")
        assertFalse(ControlledCastBackendPolicy.isTerminalForThisOperation("new", 3, "owner", historical))
        val exactFailure = JSONObject().put("state", "stopped").put("coordinatorOperationId", "new")
            .put("ownerGeneration", 3).put("ownerIdentity", "owner")
        assertTrue(ControlledCastBackendPolicy.isTerminalForThisOperation("new", 3, "owner", exactFailure))
        assertFalse(ControlledCastBackendPolicy.isTerminalForThisOperation("new", 4, "owner", exactFailure))
    }

    @Test fun exactOperationCleanupDoesNotNeedRegistryEntryAndUsesOwnerGeneration() {
        val committedB = JSONObject().put("id", "backend-B").put("coordinatorOperationId", "operation-B")
            .put("ownerGeneration", 18).put("ownerIdentity", "principal-B").put("state", "starting")
        // The backend invokes this predicate with the captured Operation, even after removing its
        // pending-operation table entry and before the coordinator publishes a session handle.
        assertTrue(ControlledCastBackendPolicy.operationMatches(
            "operation-B", 18, "principal-B", "backend-B", committedB))
        assertFalse(ControlledCastBackendPolicy.operationMatches(
            "operation-A", 17, "principal-A", "backend-B", committedB))
        assertFalse(ControlledCastBackendPolicy.operationMatches(
            "operation-B", 19, "principal-B", "backend-B", committedB))
    }

    @Test fun appliedDensityComesFromRenderedSessionNotViewportBufferMetadata() {
        val status = JSONObject().put("density", 240).put("viewport", JSONObject()
            .put("density", 440).put("bufferWidth", 1080).put("bufferHeight", 1920))
        assertEquals(240, ControlledCastBackendPolicy.appliedRenderDensity(status))
        assertNotEquals(status.getJSONObject("viewport").getInt("density"),
            ControlledCastBackendPolicy.appliedRenderDensity(status))
    }

    @Test fun pauseBindAndCommitReuseOneActualGateObjectForTheOperation() {
        val slot = CandidateActionGateSlot<Any>()
        val gateFromBegin = Any()
        assertTrue(slot.bind("candidate-1", gateFromBegin))
        assertFalse("a second Binder must not replace the gate used by begin", slot.bind("candidate-1", Any()))
        val gateForBind = slot.get("candidate-1")
        val gateForCommit = slot.get("candidate-1")
        assertSame(gateFromBegin, gateForBind)
        assertSame(gateForBind, gateForCommit)
        assertNull(slot.get("candidate-2"))
        slot.clear()
        assertNull(slot.get("candidate-1"))
    }
}
