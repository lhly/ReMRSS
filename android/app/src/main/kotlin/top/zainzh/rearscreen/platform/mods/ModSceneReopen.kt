package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import top.zainzh.rearscreen.platform.RearRegion
import top.zainzh.rearscreen.platform.SceneError
import top.zainzh.rearscreen.platform.SessionOwner

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
