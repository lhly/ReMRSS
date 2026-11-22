package top.zainzh.rearscreen

enum class ModWallpaperThemeKind { GRID_TEMPLATE, DRAWN_CONTROLS }

/** Signed artwork only; the host supplies MAML, app bindings, and all activation behavior. */
internal class AiRearModWallpaperTheme private constructor(
    val backdrop: ByteArray,
    val art: List<Button>,
    val defaultAllApps: Boolean = false,
) {
    val buttons get() = if (art.isEmpty()) 6 else art.size

    fun validGrid(grid: AiRearGrid, canCast: (String) -> Boolean): Boolean =
        if (art.isEmpty()) grid.isFull(canCast) else validControls(grid, buttons, canCast)

    data class Button(val index: Int, val x: Int, val y: Int, val width: Int, val height: Int, val png: ByteArray) {
        fun cell(v: AiRearWallpaperArchive.Viewport): AiRearWallpaperArchive.Cell {
            val safeWidth = v.width - v.left - v.right
            val safeHeight = v.height - v.top - v.bottom
            val left = safeWidth * x / 1000
            val top = safeHeight * y / 1000
            return AiRearWallpaperArchive.Cell(v.left + left, v.top + top,
                safeWidth * (x + width) / 1000 - left, safeHeight * (y + height) / 1000 - top)
        }
    }

    companion object {
        const val PREFIX = "assets/rear-wallpaper/controls/"
        private const val BACKDROP = "background.png"
        private val buttonPath = Regex("([1-6])-([0-9]{1,3})-([0-9]{1,3})-([0-9]{3})-([0-9]{3})\\.png")

        fun classify(paths: Collection<String>): ModWallpaperThemeKind? = when {
            paths.any { it.startsWith(PREFIX) } -> ModWallpaperThemeKind.DRAWN_CONTROLS
            AiRearWallpaperTemplate.SIX_APPS in paths || AiRearWallpaperTemplate.APPS_AND_LIST in paths ->
                ModWallpaperThemeKind.GRID_TEMPLATE
            else -> null
        }

        fun fromVerifiedAssets(payloads: Map<String, ByteArray>): AiRearModWallpaperTheme {
            if (classify(payloads.keys) == ModWallpaperThemeKind.GRID_TEMPLATE) {
                val template = AiRearWallpaperTemplate.fromVerifiedAssets(payloads)
                return AiRearModWallpaperTheme(template.background, emptyList(), template.allApps)
            }
            require(AiRearWallpaperTemplate.SIX_APPS !in payloads && AiRearWallpaperTemplate.APPS_AND_LIST !in payloads)
            val assets = payloads.filterKeys { it.startsWith(PREFIX) }
            require(assets.size in 2..7)
            val background = requireNotNull(assets[PREFIX + BACKDROP]).also(::requirePng)
            val buttons = assets.filterKeys { it != PREFIX + BACKDROP }.map { (path, image) ->
                val parts = requireNotNull(buttonPath.matchEntire(path.removePrefix(PREFIX))).groupValues.drop(1).map(String::toInt)
                val (ordinal, x, y, w, h) = parts
                require(x >= 30 && y >= 30 && w >= 150 && h >= 150 && x + w <= 970 && y + h <= 970)
                requirePng(image)
                Button(ordinal - 1, x, y, w, h, image)
            }.sortedBy { it.index }
            require(buttons.map { it.index } == buttons.indices.toList())
            buttons.forEachIndexed { i, a -> buttons.drop(i + 1).forEach { b ->
                require(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y)
            } }
            return AiRearModWallpaperTheme(background, buttons)
        }

        private fun requirePng(bytes: ByteArray) {
            require(bytes.size in 16..(512 * 1024) && bytes.copyOf(8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        }

        fun validControls(grid: AiRearGrid, count: Int, canCast: (String) -> Boolean): Boolean =
            count in 1..6 && !grid.allApps && grid.apps.take(count).all { it != null && canCast(it) } &&
                grid.apps.drop(count).all { it == null }
    }
}
