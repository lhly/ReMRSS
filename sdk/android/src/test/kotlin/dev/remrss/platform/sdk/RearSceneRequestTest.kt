package dev.remrss.platform.sdk

import android.content.ComponentName
import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RearSceneRequestTest {
    @Test fun legacySceneRequestOmitsOrientationAndPreservesLegacyReporterContract() {
        val request = RearSceneRequest(ComponentName("sample.app", "sample.app.Scene"))
        val json = JSONObject(request.toJson().toString())
        assertTrue("contentRotation must be absent for legacy requests", !json.has("contentRotation"))
        assertEquals("CAMERA_SAFE", json.getString("region"))
        assertTrue((0 until json.getJSONArray("requiredCapabilities").length()).none {
            json.getJSONArray("requiredCapabilities").getJSONObject(it).getString("name") == "rear.scene.orientation"
        })
    }

    @Test fun explicitZeroAndNonzeroQuarterTurnsAreWireDistinguishable() {
        val request = RearSceneRequest(
            ComponentName("sample.app", "sample.app.Scene"),
            region = RearRegion.FULL,
        )
        val legacyJson = JSONObject(request.toJson().toString())
        assertTrue(!legacyJson.has("contentRotation"))
        val explicitZero = JSONObject(request.toJson(0).toString())
        assertEquals(0, explicitZero.getInt("contentRotation"))
        assertTrue((0 until explicitZero.getJSONArray("requiredCapabilities").length()).any {
            explicitZero.getJSONArray("requiredCapabilities").getJSONObject(it).getString("name") == "rear.scene.orientation"
        })
        val explicitTurn = JSONObject(request.toJson(1).toString())
        assertEquals(1, explicitTurn.getInt("contentRotation"))
        val names = (0 until explicitTurn.getJSONArray("requiredCapabilities").length()).map {
            explicitTurn.getJSONArray("requiredCapabilities").getJSONObject(it).getString("name")
        }
        assertTrue("rear.scene.orientation" in names)
    }

    @Test fun launchPlacementReadsTicketBoundIntentAndLegacyMissingExtrasStayDefault() {
        assertEquals(null, RearSceneLaunchMetadata.read(Intent()))
        val explicitZero = Intent().putExtra(RearSceneLaunchMetadata.EXTRA_REGION, "FULL")
            .putExtra(RearSceneLaunchMetadata.EXTRA_EXPLICIT_ROTATION, true)
            .putExtra(RearSceneLaunchMetadata.EXTRA_CONTENT_ROTATION, 0)
        assertEquals(RearSceneLaunchPlacement(RearRegion.FULL, 0), RearSceneLaunchMetadata.read(explicitZero))
        val ninety = Intent().putExtra(RearSceneLaunchMetadata.EXTRA_REGION, "CAMERA_SAFE")
            .putExtra(RearSceneLaunchMetadata.EXTRA_EXPLICIT_ROTATION, true)
            .putExtra(RearSceneLaunchMetadata.EXTRA_CONTENT_ROTATION, 1)
        assertEquals(RearSceneLaunchPlacement(RearRegion.CAMERA_SAFE, 1), RearSceneLaunchMetadata.read(ninety))
    }
}
