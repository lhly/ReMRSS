package top.zainzh.rearscreen.platform.mods

import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.NativeModuleRendererTestHostActivity
import top.zainzh.rearscreen.platform.ScenePresentationSnapshot
import dev.remrss.scene.geometry.QuarterTurnSceneContainer

@RunWith(AndroidJUnit4::class)
class ModSceneWindowPolicyTest {
    @get:Rule val compose = createAndroidComposeRule<NativeModuleRendererTestHostActivity>()

    @Test fun readyAndMeasuredWindowStayBlackUntilCoordinatorReportsVisible() {
        val policy = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = true,
            coordinatorVisible = false,
            keepVisibleRequested = true,
        )

        assertFalse(policy.showSceneContent)
        assertFalse(policy.keepScreenOn)
    }

    @Test fun readinessRequiresLatestNativeFrameAndStaleCompletionResumesOnlyAgainstThatFrame() {
        val applied = ModSceneContainerLayout(
            actualWidth = 200, actualHeight = 100, actualLeft = 0, actualTop = 20,
            expectedWidth = 200, expectedHeight = 100, expectedLeft = 0, expectedTop = 20,
            contentApplied = true,
        )
        assertTrue(applied.matchesCurrentGeometry())
        assertFalse("old size cannot satisfy a resized SAFE/FULL target", applied.copy(expectedHeight = 120).matchesCurrentGeometry())
        assertFalse("old origin cannot satisfy a moved cutout-safe target", applied.copy(expectedTop = 0).matchesCurrentGeometry())

        assertEquals(ModSceneStaleReportAction.RESUBMIT_LATEST, modSceneStaleReportAction(4, 5, latestLayoutApplied = true))
        assertEquals(ModSceneStaleReportAction.WAIT_FOR_LAYOUT, modSceneStaleReportAction(4, 5, latestLayoutApplied = false))
        assertNull(modSceneStaleReportAction(5, 5, latestLayoutApplied = true))
    }

    @Test fun stoppedRequestCannotClearOrStealTheResumedRequest() {
        val requests = ModSceneWindowReportRequests()
        assertNull("an inactive Activity must not reserve local in-flight state", requests.begin(activityStarted = false, layoutApplied = true))
        val stoppedToken = requireNotNull(requests.begin(activityStarted = true, layoutApplied = true))
        requests.invalidate() // onStop detaches this Activity callback
        assertFalse(requests.isInFlight)

        val resumedToken = requireNotNull(requests.begin(activityStarted = true, layoutApplied = true))
        assertFalse("late completion from before stop is stale", requests.finish(stoppedToken))
        assertTrue("stale completion must not clear the resumed request", requests.isInFlight)
        assertTrue(requests.finish(resumedToken))
        assertFalse(requests.isInFlight)
    }

    @Test fun viewportRevisionIgnoresOnlyRevisionAndDetectsFullMetadataChanges() {
        val base = top.zainzh.rearscreen.platform.RearViewportSnapshot(
            revision = 3L,
            displayId = 1,
            physicalDisplayPx = top.zainzh.rearscreen.platform.PixelSize(1200, 800),
            contentBoundsPx = top.zainzh.rearscreen.platform.PixelRect(0, 0, 1200, 800),
            windowPx = top.zainzh.rearscreen.platform.PixelSize(1200, 800),
            windowDp = top.zainzh.rearscreen.platform.DpSize(400f, 266.67f),
            densityDpi = 240,
            renderBufferPx = null,
            displayRotation = 0,
            contentRotation = 0,
            inputTransform = top.zainzh.rearscreen.platform.CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f),
            region = dev.remrss.scene.geometry.SceneRegion.FULL,
            cutoutKnowledge = dev.remrss.scene.geometry.CutoutKnowledge.KNOWN_NONE,
        )
        val geometry = ModSceneWindowGeometry(base, 0, 0, 0, 0, insetsKnown = true)
        assertFalse(modSceneViewportGeometryChanged(geometry, geometry.copy(viewport = base.copy(revision = 4L))))
        assertTrue(modSceneViewportGeometryChanged(geometry, geometry.copy(viewport = base.copy(densityDpi = 320))))
        assertTrue(modSceneViewportGeometryChanged(geometry, geometry.copy(viewport = base.copy(displayRotation = 1))))
        assertTrue(modSceneViewportGeometryChanged(geometry, geometry.copy(
            viewport = base.copy(
                cutoutKnowledge = dev.remrss.scene.geometry.CutoutKnowledge.KNOWN_PRESENT,
                cutoutBoundsPx = listOf(dev.remrss.scene.geometry.PixelRect(0, 0, 80, 30)),
            ),
        )))
        assertTrue("FULL bounds remain unchanged, but cutout safety metadata still advances geometry", modSceneViewportGeometryChanged(
            geometry,
            geometry.copy(safeTopPx = 60),
        ))
        assertTrue(modSceneViewportGeometryChanged(geometry, geometry.copy(insetsKnown = false)))
    }

    @Test fun geometryInvalidationRejectsOldCoreVisibleSnapshotUntilFreshRequery() {
        val gate = ModSceneWindowLifecycleGate("session-layout", 4L) {}
        gate.onActivityStarted(true)
        gate.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
        val oldQueryEpoch = gate.currentWindowRequestEpoch()
        assertTrue(gate.applyPresentationSnapshot(
            ScenePresentationSnapshot("session-layout", 4L, 8L, visible = true, hiddenReason = null),
            oldQueryEpoch,
        ))
        assertTrue(gate.currentState().contentVisible)

        val invalidationEpoch = gate.currentWindowRequestEpoch()
        gate.invalidateForGeometryChange()
        assertTrue(gate.currentWindowRequestEpoch() > invalidationEpoch)
        assertFalse("old visible lease cannot reopen the invalidated geometry", gate.currentState().contentVisible)
        assertFalse("an already-running query from the old geometry is rejected", gate.applyPresentationSnapshot(
            ScenePresentationSnapshot("session-layout", 4L, 8L, visible = true, hiddenReason = null),
            oldQueryEpoch,
        ))

        val freshQueryEpoch = gate.currentWindowRequestEpoch()
        assertTrue(gate.applyPresentationSnapshot(
            ScenePresentationSnapshot("session-layout", 4L, 9L, visible = true, hiddenReason = null),
            freshQueryEpoch,
        ))
        assertTrue("fresh visibility after new geometry acceptance may reopen the scene", gate.currentState().contentVisible)
    }

    @Test fun keepVisibleOnlyControlsFlagAfterBothVisibilitySourcesAgree() {
        val keepVisible = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = true,
            coordinatorVisible = true,
            keepVisibleRequested = true,
        )
        val ordinary = keepVisible.copy(keepVisibleRequested = false)

        assertTrue(keepVisible.showSceneContent)
        assertTrue(keepVisible.keepScreenOn)
        assertTrue(ordinary.showSceneContent)
        assertFalse(ordinary.keepScreenOn)
    }

    @Test fun aWindowThatLostActualVisibilityIsCoveredEvenIfCoreHasNotDeliveredTheEventYet() {
        val policy = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = false,
            coordinatorVisible = true,
            keepVisibleRequested = true,
        )

        assertFalse(policy.showSceneContent)
        assertFalse(policy.keepScreenOn)
    }

    @Test fun mainDisplayInputFocusDoesNotClearVisibleRearKeepScreenPolicy() {
        // Input focus is deliberately not a policy input: the main display can
        // own input while the rear Activity window remains actually visible.
        val rearStillVisible = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = true,
            coordinatorVisible = true,
            keepVisibleRequested = true,
        )

        assertTrue(rearStillVisible.showSceneContent)
        assertTrue(rearStillVisible.keepScreenOn)
        val trulyCoveredByCore = rearStillVisible.copy(coordinatorVisible = false)
        assertFalse(trulyCoveredByCore.showSceneContent)
        assertFalse(trulyCoveredByCore.keepScreenOn)
    }

    @Test fun hiddenSceneSurfaceRendersSolidBlackOverItsContent() {
        compose.setContent {
            ModSceneVisibilitySurface(visible = false) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(Color.Red)
                )
            }
        }

        val image = compose.onRoot().captureToImage()
        val bitmap = image.asAndroidBitmap()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mod-scene-hidden-black.png")
        FileOutputStream(screenshot).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val pixels = image.toPixelMap()
        assertTrue(pixels.width > 0 && pixels.height > 0)
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                assertTrue("expected black at ($x,$y), got ${pixels[x, y]}", pixels[x, y].toArgb() == AndroidColor.BLACK)
            }
        }
    }

    @Test fun hostGeometryCanLayOutWhileContentIsGatedAndContentAppearsOnlyAfterVisibility() {
        var hostWidth = 0
        var hiddenContentComposed = false
        val visible = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().onGloballyPositioned { hostWidth = it.size.width }
            ) {
                ModSceneVisibilitySurface(visible = visible.value) {
                    hiddenContentComposed = true
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }

        compose.waitForIdle()
        assertTrue("host layout must be measurable during black bootstrap", hostWidth > 0)
        assertFalse("gated scene content must not be composed during bootstrap", hiddenContentComposed)
        assertFalse(ModSceneWindowPolicy(
            windowReady = false,
            windowAttachedAndVisible = true,
            coordinatorVisible = false,
            keepVisibleRequested = false,
        ).showSceneContent)

        val container = arrayOfNulls<QuarterTurnSceneContainer>(1)
        val child = arrayOfNulls<View>(1)
        compose.runOnUiThread {
            container[0] = QuarterTurnSceneContainer(compose.activity).apply {
                contentRotation = 1
                child[0] = View(compose.activity)
                addView(child[0])
            }
            compose.activity.addContentView(container[0], FrameLayout.LayoutParams(200, 100))
        }
        compose.waitForIdle()
        assertTrue("native host must apply the real quarter-turn child layout", container[0]?.hasAppliedContentLayout == true)
        assertEquals(100, child[0]?.measuredWidth)
        assertEquals(200, child[0]?.measuredHeight)

        compose.runOnUiThread {
            visible.value = ModSceneWindowPolicy(
                windowReady = true,
                windowAttachedAndVisible = true,
                coordinatorVisible = true,
                keepVisibleRequested = false,
            ).showSceneContent
        }
        compose.waitForIdle()
        assertTrue("content is composed only after the host opens the visibility gate", hiddenContentComposed)
        compose.runOnUiThread { (container[0]?.parent as? android.view.ViewGroup)?.removeView(container[0]) }
    }
}
