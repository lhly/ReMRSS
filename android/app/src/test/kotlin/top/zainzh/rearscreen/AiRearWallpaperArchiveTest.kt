package top.zainzh.rearscreen

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
                AiRearWallpaperArchive.Badge("全部应用"), "未绑定", custom, action)
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
        assertEquals(6, list.size)
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

    @Test fun emptyCellsDoNotConsumeTheOfficialSwipe() {
        ZipFile(compile(AiRearGrid())).use { zip ->
            val root = zip.getInputStream(zip.getEntry("manifest.xml")).use {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
            }
            assertEquals(0, root.getElementsByTagName("Button").length)
            assertEquals(0, root.getElementsByTagName("IntentCommand").length)
            assertEquals(6, root.getElementsByTagName("Group").length)
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

    @Test fun rejectsCarrierWithoutAiResourcesOrWithConflictingGeneratedAssets() {
        for (entries in listOf(originalEntries - "var_config.xml", originalEntries - "assets/ai/ai.mp4",
            originalEntries + ("remrss_ai/icon-0.png" to byteArrayOf(0)))) {
            val out = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                AiRearWallpaperArchive.write(carrier(entries), out, viewport, apps, lookup,
                    AiRearWallpaperArchive.Badge("全部应用"), "未绑定", null, action)
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
                AiRearWallpaperArchive.Badge("全部应用"), "未绑定", ByteArray(4 * 1024 * 1024 + 1), action)
        }
    }
}
