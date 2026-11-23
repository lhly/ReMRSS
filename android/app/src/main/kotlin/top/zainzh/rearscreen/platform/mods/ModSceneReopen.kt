package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import top.zainzh.rearscreen.platform.CapabilityRequirement
import top.zainzh.rearscreen.platform.SceneRequest
import top.zainzh.rearscreen.platform.RearRegion
import top.zainzh.rearscreen.platform.SceneError
import top.zainzh.rearscreen.platform.SessionOwner

/** The host's “show on rear” button selects SAFE, not a content rotation. */
internal fun initialModSceneRequest(component: ComponentName, grants: Set<String>): SceneRequest {
    val keepVisible = "rear.scene.keep_visible" in grants
    return SceneRequest(
        component = component,
        region = RearRegion.CAMERA_SAFE,
        keepVisible = keepVisible,
        hasExplicitContentRotation = false,
        requiredCapabilities = buildSet {
            add(CapabilityRequirement("rear.scene", 1))
            if (keepVisible) add(CapabilityRequirement("rear.scene.keep_visible", 1))
        },
    )
}

/** Host-only route for reopening the exact live lease owned by an installed Mod. */
internal interface ModSceneReopenCoordinator {
    fun reopenOwnedModScene(
        owner: SessionOwner,
        component: ComponentName,
        grantedCapabilities: Set<String>,
        isInstalledOwnerCurrent: () -> Boolean,
    ): ModSceneReopenResult
}

internal enum class ModSceneReopenPendingReason {
    TASK_VISIBILITY_AMBIGUOUS,
    SCENE_WINDOW_NOT_VISIBLE,
    PRESENTATION_UNVERIFIED,
}

internal sealed interface ModSceneReopenResult {
    data object NoExistingSession : ModSceneReopenResult
    data object Busy : ModSceneReopenResult
    data class Pending(
        val sessionId: String,
        val generation: Long,
        val reason: ModSceneReopenPendingReason,
    ) : ModSceneReopenResult
    data class Resumed(
        val sessionId: String,
        val generation: Long,
        val region: RearRegion,
    ) : ModSceneReopenResult
    data class Rejected(val error: SceneError) : ModSceneReopenResult
}
