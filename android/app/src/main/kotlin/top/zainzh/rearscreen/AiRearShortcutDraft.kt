package top.zainzh.rearscreen

/** The editor orders entries; export and activation still use the six stable legacy slots. */
internal data class AiRearShortcutDraft(
    val entries: List<Entry> = emptyList(),
    val allApps: Boolean = false,
    private val nextKey: Int = LIMIT,
    private val untouchedSlots: List<String?>? = null,
) {
    data class Entry(val key: Int, val component: String)

    val full get() = entries.size >= LIMIT
    val grid get() = AiRearGrid(untouchedSlots ?: List(LIMIT) { entries.getOrNull(it)?.component }, allApps)

    fun parked(index: Int): Boolean {
        val entry = entries.getOrNull(index) ?: return false
        val slot = if (untouchedSlots == null) index else entry.key
        return allApps && slot == LIMIT - 1
    }

    fun add(component: String): AiRearShortcutDraft =
        if (full || entries.any { it.component == component }) this
        else copy(entries = entries + Entry(nextKey, component), nextKey = nextKey + 1, untouchedSlots = null)

    fun remove(key: Int): AiRearShortcutDraft =
        if (entries.none { it.key == key }) this
        else copy(entries = entries.filterNot { it.key == key }, untouchedSlots = null)

    fun move(key: Int, to: Int): AiRearShortcutDraft {
        val from = entries.indexOfFirst { it.key == key }
        if (from == -1 || to !in entries.indices || from == to) return this
        return copy(entries = entries.toMutableList().apply { add(to, removeAt(from)) }, untouchedSlots = null)
    }

    fun withAllApps(enabled: Boolean): AiRearShortcutDraft =
        if (enabled == allApps) this else copy(allApps = enabled, untouchedSlots = null)

    companion object {
        const val LIMIT = 6

        fun fromGrid(grid: AiRearGrid) = AiRearShortcutDraft(
            grid.apps.mapIndexedNotNull { index, component -> component?.let { Entry(index, it) } },
            grid.allApps, untouchedSlots = grid.apps,
        )
    }
}
