package top.zainzh.rearscreen.platform.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import top.zainzh.rearscreen.platform.ControlledCastRuntimeProvider
import top.zainzh.rearscreen.platform.SdkCastIdentityRevocation
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** App-process monitor: SDK Cast ownership outlives the Binder service that requested it. */
internal object SdkCastPackageIdentityMonitor {
    private val lock = Any()
    private val ownersByIdentity = ConcurrentHashMap<String, MutableSet<SessionOwner>>()
    @Volatile private var appContext: Context? = null
    @Volatile private var receiverRegistered = false

    fun install(context: Context) {
        if (receiverRegistered) return
        synchronized(lock) {
            if (receiverRegistered) return
            val app = context.applicationContext
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(app, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            appContext = app
            receiverRegistered = true
        }
    }

    fun track(owner: SessionOwner) {
        require(owner.principal is SessionPrincipal.AndroidClient)
        install(appContext ?: throw IllegalStateException("package monitor not installed"))
        val key = "${owner.transportUid}:${owner.transportPackageName}:${owner.transportSigningCertificateDigest}"
        ownersByIdentity.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(owner)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
            if (action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            revokeStale(packageName,
                removed = action == Intent.ACTION_PACKAGE_REMOVED || action == Intent.ACTION_PACKAGE_FULLY_REMOVED)
        }
    }

    private fun revokeStale(packageName: String, removed: Boolean) {
        val app = appContext ?: return
        val digest = if (removed) null else runCatching { signingDigest(app, packageName) }.getOrNull()
        val stale = ownersByIdentity.entries.flatMap { (key, owners) ->
            owners.filter { SdkCastIdentityRevocation.shouldRevoke(it, packageName, digest) }
                .map { Triple(key, owners, it) }
        }
        stale.forEach { (key, owners, owner) ->
            runCatching {
                val runtime = ControlledCastRuntimeProvider.get(app)
                runtime.owners.sdkConnectionClosed(owner)
                RemoteRearPlatformService.clearPendingCastActivationsForSdkOwner(owner)
                runtime.coordinator.revokeOwner(owner)
            }.onSuccess {
                owners.remove(owner)
                if (owners.isEmpty()) ownersByIdentity.remove(key, owners)
            }
        }
    }

    private fun signingDigest(context: Context, packageName: String): String {
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        }
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION") info.signatures
        }
        val certificate = signatures?.firstOrNull()?.toByteArray() ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02X".format(it) }
    }
}
