package top.zainzh.rearscreen.platform

import android.content.Context
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.BuildConfig
import top.zainzh.rearscreen.ITaskService
import top.zainzh.rearscreen.RearDisplay
import top.zainzh.rearscreen.TaskServiceManager
import top.zainzh.rearscreen.VirtualRearHostActivity

/** A DUMP-protected Debug-only read path. It never creates a service connection or touches a task. */
internal object DebugCastAdmissionDiagnostic {
    private const val OEM_PACKAGE = "com.xiaomi.subscreencenter"

    fun collect(context: Context, remote: ITaskService): JSONObject {
        check(BuildConfig.DEBUG)
        val report = JSONObject().put("backend", "AndroidControlledCastBackend")
            .put("privilegeBackend", if (TaskServiceManager.isRoot(context)) "ROOT" else "SHIZUKU")
            .put("service", if (remote.asBinder()?.isBinderAlive == true) "ready" else "dead")
            .put("serviceContract", serviceContractVersion(remote))
        val rear = runCatching { JSONObject(remote.getRearDisplayState()) }.getOrNull()
        val rearId = rear?.optInt("selectedId", -1) ?: -1
        val rearUnique = rear?.optString("selectedUnique", "").orEmpty()
        val rearVerified = rearId > 0 && rearUnique.isNotBlank()
        report.put("rearIdentityVerified", rearVerified)
        val localId = runCatching { RearDisplay.requireId() }.getOrDefault(-1)
        val localIdentity = runCatching { RearDisplay.identity() }.getOrNull()
        report.put("localSelectedId", localId)
            .put("localIdentityAvailable", !localIdentity.isNullOrBlank())
            .put("localSelectedIdMatchesService", localId > 0 && localId == rearId)
            .put("localIdentityMatchesService", localIdentity != null && localIdentity == "$rearId:$rearUnique")
            .put("identityComparison", when {
                !rearVerified -> "service_identity_unavailable"
                localId < 1 -> "local_selection_unavailable"
                localId != rearId -> "selected_id_mismatch"
                localIdentity == null -> "local_identity_unavailable"
                localIdentity != "$rearId:$rearUnique" -> "identity_mismatch"
                else -> "matched"
            })
        report.put("hostStart", VirtualRearHostActivity.debugControlledStartSnapshot(context, remote))
        if (!rearVerified) return report.put("snapshot", "not_read")

        val virtualState = runCatching { JSONObject(remote.getRearVirtualCastStatus()).optString("state", "unknown") }
            .getOrDefault("unknown").takeIf { it in setOf("running", "starting", "stopped") } ?: "unknown"
        report.put("virtualOwnerState", virtualState)
        report.put("physicalOwnerBranch", LegacyDisplayOccupancy.diagnosticPhysicalProjectionBranch(
            context.applicationContext, remote, rearId))

        val snapshot = runCatching { JSONObject(remote.getDisplayCastOccupancy(rearId)) }.getOrNull()
            ?: return report.put("snapshot", "unavailable")
        report.put("snapshot", snapshot.optString("status", "unknown"))
        report.put("snapshotDisplayMatchesService", snapshot.optInt("display", -1) == rearId)
        report.put("snapshotIdentityComparison", if (snapshot.optInt("display", -1) == rearId
            && localId == rearId && localIdentity == "$rearId:$rearUnique") "matched" else "mismatch")
        report.put("source", snapshot.optString("source", "unknown"))
        report.put("power", powerName(snapshot.optInt("power", -1)))
        report.put("powerBefore", powerName(snapshot.optInt("powerBefore", -1)))
        report.put("powerAfter", powerName(snapshot.optInt("powerAfter", -1)))
        val rows = snapshot.optJSONArray("tasks") ?: return report.put("rowCount", -1)
        report.put("rowCount", rows.length())
        val oemRows = JSONArray()
        val evidence = ArrayList<CastAdmissionRowEvidence>(rows.length())
        var malformedRows = 0
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
            if (row == null) { malformedRows++; continue }
            val taskId = row.optInt("task", -1)
            val basePackage = row.optString("base", "")
            val topPackage = row.optString("top", "")
            val queriedBase = if (taskId > 0)
                runCatching { remote.getTaskBaseComponent(taskId, rearId) }.getOrNull() else null
            val typedBase = row.optString("baseComponent", "")
            val topComponent = row.optString("topComponent", "")
            val basePackageExact = basePackage == OEM_PACKAGE
            val topPackageExact = topPackage == OEM_PACKAGE
            val rowComponentExact = ControlledCastStandbyPolicy.isExactOemDesktop(
                basePackage, topPackage, typedBase, topComponent)
            val queriedComponentExact = ControlledCastStandbyPolicy.isExactOemDesktop(
                basePackage, topPackage, queriedBase, topComponent)
            val visible = row.opt("visible") as? Boolean
            val requested = row.opt("requested") as? Boolean
            val sleeping = row.opt("sleeping") as? Boolean
            evidence.add(CastAdmissionRowEvidence(basePackage, topPackage, queriedBase, topComponent,
                visible, requested, sleeping))
            if (basePackageExact || topPackageExact || rowComponentExact || queriedComponentExact) {
                oemRows.put(JSONObject().put("basePackageExact", basePackageExact)
                    .put("topPackageExact", topPackageExact).put("rowComponentExact", rowComponentExact)
                    .put("queriedComponentExact", queriedComponentExact).put("visible", tri(visible))
                    .put("requested", tri(requested)).put("sleeping", tri(sleeping)))
            }
        }
        val power = CastDisplayPowerEvidence(snapshot.optInt("power", -1), snapshot.optInt("powerBefore", -1),
            snapshot.optInt("powerAfter", -1))
        val decision = ControlledCastAdmissionRows.classify(power, evidence)
        val blockedRows = malformedRows + decision.blockedRows
        val physical = report.optString("physicalOwnerBranch")
        val classification = when {
            virtualState == "running" || virtualState == "starting" -> "virtual_owner_path"
            physical == "active_owner_branch" || physical == "query_unknown_fail_closed_branch" -> "physical_owner_path"
            snapshot.optString("status") != "ok" || !power.isStableKnown() || decision.unknownRows > 0 -> "unknown"
            blockedRows > 0 -> "occupied"
            else -> "empty"
        }
        return report.put("exactOemRows", decision.exactOemRows)
            .put("duplicateOemRows", decision.duplicateOemRows).put("unknownRows", decision.unknownRows)
            .put("blockedRows", blockedRows)
            .put("prepareClassification", classification).put("oemRows", oemRows)
    }

    private fun serviceContractVersion(remote: ITaskService): Int {
        val events = runCatching { JSONObject(remote.getCompatibilityDiagnostics()).optJSONArray("events") }.getOrNull()
            ?: return -1
        val pattern = Regex("user_service_contract=(\\d+)")
        for (index in events.length() - 1 downTo 0) {
            val match = pattern.find(events.optString(index)) ?: continue
            return match.groupValues[1].toIntOrNull() ?: -1
        }
        return -1
    }

    private fun tri(value: Boolean?): String = when (value) {
        true -> "true"; false -> "false"; null -> "unknown"
    }

    private fun powerName(value: Int): String = when (value) {
        Display.STATE_ON -> "ON"; Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"; else -> "UNKNOWN"
    }
}
