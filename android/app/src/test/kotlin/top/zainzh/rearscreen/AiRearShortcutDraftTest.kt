package top.zainzh.rearscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRearShortcutDraftTest {
    private fun app(i: Int) = "app$i/.Main"

    @Test fun sixthKeepsItsPlaceAndMovesWithoutLosingAnEntry() {
        val full = (0..5).fold(AiRearShortcutDraft()) { draft, i -> draft.add(app(i)) }
        assertTrue(full.full)
        assertSame(full, full.add(app(6)))
        val mixed = full.withAllApps(true)
        assertEquals(full.entries, mixed.entries)
        assertTrue(mixed.parked(5))
        assertEquals(AiRearGrid.Tile.AllApps, mixed.grid.tile(5))
        val moved = mixed.move(full.entries[5].key, 1)
        assertEquals(listOf(app(0), app(5), app(1), app(2), app(3), app(4)), moved.entries.map { it.component })
        assertEquals(AiRearGrid.Tile.App(app(5)), moved.grid.tile(1))
        assertEquals(AiRearGrid.Tile.App(app(4)), moved.withAllApps(false).grid.tile(5))
        val cleared = moved.remove(moved.entries[1].key)
        assertFalse(cleared.full)
        assertFalse(cleared.parked(4))
        assertTrue(cleared.grid.isReady { true })
        assertFalse(cleared.withAllApps(false).grid.isReady { true })
    }

    @Test fun editingLegacyDraftKeepsDuplicatesAndPacksOnlyOnARealChange() {
        val legacy = AiRearGrid(listOf(null, app(0), null, app(0), null, app(2)), allApps = true)
        val opened = AiRearShortcutDraft.fromGrid(legacy)
        assertEquals(legacy, opened.grid)
        assertEquals(listOf(1, 3, 5), opened.entries.map { it.key })
        assertTrue(opened.parked(2)) // This sparse pre-upgrade draft still shadows its original sixth slot.
        assertSame(opened, opened.add(app(0)))
        assertSame(opened, opened.move(3, 1))
        val changed = opened.add(app(3))
        assertEquals(listOf(app(0), app(0), app(2), app(3), null, null), changed.grid.apps)
        assertFalse(changed.parked(2))
        assertEquals(listOf(app(0), app(2), app(3)), changed.remove(1).entries.map { it.component })
        assertEquals(legacy, opened.grid)
    }

    @Test fun draggedCenterCrossesIntoAnotherRowDespiteTheInitialTouchSlop() {
        val order = AiRearShortcutOrder(8f)
        val keys = listOf(0, 1, 2)
        for (key in keys) order.measure(key, 64)
        order.start(2, 176f, keys)
        order.move(2, -56f)
        val placement = checkNotNull(order.placement(keys))
        assertEquals(1, placement.to)
        assertEquals(72f, placement.shift(1), 0f)
        order.stop(2)
        assertEquals(null, order.placement(keys))
    }
}
