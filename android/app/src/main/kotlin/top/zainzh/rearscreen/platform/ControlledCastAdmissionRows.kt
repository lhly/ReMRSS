package top.zainzh.rearscreen.platform

/** One row of the privileged typed occupancy snapshot, with the remotely re-read base identity. */
internal data class CastAdmissionRowEvidence(
    val basePackage: String,
    val topPackage: String,
    val baseComponent: String?,
    val topComponent: String?,
    val visible: Boolean?,
    val requested: Boolean?,
    val sleeping: Boolean?,
    val taskId: Int = -1,
    val userId: Int = -1
)

internal data class CastAdmissionRowsResult(
    val exactOemRows: Int,
    val blockedRows: Int,
    val unknownRows: Int,
    val firstBlockedIndex: Int?,
    val duplicateOemRows: Boolean
) {
    val empty: Boolean get() = blockedRows == 0 && !duplicateOemRows
}

/** Shared row classifier for prepare, final wake admission, and the redacted Debug diagnostic. */
internal object ControlledCastAdmissionRows {
    fun classify(power: CastDisplayPowerEvidence, rows: List<CastAdmissionRowEvidence>): CastAdmissionRowsResult {
        val exactOemIndices = rows.indices.filter { index ->
            val row = rows[index]
            ControlledCastStandbyPolicy.isExactOemDesktop(row.basePackage, row.topPackage,
                row.baseComponent, row.topComponent)
        }
        val duplicate = exactOemIndices.size > 1
        val unknownIndices = rows.indices.filter { index -> rows[index].baseComponent.isNullOrBlank() }
        val blocked = rows.indices.filter { index ->
            val row = rows[index]
            row.baseComponent.isNullOrBlank() || !ControlledCastStandbyPolicy.isIgnorableTask(
                power, row.basePackage, row.topPackage, row.baseComponent, row.topComponent,
                row.visible, row.requested, row.sleeping)
        }.toMutableList()
        if (duplicate) {
            val duplicateIndex = exactOemIndices[1]
            if (duplicateIndex !in blocked) blocked.add(duplicateIndex)
        }
        return CastAdmissionRowsResult(exactOemIndices.size, blocked.size, unknownIndices.size,
            blocked.firstOrNull(), duplicate)
    }
}
