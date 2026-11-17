package top.zainzh.rearscreen.platform.ipc

/** One exact SDK connection's prepared confirmation, governed by the coordinator ticket deadline. */
internal class PendingCastActivationGate(
    private val ownerConnection: Any,
    val expiresAtElapsedRealtimeMs: Long
) {
    private val lock = Any()
    private var terminal = false

    enum class Claim { CLAIMED, EXPIRED, NOT_DUE, STALE }

    fun claim(connection: Any, nowElapsedRealtimeMs: Long): Claim = synchronized(lock) {
        if (connection !== ownerConnection || terminal) return@synchronized Claim.STALE
        terminal = true
        if (nowElapsedRealtimeMs >= expiresAtElapsedRealtimeMs) Claim.EXPIRED else Claim.CLAIMED
    }

    fun expire(connection: Any, nowElapsedRealtimeMs: Long): Claim = synchronized(lock) {
        if (connection !== ownerConnection || terminal) return@synchronized Claim.STALE
        if (nowElapsedRealtimeMs < expiresAtElapsedRealtimeMs) return@synchronized Claim.NOT_DUE
        terminal = true
        Claim.EXPIRED
    }

    fun isOpenFor(connection: Any, nowElapsedRealtimeMs: Long): Boolean = synchronized(lock) {
        connection === ownerConnection && !terminal && nowElapsedRealtimeMs < expiresAtElapsedRealtimeMs
    }
}
