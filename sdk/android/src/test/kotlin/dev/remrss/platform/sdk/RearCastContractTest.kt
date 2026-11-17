package dev.remrss.platform.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RearCastContractTest {
    @Test fun requestUsesCurrentForegroundAndOmittedGeometryKeepsSavedPreferences() {
        val json = RearCastRequest(RearCastMode.VIRTUAL).toJson().toString()
        val request = JSONObject(json)
        assertEquals("CURRENT_FOREGROUND", request.getString("selector"))
        assertFalse(request.has("component"))
        assertFalse(request.has("region"))
        assertFalse(request.has("contentRotation"))
        assertFalse(request.has("requestedDensityDpi"))
    }

    @Test fun explicitFullAndQuarterTurnArePreserved() {
        val request = JSONObject(RearCastRequest(RearCastMode.VIRTUAL, RearRegion.FULL, 1, 440).toJson().toString())
        assertEquals("FULL", request.getString("region"))
        assertEquals(1, request.getInt("contentRotation"))
        assertEquals(440, request.getInt("requestedDensityDpi"))
    }

    @Test fun readyQueryKeepsBothAffineCrossTermsForNinetyDegreeInput() {
        val viewport = JSONObject().put("revision", 2).put("rearDisplayId", 7).put("region", "FULL")
            .put("densityDpi", 440).put("leftPx", 0).put("topPx", 0).put("widthPx", 976).put("heightPx", 596)
            .put("bufferWidthPx", 596).put("bufferHeightPx", 976).put("displayRotation", 0).put("contentRotation", 1)
            .put("windowLeftPx", 12).put("windowTopPx", 34).put("windowWidthPx", 976).put("windowHeightPx", 596)
            .put("inputScaleX", 0f).put("inputSkewX", 1f).put("inputTranslateX", 0f)
            .put("inputSkewY", -1f).put("inputScaleY", 0f).put("inputTranslateY", 976f)
        val snapshot = RearCastSessionSnapshot.fromJson(JSONObject().put("success", true).put("sessionId", "opaque")
            .put("generation", 3).put("state", "READY").put("mode", "VIRTUAL").put("region", "FULL")
            .put("contentRotation", 1).put("presentationKind", "MANAGED_VIEWPORT").put("viewport", viewport).toString())
        assertEquals(1f, snapshot.appliedViewport?.inputSkewX)
        assertEquals(-1f, snapshot.appliedViewport?.inputSkewY)
        assertEquals(596, snapshot.appliedViewport?.bufferWidthPx)
        assertEquals(12, snapshot.appliedViewport?.windowLeftPx)
        assertEquals(976, snapshot.appliedViewport?.windowWidthPx)
        assertTrue(snapshot.handle.sessionId == "opaque")
    }

    @Test fun systemManagedPhysicalProjectionDoesNotInventViewport() {
        val snapshot = RearCastSessionSnapshot.fromJson(JSONObject().put("success", true).put("sessionId", "opaque")
            .put("generation", 4).put("state", "READY").put("mode", "DIRECT_PHYSICAL").put("region", "CAMERA_SAFE")
            .put("contentRotation", 0).put("presentationKind", "SYSTEM_MANAGED").put("viewport", JSONObject.NULL).toString())
        assertEquals(RearCastPresentationKind.SYSTEM_MANAGED, snapshot.presentationKind)
        assertEquals(null, snapshot.appliedViewport)
    }
}
