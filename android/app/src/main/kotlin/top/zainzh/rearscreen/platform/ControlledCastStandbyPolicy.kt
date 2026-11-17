package top.zainzh.rearscreen.platform

import android.view.Display

/** Power evidence from one typed occupancy snapshot; transitions and unknown states are never idle. */
internal data class CastDisplayPowerEvidence(val state: Int, val before: Int, val after: Int) {
    fun isStableKnown(): Boolean = state == before && state == after && state in KNOWN_STATES
    fun needsWake(): Boolean = isStableKnown() && state != Display.STATE_ON

    companion object {
        private val KNOWN_STATES = setOf(Display.STATE_ON, Display.STATE_OFF,
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)
    }
}

internal enum class ApprovedCastStartGate {
    READY, CANCELLED, ACTION_BUSY, REAR_CHANGED, TARGET_CHANGED, OCCUPANCY_CHANGED,
    OCCUPANCY_UNKNOWN, STANDBY_UNKNOWN, WAKE_FAILED
}
internal enum class ControlledCastWakeResult { CONFIRMED, ALREADY_ON, FAILED }
internal data class ControlledCastStartEvidence(
    val occupant: CastOccupantIdentity?,
    val power: CastDisplayPowerEvidence?,
    /** Exact single OEM desktop row is still reporting asleep after a successful wake. */
    val oemWakeTransition: Boolean = false,
    val oemWakeTransitionTaskId: Int? = null,
    /** Unique exact OEM row observed in the pre-wake typed empty snapshot. */
    val preWakeOemTaskId: Int? = null,
    val preWakeOemUserId: Int? = null,
    val preWakeOemComponent: String? = null
)

/** The exact admission policy used after confirmation; all effects are behind the explicit approval bit. */
internal object ControlledCastStandbyPolicy {
    private const val WAKE_CONFIRM_MS = 1_500L
    private const val POLL_MS = 50L

    interface Access {
        fun isAuthorized(): Boolean
        fun claimSideEffect(): Boolean
        fun beginActionWindow(): Boolean
        fun endActionWindow()
        fun rearIsCurrent(): Boolean
        fun targetIsCurrent(): Boolean
        @Throws(Exception::class) fun inspectStartEvidence(): ControlledCastStartEvidence
        /** Fresh typed occupancy read reserved for the bounded interval after wake returned true. */
        @Throws(Exception::class) fun inspectPostWakeEvidence(expectedOemTaskId: Int?, expectedOemUserId: Int?,
            expectedOemComponent: String?): ControlledCastStartEvidence = inspectStartEvidence()
        fun wakeRearOnce(): ControlledCastWakeResult
        fun elapsedRealtime(): Long
        @Throws(InterruptedException::class) fun sleep(ms: Long)
    }

    fun isExactOemDesktop(basePackage: String?, topPackage: String?, baseComponent: String?, topComponent: String?): Boolean =
        basePackage == OEM_PACKAGE && topPackage == OEM_PACKAGE
            && StandbyComponentName.canonicalComponent(baseComponent.orEmpty()) == OEM_LAUNCHER
            && StandbyComponentName.canonicalComponent(topComponent.orEmpty()) == OEM_LAUNCHER

    /** Hidden third-party rows are only ignorable while the panel is verifiably ON. */
    fun isIgnorableTask(power: CastDisplayPowerEvidence, basePackage: String?, topPackage: String?,
                        baseComponent: String?, topComponent: String?, visible: Boolean?, requested: Boolean?, sleeping: Boolean?): Boolean {
        if (!power.isStableKnown()) return false
        if (isExactOemDesktop(basePackage, topPackage, baseComponent, topComponent)) {
            val hidden = visible == false && requested == false && sleeping == false
            val visibleLauncher = power.state == Display.STATE_ON && visible == true && requested == true && sleeping == false
            // HyperOS task visibility is not the physical panel state: the exact secondary-home
            // row can be logically visible or hidden while the panel sleeps. The typed evidence
            // must still identify the sole exact launcher, not pending a show, and sleeping.
            val sleepingLauncher = power.state in ASLEEP_STATES
                && visible != null && requested == false && sleeping == true
            return hidden || visibleLauncher || sleepingLauncher
        }
        return power.state == Display.STATE_ON && visible == false && requested == false && sleeping == false
    }

