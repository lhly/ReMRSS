package top.zainzh.rearscreen.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionCommitHandoffTest {
    @Test fun controlledCommitRunsTakeoverWithoutRewritingExactOwner() {
        var ownerWrites = 0
        var takeovers = 0
        dispatchProjectionCommitEffects(
            controlledOwner = true,
            writeLegacyOwner = { ownerWrites++ },
            takeOverPresentation = { takeovers++ },
        )
        assertEquals("controlled exact record remains untouched", 0, ownerWrites)
        assertEquals("Scene/animation takeover still runs", 1, takeovers)
    }

    @Test fun legacyCommitStillWritesItsOwnerBeforeSharedTakeover() {
        val order = mutableListOf<String>()
        dispatchProjectionCommitEffects(
            controlledOwner = false,
            writeLegacyOwner = { order += "legacy_owner" },
            takeOverPresentation = { order += "takeover" },
        )
        assertEquals(listOf("legacy_owner", "takeover"), order)
    }
}
