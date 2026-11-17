package top.zainzh.rearscreen.platform

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledCastWakeTransitionTest {
    private val launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher"
    private val verifier = ControlledCastWakeTransition.OemLauncherVerifier { component, user ->
        component == launcher && user == 0
    }

    @Test fun preWakeEmptySnapshotRetainsOnlyTheUniqueExactOemTaskId() {
        assertEquals(41, ControlledCastWakeTransition.preWakeTaskId(off, listOf(row(41)), verifier))
        assertNull(ControlledCastWakeTransition.preWakeTaskId(off, emptyList(), verifier))
        assertNull(ControlledCastWakeTransition.preWakeTaskId(off, listOf(row(41), row(42)), verifier))
        assertNull(ControlledCastWakeTransition.preWakeTaskId(off, listOf(row(task = 0)), verifier))
        assertNull(ControlledCastWakeTransition.preWakeTaskId(off, listOf(row(41, user = -1)), verifier))
        assertNull(ControlledCastWakeTransition.preWakeTaskId(off, listOf(row(41, user = 10)), verifier))
    }

    @Test fun productionFallbackOwnerShapeIsAcceptedButLegacyOwnerIsNot() {
        // inspectOccupant's generic blocked-row fallback is "unmanaged:<pkg>" (no trailing colon).
        val genericFallback = ControlledCastWakeTransition.blockedTaskRow(
            "com.xiaomi.subscreencenter", 41, launcher, 5, "5:rear")
        assertEquals("unmanaged:com.xiaomi.subscreencenter", genericFallback.owner)
        assertEquals(41, ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, genericFallback,
            listOf(row(41, visible = false, requested = false, sleeping = true)), verifier))

        val actualLegacyOwner = ControlledCastWakeTransition.physicalProjection(
            "unmanaged", "physical:41:9", 9L, -1L, 41, launcher, 5, "5:rear")
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, actualLegacyOwner,
            listOf(row(41, visible = false, requested = false, sleeping = true)), verifier))
    }

    @Test fun changedTaskOrUncorroboratedRowNeverSettles() {
        val genericFallback = CastOccupantIdentity(CastMode.DIRECT_PHYSICAL,
            "unmanaged:com.xiaomi.subscreencenter", "task:41", 0L, -1L, 41, 0,
            "", launcher, 5, "5:rear", CastOccupantSource.BLOCKED_TASK_ROW)
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, genericFallback,
            listOf(row(42, visible = false, requested = false, sleeping = true)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(null, 0, launcher, on, genericFallback,
            listOf(row(41, visible = false, requested = false, sleeping = true)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, genericFallback,
            listOf(row(41, visible = true, requested = false, sleeping = true)), verifier))
    }

    @Test fun exactOemWakeMayAdvanceOnlyFromAsleepToRequestedShowForTheSameTask() {
        val exact = ControlledCastWakeTransition.blockedTaskRow(
            "com.xiaomi.subscreencenter", 41, launcher, 5, "5:rear")
        assertEquals(41, ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, exact,
            listOf(row(41, visible = false, requested = false, sleeping = true)), verifier))
        assertEquals(41, ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, exact,
            listOf(row(41, visible = false, requested = true, sleeping = false)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, exact,
            listOf(row(41, visible = true, requested = true, sleeping = false)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, exact,
            listOf(row(42, visible = false, requested = true, sleeping = false)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, exact,
            listOf(row(41, visible = false, requested = true, sleeping = false), row(42)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, exact,
            listOf(row(41, visible = null, requested = true, sleeping = false)), verifier))
        assertNull(ControlledCastWakeTransition.postWakeTaskId(41, 1, launcher, on, exact,
            listOf(row(41, visible = false, requested = true, sleeping = false)), verifier))
    }

    @Test fun onlyTheSameVerifiedOemFallbackMaySettleToEmptyOwner() {
        val fallback = ControlledCastWakeTransition.blockedTaskRow(
            "com.xiaomi.subscreencenter", 41, launcher, 5, "5:rear")
        val stableLauncher = listOf(row(41, visible = true, requested = true, sleeping = false))
        assertTrue(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, null, 41, 0, launcher, on, stableLauncher, verifier))
        assertFalse(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, ControlledCastWakeTransition.physicalProjection("owner", "physical:41:7", 7L,
                2L, 41, launcher, 5, "5:rear"), 41, 0, launcher, on, stableLauncher, verifier))
        assertFalse(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, null, 41, null, launcher, on, stableLauncher, verifier))
        assertFalse(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, null, 41, 1, launcher, on, stableLauncher, verifier))
        assertFalse(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, null, 41, 0, launcher, on,
            stableLauncher + row(42), verifier))
        assertFalse(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, null, 41, 0, launcher, off, stableLauncher, verifier))
    }

    @Test fun requestedOemRowDisappearingBetweenReadsIsPendingNotSettled() {
        val fallback = ControlledCastWakeTransition.blockedTaskRow(
            "com.xiaomi.subscreencenter", 41, launcher, 5, "5:rear")
        val showing = listOf(row(41, visible = false, requested = true, sleeping = false))
        val transition = ControlledCastWakeTransition.postWakeTaskId(41, 0, launcher, on, fallback, showing, verifier)
        assertEquals(41, transition)
        assertTrue(ControlledCastWakeTransition.canKeepPollingExactOem(transition, fallback, null))
        assertFalse(ControlledCastWakeTransition.exactFallbackSettledToEmpty(
            fallback, null, 41, 0, launcher, on, showing, verifier))
        assertFalse(ControlledCastWakeTransition.canKeepPollingExactOem(null, fallback, null))
        assertFalse(ControlledCastWakeTransition.canKeepPollingExactOem(transition, fallback,
            ControlledCastWakeTransition.blockedTaskRow("foreign.app", 42, "foreign.app/.Main", 5, "5:rear")))
        assertFalse(ControlledCastWakeTransition.canKeepPollingExactOem(transition, fallback,
            ControlledCastWakeTransition.physicalProjection("owner", "physical:41:7", 7L,
                2L, 41, launcher, 5, "5:rear")))
    }

    private fun row(task: Int, visible: Boolean? = true, requested: Boolean? = false,
                    sleeping: Boolean? = true, user: Int = 0) = CastAdmissionRowEvidence(
        "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", launcher, launcher,
        visible, requested, sleeping, task, user)

    private companion object {
        val off = CastDisplayPowerEvidence(Display.STATE_OFF, Display.STATE_OFF, Display.STATE_OFF)
        val on = CastDisplayPowerEvidence(Display.STATE_ON, Display.STATE_ON, Display.STATE_ON)
    }
}
