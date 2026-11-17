package top.zainzh.rearscreen.platform

import android.view.Display

/** Narrow proof for the one OEM task row that can lag a successful panel wake. */
internal object ControlledCastWakeTransition {
    fun interface OemLauncherVerifier { fun isVerified(component: String, userId: Int): Boolean }
    fun blockedTaskRow(packageName: String, taskId: Int, component: String,
                       rearDisplayId: Int, rearIdentity: String): CastOccupantIdentity =
        CastOccupantIdentity(CastMode.DIRECT_PHYSICAL, "unmanaged:$packageName", "task:$taskId",
            0L, -1L, taskId, 0, "", component, rearDisplayId, rearIdentity,
            CastOccupantSource.BLOCKED_TASK_ROW)

    fun physicalProjection(owner: String, sessionId: String, generation: Long, ownerGeneration: Long,
                           taskId: Int, component: String, rearDisplayId: Int,
                           rearIdentity: String): CastOccupantIdentity =
        CastOccupantIdentity(CastMode.DIRECT_PHYSICAL, owner, sessionId, generation, ownerGeneration,
            taskId, 0, "", component, rearDisplayId, rearIdentity,
            CastOccupantSource.PHYSICAL_PROJECTION_OWNER)

    fun preWakeTaskId(power: CastDisplayPowerEvidence, rows: List<CastAdmissionRowEvidence>,
                      verifier: OemLauncherVerifier): Int? {
        if (!power.isStableKnown() || power.state !in setOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)) return null
        if (!ControlledCastAdmissionRows.classify(power, rows).empty || rows.size != 1) return null
        val row = rows.single()
        return row.taskId.takeIf { it > 0 && row.userId >= 0 && ControlledCastStandbyPolicy.isExactOemDesktop(
            row.basePackage, row.topPackage, row.baseComponent, row.topComponent)
            && verifier.isVerified(row.baseComponent.orEmpty(), row.userId) }
    }

    fun postWakeTaskId(expectedTaskId: Int?, expectedUserId: Int?, expectedComponent: String?,
                       power: CastDisplayPowerEvidence, occupant: CastOccupantIdentity?,
                       rows: List<CastAdmissionRowEvidence>, verifier: OemLauncherVerifier): Int? {
        if (expectedTaskId == null || !power.isStableKnown() || power.state != Display.STATE_ON || rows.size != 1) return null
        val row = rows.single()
        val stillAsleep = row.visible == false && row.requested == false && row.sleeping == true
        val showingRequested = row.visible == false && row.requested == true && row.sleeping == false
        if (row.taskId != expectedTaskId || row.userId != expectedUserId || expectedUserId == null
            || expectedComponent == null || ControlledCastBackendPolicy.canonicalComponent(row.baseComponent.orEmpty())
                != ControlledCastBackendPolicy.canonicalComponent(expectedComponent)
            || !verifier.isVerified(row.baseComponent.orEmpty(), row.userId) || !(stillAsleep || showingRequested)
            || !ControlledCastStandbyPolicy.isExactOemDesktop(
                row.basePackage, row.topPackage, row.baseComponent, row.topComponent)) return null
        // Must be the occupancy classifier's generic row fallback, not a legacy physical owner.
        if (occupant == null || occupant.source != CastOccupantSource.BLOCKED_TASK_ROW
            || occupant.mode != CastMode.DIRECT_PHYSICAL || occupant.generation != 0L
            || occupant.sessionId != "task:$expectedTaskId" || occupant.taskId != expectedTaskId
            || occupant.owner != "unmanaged:com.xiaomi.subscreencenter") return null
        if (ControlledCastBackendPolicy.canonicalComponent(row.baseComponent.orEmpty()) !=
            ControlledCastBackendPolicy.canonicalComponent(occupant.baseComponent)) return null
        return expectedTaskId
    }

    fun stableVisibleOemRow(expectedTaskId: Int?, expectedUserId: Int?, expectedComponent: String?,
        power: CastDisplayPowerEvidence, rows: List<CastAdmissionRowEvidence>,
        verifier: OemLauncherVerifier): Boolean {
        if (expectedTaskId == null || expectedUserId == null || expectedComponent == null
            || !power.isStableKnown() || power.state != Display.STATE_ON || rows.size != 1) return false
        val row = rows.single()
        return row.taskId == expectedTaskId && row.userId == expectedUserId
            && row.visible == true && row.requested == true && row.sleeping == false
            && (ControlledCastBackendPolicy.canonicalComponent(row.baseComponent.orEmpty()) ==
                ControlledCastBackendPolicy.canonicalComponent(expectedComponent))
            && ControlledCastStandbyPolicy.isExactOemDesktop(row.basePackage, row.topPackage,
                row.baseComponent, row.topComponent)
            && verifier.isVerified(row.baseComponent.orEmpty(), row.userId)
    }

    fun isExactBlockedFallback(occupant: CastOccupantIdentity?, expectedTaskId: Int?,
        expectedComponent: String?): Boolean = expectedTaskId != null && expectedComponent != null
        && occupant != null && occupant.source == CastOccupantSource.BLOCKED_TASK_ROW
        && occupant.mode == CastMode.DIRECT_PHYSICAL && occupant.generation == 0L
        && occupant.sessionId == "task:$expectedTaskId" && occupant.taskId == expectedTaskId
        && occupant.owner == "unmanaged:com.xiaomi.subscreencenter"
        && (ControlledCastBackendPolicy.canonicalComponent(occupant.baseComponent) ==
            ControlledCastBackendPolicy.canonicalComponent(expectedComponent))

    fun exactFallbackSettledToEmpty(previous: CastOccupantIdentity?, current: CastOccupantIdentity?,
        expectedTaskId: Int?, expectedUserId: Int?, expectedComponent: String?,
        power: CastDisplayPowerEvidence, rows: List<CastAdmissionRowEvidence>,
        verifier: OemLauncherVerifier): Boolean = current == null
        && isExactBlockedFallback(previous, expectedTaskId, expectedComponent)
        && stableVisibleOemRow(expectedTaskId, expectedUserId, expectedComponent, power, rows, verifier)

    /** Empty on the last read can precede a fully settled typed row. This only allows polling. */
    fun canKeepPollingExactOem(transitionTaskId: Int?, before: CastOccupantIdentity?,
                              after: CastOccupantIdentity?): Boolean =
        transitionTaskId != null && before?.taskId == transitionTaskId &&
            (after == before || after == null)
}
