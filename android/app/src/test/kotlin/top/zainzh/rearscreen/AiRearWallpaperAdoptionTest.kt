package top.zainzh.rearscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AiRearWallpaperAdoptionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun inspectAndCancelDoNotWriteAndConfirmedInstallRestoresTheOriginalInode() {
        val theme = ThemeFiles(temp.newFolder("maml"))
        val first = theme.create("first", "原画一")
        val other = theme.create("other", "原画二")
        val original = first.readBytes()
        val mode = PosixFilePermissions.fromString("rwxrwxr-x")
        Files.setPosixFilePermissions(first.toPath(), mode)
        val originalKey = fileKey(first)
        val journalDir = File(temp.newFolder("app"), "installed")
        val store = Store(theme.files)
        val journal = AiRearWallpaperJournal(journalDir, store)
        assertEquals("", theme.files.inspectReplacement("first", null))
        theme.installLegacy("first", DEBUG, OLD_TOKEN)
        theme.installLegacy("other", DEBUG, OLD_TOKEN)
        val previous = first.readBytes()
        val otherBefore = other.readBytes()
        val otherBackup = theme.backup("other").readBytes()
        val oldKey = fileKey(first)
        val slot = checkNotNull(first.parentFile)
        val names = slot.list()!!.toSet()

        val ticket = theme.files.inspectReplacement("first", null)
        assertEquals(DEBUG, ticket.substringBefore('\n'))
        // Dismissing the UI discards this ticket, without running a journal transaction.
        assertFalse(journalDir.exists())
        assertEquals(names, slot.list()!!.toSet())
        assertEquals(oldKey, fileKey(first))
        assertArrayEquals(previous, first.readBytes())
        assertEquals(originalKey, fileKey(theme.backup("first")))
        assertEquals(0, store.adoptions)

        journal.adopt("first", ticket) { clean, output ->
            assertArrayEquals(original, clean.readBytes())
            assertArrayEquals(previous, first.readBytes())
            render(clean, output, FORMAL, NEW_TOKEN)
        }
        assertEquals(1, store.adoptions)
        assertEquals(0, store.replaces)
        assertEquals(AiRearWallpaperJournal.State.APPLIED, journal.state())
        val macro = ZipFile(first).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.xml")).use { it.reader().readText() }
        }
        assertTrue(macro.contains("package=\"$FORMAL\""))
        assertTrue(macro.contains(NEW_TOKEN))
        assertFalse(macro.contains(OLD_TOKEN))
        assertEquals(originalKey, fileKey(theme.backup("first")))
        assertArrayEquals(original, theme.backup("first").readBytes())
        assertEquals(mode, Files.getPosixFilePermissions(first.toPath()))

        val reopened = AiRearWallpaperJournal(journalDir, store)
        assertEquals(AiRearWallpaperJournal.State.ORIGINAL, reopened.restore())
        assertNull(reopened.activeId())
        assertArrayEquals(original, first.readBytes())
        assertEquals(originalKey, fileKey(first))
        assertEquals(mode, Files.getPosixFilePermissions(first.toPath()))
        assertFalse(theme.backup("first").exists())
        assertArrayEquals(otherBefore, other.readBytes())
        assertArrayEquals(otherBackup, theme.backup("other").readBytes())
    }

    @Test fun changedLegacyDuringRenderIsNeverOverwrittenAndDoesNotReserveThePlacement() {
        val theme = ThemeFiles(temp.newFolder("maml"))
        val live = theme.create("first", "原画")
        val original = live.readBytes()
        theme.installLegacy("first", DEBUG, OLD_TOKEN)
        val ticket = theme.files.inspectReplacement("first", null)
        val journal = AiRearWallpaperJournal(temp.newFolder("app"), Store(theme.files))
        lateinit var changed: ByteArray
        val failure = assertThrows(IOException::class.java) {
            journal.adopt("first", ticket) { clean, output ->
                render(clean, output, FORMAL, NEW_TOKEN)
                live.outputStream().use { render(clean, it, DEBUG, NEW_TOKEN) }
                Files.setLastModifiedTime(live.toPath(), FileTime.fromMillis(1_000))
                changed = live.readBytes()
            }
        }
        assertEquals("WALLPAPER_ADOPTION_CHANGED", failure.message)
        assertNull(journal.activeId())
        assertArrayEquals(changed, live.readBytes())
        assertArrayEquals(original, theme.backup("first").readBytes())
    }

    @Test fun missingOrUnrelatedPreimageAndForeignOverlayDoNotOfferAdoption() {
        val theme = ThemeFiles(temp.newFolder("maml"))
        theme.create("missing", "缺原件")
        theme.installLegacy("missing", DEBUG, OLD_TOKEN)
        assertTrue(theme.backup("missing").delete())
        assertEquals("WALLPAPER_LEGACY_BACKUP_INVALID", assertThrows(IOException::class.java) {
            theme.files.inspectReplacement("missing", null)
        }.message)

        theme.create("mismatch", "正确的原画")
        theme.installLegacy("mismatch", DEBUG, OLD_TOKEN)
        val unrelated = theme.create("different", "另一张")
        unrelated.copyTo(theme.backup("mismatch"), overwrite = true)
        assertEquals("WALLPAPER_LEGACY_UNVERIFIED", assertThrows(IOException::class.java) {
            theme.files.inspectReplacement("mismatch", null)
        }.message)

        theme.create("foreign", "第三方主题")
        theme.installLegacy("foreign", "example.other.app", OLD_TOKEN)
        assertEquals("WALLPAPER_LEGACY_UNVERIFIED", assertThrows(IOException::class.java) {
            theme.files.inspectReplacement("foreign", null)
        }.message)
    }

    private class Store(private val files: AiRearWallpaperFiles) : AiRearWallpaperJournal.Store {
        var adoptions = 0
        var replaces = 0

        override fun readArchive(id: String, destination: File) {
            destination.outputStream().use { files.copy(id, false, it) }
        }

        override fun readOriginalForAdoption(id: String, ticket: String, destination: File) {
            val current = destination.outputStream().use { files.inspectReplacement(id, it) }
            if (current != ticket) throw IOException("WALLPAPER_ADOPTION_CHANGED")
        }

        override fun adoptArchive(id: String, ticket: String, original: File, candidate: File): String {
            adoptions++
            return original.inputStream().use { expected ->
                candidate.inputStream().use { files.adopt(id, ticket, expected, it) }
            }
        }

        override fun replaceArchive(id: String, original: File, replacement: File) {
            replaces++
            original.inputStream().use { expected ->
                replacement.inputStream().use { files.replace(id, expected, it) }
            }
        }

        override fun restoreArchive(id: String, applied: File, original: File): Boolean =
            applied.inputStream().use { expected -> original.inputStream().use { files.restore(id, expected, it) } }
    }

    private class ThemeFiles(private val directory: File) {
        val files = AiRearWallpaperFiles(directory)
        private fun slot(id: String) = File(directory, id)
        fun backup(id: String) = File(slot(id), ".remrss-original-rearscreen")

        fun create(id: String, label: String): File {
            check(slot(id).mkdir())
            File(slot(id), "description.xml").writeText("<theme><resourceType>rearscreen</resourceType></theme>")
            val resource = File(slot(id), "rearscreen")
            ZipOutputStream(resource.outputStream()).use { out ->
                for ((name, contents) in mapOf(
                    "manifest.xml" to "<Widget screenWidth=\"960\"><Text text=\"$label\"/></Widget>\n",
                    "var_config.xml" to "<config/>",
                    "assets/ai/ai.mp4" to "video-$label",
                )) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(contents.toByteArray())
                    out.closeEntry()
                }
            }
            return resource
        }

        fun installLegacy(id: String, owner: String, token: String) {
            val original = File(slot(id), "rearscreen")
            val candidate = Files.createTempFile(directory.toPath(), "legacy-", ".zip").toFile()
            try {
                candidate.outputStream().use { render(original, it, owner, token) }
                original.inputStream().use { expected ->
                    candidate.inputStream().use { files.replace(id, expected, it) }
                }
            } finally {
                candidate.delete()
            }
        }
    }

    private companion object {
        const val FORMAL = "top.zainzh.rearscreen"
        const val DEBUG = "$FORMAL.debug"
        const val OLD_TOKEN = "fedcba9876543210fedcba9876543210"
        const val NEW_TOKEN = "1234567890abcdef1234567890abcdef"
        fun fileKey(file: File): Any? =
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

        fun render(original: File, output: OutputStream, owner: String, token: String) {
            val app = "com.example.calc/.Main"
            val slots = if (owner == FORMAL) listOf(app, null, null, null, null, null) else List(6) { app }
            AiRearWallpaperArchive.write(
                original, output, AiRearWallpaperArchive.Viewport(960, 480, 256, 0, 0, 0),
                AiRearGrid(slots, allApps = true),
                mapOf(app to AiRearWallpaperArchive.Badge("Calculator", byteArrayOf(1, 2))),
                AiRearWallpaperArchive.Badge("All apps", byteArrayOf(3, 4)), null,
                AiRearWallpaperArchive.Activation(owner, "$FORMAL.AiRearWallpaperActivationActivity", token),
            )
        }
    }
}
