package top.zainzh.rearscreen.platform

import android.view.Display
import org.junit.Assert.*
import org.junit.Test

class ControlledCastStandbyPolicyTest {
    private class FakeAccess(var power: CastDisplayPowerEvidence? = stable(Display.STATE_OFF)) :
        ControlledCastStandbyPolicy.Access {
        var rear = true
        var target = true
        var occupant: CastOccupantIdentity? = null
        var authorized = true
        var claimCount = 0
        var reservationCount = 0
        var wakeCount = 0
        var now = 0L
        var onWake: (() -> Unit)? = null
        var onClaim: (() -> Unit)? = null
        var wakeResult = ControlledCastWakeResult.CONFIRMED
        var onInspect: (() -> Unit)? = null
        var onSleep: (() -> Unit)? = null
        var evidenceSequence: ArrayDeque<ControlledCastStartEvidence>? = null
        override fun isAuthorized() = authorized
        override fun claimSideEffect(): Boolean { claimCount++; onClaim?.invoke(); return authorized }
        override fun beginActionWindow(): Boolean { reservationCount++; return authorized }
        override fun endActionWindow() { reservationCount-- }
        override fun rearIsCurrent() = rear
        override fun targetIsCurrent() = target
        override fun inspectStartEvidence(): ControlledCastStartEvidence {
            onInspect?.invoke()
            val queued = evidenceSequence?.removeFirstOrNull()
            return queued ?: ControlledCastStartEvidence(occupant, power)
        }
        override fun wakeRearOnce(): ControlledCastWakeResult { wakeCount++; onWake?.invoke(); return wakeResult }
        override fun elapsedRealtime() = now
        override fun sleep(ms: Long) { now += ms; onSleep?.invoke() }
    }

    @Test fun offIdleWakesExactlyOnceOnlyAfterApprovalAndRechecks() {
        val access = FakeAccess()
        access.onWake = { access.power = stable(Display.STATE_ON) }
        assertEquals(ApprovedCastStartGate.CANCELLED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(false, null, access))
        assertEquals(0, access.wakeCount)
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(1, access.wakeCount)
        assertEquals(2, access.claimCount)
        assertEquals(0, access.reservationCount)
    }

    @Test fun onIdleDoesNotWake() {
        val access = FakeAccess(stable(Display.STATE_ON))
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(0, access.wakeCount)
    }

    @Test fun transitionAndUnknownPowerAreNeverTreatedAsIdle() {
        for (power in listOf(
            CastDisplayPowerEvidence(Display.STATE_ON, Display.STATE_ON, Display.STATE_OFF),
            CastDisplayPowerEvidence(Display.STATE_OFF, Display.STATE_OFF, Display.STATE_ON),
            null
        )) {
            val access = FakeAccess(power)
            assertEquals(ApprovedCastStartGate.STANDBY_UNKNOWN,
                ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
            assertEquals(0, access.wakeCount)
        }
    }

    @Test fun newlyAppearingOccupantOrTargetChangeAfterWakeRejectsStart() {
        val access = FakeAccess()
        access.onWake = {
            access.power = stable(Display.STATE_ON)
            access.occupant = occupant()
        }
        assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(1, access.wakeCount)

        val changedTarget = FakeAccess()
        changedTarget.onWake = { changedTarget.power = stable(Display.STATE_ON); changedTarget.target = false }
        assertEquals(ApprovedCastStartGate.TARGET_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, changedTarget))
        assertEquals(1, changedTarget.wakeCount)
    }

