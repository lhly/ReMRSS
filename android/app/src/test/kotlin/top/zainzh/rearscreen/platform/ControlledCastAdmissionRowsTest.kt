package top.zainzh.rearscreen.platform

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledCastAdmissionRowsTest {
    private val launcher = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher"
    private val off = CastDisplayPowerEvidence(Display.STATE_OFF, Display.STATE_OFF, Display.STATE_OFF)

    @Test fun exactOemSleepRowAllowsEitherKnownVisibilityBitButRequiresNoPendingShow() {
        for (powerState in listOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)) {
            val power = CastDisplayPowerEvidence(powerState, powerState, powerState)
            for (visible in listOf(true, false)) {
                val accepted = ControlledCastAdmissionRows.classify(power,
                    listOf(oem(visible = visible, requested = false, sleeping = true)))
                assertTrue(accepted.empty)
                assertEquals(1, accepted.exactOemRows)
            }
        }
        for (row in listOf(
            oem(visible = true, requested = false, sleeping = false),
            oem(visible = true, requested = false, sleeping = null),
            oem(visible = true, requested = null, sleeping = true),
            oem(visible = null, requested = false, sleeping = true)
        )) {
            val rejected = ControlledCastAdmissionRows.classify(off, listOf(row))
            assertFalse(rejected.empty)
            assertEquals(1, rejected.blockedRows)
        }
    }

    @Test fun duplicateOemRowsWrongComponentAndForeignRowsNeverClassifyEmpty() {
        val duplicate = ControlledCastAdmissionRows.classify(off, listOf(
            oem(visible = true, requested = false, sleeping = true),
            oem(visible = true, requested = false, sleeping = true)
        ))
        assertFalse(duplicate.empty)
        assertTrue(duplicate.duplicateOemRows)
        assertEquals(2, duplicate.exactOemRows)

        val wrongComponent = oem(visible = true, requested = false, sleeping = true).copy(
            topComponent = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.Settings")
        assertFalse(ControlledCastAdmissionRows.classify(off, listOf(wrongComponent)).empty)

        val foreign = CastAdmissionRowEvidence("other.app", "other.app", "other.app/.Main",
            "other.app/.Main", false, false, false)
        assertFalse(ControlledCastAdmissionRows.classify(off, listOf(oem(true, false, true), foreign)).empty)
    }

    @Test fun missingQueriedBaseComponentIsUnknownAndNeverIgnoredForOnHiddenRows() {
        val on = CastDisplayPowerEvidence(Display.STATE_ON, Display.STATE_ON, Display.STATE_ON)
        val hiddenThirdParty = CastAdmissionRowEvidence("other.app", "other.app", null,
            "other.app/.Main", false, false, false)
        val result = ControlledCastAdmissionRows.classify(on, listOf(hiddenThirdParty))
        assertFalse(result.empty)
        assertEquals(1, result.unknownRows)
        assertEquals(1, result.blockedRows)
    }

    private fun oem(visible: Boolean?, requested: Boolean?, sleeping: Boolean?) = CastAdmissionRowEvidence(
        "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", launcher, launcher,
        visible, requested, sleeping)
}
