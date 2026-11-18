package top.zainzh.rearscreen;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AiRearWallpaperFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private File root;
    private AiRearWallpaperFiles files;
    private byte[] initial;
    private byte[] preview;

    @Before public void setup() throws Exception {
        root = folder.newFolder("maml");
        files = new AiRearWallpaperFiles(root);
        initial = archive("initial");
        preview = new byte[]{42, 12, 19};
        make("first", initial);
        make("different", archive("other"));
    }

    private File slot(String name) { return new File(root, name); }
    private File data(String name) { return new File(slot(name), "rearscreen"); }

    private void make(String id, byte[] bytes) throws IOException {
        assertTrue(slot(id).mkdir());
        Files.write(data(id).toPath(), bytes);
        Files.writeString(new File(slot(id), "description.xml").toPath(),
                "<theme><title>个性签名</title><resourceType>rearscreen</resourceType><titles>"
                        + "<title locale=\"en_US\">Signature</title></titles></theme>");
    }

    private static byte[] archive(String label) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "manifest.xml", ("<Widget frameRate=\"0\"><Text text=\"" + label + "\"/></Widget>").getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/ai/ai.mp4", new byte[]{42, 12, 19});
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    @Test public void localTitleAndStableIdentityForSameName() throws Exception {
        JSONArray rows = new JSONArray(files.inventory("en-US"));
        assertEquals(2, rows.length());
        assertEquals("Signature", rows.getJSONObject(0).getString("title"));
        assertEquals("Signature", rows.getJSONObject(1).getString("title"));
        assertNotEquals(rows.getJSONObject(0).getString("id"), rows.getJSONObject(1).getString("id"));
    }

    @Test public void realAssetAndUnavailableRows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals("video", files.copy("first", true, out));
        assertArrayEquals(preview, out.toByteArray());
        assertTrue(data("first").delete());
        JSONArray rows = new JSONArray(files.inventory("zh-CN"));
        boolean found = false;
        for (int i = 0; i < rows.length(); i++)
            if (rows.getJSONObject(i).getString("id").equals("first")) {
                assertFalse(rows.getJSONObject(i).getBoolean("available"));
                found = true;
            }
        assertTrue(found);
    }

    @Test public void rejectsTraversalAndRedirectedSlot() throws Exception {
        assertThrows(IOException.class, () -> files.copy("../maml/first", false, new ByteArrayOutputStream()));
        Files.createSymbolicLink(slot("redirect").toPath(), slot("first").toPath());
        assertThrows(IOException.class, () -> files.copy("redirect", false, new ByteArrayOutputStream()));
    }

    @Test public void atomicReplaceChangesOnlySelectedArchive() throws Exception {
        byte[] other = Files.readAllBytes(data("different").toPath());
        byte[] metadata = Files.readAllBytes(new File(slot("first"), "description.xml").toPath());
        byte[] next = archive("replacement");
        files.replace("first", new ByteArrayInputStream(initial), new ByteArrayInputStream(next));
        assertArrayEquals(next, Files.readAllBytes(data("first").toPath()));
        assertArrayEquals(other, Files.readAllBytes(data("different").toPath()));
        assertArrayEquals(metadata, Files.readAllBytes(new File(slot("first"), "description.xml").toPath()));
        assertEquals(3, slot("first").list().length);
    }

    @Test public void copiesOemModeAndRestoresTheOriginalInode() throws Exception {
        var live = data("first").toPath();
        Files.setPosixFilePermissions(live, PosixFilePermissions.fromString("rwxrwxr-x"));
        var before = Files.readAttributes(live, PosixFileAttributes.class);
        var next = archive("new");
        files.replace("first", new ByteArrayInputStream(initial), new ByteArrayInputStream(next));
        assertEquals(before.permissions(), Files.getPosixFilePermissions(live));
        assertNotEquals(before.fileKey(), Files.readAttributes(live, BasicFileAttributes.class).fileKey());
        assertTrue(files.restore("first", new ByteArrayInputStream(next), new ByteArrayInputStream(initial)));
        var after = Files.readAttributes(live, PosixFileAttributes.class);
        assertEquals(before.fileKey(), after.fileKey());
        assertEquals(before.owner(), after.owner());
        assertEquals(before.group(), after.group());
        assertEquals(before.permissions(), after.permissions());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        assertEquals(2, slot("first").list().length);
        assertArrayEquals(initial, Files.readAllBytes(live));
    }

    @Test public void restoresPreimageWhenProcessStoppedBeforePublishingCandidate() throws Exception {
        var old = Files.readAttributes(data("first").toPath(), BasicFileAttributes.class);
        var next = archive("new");
        var dir = folder.newFolder("interrupted");
        var journal = new AiRearWallpaperJournal(dir, fakeStore(false));
        journal.apply("first", (original, out) -> {
            assertArrayEquals(initial, Files.readAllBytes(original.toPath()));
            out.write(next);
        });
        Files.delete(data("first").toPath());
        assertEquals(AiRearWallpaperJournal.State.ORIGINAL, journal.restore());
        assertEquals(old.fileKey(), Files.readAttributes(data("first").toPath(), BasicFileAttributes.class).fileKey());
        assertArrayEquals(initial, Files.readAllBytes(data("first").toPath()));
    }

    @Test public void changedOriginalAndMalformedCandidateNeverOverwrite() throws Exception {
        byte[] bad = new byte[]{8, 6, 0};
        assertThrows(IOException.class, () -> files.replace("first", new ByteArrayInputStream(initial), new ByteArrayInputStream(bad)));
        assertThrows(IOException.class, () -> files.replace("first", new ByteArrayInputStream(bad), new ByteArrayInputStream(archive("new"))));
        assertArrayEquals(initial, Files.readAllBytes(data("first").toPath()));
        assertEquals(2, slot("first").list().length);
    }

    @Test public void journalResolvesLostReplyAndRestoresAcrossProcess() throws Exception {
        File journal = folder.newFolder("journal");
        byte[] next = archive("replacement");
        AiRearWallpaperJournal.Store stable = fakeStore(false);
        assertThrows(IOException.class, () -> new AiRearWallpaperJournal(journal, fakeStore(true)).apply("first", (original, out) -> out.write(next)));
        AiRearWallpaperJournal reopened = new AiRearWallpaperJournal(journal, stable);
        assertEquals(AiRearWallpaperJournal.State.APPLIED, reopened.state());
        assertEquals(AiRearWallpaperJournal.State.ORIGINAL, reopened.restore());
        assertEquals(AiRearWallpaperJournal.State.NONE, reopened.state());
        assertArrayEquals(initial, Files.readAllBytes(data("first").toPath()));
    }

    @Test public void journalDoesNotUndoAUserEdit() throws Exception {
        byte[] next = archive("replacement");
        AiRearWallpaperJournal journal = new AiRearWallpaperJournal(folder.newFolder("journal"), fakeStore(false));
        journal.apply("first", (original, out) -> out.write(next));
        byte[] userEdit = archive("user");
        Files.write(data("first").toPath(), userEdit);
        assertEquals(AiRearWallpaperJournal.State.CONFLICT, journal.restore());
        assertEquals("first", journal.activeId());
        assertArrayEquals(userEdit, Files.readAllBytes(data("first").toPath()));
    }

    @Test public void unsupportedCarrierDoesNotPublishJournalOrReplaceOriginal() throws Exception {
        AiRearWallpaperJournal journal = new AiRearWallpaperJournal(folder.newFolder("render-failure"), fakeStore(false));
        assertThrows(IOException.class, () -> journal.apply("first", (original, out) -> {
            out.write(9);
            throw new IOException("UNSUPPORTED_AI_WALLPAPER_CARRIER");
        }));
        assertEquals(AiRearWallpaperJournal.State.NONE, journal.state());
        assertArrayEquals(initial, Files.readAllBytes(data("first").toPath()));
        assertEquals(2, slot("first").list().length);
    }

    private AiRearWallpaperJournal.Store fakeStore(boolean loseApplyReply) {
        return new AiRearWallpaperJournal.Store() {
            @Override public void readArchive(String id, File destination) throws IOException {
                try (java.io.OutputStream out = new java.io.FileOutputStream(destination)) {
                    files.copy(id, false, out);
                }
            }
            @Override public void replaceArchive(String id, File original, File replacement) throws IOException {
                try (java.io.InputStream expected = new java.io.FileInputStream(original);
                     java.io.InputStream candidate = new java.io.FileInputStream(replacement)) {
                    files.replace(id, expected, candidate);
                }
                if (loseApplyReply) throw new IOException("reply interrupted");
            }
            @Override public boolean restoreArchive(String id, File applied, File original) throws IOException {
                try (java.io.InputStream installed = new java.io.FileInputStream(applied);
                     java.io.InputStream preimage = new java.io.FileInputStream(original)) {
                    return files.restore(id, installed, preimage);
                }
            }
        };
    }
}
