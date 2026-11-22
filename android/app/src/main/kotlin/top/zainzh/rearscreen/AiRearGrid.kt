package top.zainzh.rearscreen

/** Six stable slot IDs. Visible tiles are compacted for display, not renumbered for activation. */
internal data class AiRearGrid(
    val apps: List<String?> = List(6) { null },
    val allApps: Boolean = false,
) {
    init { require(apps.size == 6) }

    sealed interface Tile {
        data class App(val component: String) : Tile
        data object Empty : Tile
        data object AllApps : Tile
    }

    fun tile(index: Int): Tile {
        require(index in apps.indices)
        if (index == 5 && allApps) return Tile.AllApps
        return apps[index]?.let { Tile.App(it) } ?: Tile.Empty
    }

    fun visibleShortcutCapacity(limit: Int = apps.size, reserveAllApps: Boolean = true): Int =
        if (reserveAllApps && allApps) minOf(limit, apps.lastIndex) else limit

    fun boundShortcutCount(capacity: Int, available: (String) -> Boolean): Int =
        apps.take(capacity).count { it?.let(available) == true }

    val appCapacity get() = visibleShortcutCapacity()
    val occupiedSlots get() = apps.indices.filter { tile(it) != Tile.Empty }

    fun isReady(available: (String) -> Boolean): Boolean {
        val occupied = occupiedSlots
        return occupied.isNotEmpty() && occupied.all { index ->
            when (val item = tile(index)) {
                is Tile.App -> available(item.component)
                Tile.AllApps -> true
                Tile.Empty -> false
            }
        }
    }

    /** Signed six-slot templates still require exactly the layout their asset path declares. */
    fun isFull(available: (String) -> Boolean): Boolean =
        apps.take(appCapacity).all { it?.let(available) == true }

    fun bind(index: Int, component: String?): AiRearGrid {
        require(index in apps.indices && !(index == 5 && allApps))
        return copy(apps = apps.toMutableList().also { it[index] = component })
    }
}