    @Test fun replacementNeverWakesAndMustRemainExact() {
        val exact = occupant()
        val access = FakeAccess(stable(Display.STATE_OFF)).apply { occupant = exact }
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, exact, access))
        assertEquals(0, access.wakeCount)
        access.occupant = null
        assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, exact, access))
        assertEquals(0, access.wakeCount)
    }

    @Test fun standbyRowsOnlyIgnoreExactOemDesktopOrKnownOnHiddenRows() {
        val off = stable(Display.STATE_OFF)
        val on = stable(Display.STATE_ON)
        val launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher"
        assertTrue(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, false, false, false))
        // HyperOS keeps its exact secondary-home row logically visible while the
        // physical display sleeps; visibleRequested=false plus known sleeping=true
        // is the observed, non-occupying combination.
        assertTrue(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, true, false, true))
        assertTrue(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, false, false, true))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, true, false, false))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, true, null, true))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, null, false, true))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, false, null, false))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "third.party", "third.party", "third.party/.MainActivity", "third.party/.MainActivity", false, false, false))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            "com.xiaomi.subscreencenter/.SettingsActivity", "com.xiaomi.subscreencenter/.SettingsActivity", false, false, false))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "other.top",
            launcher, launcher, false, false, false))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(off,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, "com.xiaomi.subscreencenter/.SettingsActivity", false, false, false))
        assertTrue(ControlledCastStandbyPolicy.isIgnorableTask(on,
            "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
            launcher, launcher, true, true, false))
        assertTrue(ControlledCastStandbyPolicy.isIgnorableTask(on,
            "third.party", "third.party", "third.party/.MainActivity", "third.party/.MainActivity", false, false, false))
        assertFalse(ControlledCastStandbyPolicy.isIgnorableTask(
            CastDisplayPowerEvidence(Display.STATE_ON, Display.STATE_ON, Display.STATE_OFF),
            "third.party", "third.party", "third.party/.MainActivity", "third.party/.MainActivity", false, false, false))
    }

    @Test fun cancellationDuringInspectionPreventsWake() {
        val access = FakeAccess().apply { onInspect = { authorized = false } }
        assertEquals(ApprovedCastStartGate.CANCELLED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(0, access.wakeCount)
        assertEquals(0, access.claimCount)
    }

    @Test fun cancellationWhileWaitingPreventsReadyAndLaterStartClaim() {
        val access = FakeAccess()
        access.onWake = { access.power = stable(Display.STATE_OFF) }
        access.onSleep = { access.authorized = false }
        assertEquals(ApprovedCastStartGate.CANCELLED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access, wakeWaitMs = 100L))
        assertEquals(1, access.wakeCount)
        assertEquals(1, access.claimCount) // wake was linearized; no post-wait/start claim is issued.
    }

    @Test fun successfulWakeWaitsForExactOemRowToSettleThenRequiresFreshEmptyOnRead() {
        val access = FakeAccess()
        access.onWake = { access.power = stable(Display.STATE_ON) }
        access.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            transition(41),
            transition(41),
            ControlledCastStartEvidence(null, stable(Display.STATE_ON))
        ))
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access, wakeWaitMs = 100L))
        assertEquals(1, access.wakeCount)
        assertEquals(50L, access.now)
    }

    @Test fun actualOemWakeEvolutionHiddenToRequestedMustSettleToFreshEmpty() {
        val access = FakeAccess().apply { onWake = { power = stable(Display.STATE_ON) } }
        access.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            transition(41), // sole exact OEM row: false / false / true
            transition(41), // same task begins show: false / true / false
            ControlledCastStartEvidence(null, stable(Display.STATE_ON)),
            ControlledCastStartEvidence(null, stable(Display.STATE_ON))
        ))
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access, wakeWaitMs = 100L))
        assertEquals(1, access.wakeCount)
        assertEquals(2, access.claimCount)
        assertEquals(50L, access.now)
    }

    @Test fun requestedOemTransitionStillRejectsOtherRowsAndMustConvergeBeforeDeadline() {
        val foreign = FakeAccess().apply { onWake = { power = stable(Display.STATE_ON) } }
        foreign.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            transition(41),
            ControlledCastStartEvidence(occupant(), stable(Display.STATE_ON))
        ))
        assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, foreign, wakeWaitMs = 100L))
        assertEquals("only the single wake reservation was claimed; no cast allocation claim follows",
            1, foreign.claimCount)
        assertEquals(1, foreign.wakeCount)

        val stuck = FakeAccess().apply { onWake = { power = stable(Display.STATE_ON) } }
        stuck.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            transition(41), transition(41), transition(41)
        ))
        assertEquals(ApprovedCastStartGate.WAKE_FAILED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, stuck, wakeWaitMs = 100L))
        assertEquals("the wake claim is not repeated and no cast allocation claim follows",
            1, stuck.claimCount)
        assertEquals(1, stuck.wakeCount)
    }

    @Test fun wakeTransitionExpiresAfterOneWakeAndNeverTreatsForeignOrChangingTaskAsSettle() {
        val expires = FakeAccess()
        expires.onWake = { expires.power = stable(Display.STATE_ON) }
        expires.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            transition(41), transition(41)
        ))
        assertEquals(ApprovedCastStartGate.WAKE_FAILED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, expires, wakeWaitMs = 50L))
        assertEquals(1, expires.wakeCount)

        for (replacement in listOf(transition(42), ControlledCastStartEvidence(occupant(), stable(Display.STATE_ON)))) {
            val changed = FakeAccess().apply {
                onWake = { power = stable(Display.STATE_ON) }
                evidenceSequence = ArrayDeque(listOf(
                    ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
                    ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
                    transition(41), replacement
                ))
            }
            assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
                ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, changed, wakeWaitMs = 100L))
            assertEquals(1, changed.wakeCount)
        }
    }

    @Test fun alreadyOnAtWakeBoundaryDoesNotOpenPostWakeSettleException() {
        val access = FakeAccess().apply {
            evidenceSequence = ArrayDeque(listOf(
                ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                ControlledCastStartEvidence(occupant(), stable(Display.STATE_ON))
            ))
        }
        assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(0, access.wakeCount)
    }

    @Test fun missingPreWakeTaskIdentityNeverAcceptsASettlingRow() {
        val access = FakeAccess().apply {
            onWake = { power = stable(Display.STATE_ON) }
            evidenceSequence = ArrayDeque(listOf(
                ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                transition(41)
            ))
        }
        assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(1, access.wakeCount)
    }

    @Test fun confirmedWakeDeadlineIncludesBlockedInitialAndFinalReads() {
        for (blockedRead in listOf(3, 4)) {
            val access = FakeAccess().apply {
                onWake = { power = stable(Display.STATE_ON); now = 10 }
                var reads = 0
                onInspect = { if (++reads == blockedRead) now += 101 }
                evidenceSequence = ArrayDeque(listOf(
                    ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                    ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                    ControlledCastStartEvidence(null, stable(Display.STATE_ON)),
                    ControlledCastStartEvidence(null, stable(Display.STATE_ON))
                ))
            }
            assertEquals(ApprovedCastStartGate.WAKE_FAILED,
                ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access, wakeWaitMs = 100L))
            assertEquals(1, access.wakeCount)
        }
    }

    @Test fun alreadyOnResultDoesNotStartASettleWait() {
        val access = FakeAccess().apply {
            wakeResult = ControlledCastWakeResult.ALREADY_ON
            power = stable(Display.STATE_ON)
            evidenceSequence = ArrayDeque(listOf(
                ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
                ControlledCastStartEvidence(null, stable(Display.STATE_ON))
            ))
        }
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(0L, access.now)
        assertEquals(1, access.wakeCount)
    }

    @Test fun offWithOemIdThenFreshAlreadyOnAndEmptyDoesNotWakeOrSettle() {
        val access = FakeAccess()
        access.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF), preWakeOemTaskId = 41),
            ControlledCastStartEvidence(null, stable(Display.STATE_ON)),
            ControlledCastStartEvidence(null, stable(Display.STATE_ON)),
            ControlledCastStartEvidence(null, stable(Display.STATE_ON))
        ))
        assertEquals(ApprovedCastStartGate.READY,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(0, access.wakeCount)
        assertEquals(0L, access.now)
    }

    @Test fun claimCrossingWakeDeadlineCannotReturnReady() {
        val access = FakeAccess().apply {
            onWake = { power = stable(Display.STATE_ON) }
            onClaim = { if (claimCount == 2) now += 100L }
        }
        assertEquals(ApprovedCastStartGate.WAKE_FAILED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access, wakeWaitMs = 100L))
        assertEquals(1, access.wakeCount)
    }

    @Test fun foreignOccupantInFreshPreWakeSnapshotBlocksWake() {
        val access = FakeAccess()
        access.evidenceSequence = ArrayDeque(listOf(
            ControlledCastStartEvidence(null, stable(Display.STATE_OFF)),
            ControlledCastStartEvidence(occupant(), stable(Display.STATE_OFF))
        ))
        assertEquals(ApprovedCastStartGate.OCCUPANCY_CHANGED,
            ControlledCastStandbyPolicy.evaluateAfterApproval(true, null, access))
        assertEquals(0, access.wakeCount)
        assertEquals(0, access.claimCount)
    }

    private fun occupant() = CastOccupantIdentity(CastMode.VIRTUAL, "owner", "session", 3L, 7L,
        11, 12, "input", "pkg/.A", 5, "5:rear")

    private fun transition(taskId: Int) = ControlledCastStartEvidence(null, stable(Display.STATE_ON),
        oemWakeTransition = true, oemWakeTransitionTaskId = taskId)

    private companion object {
        fun stable(state: Int) = CastDisplayPowerEvidence(state, state, state)
    }
}
