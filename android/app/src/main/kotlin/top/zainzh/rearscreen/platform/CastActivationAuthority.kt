package top.zainzh.rearscreen.platform

import android.os.SystemClock
import java.security.SecureRandom
import java.util.Base64

/** Ticket-scoped one-shot activation minted only by trusted host confirmation UI. */
internal class CastActivationAuthority(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val newToken: () -> String = ::token
) : CastActivationVerifier {
    private data class Grant(val owner: SessionOwner, val ticketId: String, val generation: Long, val expiresAt: Long)
    private val lock = Any()
    private val grants = HashMap<String, Grant>()

    fun issueFromConfirmedHostAction(owner: SessionOwner, ticketId: String, generation: Long): UserActivationToken {
        require(ticketId.isNotBlank() && generation > 0L)
        val value = newToken()
        synchronized(lock) {
            purgeLocked()
            grants[value] = Grant(owner, ticketId, generation, clock() + TTL_MS)
        }
        return UserActivationToken(value)
    }

    override fun consume(owner: SessionOwner, ticketId: String, generation: Long, token: UserActivationToken): Boolean =
        synchronized(lock) {
            purgeLocked()
            val grant = grants.remove(token.opaqueValue) ?: return@synchronized false
            grant.owner == owner && grant.ticketId == ticketId && grant.generation == generation && clock() < grant.expiresAt
        }

    private fun purgeLocked() { grants.entries.removeAll { clock() >= it.value.expiresAt } }

    companion object {
        const val TTL_MS = 5_000L
        private val secureRandom = SecureRandom()
        private fun token(): String = ByteArray(24).also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
