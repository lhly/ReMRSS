package top.zainzh.rearscreen

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Overlays bounded host tiles on the selected local AI carrier. No module XML or caller intents. */
internal object AiRearWallpaperArchive {
    data class Viewport(val width: Int, val height: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        init {
            require(width in 400..3200 && height in 240..3200)
            require(listOf(left, top, right, bottom).all { it in 0..3200 })
            require(width - left - right >= 330 && height - top - bottom >= 216)
        }

        companion object {
            /** RearCastViewport exposes coordinates, not right/bottom inset distances. */
            fun fromCast(v: RearCastViewport) = Viewport(v.physicalWidth, v.physicalHeight, v.left, v.top,
                v.physicalWidth - v.right, v.physicalHeight - v.bottom)
        }
    }
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Badge(val label: String, val icon: ByteArray? = null)
    data class Activation(val packageName: String, val className: String, val binding: String) {
        init {
            require(qualified.matches(packageName) && qualified.matches(className))
            require(id.matches(binding))
        }
    }

    private val qualified = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val id = Regex("[a-f0-9]{32}")
    private const val assetPrefix = "remrss_ai/"
    private const val manifest = "manifest.xml"
    private const val manifestLimit = 1024 * 1024
    private const val pressedSlot = "remrssAiPressedSlot"
    private const val margin = 18
    private const val gap = 12

    fun cells(v: Viewport): List<Cell> {
        val availableWidth = v.width - v.left - v.right - 2 * margin - 2 * gap
        val availableHeight = v.height - v.top - v.bottom - 2 * margin - gap
        val cw = availableWidth / 3
        val ch = availableHeight / 2
        return List(6) { i ->
            Cell(v.left + margin + (i % 3) * (cw + gap), v.top + margin + (i / 3) * (ch + gap), cw, ch)
        }
    }

