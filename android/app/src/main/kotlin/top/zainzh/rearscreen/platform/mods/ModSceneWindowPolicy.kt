package top.zainzh.rearscreen.platform.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.zainzh.rearscreen.platform.RearViewportSnapshot
import top.zainzh.rearscreen.platform.ScenePresentationSnapshot

/** Derived Mod scene presentation state; Core visibility and the measured Activity window are independent inputs. */
internal data class ModSceneWindowPolicy(
    val windowReady: Boolean,
    val windowAttachedAndVisible: Boolean,
    val coordinatorVisible: Boolean,
    val keepVisibleRequested: Boolean,
) {
    val showSceneContent: Boolean
        get() = windowReady && windowAttachedAndVisible && coordinatorVisible

    val keepScreenOn: Boolean
        get() = showSceneContent && keepVisibleRequested
}

/** A measured native rectangle must match its latest requested frame before it can report READY. */
internal data class ModSceneContainerLayout(
    val actualWidth: Int,
    val actualHeight: Int,
    val actualLeft: Int,
    val actualTop: Int,
    val expectedWidth: Int,
    val expectedHeight: Int,
    val expectedLeft: Int,
    val expectedTop: Int,
    val contentApplied: Boolean,
) {
    fun matchesCurrentGeometry(): Boolean = contentApplied && expectedWidth > 0 && expectedHeight > 0 &&
        actualWidth == expectedWidth && actualHeight == expectedHeight &&
        actualLeft == expectedLeft && actualTop == expectedTop
}

internal enum class ModSceneStaleReportAction { RESUBMIT_LATEST, WAIT_FOR_LAYOUT }

/** Reported viewport plus raw cutout facts that FULL-region bounds intentionally do not encode. */
internal data class ModSceneWindowGeometry(
    val viewport: RearViewportSnapshot,
    val safeLeftPx: Int?,
    val safeTopPx: Int?,
    val safeRightPx: Int?,
    val safeBottomPx: Int?,
    val insetsKnown: Boolean,
)

/** Core revisions differ across reports; all other viewport fields and measured cutout facts are geometry. */
internal fun modSceneViewportGeometryChanged(
    previous: ModSceneWindowGeometry?,
    current: ModSceneWindowGeometry,
): Boolean = previous?.copy(viewport = previous.viewport.copy(revision = 0L)) !=
    current.copy(viewport = current.viewport.copy(revision = 0L))

/** A stale Binder completion is consumed, then either retried against current layout or left to its layout edge. */
internal fun modSceneStaleReportAction(
    submittedGeometryEpoch: Long,
    currentGeometryEpoch: Long,
    latestLayoutApplied: Boolean,
): ModSceneStaleReportAction? = when {
    submittedGeometryEpoch == currentGeometryEpoch -> null
    latestLayoutApplied -> ModSceneStaleReportAction.RESUBMIT_LATEST
    else -> ModSceneStaleReportAction.WAIT_FOR_LAYOUT
}

/** Owns one Activity's callback across stop/start; stale completions cannot clear a resumed request. */
internal class ModSceneWindowReportRequests {
    private var nextToken = 0L
    private var inFlightToken: Long? = null

    val isInFlight: Boolean get() = inFlightToken != null

    fun begin(activityStarted: Boolean, layoutApplied: Boolean): Long? {
        if (!activityStarted || !layoutApplied || isInFlight) return null
        return (++nextToken).also { inFlightToken = it }
    }

    fun invalidate() {
        nextToken++
        inFlightToken = null
    }

    fun finish(token: Long): Boolean {
        if (inFlightToken != token) return false
        inFlightToken = null
        return true
    }
}

/** Deduplicates bounded state/attribute diagnostics for the current Activity Window policy. */
internal class ModSceneWindowPolicyTraceGate {
    private var lastStateKey: String? = null
    private var lastAppliedKeepFlag: Boolean? = null

    fun shouldLog(stateKey: String, flagBefore: Boolean, flagAfter: Boolean): Boolean {
        val shouldLog = lastStateKey != stateKey || lastAppliedKeepFlag != flagBefore || flagBefore != flagAfter
        lastStateKey = stateKey
        lastAppliedKeepFlag = flagAfter
        return shouldLog
    }
}

