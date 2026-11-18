package top.zainzh.rearscreen

/** Six stable settings slots; List is a view of slot six, not a seventh slot or a destructive edit. */
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

    fun isReady(available: (String) -> Boolean): Boolean =
        (0 until if (allApps) 5 else 6).all { apps[it]?.let(available) == true }

    fun bind(index: Int, component: String?): AiRearGrid {
        require(index in apps.indices && !(index == 5 && allApps))
        return copy(apps = apps.toMutableList().also { it[index] = component })
    }
}
