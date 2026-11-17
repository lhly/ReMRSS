package top.zainzh.rearscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the production WMS/Input parser under Android ICU, not desktop java.util.regex. */
@RunWith(AndroidJUnit4::class)
class PhysicalTaskPresentationAndroidRegexSmokeTest {
    @Test fun androidIcuParsesClosingBraceHeaderAndExactTouchWindow() {
        val windows = """
            Window{abc123 u0 smoke.pkg/.MainActivity}:
              mDisplayId=1 taskId=42 mOwnerUid=10000 mHasSurface=true mDrawState=HAS_DRAWN isOnScreen=true isVisible=true ty=BASE_APPLICATION
        """.trimIndent()
        val input = """
            Window name=abc123 smoke.pkg/.MainActivity, id=7, displayId=1, ownerUid=10000, ownerPid=1234, inputConfig=0x0 alpha=1.0 frame=[0,0][100,100] touchableRegion=[0,0][100,100] ownerPid=1234
        """.trimIndent()

        val result = PhysicalTaskPresentationEvidence.evaluate(
            windows, input, 42, "smoke.pkg", "smoke.pkg/.MainActivity", 1, 10000)
        assertTrue("Android ICU must recognize the production WMS closing-brace form: ${result.reason}", result.ready)
    }
}
