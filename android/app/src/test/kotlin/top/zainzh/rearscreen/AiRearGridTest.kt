package top.zainzh.rearscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiRearGridTest {
    @Test fun togglingListNeverAddsATileOrLosesSixthBinding() {
        val desktop = (0..5).fold(AiRearGrid()) { grid, i -> grid.bind(i, "app$i/.Main") }
        val mixed = desktop.copy(allApps = true)
        assertEquals((0..4).map(desktop::tile) + AiRearGrid.Tile.AllApps, (0..5).map(mixed::tile))
        assertEquals(AiRearGrid.Tile.App("app5/.Main"), mixed.copy(allApps = false).tile(5))
        assertThrows(IllegalArgumentException::class.java) { mixed.tile(6) }
        assertThrows(IllegalArgumentException::class.java) { mixed.bind(5, null) }
    }
}