    /** Called only after the host confirmation was positively approved. It never retries a wake command. */
    fun evaluateAfterApproval(approved: Boolean, expectedOccupant: CastOccupantIdentity?, access: Access,
                              wakeWaitMs: Long = WAKE_CONFIRM_MS): ApprovedCastStartGate {
        if (!approved || !access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        if (!access.rearIsCurrent()) return ApprovedCastStartGate.REAR_CHANGED
        if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        if (!access.targetIsCurrent()) return ApprovedCastStartGate.TARGET_CHANGED
        val first = try { access.inspectStartEvidence() }
        catch (_: Exception) { return ApprovedCastStartGate.OCCUPANCY_UNKNOWN }
        if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        if (first.occupant != expectedOccupant) return ApprovedCastStartGate.OCCUPANCY_CHANGED

        // A confirmed replacement preserves the active owner; it does not wake or alter its power state.
        if (expectedOccupant != null) return finalOwnerCheck(expectedOccupant, access)

        val power = first.power ?: return ApprovedCastStartGate.STANDBY_UNKNOWN
        if (!power.isStableKnown()) return ApprovedCastStartGate.STANDBY_UNKNOWN
        var wakeAttempted = false
        var wakeDeadline: Long? = null
        if (power.needsWake()) {
            wakeAttempted = true
            val wake = approvedWake(expectedOccupant, first.preWakeOemTaskId, first.preWakeOemUserId,
                first.preWakeOemComponent, access, wakeWaitMs)
            wake.gate?.let { return it }
            wakeDeadline = wake.deadline
        }
        if (!access.rearIsCurrent()) return ApprovedCastStartGate.REAR_CHANGED
        if (!access.targetIsCurrent()) return ApprovedCastStartGate.TARGET_CHANGED
        if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        val final = try { access.inspectStartEvidence() }
        catch (_: Exception) { return ApprovedCastStartGate.OCCUPANCY_UNKNOWN }
        if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        if (!access.rearIsCurrent()) return ApprovedCastStartGate.REAR_CHANGED
        if (!access.targetIsCurrent()) return ApprovedCastStartGate.TARGET_CHANGED
        if (wakeDeadline != null && access.elapsedRealtime() >= wakeDeadline) return ApprovedCastStartGate.WAKE_FAILED
        if (final.occupant != null) return ApprovedCastStartGate.OCCUPANCY_CHANGED
        val finalPower = final.power ?: return ApprovedCastStartGate.STANDBY_UNKNOWN
        if (!finalPower.isStableKnown()) return ApprovedCastStartGate.STANDBY_UNKNOWN
        if (finalPower.state != Display.STATE_ON) {
            if (!finalPower.needsWake() || wakeAttempted) return ApprovedCastStartGate.WAKE_FAILED
            wakeAttempted = true
            val wake = approvedWake(null, final.preWakeOemTaskId, final.preWakeOemUserId,
                final.preWakeOemComponent, access, wakeWaitMs)
            wake.gate?.let { return it }
            wakeDeadline = wake.deadline
            if (!access.rearIsCurrent()) return ApprovedCastStartGate.REAR_CHANGED
            if (!access.targetIsCurrent()) return ApprovedCastStartGate.TARGET_CHANGED
            if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
            val postWake = try { access.inspectStartEvidence() }
            catch (_: Exception) { return ApprovedCastStartGate.OCCUPANCY_UNKNOWN }
            if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
            if (!access.rearIsCurrent()) return ApprovedCastStartGate.REAR_CHANGED
            if (!access.targetIsCurrent()) return ApprovedCastStartGate.TARGET_CHANGED
            if (wakeDeadline != null && access.elapsedRealtime() >= wakeDeadline) return ApprovedCastStartGate.WAKE_FAILED
            if (postWake.occupant != null) return ApprovedCastStartGate.OCCUPANCY_CHANGED
            val postWakePower = postWake.power ?: return ApprovedCastStartGate.STANDBY_UNKNOWN
            if (!postWakePower.isStableKnown()) return ApprovedCastStartGate.STANDBY_UNKNOWN
            if (postWakePower.state != Display.STATE_ON) return ApprovedCastStartGate.WAKE_FAILED
        }
        if (wakeDeadline != null && access.elapsedRealtime() >= wakeDeadline) return ApprovedCastStartGate.WAKE_FAILED
        if (!access.claimSideEffect()) return ApprovedCastStartGate.CANCELLED
        if (wakeDeadline != null && access.elapsedRealtime() >= wakeDeadline) return ApprovedCastStartGate.WAKE_FAILED
        return ApprovedCastStartGate.READY
    }

    private data class WakeAttempt(val gate: ApprovedCastStartGate?, val deadline: Long?)

    private fun approvedWake(expectedOccupant: CastOccupantIdentity?, expectedOemTaskId: Int?,
                             expectedOemUserId: Int?, expectedOemComponent: String?, access: Access,
                             timeoutMs: Long): WakeAttempt {
        if (!access.isAuthorized()) return WakeAttempt(ApprovedCastStartGate.CANCELLED, null)
        if (!access.beginActionWindow()) return WakeAttempt(ApprovedCastStartGate.ACTION_BUSY, null)
        var issuedDeadline: Long? = null
        val wakeResult = try {
            // Snapshot and power are paired, and the action lease is live at the privileged gate.
            val beforeWake = try { access.inspectStartEvidence() }
            catch (_: Exception) { return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_UNKNOWN, null) }
            if (!access.isAuthorized()) return WakeAttempt(ApprovedCastStartGate.CANCELLED, null)
            if (beforeWake.occupant != expectedOccupant) return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_CHANGED, null)
            val power = beforeWake.power ?: return WakeAttempt(ApprovedCastStartGate.STANDBY_UNKNOWN, null)
            if (!power.isStableKnown()) return WakeAttempt(ApprovedCastStartGate.STANDBY_UNKNOWN, null)
            if (power.state == Display.STATE_ON) ApprovedCastStartGate.READY
            else if (beforeWake.preWakeOemTaskId != expectedOemTaskId
                || beforeWake.preWakeOemUserId != expectedOemUserId
                || beforeWake.preWakeOemComponent != expectedOemComponent)
                return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_CHANGED, null)
            else if (!power.needsWake()) ApprovedCastStartGate.STANDBY_UNKNOWN
            else if (!access.claimSideEffect()) ApprovedCastStartGate.CANCELLED
            else when (access.wakeRearOnce()) {
                ControlledCastWakeResult.FAILED -> ApprovedCastStartGate.WAKE_FAILED
                ControlledCastWakeResult.ALREADY_ON -> ApprovedCastStartGate.READY
                ControlledCastWakeResult.CONFIRMED -> {
                    issuedDeadline = access.elapsedRealtime() + timeoutMs
                    null
                }
            }
        } finally { access.endActionWindow() }
        if (wakeResult != null && wakeResult != ApprovedCastStartGate.READY) return WakeAttempt(wakeResult, null)
        val deadline = issuedDeadline
        if (!access.isAuthorized()) return WakeAttempt(ApprovedCastStartGate.CANCELLED, deadline)
        if (!access.rearIsCurrent()) return WakeAttempt(ApprovedCastStartGate.REAR_CHANGED, deadline)
        if (!access.targetIsCurrent()) return WakeAttempt(ApprovedCastStartGate.TARGET_CHANGED, deadline)
        val initialAfterGate = try { if (deadline != null) access.inspectPostWakeEvidence(
            expectedOemTaskId, expectedOemUserId, expectedOemComponent) else access.inspectStartEvidence() }
            catch (_: Exception) { return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_UNKNOWN, deadline) }
        if (deadline != null && access.elapsedRealtime() >= deadline) return WakeAttempt(ApprovedCastStartGate.WAKE_FAILED, deadline)
        if (!access.isAuthorized()) return WakeAttempt(ApprovedCastStartGate.CANCELLED, deadline)
        if (!access.rearIsCurrent()) return WakeAttempt(ApprovedCastStartGate.REAR_CHANGED, deadline)
        if (!access.targetIsCurrent()) return WakeAttempt(ApprovedCastStartGate.TARGET_CHANGED, deadline)
        var settlingTaskId: Int? = null
        if (initialAfterGate.occupant != expectedOccupant && !initialAfterGate.oemWakeTransition) {
            return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_CHANGED, deadline)
        }
        if (initialAfterGate.oemWakeTransition) {
            if (expectedOccupant != null || !isExactWakeTransition(initialAfterGate)
                || initialAfterGate.oemWakeTransitionTaskId != expectedOemTaskId)
                return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_CHANGED, deadline)
            settlingTaskId = initialAfterGate.oemWakeTransitionTaskId
        }
        val initiallyOn = initialAfterGate.power ?: return WakeAttempt(ApprovedCastStartGate.STANDBY_UNKNOWN, deadline)
        if (!initiallyOn.isStableKnown()) return WakeAttempt(ApprovedCastStartGate.STANDBY_UNKNOWN, deadline)
        if (initiallyOn.state == Display.STATE_ON && !initialAfterGate.oemWakeTransition) return WakeAttempt(null, deadline)
        if (deadline == null) return WakeAttempt(ApprovedCastStartGate.WAKE_FAILED, null)
        while (access.elapsedRealtime() < deadline) {
            if (!access.isAuthorized()) return WakeAttempt(ApprovedCastStartGate.CANCELLED, deadline)
            if (!access.rearIsCurrent()) return WakeAttempt(ApprovedCastStartGate.REAR_CHANGED, deadline)
            if (!access.targetIsCurrent()) return WakeAttempt(ApprovedCastStartGate.TARGET_CHANGED, deadline)
            val evidence = try { access.inspectPostWakeEvidence(expectedOemTaskId, expectedOemUserId, expectedOemComponent) }
            catch (_: Exception) { return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_UNKNOWN, deadline) }
            if (access.elapsedRealtime() >= deadline) return WakeAttempt(ApprovedCastStartGate.WAKE_FAILED, deadline)
            if (!access.isAuthorized()) return WakeAttempt(ApprovedCastStartGate.CANCELLED, deadline)
            if (!access.rearIsCurrent()) return WakeAttempt(ApprovedCastStartGate.REAR_CHANGED, deadline)
            if (!access.targetIsCurrent()) return WakeAttempt(ApprovedCastStartGate.TARGET_CHANGED, deadline)
            if (evidence.occupant != expectedOccupant && !evidence.oemWakeTransition) {
                if (expectedOccupant != null || !isExactWakeTransition(evidence)
                    || (settlingTaskId != null && evidence.oemWakeTransitionTaskId != settlingTaskId))
                    return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_CHANGED, deadline)
                settlingTaskId = evidence.oemWakeTransitionTaskId
            }
            if (evidence.oemWakeTransition) {
                if (expectedOccupant != null || !isExactWakeTransition(evidence)
                    || (settlingTaskId != null && evidence.oemWakeTransitionTaskId != settlingTaskId))
                    return WakeAttempt(ApprovedCastStartGate.OCCUPANCY_CHANGED, deadline)
                settlingTaskId = evidence.oemWakeTransitionTaskId
            }
            val power = evidence.power ?: return WakeAttempt(ApprovedCastStartGate.STANDBY_UNKNOWN, deadline)
            if (!power.isStableKnown()) return WakeAttempt(ApprovedCastStartGate.STANDBY_UNKNOWN, deadline)
            if (power.state == Display.STATE_ON && !evidence.oemWakeTransition) return WakeAttempt(null, deadline)
            try { access.sleep(POLL_MS) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return WakeAttempt(ApprovedCastStartGate.WAKE_FAILED, deadline)
            }
        }
        return WakeAttempt(ApprovedCastStartGate.WAKE_FAILED, deadline)
    }

    private fun isExactWakeTransition(evidence: ControlledCastStartEvidence): Boolean =
        evidence.oemWakeTransition && evidence.oemWakeTransitionTaskId != null
            && evidence.power?.state == Display.STATE_ON && evidence.power.isStableKnown()

    private fun finalOwnerCheck(expected: CastOccupantIdentity, access: Access): ApprovedCastStartGate {
        if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        if (!access.rearIsCurrent()) return ApprovedCastStartGate.REAR_CHANGED
        if (!access.targetIsCurrent()) return ApprovedCastStartGate.TARGET_CHANGED
        val evidence = try { access.inspectStartEvidence() }
        catch (_: Exception) { return ApprovedCastStartGate.OCCUPANCY_UNKNOWN }
        if (!access.isAuthorized()) return ApprovedCastStartGate.CANCELLED
        if (evidence.occupant != expected) return ApprovedCastStartGate.OCCUPANCY_CHANGED
        return if (access.claimSideEffect()) ApprovedCastStartGate.READY else ApprovedCastStartGate.CANCELLED
    }

    private const val OEM_PACKAGE = "com.xiaomi.subscreencenter"
    private const val OEM_LAUNCHER = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher"
    private val ASLEEP_STATES = setOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)
}

private object StandbyComponentName {
    fun canonicalComponent(flattened: String): String {
        val separator = flattened.indexOf('/')
        if (separator <= 0 || separator == flattened.lastIndex) return ""
        val pkg = flattened.substring(0, separator)
        val cls = flattened.substring(separator + 1).let { if (it.startsWith('.')) pkg + it else it }
        return "$pkg/$cls"
    }
}
