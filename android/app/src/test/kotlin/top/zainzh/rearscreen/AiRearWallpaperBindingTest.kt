package top.zainzh.rearscreen

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test

class AiRearWallpaperBindingTest {
    private val apps = List(6) { "com.example.app$it/.Launcher" }
    private val grid = AiRearGrid(apps)
    private val token = "a".repeat(32)

    @Test fun appliedSlotsDoNotFollowLaterDraftEdits() {
        val plain = AiRearWallpaperBinding("local_work", token, grid)
        val mixed = AiRearWallpaperBinding("local_work", token, grid.copy(allApps = true))
        val edited = grid.bind(5, "com.example.newapp/.Launcher")
        val loaded = AiRearWallpaperBinding.decode(plain.encode())!!
        assertEquals(AiRearGrid.Tile.App(apps[5]), loaded.tile(token, "/5", "local_work"))
        assertNotEquals(edited.tile(5), loaded.tile(token, "/5", "local_work"))
        assertEquals(AiRearGrid.Tile.AllApps,
            AiRearWallpaperBinding.decode(mixed.encode())!!.tile(token, "/5", "local_work"))
        for (index in 0..4) assertEquals(AiRearGrid.Tile.App(apps[index]),
            mixed.tile(token, "/$index", "local_work"))
    }

    @Test fun tokenWallpaperAndCanonicalSlotMustAllMatch() {
        val binding = AiRearWallpaperBinding("local_work", token, grid)
        for (path in listOf(null, "", "/", "/00", "/6", "/-1", "/%35", "/1?app=other", "/0/1"))
            assertNull(binding.tile(token, path, "local_work"))
        assertNull(binding.tile("b".repeat(32), "/0", "local_work"))
        assertNull(binding.tile(token, "/0", "different"))
        assertNull(binding.tile(token, "/0", null))
        assertNull(AiRearWallpaperBinding.decode("{\"apps\":[]}"))
    }

    @Test fun everyVisibleAppMustSupportItsOwnTaskButHiddenSixthIsRetained() {
        val partial = grid.bind(5, null)
        assertFalse(partial.isFull { true })
        assertTrue(partial.isReady { true })
        assertTrue(partial.copy(allApps = true).isReady { true })
        assertFalse(partial.copy(allApps = true).isReady { it != apps[4] })
        assertTrue(grid.copy(allApps = true).isReady { it != apps[5] })
        assertFalse(grid.isReady { it != apps[5] })
        assertThrows(IllegalArgumentException::class.java) {
            AiRearWallpaperBinding("local_work", token, grid.bind(0, "not a component"))
        }
    }

    @Test fun sparseBindingKeepsSlotIdsAndNeverResolvesEmptySlots() {
        val sparse = AiRearGrid().bind(1, apps[1]).bind(4, apps[4]).copy(allApps = true)
        val applied = AiRearWallpaperBinding.decode(AiRearWallpaperBinding("work", token, sparse).encode())!!
        assertEquals(listOf(1, 4, 5), applied.grid.occupiedSlots)
        assertEquals(AiRearGrid.Tile.App(apps[1]), applied.tile(token, "/1", "work"))
        assertEquals(AiRearGrid.Tile.App(apps[4]), applied.tile(token, "/4", "work"))
        assertEquals(AiRearGrid.Tile.AllApps, applied.tile(token, "/5", "work"))
        assertNull(applied.tile(token, "/0", "work"))
        assertNull(applied.tile(token, "/2", "work"))
        assertFalse(AiRearGrid().isReady { true })
        assertTrue(AiRearGrid(allApps = true).isReady { false })
        assertEquals(listOf(5), AiRearGrid(allApps = true).occupiedSlots)
    }

    @Test fun independentTaskModesDoNotAdoptAnExistingMainTask() {
        val accepted = AiRearAppCatalog::supportsIndependentTask
        val documentNone = ActivityInfo.DOCUMENT_LAUNCH_NONE
        assertTrue(accepted(ActivityInfo.LAUNCH_MULTIPLE, documentNone))
        assertTrue(accepted(ActivityInfo.LAUNCH_SINGLE_TOP, documentNone))
        assertFalse(accepted(ActivityInfo.LAUNCH_SINGLE_TASK, documentNone))
        assertFalse(accepted(ActivityInfo.LAUNCH_SINGLE_INSTANCE, documentNone))
        assertFalse(accepted(ActivityInfo.LAUNCH_MULTIPLE, ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING))
    }
}
