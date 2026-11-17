package top.zainzh.rearscreen.platform

/** Host-updated authority cache: activation is valid only for a live SDK connection or trusted Mod grant. */
internal class ControlledCastOwnerRegistry : CastOwnerAuthority {
    private val lock = Any()
    private val sdkConnections = HashSet<SessionOwner>()
    private val activeModPrincipals = HashSet<CastOwnerKey>()

    fun sdkConnectionOpened(owner: SessionOwner) {
        require(owner.principal is SessionPrincipal.AndroidClient)
        synchronized(lock) { sdkConnections.add(owner) }
    }

    fun sdkConnectionClosed(owner: SessionOwner) {
        synchronized(lock) { sdkConnections.remove(owner) }
    }

    fun modAuthorizationChanged(owner: SessionOwner, authorized: Boolean) {
        require(owner.principal is SessionPrincipal.SignedMod)
        synchronized(lock) {
            val key = CastOwnerKey.from(owner)
            if (authorized) activeModPrincipals.add(key) else activeModPrincipals.remove(key)
        }
    }

    override fun isCurrentForActivation(owner: SessionOwner): Boolean = synchronized(lock) {
        when (owner.principal) {
            is SessionPrincipal.AndroidClient -> owner in sdkConnections
            is SessionPrincipal.SignedMod -> CastOwnerKey.from(owner) in activeModPrincipals
            is SessionPrincipal.HostFeature -> false
        }
    }
}