    fun write(
        original: File, out: OutputStream, physical: Viewport, grid: AiRearGrid, apps: Map<String, Badge>,
        listBadge: Badge, emptyText: String, backdropJpeg: ByteArray?, activation: Activation,
        themeArt: AiRearModWallpaperTheme? = null,
    ) {
        require(backdropJpeg == null || backdropJpeg.size in 1..(4 * 1024 * 1024))
        require(themeArt == null || themeArt.art.isNotEmpty() && themeArt.validGrid(grid, apps::containsKey))
        ZipFile(original).use { source ->
            val manifestEntry = source.getEntry(manifest) ?: throw IOException("NO_WALLPAPER_MANIFEST")
            if (source.getEntry("var_config.xml")?.isDirectory != false ||
                source.getEntry("assets/ai/ai.mp4")?.isDirectory != false ||
                source.entries().asSequence().any { it.name.startsWith(assetPrefix) }) {
                throw IOException("UNSUPPORTED_AI_WALLPAPER_CARRIER")
            }
            val originalMarkup = source.getInputStream(manifestEntry).use { it.readNBytes(manifestLimit + 1) }
            if (originalMarkup.size > manifestLimit) throw IOException("UNSUPPORTED_WALLPAPER_MANIFEST")
            val root = AiRearWallpaperFiles.document(originalMarkup)
            val screenWidth = root.getAttribute("screenWidth").toIntOrNull()?.takeIf { it in 400..3200 }
            val xml = originalMarkup.toString(Charsets.UTF_8).trimEnd()
            val closing = "</Widget>"
            if (root.tagName != "Widget" || screenWidth == null || !xml.endsWith(closing)) {
                throw IOException("UNSUPPORTED_WALLPAPER_MANIFEST")
            }
            val scale = screenWidth.toDouble() / physical.width
            val h = (physical.height * scale).roundToInt()
            val viewport = Viewport(screenWidth, h, (physical.left * scale).roundToInt(),
                (physical.top * scale).roundToInt(), screenWidth - ((physical.width - physical.right) * scale).roundToInt(),
                h - ((physical.height - physical.bottom) * scale).roundToInt())
            val assets = linkedMapOf<String, ByteArray>()
            val markup = buildString {
                // Retain official AI config, lifecycle and media references. Cover that artwork visually.
                append(xml.removeSuffix(closing))
                append("<Var name=\"$pressedSlot\" type=\"number\" expression=\"0\" const=\"true\"/>")
                append("<Rectangle x=\"0\" y=\"0\" w=\"${viewport.width}\" h=\"${viewport.height}\" fillColor=\"${AiRearWallpaperArt.mamlColor(AiRearWallpaperArt.WALL)}\"/>")
                if (themeArt != null) {
                    val image = "${assetPrefix}theme-backdrop.png"
                    assets[image] = themeArt.backdrop
                    image(image, viewport.width, viewport.height)
                } else if (backdropJpeg != null) {
                    val image = "${assetPrefix}background.jpg"
                    assets[image] = backdropJpeg
                    append("<Image x=\"0\" y=\"0\" w=\"${viewport.width}\" h=\"${viewport.height}\" src=\"$image\"/>")
                    append("<Rectangle x=\"0\" y=\"0\" w=\"${viewport.width}\" h=\"${viewport.height}\" fillColor=\"${AiRearWallpaperArt.mamlColor(AiRearWallpaperArt.PHOTO_SHADE)}\"/>")
                }
                if (themeArt != null) themeArt.art.forEach { art ->
                    val index = art.index
                    val cell = art.cell(viewport)
                    val src = "${assetPrefix}theme-button-$index.png"
                    assets[src] = art.png
                    val target = grid.tile(index) as AiRearGrid.Tile.App
                    val label = escape(apps.getValue(target.component).label)
                    button(cell, index, activation) { pressed ->
                        image(src, cell.width, cell.height, if (pressed) 174 else 255)
                        val text = artLabelSize(cell)
                        append("<Text x=\"${cell.width / 2}\" y=\"${artLabelY(cell)}\" align=\"center\" alignV=\"center\" w=\"${cell.width - 16}\" size=\"$text\" color=\"#ffffffff\" text=\"$label\"/>")
                    }
                } else cells(viewport).forEachIndexed { index, cell ->
                    val tile = grid.tile(index)
                    val badge = when (tile) {
                        AiRearGrid.Tile.AllApps -> listBadge
                        AiRearGrid.Tile.Empty -> null
                        is AiRearGrid.Tile.App -> apps[tile.component]
                    }
                    val asset = badge?.icon?.let { bytes ->
                        require(bytes.size in 1..(128 * 1024))
                        "${assetPrefix}icon-$index.png".also { assets[it] = bytes }
                    }
                    val label = escape(badge?.label ?: emptyText)
                    val (x, y, w, h) = cell
                    val library = tile == AiRearGrid.Tile.AllApps
                    if (badge == null) {
                        append("<Group x=\"$x\" y=\"$y\">")
                        face(w, h, label, null, library, false)
                        append("</Group>")
                    } else button(cell, index, activation) { pressed -> face(w, h, label, asset, library, pressed) }
                }
                append(closing)
            }.toByteArray(Charsets.UTF_8)
            if (markup.size > manifestLimit) throw IOException("UNSUPPORTED_WALLPAPER_MANIFEST")
            // Closing ZIP must not close the caller-owned stream before the journal fsyncs it.
            val destination = object : java.io.FilterOutputStream(out) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                override fun close() = flush()
            }
            ZipOutputStream(destination).use { zip ->
                for (item in source.entries().asSequence()) {
                    val entry = ZipEntry(item.name)
                    if (item.name != manifest) {
                        entry.method = item.method
                        if (item.method == ZipEntry.STORED) {
                            entry.size = item.size
                            entry.crc = item.crc
                        }
                    }
                    zip.putNextEntry(entry)
                    if (item.name == manifest) zip.write(markup)
                    else source.getInputStream(item).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                for ((name, bytes) in assets) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    fun artLabelSize(cell: Cell): Int = (cell.width / 9).coerceIn(17, 32)
    fun artLabelY(cell: Cell): Int = cell.height * 78 / 100

    private fun StringBuilder.image(src: String, w: Int, h: Int, alpha: Int = 255) {
        append("<Image x=\"0\" y=\"0\" w=\"$w\" h=\"$h\" alpha=\"$alpha\" src=\"$src\"/>")
    }

    private fun StringBuilder.button(cell: Cell, index: Int, activation: Activation, render: StringBuilder.(Boolean) -> Unit) {
        val (x, y, w, h) = cell
        val slot = index + 1 // Zero means no tile is pressed.
        val owner = "eq(#$pressedSlot,$slot)"
        val start = cell.hitExpression("touch_begin")
        val pressed = "$owner*$start*${cell.hitExpression("touch")}"
        // Both renderers share the repaired one-owner press and gesture gate.
        append("<Button x=\"$x\" y=\"$y\" w=\"$w\" h=\"$h\" alignChildren=\"true\">")
        append("<Normal><Group visibility=\"not($pressed)\">")
        render(false)
        append("</Group><Group visibility=\"$pressed\">")
        render(true)
        append("</Group></Normal><Triggers><Trigger action=\"down\" condition=\"$start\">")
        setPressedSlot(slot)
        append("</Trigger><Trigger action=\"up,cancel\" condition=\"$owner\">")
        setPressedSlot(0)
        append("</Trigger><Trigger action=\"up\" condition=\"lt(abs(#touch_x-#touch_begin_x),24)*lt(abs(#touch_y-#touch_begin_y),24)\">")
        appendActivation(activation, index)
        append("</Trigger></Triggers></Button>")
    }

    private fun Cell.hitExpression(touch: String) =
        "ge(#${touch}_x,$x)*lt(#${touch}_x,${x + width})*ge(#${touch}_y,$y)*lt(#${touch}_y,${y + height})"

    private fun StringBuilder.setPressedSlot(slot: Int) {
        append("<VariableCommand name=\"$pressedSlot\" type=\"number\" expression=\"$slot\" requestUpdate=\"true\"/>")
    }

    private fun StringBuilder.face(w: Int, h: Int, label: String, asset: String?, library: Boolean, pressed: Boolean) {
        val f = AiRearWallpaperArt.face(w, h, asset != null, library, pressed)
        val edge = if (pressed) AiRearWallpaperArt.PRESSED_OUTLINE else AiRearWallpaperArt.OUTLINE
        val i = f.inset
        append("<Rectangle x=\"$i\" y=\"$i\" w=\"${w - 2 * i}\" h=\"${h - 2 * i}\" cornerRadius=\"${f.radius}\" fillColor=\"${AiRearWallpaperArt.mamlColor(edge)}\"/>")
        append("<Rectangle x=\"${i + 2}\" y=\"${i + 2}\" w=\"${w - 2 * i - 4}\" h=\"${h - 2 * i - 4}\" cornerRadius=\"${f.radius - 2}\" fillColor=\"${AiRearWallpaperArt.mamlColor(f.surface)}\"/>")
        if (asset != null) append("<Image x=\"${(w - f.icon) / 2}\" y=\"${f.iconTop}\" w=\"${f.icon}\" h=\"${f.icon}\" src=\"$asset\"/>")
        append("<Text x=\"${w / 2}\" y=\"${f.labelY}\" align=\"center\" alignV=\"center\" w=\"${w - 18}\" size=\"${f.textSize}\" color=\"${AiRearWallpaperArt.mamlColor(AiRearWallpaperArt.TEXT)}\" text=\"$label\"/>")
    }

    /**
     * The OEM renderer explicitly shares its caller identity. The receiver must also check
     * installed binding and slot; a public URI alone is never a launch authorization.
     */
    private fun StringBuilder.appendActivation(a: Activation, slot: Int) {
        append("<MethodCommand target=\"aiResult\" targetType=\"var\" class=\"android.app.ActivityOptions\" method=\"makeBasic\" return=\"aiOptions\" returnType=\"object\" errorVar=\"aiCreate\"/>")
        append("<MethodCommand target=\"aiOptions\" targetType=\"var\" class=\"android.app.ActivityOptions\" method=\"setLaunchDisplayId\" params=\"0\" paramTypes=\"int\" errorVar=\"aiDisplay\"/>")
        append("<MethodCommand target=\"aiOptions\" targetType=\"var\" class=\"android.app.ActivityOptions\" method=\"setShareIdentityEnabled\" params=\"1\" paramTypes=\"boolean\" errorVar=\"aiIdentity\"/>")
        append("<MethodCommand target=\"aiOptions\" targetType=\"var\" class=\"android.app.ActivityOptions\" method=\"toBundle\" return=\"aiBundle\" returnType=\"object\" errorVar=\"aiBundled\"/>")
        append("<IntentCommand condition=\"eq(#aiCreate,1)*eq(#aiDisplay,1)*eq(#aiIdentity,1)*eq(#aiBundled,1)\" package=\"${a.packageName}\" class=\"${a.className}\" action=\"android.intent.action.VIEW\" ")
        append("uri=\"remrss-ai-activation://${a.binding}/$slot\" activityOption=\"aiBundle\" flags=\"268435456\"/>")
    }

    private fun escape(original: String): String {
        // Bound user-facing names and strip disallowed XML control characters.
        val normalized = original.take(48).filter { it >= ' ' && it != '\uFFFE' && it != '\uFFFF' }
        return normalized.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
    }
}
