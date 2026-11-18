package top.zainzh.rearscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import java.text.Collator

internal data class AiRearApp(val component: String, val label: String, val canCast: Boolean)

internal object AiRearAppCatalog {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    fun list(context: Context): List<AiRearApp> {
        val manager = context.packageManager
        val labels = Collator.getInstance(context.resources.configuration.locales[0])
        return manager.queryIntentActivities(launcher(), 0).mapNotNull { item ->
            val info = item.activityInfo ?: return@mapNotNull null
            if (!visible(info, context.packageName)) return@mapNotNull null
            val component = ComponentName(info.packageName, info.name).flattenToString()
            if (runCatching { VirtualCastRequest.component(component) }.isFailure) return@mapNotNull null
            AiRearApp(component, item.loadLabel(manager).toString(), canStartIndependent(info, manager))
        }.distinctBy { it.component }.sortedWith { a, b ->
            labels.compare(a.label, b.label).takeUnless { it == 0 } ?: a.component.compareTo(b.component)
        }
    }

    /** Shared by the UI and Shizuku; only public, enabled launcher entries can start a separate task. */
    @JvmStatic
    fun isCastTarget(context: Context, target: ComponentName?, hostPackage: String): Boolean {
        if (target == null) return false
        val manager = context.packageManager
        return runCatching {
            manager.queryIntentActivities(launcher().setPackage(target.packageName), 0).any { result ->
                val info = result.activityInfo
                info != null && target == ComponentName(info.packageName, info.name) &&
                    visible(info, hostPackage) && canStartIndependent(info, manager)
            }
        }.getOrDefault(false)
    }

    private fun visible(info: ActivityInfo, host: String) = info.enabled && info.exported &&
        info.applicationInfo.enabled && info.packageName != host

    private fun canStartIndependent(info: ActivityInfo, manager: PackageManager): Boolean {
        if (!info.permission.isNullOrEmpty()) return false
        val actual = info.targetActivity?.let { name ->
            runCatching { manager.getActivityInfo(ComponentName.createRelative(info.packageName, name), 0) }.getOrNull()
                ?: return false
        } ?: info
        return actual.enabled && supportsIndependentTask(actual.launchMode, actual.documentLaunchMode)
    }

    /** NEW_TASK | MULTIPLE_TASK cannot promise isolation for singleton or document-reuse activities. */
    internal fun supportsIndependentTask(launchMode: Int, documentMode: Int): Boolean =
        (launchMode == ActivityInfo.LAUNCH_MULTIPLE || launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) &&
            documentMode == ActivityInfo.DOCUMENT_LAUNCH_NONE
}
