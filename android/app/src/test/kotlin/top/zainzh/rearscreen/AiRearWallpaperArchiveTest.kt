package top.zainzh.rearscreen

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class AiRearWallpaperArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val viewport = AiRearWallpaperArchive.Viewport(976, 596, 296, 12, 0, 10)
    private val action = AiRearWallpaperArchive.Activation("top.zainzh.rearscreen.debug",
        "top.zainzh.rearscreen.AiRearWallpaperEntryActivity", "a".repeat(32))
    private val lookup = (0..5).associate { "app$it/.Main" to AiRearWallpaperArchive.Badge("App <$it> & \"Name\"", byteArrayOf(1)) }
    private val apps = AiRearGrid((0..5).map { "app$it/.Main" })

    private val originalXml = """<Widget version="2" screenWidth="1080" useVariableUpdater="DateTime.Minute"><Var name="aiOriginal" expression="1"/><Video x="0" src="assets/ai/ai.mp4"/></Widget>"""
    private val originalEntries = linkedMapOf(
        "manifest.xml" to originalXml.toByteArray(),
        "var_config.xml" to "<WidgetConfig/>".toByteArray(),
        "strings/strings.xml" to "<resources/>".toByteArray(),
        "assets/ai/ai.mp4" to byteArrayOf(3, 2, 1),
    )

    private fun carrier(entries: Map<String, ByteArray> = originalEntries): File = temp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    private fun compile(grid: AiRearGrid, custom: ByteArray? = null): File {
        val file = temp.newFile()
        file.outputStream().use {
            AiRearWallpaperArchive.write(carrier(), it, viewport, grid, lookup,
                AiRearWallpaperArchive.Badge("全部应用"), custom, action)
        }
        return file
    }

    @Test fun castsBoundsToInsetsIncludingFullRightAndBottom() {
        val raw = RearCastViewport.safe(976, 596, 0, 440, 296, 0, 0, 0)
        assertEquals(AiRearWallpaperArchive.Viewport(976, 596, 296, 0, 0, 0),
            AiRearWallpaperArchive.Viewport.fromCast(raw))
        assertEquals(viewport, AiRearWallpaperArchive.Viewport.fromCast(
            RearCastViewport.safe(976, 596, 0, 440, 296, 12, 0, 10)))
    }

    @Test fun bothModesStayInsideSameSixBounds() {
        val list = AiRearWallpaperArchive.cells(viewport)
        assertEquals(listOf(30, 305).flatMap { y ->
            listOf(314, 532, 750).map { x -> AiRearWallpaperArchive.Cell(x, y, 206, 263) }
        }, list)
        assertTrue(list.all { it.x >= viewport.left && it.x + it.width <= viewport.width - viewport.right
            && it.y >= viewport.top && it.y + it.height <= viewport.height - viewport.bottom })
        assertEquals(3, list.map { it.x }.distinct().size)
        assertEquals(2, list.map { it.y }.distinct().size)
        for (mixed in listOf(false, true)) {
            val file = compile(apps.copy(allApps = mixed), byteArrayOf(4, 2))
            AiRearWallpaperFiles.checkArchive(file)
            ZipFile(file).use { zip ->
                assertArrayEquals(byteArrayOf(4, 2), zip.getInputStream(zip.getEntry("remrss_ai/background.jpg")).readBytes())
                assertEquals(mixed, zip.getEntry("remrss_ai/icon-5.png") == null)
                val root = zip.getInputStream(zip.getEntry("manifest.xml")).use {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
                }
                val commands = root.getElementsByTagName("IntentCommand")
                assertEquals(6, root.getElementsByTagName("Button").length)
                assertEquals(6, commands.length)
                val intent = commands.item(5) as Element
                assertEquals("remrss-ai-activation://${action.binding}/5", intent.getAttribute("uri"))
                assertEquals(action.className, intent.getAttribute("class"))
                assertTrue(intent.getAttribute("condition").contains("aiIdentity"))
                val text = root.getElementsByTagName("Text")
                // Each interactive cell has normal and pressed labels.
                assertEquals(if (mixed) "全部应用" else "App <5> & \"Name\"",
                    (text.item(10) as Element).getAttribute("text"))
            }
        }
    }

    @Test fun eachSparseRowAndTheWholeGroupAreCenteredInsideTheSafeArea() {
        val rows = listOf(listOf(1), listOf(2), listOf(3), listOf(2, 2), listOf(3, 2))
        rows.forEachIndexed { i, sizes ->
            val cells = AiRearWallpaperArchive.cells(viewport, i + 1)
            val byRow = cells.groupBy { it.y }.values
            assertEquals(sizes, byRow.map { it.size })
            val centerX2 = viewport.left + viewport.width - viewport.right
            val centerY2 = viewport.top + viewport.height - viewport.bottom
            assertTrue(abs(cells.first().y + cells.last().y + cells.last().height - centerY2) <= 1)
            byRow.forEach { row ->
                assertTrue(abs(row.first().x + row.last().x + row.last().width - centerX2) <= 1)
                assertTrue(row.zipWithNext().all { (a, b) -> a.x + a.width + 12 == b.x })
                assertTrue(row.first().x >= viewport.left)
                assertTrue(row.last().x + row.last().width <= viewport.width - viewport.right)
            }
            assertTrue(cells.first().y >= viewport.top)
            assertTrue(cells.last().y + cells.last().height <= viewport.height - viewport.bottom)
        }
        assertTrue(AiRearWallpaperArchive.cells(viewport, 0).isEmpty())
    }

    @Test fun twoAppsAndOptionalListPublishOnlyTheirOriginalTouchSlots() {
        val draft = apps.copy(apps = listOf(apps.apps[0], null, apps.apps[2], null, null, null))
        for (mixed in listOf(false, true)) ZipFile(compile(draft.copy(allApps = mixed))).use { zip ->
            val root = zip.getInputStream(zip.getEntry("manifest.xml")).use {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
            }
            val slots = if (mixed) listOf(0, 2, 5) else listOf(0, 2)
            val buttons = root.getElementsByTagName("Button")
            assertEquals(slots.size, buttons.length)
            assertEquals(slots.size * 2, root.getElementsByTagName("Group").length)
            val intents = root.getElementsByTagName("IntentCommand")
            assertEquals(slots.map { "remrss-ai-activation://${action.binding}/$it" },
                (0 until intents.length).map { (intents.item(it) as Element).getAttribute("uri") })
            val positions = (0 until buttons.length).map { buttons.item(it) as Element }
            assertEquals(1, positions.map { it.getAttribute("y") }.distinct().size)
            assertNull(zip.getEntry("remrss_ai/icon-1.png"))
        }
    }

    @Test fun emptyOrUnresolvableConfigurationNeverStartsWriting() {
        for (grid in listOf(AiRearGrid(), AiRearGrid().bind(0, "missing/.Main"))) {
            val out = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                AiRearWallpaperArchive.write(carrier(), out, viewport, grid, lookup,
                    AiRearWallpaperArchive.Badge("全部应用"), null, action)
            }
            assertEquals(0, out.size())
        }
    }

    @Test fun retainsOemCarrierAndRendersInItsOwnCoordinateSpace() {
        ZipFile(compile(apps)).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(originalEntries.keys.toList(), names.take(originalEntries.size))
            for ((name, data) in originalEntries.filterKeys { it != "manifest.xml" }) {
                assertArrayEquals(data, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
            }
            val raw = zip.getInputStream(zip.getEntry("manifest.xml")).use { it.readBytes() }
            assertTrue(raw.decodeToString().startsWith(originalXml.removeSuffix("</Widget>")))
            val root = AiRearWallpaperFiles.document(raw)
            assertEquals("1080", root.getAttribute("screenWidth"))
            val background = root.getElementsByTagName("Rectangle").item(0) as Element
            assertEquals("1080", background.getAttribute("w"))
            assertEquals("660", background.getAttribute("h"))
            val first = root.getElementsByTagName("Button").item(0) as Element
            assertEquals("346", first.getAttribute("x"))
        }
    }

    @Test fun oneTileOwnsPressArtworkWithoutChangingTheTapBoundary() {
        ZipFile(compile(apps.copy(allApps = true))).use { zip ->
            val root = AiRearWallpaperFiles.document(zip.getInputStream(zip.getEntry("manifest.xml")).use { it.readBytes() })
            val buttons = root.getElementsByTagName("Button")
            val vars = root.getElementsByTagName("Var")
            val pressState = (0 until vars.length).map { vars.item(it) as Element }
                .single { it.getAttribute("name") == "remrssAiPressedSlot" }
            assertEquals("0", pressState.getAttribute("expression"))
            assertEquals("true", pressState.getAttribute("const"))
            assertEquals(0, root.getElementsByTagName("Pressed").length)
            for (index in 0 until buttons.length) {
                val button = buttons.item(index) as Element
                val groups = button.getElementsByTagName("Group")
                val normal = groups.item(0) as Element
                val pressed = groups.item(1) as Element
                val x = button.getAttribute("x").toInt()
                val y = button.getAttribute("y").toInt()
                val w = button.getAttribute("w").toInt()
                val h = button.getAttribute("h").toInt()
                val owner = "eq(#remrssAiPressedSlot,${index + 1})"
                val start = "ge(#touch_begin_x,$x)*lt(#touch_begin_x,${x + w})*ge(#touch_begin_y,$y)*lt(#touch_begin_y,${y + h})"
                val current = "ge(#touch_x,$x)*lt(#touch_x,${x + w})*ge(#touch_y,$y)*lt(#touch_y,${y + h})"
                assertEquals("$owner*$start*$current", pressed.getAttribute("visibility"))
                assertEquals("not($owner*$start*$current)", normal.getAttribute("visibility"))
                val border = normal.getElementsByTagName("Rectangle").item(0) as Element
                val inset = pressed.getElementsByTagName("Rectangle").item(0) as Element
                assertEquals(button.getAttribute("w"), border.getAttribute("w"))
                assertTrue(inset.getAttribute("x").toInt() > 0)
                assertTrue(inset.getAttribute("w").toInt() < border.getAttribute("w").toInt())
                val nodes = button.getElementsByTagName("Trigger")
                val triggers = (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("action") }
                val down = triggers.getValue("down")
                val clear = triggers.getValue("up,cancel")
                assertEquals(start, down.getAttribute("condition"))
                assertEquals(owner, clear.getAttribute("condition"))
                for ((event, value) in listOf(down to (index + 1), clear to 0)) {
                    val write = event.getElementsByTagName("VariableCommand").item(0) as Element
                    assertEquals("remrssAiPressedSlot", write.getAttribute("name"))
                    assertEquals(value.toString(), write.getAttribute("expression"))
                    assertEquals("true", write.getAttribute("requestUpdate"))
                }
                val trigger = triggers.getValue("up")
                assertEquals("lt(abs(#touch_x-#touch_begin_x),24)*lt(abs(#touch_y-#touch_begin_y),24)",
                    trigger.getAttribute("condition"))
                assertEquals(1, trigger.getElementsByTagName("IntentCommand").length)
            }
        }
    }

    @Test fun rejectsCarrierWithoutAiResourcesOrWithConflictingGeneratedAssets() {
        for (entries in listOf(originalEntries - "var_config.xml", originalEntries - "assets/ai/ai.mp4",
            originalEntries + ("remrss_ai/icon-0.png" to byteArrayOf(0)))) {
            val out = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                AiRearWallpaperArchive.write(carrier(entries), out, viewport, apps, lookup,
                    AiRearWallpaperArchive.Badge("全部应用"), null, action)
            }
            assertEquals(0, out.size())
        }
    }

    @Test fun rejectsMalformedTargetAndOversizedResources() {
        assertThrows(IllegalArgumentException::class.java) {
            AiRearWallpaperArchive.Activation("android.settings", "\"escape", action.binding)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRearWallpaperArchive.write(carrier(), ByteArrayOutputStream(), viewport, apps, lookup,
                AiRearWallpaperArchive.Badge("全部应用"), ByteArray(4 * 1024 * 1024 + 1), action)
        }
    }
}