/** A configuration recreation keeps the lease; destroying the current Activity does not. */
internal fun shouldCloseModSceneOnDestroy(
    isCurrentInstance: Boolean,
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Boolean = isCurrentInstance && (isFinishing || !isChangingConfigurations)

/** Shared by the real rear Activity and its instrumentation check: hidden scenes are fully black. */
@Composable
internal fun ModSceneVisibilitySurface(visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (visible) content() else Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * Observes real Activity/window edges independently from input focus and Core's
 * effective visibility. Coordinator events only trigger a current snapshot read;
 * a snapshot request captured before a local start/stop edge cannot reopen the
 * window after that edge, even if its callback arrives late.
 */
internal class ModSceneWindowLifecycleGate(
    private val sessionId: String,
    private val generation: Long,
    private val onStateChanged: (State) -> Unit,
) {
    data class State(
        val activityStarted: Boolean,
        val windowVisible: Boolean,
        val coordinatorVisible: Boolean,
        val presentationValid: Boolean,
        val presentationRevision: Long?,
        val windowRequestEpoch: Long,
        val contentVisible: Boolean,
    )

    private var activityStarted = false
    private var attached = false
    private var viewShown = false
    private var windowVisible = false
    private var presentationValid = false
    private var presentationVisible = false
    private var presentationRevision: Long? = null
    private var windowRequestEpoch = 0L
    private var lastState: State? = null

    fun onActivityStarted(started: Boolean) {
        if (activityStarted != started) invalidatePresentationForWindowEpoch()
        activityStarted = started
        update()
    }

    fun onWindowStateChanged(attached: Boolean, viewShown: Boolean, windowVisible: Boolean) {
        val wasVisible = rawWindowVisible()
        this.attached = attached
        this.viewShown = viewShown
        this.windowVisible = windowVisible
        val isVisible = rawWindowVisible()
        if (wasVisible != isVisible) invalidatePresentationForWindowEpoch()
        update()
    }

    fun currentWindowRequestEpoch(): Long = windowRequestEpoch

    /** Explain why a current authoritative snapshot was discarded, for bounded diagnostics. */
    fun rejectionReason(snapshot: ScenePresentationSnapshot?, requestEpoch: Long): String? = when {
        requestEpoch != windowRequestEpoch -> "WINDOW_EPOCH_STALE"
        snapshot == null -> "PRESENTATION_MISSING"
        snapshot.sessionId != sessionId || snapshot.generation != generation -> "BINDING_MISMATCH"
        presentationRevision != null && snapshot.revision < presentationRevision!! -> "REVISION_OLDER"
        presentationRevision == snapshot.revision && presentationValid && snapshot.visible != presentationVisible ->
            "REVISION_CONFLICT"
        else -> null
    }

    /** Geometry changed locally; invalidate old Core visibility snapshots without querying the lease. */
    fun invalidateForGeometryChange() {
        invalidatePresentationForWindowEpoch()
        update()
    }

    /** Apply only a fresh Core read for this exact Activity window epoch. Events never carry authority. */
    fun applyPresentationSnapshot(snapshot: ScenePresentationSnapshot?, requestEpoch: Long): Boolean {
        if (rejectionReason(snapshot, requestEpoch) == "WINDOW_EPOCH_STALE") return false
        if (snapshot == null) {
            // A missing/unauthorized lease is fail-closed and invalidates any
            // concurrent query that was captured against this local window state.
            windowRequestEpoch++
            presentationValid = false
            presentationVisible = false
            update()
            return true
        }
        if (rejectionReason(snapshot, requestEpoch) != null) return false
        presentationValid = true
        presentationVisible = snapshot.visible
        presentationRevision = snapshot.revision
        val previousState = lastState
        update()
        // A platform Window flag can be lost independently of this in-memory gate. Every
        // accepted Core snapshot is also an effect-reconciliation point, even when the logical
        // state compares equal and update() would otherwise suppress its callback.
        if (lastState == previousState) onStateChanged(state())
        return true
    }

    fun currentState(): State = state()

    private fun rawWindowVisible(): Boolean = activityStarted && attached && viewShown && windowVisible

    private fun invalidatePresentationForWindowEpoch() {
        windowRequestEpoch++
        presentationValid = false
        presentationVisible = false
    }

    private fun state(): State {
        val actualWindowVisible = rawWindowVisible()
        val coordinatorVisible = presentationValid && presentationVisible
        val contentVisible = actualWindowVisible && coordinatorVisible
        return State(
            activityStarted = activityStarted,
            windowVisible = actualWindowVisible,
            coordinatorVisible = coordinatorVisible,
            presentationValid = presentationValid,
            presentationRevision = presentationRevision,
            windowRequestEpoch = windowRequestEpoch,
            contentVisible = contentVisible,
        )
    }

    private fun update() {
        val next = state()
        if (next != lastState) {
            lastState = next
            onStateChanged(next)
        }
    }
}
