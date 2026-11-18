package top.zainzh.rearscreen;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** One reversible app-private installation. A committed journal survives task/service death. */
final class AiRearWallpaperJournal {
    interface Renderer {
        void render(File original, OutputStream destination) throws IOException;
    }

    interface Store {
        void readArchive(String id, File destination) throws IOException;
        void replaceArchive(String id, File expected, File candidate) throws IOException;
        boolean restoreArchive(String id, File applied, File original) throws IOException;
    }

    enum State { NONE, ORIGINAL, APPLIED, CONFLICT }
    private final File root;
    private final Store store;

    AiRearWallpaperJournal(File root, Store store) {
        this.root = root;
        this.store = store;
    }

    private File active() { return new File(root, "active"); }
    private File original() { return new File(active(), "original.zip"); }
    private File candidate() { return new File(active(), "replacement.zip"); }

    String activeId() throws IOException { return activeId(root); }

    static String activeId(File root) throws IOException {
        File active = new File(root, "active");
        if (!active.isDirectory()) return null;
        File idFile = new File(active, "slot");
        if (!idFile.isFile() || idFile.length() < 1 || idFile.length() > 96)
            throw new IOException("WALLPAPER_BACKUP_INCOMPLETE");
        String id = new String(Files.readAllBytes(idFile.toPath()), java.nio.charset.StandardCharsets.US_ASCII);
        if (!AiRearWallpaperFiles.validId(id) || !new File(active, "original.zip").isFile() || !new File(active, "replacement.zip").isFile())
            throw new IOException("WALLPAPER_BACKUP_INCOMPLETE");
        return id;
    }

    private static void write(File file, InputStream data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = data.read(buffer)) != -1;) out.write(buffer, 0, read);
            out.getFD().sync();
        }
    }

    void apply(String id, Renderer renderer) throws IOException {
        if (!AiRearWallpaperFiles.validId(id)) throw new IOException("INVALID_WALLPAPER_ID");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("WALLPAPER_BACKUP_UNAVAILABLE");
        if (active().exists()) throw new IOException("RESTORE_WALLPAPER_FIRST");
        File stage = Files.createTempDirectory(root.toPath(), "backup-").toFile();
        try {
            File original = new File(stage, "original.zip");
            store.readArchive(id, original);
            AiRearWallpaperFiles.checkArchive(original);
            try (FileOutputStream sync = new FileOutputStream(original, true)) { sync.getFD().sync(); }
            File replacement = new File(stage, "replacement.zip");
            try (FileOutputStream out = new FileOutputStream(replacement)) {
                // Compile from the exact bytes that will later guard replacement and restoration.
                renderer.render(original, out);
                out.getFD().sync();
            }
            AiRearWallpaperFiles.checkArchive(replacement);
            try (InputStream data = new java.io.ByteArrayInputStream(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                write(new File(stage, "slot"), data);
            }
            // Publish backup before crossing the device write boundary. The caller may lose the reply.
            Files.move(stage.toPath(), active().toPath(), StandardCopyOption.ATOMIC_MOVE);
            store.replaceArchive(id, original(), candidate());
            if (state() != State.APPLIED) throw new IOException("WALLPAPER_REPLACE_UNVERIFIED");
        } finally { removeWorkspace(stage); }
    }

    State state() throws IOException {
        String id = activeId();
        if (id == null) return State.NONE;
        File current = Files.createTempFile(root.toPath(), "readback-", ".zip").toFile();
        try {
            store.readArchive(id, current);
            if (same(current, original())) return State.ORIGINAL;
            if (same(current, candidate())) return State.APPLIED;
            return State.CONFLICT;
        } finally { Files.deleteIfExists(current.toPath()); }
    }

    State restore() throws IOException {
        String id = activeId();
        if (id == null) return State.NONE;
        // Restore is also the recovery entry point if the resource name disappeared during apply.
        if (!store.restoreArchive(id, candidate(), original())) return State.CONFLICT;
        if (state() != State.ORIGINAL) throw new IOException("WALLPAPER_RESTORE_UNVERIFIED");
        // Atomically detach the journal before cleaning. A crash cannot leave a half-deleted active backup.
        File cleanup = new File(root, "restored-" + java.util.UUID.randomUUID());
        Files.move(active().toPath(), cleanup.toPath(), StandardCopyOption.ATOMIC_MOVE);
        removeWorkspace(cleanup);
        return State.ORIGINAL;
    }

    private static boolean same(File file, File expected) throws IOException {
        try (InputStream data = new FileInputStream(expected)) {
            return AiRearWallpaperFiles.sameBytes(file, data);
        }
    }

    private static void removeWorkspace(File workspace) throws IOException {
        if (!workspace.isDirectory()) return;
        for (String name : new String[]{"original.zip", "replacement.zip", "slot"})
            Files.deleteIfExists(new File(workspace, name).toPath());
        Files.deleteIfExists(workspace.toPath());
    }
}
