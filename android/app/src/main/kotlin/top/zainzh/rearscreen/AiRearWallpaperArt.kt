package top.zainzh.rearscreen

/** Artwork tokens shared by the live editor and the generated MAML, independent of app theme. */
internal object AiRearWallpaperArt {
    val referenceViewport = AiRearWallpaperArchive.Viewport(976, 596, 296, 0, 0, 0)
    const val WALL = 0xFF141D27L
    const val PHOTO_SHADE = 0x30081220L
    const val OUTLINE = 0x36FFFFFFL
    const val PRESSED_OUTLINE = 0x66FFFFFFL
    const val TEXT = 0xFFFFFFFFL

    data class Face(
        val inset: Int, val radius: Int, val icon: Int, val iconTop: Int,
        val labelY: Int, val textSize: Int, val surface: Long,
    )

    fun face(w: Int, h: Int, icon: Boolean, library: Boolean, pressed: Boolean = false): Face {
        val size = minOf(w, h)
        return Face(
            inset = if (pressed) maxOf(3, size * 3 / 100) else 0,
            radius = (size * 14 / 100).coerceIn(12, 34),
            icon = size * (if (pressed) 36 else 38) / 100,
            iconTop = h * (if (pressed) 22 else 20) / 100,
            labelY = if (icon) h * 76 / 100 else h / 2,
            textSize = minOf(32, w / 5, h / 6),
            surface = when {
                library && pressed -> 0xF0365A85L
                library -> 0xE12D496AL
                pressed -> 0xF1435366L
                else -> 0xD9222F3DL
            },
        )
    }

    fun mamlColor(argb: Long) = "#" + argb.toString(16).uppercase().padStart(8, '0')
}
