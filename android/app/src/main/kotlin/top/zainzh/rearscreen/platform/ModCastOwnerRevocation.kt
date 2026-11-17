package top.zainzh.rearscreen.platform

import android.content.Context

internal fun revokeModControlledCastOwner(context: Context, owner: SessionOwner): ModCastRevocationSubmission {
    val runtime = ControlledCastRuntimeProvider.get(context.applicationContext)
    return ModCastOwnerRevocation.revoke(owner, runtime.owners::modAuthorizationChanged, runtime.coordinator::revokeOwner)
}

internal data class ModCastRevocationSubmission(
    val authorizationRevoked: Boolean,
    /** Coordinator accepted the explicit revoke call; this does not claim backend termination completed. */
    val cleanupRequested: Boolean
)

/** Stable Mod-owner revocation; worker/Activity teardown alone must never call this. */
internal object ModCastOwnerRevocation {
    fun revoke(
        owner: SessionOwner,
        updateActivationAuthority: (SessionOwner, Boolean) -> Unit,
        revokeCoordinatorOwner: (SessionOwner) -> Unit
    ): ModCastRevocationSubmission {
        if (owner.principal !is SessionPrincipal.SignedMod) return ModCastRevocationSubmission(false, false)
        updateActivationAuthority(owner, false)
        revokeCoordinatorOwner(owner)
        return ModCastRevocationSubmission(authorizationRevoked = true, cleanupRequested = true)
    }
}

/** Exact SDK-package certificate invalidation policy; null means the package is no longer installed. */
internal object SdkCastIdentityRevocation {
    fun shouldRevoke(owner: SessionOwner, packageName: String, currentSigningDigest: String?): Boolean {
        val principal = owner.principal as? SessionPrincipal.AndroidClient ?: return false
        if (owner.transportPackageName != packageName || principal.packageName != packageName) return false
        if (currentSigningDigest == null) return true
        return !owner.transportSigningCertificateDigest.equals(currentSigningDigest, ignoreCase = true) ||
            !principal.signingCertificateDigest.equals(currentSigningDigest, ignoreCase = true)
    }
}
