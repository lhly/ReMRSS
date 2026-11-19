package top.zainzh.rearscreen

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class AiRearThemeCoexistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val a = AiRearWallpaperInstallations.Placement("work_a")
    private val b = AiRearWallpaperInstallations.Placement("work_b", "night-tide")
    private val b2 = AiRearWallpaperInstallations.Placement("work_bb", "night-tide")
    private val c = AiRearWallpaperInstallations.Placement("work_c", "third-theme")
    private val first = AiRearWallpaperBinding("work_a", "a".repeat(32), AiRearGrid(List(6) { "com.test.app$it/.A" }))
    private val second = AiRearWallpaperBinding("work_b", "b".repeat(32),
        AiRearGrid(List(6) { if (it < 3) "com.test.other$it/.B" else null }), 3)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(16)
    private val themeAssets = listOf("background.png", "1-60-90-500-820.png", "2-640-90-300-350.png", "3-640-540-300-370.png")
        .associate { AiRearModWallpaperTheme.PREFIX + it to png }

    @Test fun uninstallRestoresEveryWorkOwnedByBWithoutChangingAOrOtherMods() {
        val root = temp.newFolder()
        val fixtures = FakeWorks(root)
        val installations = fixtures.installations
        val before = fixtures.works.mapValues { it.value.readBytes() }
        fixtures.apply(a, first)
        fixtures.apply(b, second)
        fixtures.apply(b2, second.copy(wallpaper = b2.id, token = "c".repeat(32)))
        fixtures.apply(c, second.copy(wallpaper = c.id, token = "d".repeat(32)))
        assertFalse(installations.canInstall(b.copy(id = a.id)))
        assertFalse(installations.canInstall(c.copy(id = b.id)))
        assertFalse(installations.canInstall(a.copy(id = "work_another_a")))
        assertArrayEquals(before.getValue(a.id), File(root, "active/original.zip").readBytes())
        assertArrayEquals(before.getValue(b.id), File(root, "mod-wallpapers/night-tide/work_b/active/original.zip").readBytes())

        val tapA = checkNotNull(installations.resolve(first.token, "/0"))
        val tapB = checkNotNull(installations.resolve(second.token, "/0"))
        assertEquals(first.grid.tile(0), tapA.tile)
        assertEquals(second.grid.tile(0), tapB.tile)
        assertNull(installations.resolve(second.token, "/3"))
        assertEquals(2, installations.restoreMod("night-tide", fixtures.store) { fixtures.prefs.remove(it.bindingKey) })
        assertTrue(installations.forMod("night-tide").isEmpty())
        assertFalse(installations.isCurrent(tapB))
        assertTrue(installations.isCurrent(tapA))
        assertTrue(installations.hasLiveBinding())
        assertEquals(AiRearWallpaperJournal.State.APPLIED, installations.journal(a, fixtures.store).state())
        assertEquals(AiRearWallpaperJournal.State.APPLIED, installations.journal(c, fixtures.store).state())
        for (place in listOf(b, b2)) assertArrayEquals(before.getValue(place.id), fixtures.works.getValue(place.id).readBytes())
        assertFalse(org.json.JSONObject(first.encode()).has("buttonCount"))
    }

    @Test fun anExternallyChangedWorkRetainsItsRecoveryRecordAndBlocksRemoval() {
        val fixtures = FakeWorks(temp.newFolder())
        fixtures.apply(a, first)
        fixtures.apply(b, second)
        val newer = carrier(9).readBytes()
        fixtures.works.getValue(b.id).writeBytes(newer)
        val callbacks = mutableListOf<AiRearWallpaperInstallations.Placement>()
        assertThrows(IllegalStateException::class.java) {
            fixtures.installations.restoreMod("night-tide", fixtures.store, callbacks::add)
        }
        assertEquals(listOf(b), fixtures.installations.forMod("night-tide"))
        assertTrue(callbacks.isEmpty())
        assertArrayEquals(newer, fixtures.works.getValue(b.id).readBytes())
        assertEquals(AiRearWallpaperJournal.State.APPLIED, fixtures.installations.journal(a, fixtures.store).state())
    }

    @Test fun signedAssetPathsIdentifyThemeTypesWithoutModIds() {
        assertNull(AiRearModWallpaperTheme.classify(listOf("assets/splash.png")))
        val template = mapOf(AiRearWallpaperTemplate.APPS_AND_LIST to png)
        assertEquals(ModWallpaperThemeKind.GRID_TEMPLATE, AiRearModWallpaperTheme.classify(template.keys))
        val artwork = AiRearModWallpaperTheme.fromVerifiedAssets(template)
        assertEquals(6, artwork.buttons)
        assertTrue(artwork.defaultAllApps)
        assertTrue(artwork.art.isEmpty())
        assertEquals(ModWallpaperThemeKind.DRAWN_CONTROLS, AiRearModWallpaperTheme.classify(themeAssets.keys))
        val one = { key: String -> key.endsWith("/background.png") || key.substringAfterLast('/').startsWith("1-") }
        assertEquals(1, AiRearModWallpaperTheme.fromVerifiedAssets(themeAssets.filterKeys(one)).buttons)
    }

    private inner class FakeWorks(root: File) {
        val prefs = mutableMapOf<String, String>()
        val installations = AiRearWallpaperInstallations(root) { prefs[it] }
        val works = listOf(a, b, b2, c).mapIndexed { index, place -> place.id to carrier(index.toByte()) }.toMap()
        val store = object : AiRearWallpaperJournal.Store {
            override fun readArchive(id: String, destination: File) { works.getValue(id).copyTo(destination, overwrite = true) }
            override fun replaceArchive(id: String, expected: File, candidate: File) {
                assertArrayEquals(expected.readBytes(), works.getValue(id).readBytes())
                candidate.copyTo(works.getValue(id), overwrite = true)
            }
            override fun restoreArchive(id: String, applied: File, original: File): Boolean {
                if (!works.getValue(id).readBytes().contentEquals(applied.readBytes())) return false
                original.copyTo(works.getValue(id), overwrite = true)
                return true
            }
        }

        fun apply(place: AiRearWallpaperInstallations.Placement, binding: AiRearWallpaperBinding) {
            installations.journal(place, store).apply(place.id) { _, into -> carrier(7).inputStream().use { it.copyTo(into) } }
            prefs[place.bindingKey] = binding.encode()
        }
    }

    @Test fun modArtControlsItsOwnThreeBoundsAndPreservesCarrier() {
        val theme = AiRearModWallpaperTheme.fromVerifiedAssets(themeAssets)
        val output = temp.newFile()
        val viewport = AiRearWallpaperArchive.Viewport(976, 596, 296, 0, 0, 0)
        val bindings = second.grid.apps.filterNotNull().associateWith { AiRearWallpaperArchive.Badge("Test") }
        output.outputStream().use { stream -> AiRearWallpaperArchive.write(carrier(5), stream, viewport, second.grid,
            bindings, AiRearWallpaperArchive.Badge("All"), "Empty", null,
            AiRearWallpaperArchive.Activation("com.test.remrss", "com.test.remrss.ActivationActivity", second.token), theme) }
        ZipFile(output).use { zip ->
            assertArrayEquals(byteArrayOf(5), zip.getInputStream(zip.getEntry("assets/ai/ai.mp4")).readBytes())
            val root = zip.getInputStream(zip.getEntry("manifest.xml")).use { AiRearWallpaperFiles.document(it.readBytes()) }
            val buttons = root.getElementsByTagName("Button")
            assertEquals(3, buttons.length)
            theme.art.forEachIndexed { i, art ->
                val cell = art.cell(viewport)
                val button = buttons.item(i) as Element
                assertEquals(cell.x.toString(), button.getAttribute("x"))
                assertEquals(cell.y.toString(), button.getAttribute("y"))
                assertEquals(cell.width.toString(), button.getAttribute("w"))
                assertArrayEquals(art.png, zip.getInputStream(zip.getEntry("remrss_ai/theme-button-$i.png")).readBytes())
            }
        }
    }

    @Test fun overlappingAndOutOfBoundsModButtonsAreRejected() {
        val key = AiRearModWallpaperTheme.PREFIX + "3-640-540-300-370.png"
        for (name in listOf("3-640-140-300-370.png", "3-640-540-400-370.png", "3-010-540-300-370.png")) {
            assertThrows(IllegalArgumentException::class.java) {
                AiRearModWallpaperTheme.fromVerifiedAssets(themeAssets - key + (AiRearModWallpaperTheme.PREFIX + name to png))
            }
        }
    }

    private fun carrier(marker: Byte): File = temp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { zip ->
            mapOf("manifest.xml" to "<Widget screenWidth=\"976\"><Video src=\"assets/ai/ai.mp4\"/></Widget>".toByteArray(),
                "var_config.xml" to "<WidgetConfig/>".toByteArray(), "assets/ai/ai.mp4" to byteArrayOf(marker)).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
    }
}
