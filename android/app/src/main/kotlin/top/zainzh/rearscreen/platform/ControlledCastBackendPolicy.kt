package top.zainzh.rearscreen.platform

import org.json.JSONObject

/** Small production predicates kept pure enough to exercise the exact adapter edge cases on the JVM. */
internal object ControlledCastBackendPolicy {
    fun canonicalComponent(value: String): String? = CastComponentIdentity.normalize(value)

    fun operationMatches(operationId: String, ownerGeneration: Long, ownerIdentity: String,
                         sessionId: String?, status: JSONObject): Boolean =
        operationId.isNotBlank() && ownerGeneration > 0L && ownerIdentity.isNotBlank()
            && (sessionId == null || status.optString("id") == sessionId)
            && status.optString("coordinatorOperationId") == operationId
            && status.optLong("ownerGeneration", -1L) == ownerGeneration
            && status.optString("ownerIdentity") == ownerIdentity

    fun isTerminalForThisOperation(operationId: String, ownerGeneration: Long, ownerIdentity: String,
                                   status: JSONObject): Boolean =
        status.optString("state") == "stopped"
            && operationMatches(operationId, ownerGeneration, ownerIdentity, null, status)

    fun appliedRenderDensity(status: JSONObject): Int = status.optInt("density", -1)
}
