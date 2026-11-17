package top.zainzh.rearscreen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModCastLifecycleRevocationContractTest {
    @Test fun settingsPersistsRevocationBeforeInvalidatingExactCastOwner() {
        val settings = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModSettingsActivity.kt")
        assertBefore(settings, "private fun changeEnabled(", "private fun changeGrant(", "ModPackageStore.setEnabled", "closeModSessions(updated)")
        assertBefore(settings, "private fun changeGrant(", "private fun revokeAuthor(", "ModPackageStore.setCapabilityGrant", "closeModSessions(updated)")
        assertBefore(settings, "private fun revokeAuthor(", "private fun closeModSessions(", "ModPackageStore.revokeAuthorTrust", "closeModSessions(installed)")
        assertBefore(settings, "private fun uninstall(", "private fun openRear(", "ModPackageStore.setEnabled", "revokeModControlledCastOwner")
        assertBefore(settings, "private fun uninstall(", "private fun openRear(", "revokeModControlledCastOwner", "ModPackageStore.uninstall")
        assertBefore(settings, "private fun commitInstall(", "private fun changeEnabled(", "ModPackageStore.install", "closeModSessions(old)")
        assertTrue(settings.contains("top.zainzh.rearscreen.platform.revokeModControlledCastOwner(this, sessionOwner(mod))"))
        val cleanup = settings.substringAfter("private fun closeModSessions(").substringBefore("private fun uninstall(")
        assertTrue(cleanup.contains("revokeModControlledCastOwner"))
    }

    @Test fun staleSignedModRouteAndPickerRecheckRevokeTheSameStableOwner() {
        val scene = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModRearSceneActivity.kt")
        assertTrue(scene.contains("revokeModControlledCastOwner(this, owner)"))
        val picker = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModCastTargetPickerActivity.kt")
        assertTrue(picker.contains("revokeModControlledCastOwner(this, activeOwner)"))
        assertTrue(picker.contains("runtime.coordinator.commit(activeOwner"))
        assertTrue(picker.contains("issueFromConfirmedHostAction(activeOwner"))
    }

    private fun assertBefore(source: String, start: String, end: String, first: String, second: String) {
        val section = source.substringAfter(start).substringBefore(end)
        assertTrue("Missing $first in $start", section.contains(first))
        assertTrue("Missing $second in $start", section.contains(second))
        assertTrue("$first must happen before $second", section.indexOf(first) < section.indexOf(second))
    }

    private fun source(path: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(cursor, path)
            if (candidate.isFile) return candidate.readText()
            val appRelative = File(cursor, path.removePrefix("android/app/"))
            if (appRelative.isFile) return appRelative.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Missing source fixture: $path")
    }
}
