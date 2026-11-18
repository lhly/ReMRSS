package top.zainzh.rearscreen;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Only the nominated existing AI user's rearscreen ZIP may change. No OEM settings, paths or processes. */
final class AiRearWallpaperFiles {
    static final long MAX_RESOURCE = 96L * 1024 * 1024;
    private static final String PREIMAGE = ".remrss-original-rearscreen";
    private static final int MAX_DESCRIPTION = 64 * 1024;
    private static final int MAX_THUMBNAIL_MEDIA = 24 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,96}");
    private final File root;

    AiRearWallpaperFiles(File root) { this.root = root; }

    static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    private File slot(String id) throws IOException {
        if (!validId(id)) throw new IOException("INVALID_WALLPAPER_ID");
        File parent = root.getCanonicalFile();
        File slot = new File(parent, id);
        if (!Files.isDirectory(slot.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !slot.equals(slot.getCanonicalFile())) throw new IOException("WALLPAPER_NOT_FOUND");
        return slot;
    }

    private static File child(File slot, String name) throws IOException {
        File child = new File(slot, name);
        if (!Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !child.equals(child.getCanonicalFile())) throw new IOException("WALLPAPER_NOT_FOUND");
        return child;
    }

    static Element document(byte[] bytes) throws IOException {
        try {
            String xml = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (xml.indexOf(0) >= 0 || xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
                throw new IOException("UNSUPPORTED_XML");
            DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
            parser.setExpandEntityReferences(false);
            // Parse the already validated UTF-8 text; do not let an encoding declaration reinterpret bytes.
            return parser.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement();
        } catch (Exception invalid) { throw new IOException("UNSUPPORTED_XML", invalid); }
    }

    private static Element field(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(n.getNodeName())) return (Element)n;
        return null;
    }

    private static String value(Element parent, String name) {
        Element child = field(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static byte[] bytes(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        transfer(input, data, limit);
        return data.toByteArray();
    }

    private static void transfer(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = input.read(buffer)) != -1; ) {
            limit -= read;
            if (limit < 0) throw new IOException("WALLPAPER_TOO_LARGE");
            output.write(buffer, 0, read);
        }
    }

    private static Element description(File slot) throws IOException {
        try (InputStream in = new FileInputStream(child(slot, "description.xml"))) {
            return document(bytes(in, MAX_DESCRIPTION));
        }
    }

    private static String title(Element description, String language) {
        Element titles = field(description, "titles");
        String normal = Locale.forLanguageTag(language == null ? "" : language).toLanguageTag();
        if (titles != null) {
            for (Node n = titles.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element && "title".equals(n.getNodeName())) {
                    Element item = (Element)n;
                    if (normal.equalsIgnoreCase(item.getAttribute("locale").replace('_','-')) &&
                            !item.getTextContent().trim().isEmpty())
                        return shorten(item.getTextContent().trim());
                }
            }
        }
        return shorten(value(description, "title"));
    }

    private static String shorten(String input) {
        return input.substring(0, Math.min(96, input.length()));
    }

    String inventory(String language) throws IOException {
        if (Files.notExists(root.toPath())) return "[]";
        File[] children = root.listFiles();
        if (children == null) throw new IOException("WALLPAPER_DIRECTORY_UNREADABLE");
        if (children.length > 512) throw new IOException("WALLPAPER_DIRECTORY_TOO_LARGE");
        List<JSONObject> rows = new ArrayList<>();
        for (File file : children) {
            String id = file.getName();
            if (!validId(id) || !Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            String name = "", error = "";
            long modified = file.lastModified();
            try {
                File slot = slot(id);
                Element description = description(slot);
                name = title(description, language);
                if (!"rearscreen".equals(value(description, "resourceType")))
                    throw new IOException("UNSUPPORTED_WALLPAPER_TYPE");
                File resource = child(slot, "rearscreen");
                modified = resource.lastModified();
                checkArchive(resource);
            } catch (IOException unavailable) { error = unavailable.getMessage(); }
            try {
                rows.add(new JSONObject().put("id", id).put("title", name)
                        .put("modified", modified).put("available", error.isEmpty()));
            } catch (JSONException unexpected) { throw new IOException(unexpected); }
        }
        rows.sort(Comparator.comparingLong((JSONObject row) -> row.optLong("modified")).reversed()
                .thenComparing(row -> row.optString("id")));
        return new JSONArray(rows).toString();
    }

    String copy(String id, boolean thumbnail, OutputStream destination) throws IOException {
        File slot = slot(id);
        requireRearResource(slot);
        File resource = child(slot, "rearscreen");
        if (!thumbnail) {
            try (InputStream input = new FileInputStream(resource)) { transfer(input, destination, MAX_RESOURCE); }
            return "zip";
        }
        // Prefer existing cover media, then the observed AI video. Do not read user source photos.
        try (ZipFile zip = new ZipFile(resource)) {
            for (String name : new String[]{"preview.jpg", "preview.png", "preview.webp", "assets/ai/ai.mp4"}) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() < 1 || entry.getSize() > MAX_THUMBNAIL_MEDIA) continue;
                try (InputStream data = zip.getInputStream(entry)) { transfer(data, destination, MAX_THUMBNAIL_MEDIA); }
                return name.endsWith(".mp4") ? "video" : "image";
            }
        }
        return "none";
    }

    private static void requireRearResource(File slot) throws IOException {
        if (!"rearscreen".equals(value(description(slot), "resourceType")))
            throw new IOException("UNSUPPORTED_WALLPAPER_TYPE");
    }

    static void checkArchive(File file) throws IOException {
        if (file.length() < 1 || file.length() > MAX_RESOURCE) throw new IOException("WALLPAPER_TOO_LARGE");
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() > 512) throw new IOException("UNSUPPORTED_WALLPAPER_ZIP");
            HashSet<String> names = new HashSet<>();
            long expanded = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(0) >= 0 ||
                        Arrays.asList(name.split("/")).contains("..") || !names.add(name) ||
                        entry.getSize() < 0 || (expanded += entry.getSize()) > MAX_RESOURCE * 2)
                    throw new IOException("UNSUPPORTED_WALLPAPER_ZIP");
            }
            ZipEntry manifest = zip.getEntry("manifest.xml");
            if (manifest == null || manifest.isDirectory()) throw new IOException("NO_WALLPAPER_MANIFEST");
            try (InputStream input = zip.getInputStream(manifest)) {
                if (!"Widget".equals(document(bytes(input, 1024 * 1024)).getTagName()))
                    throw new IOException("UNSUPPORTED_WALLPAPER_MANIFEST");
            }
        }
    }

    private static int readBlock(InputStream input, byte[] data) throws IOException {
        int count = 0;
        while (count < data.length) {
            int n = input.read(data, count, data.length - count);
            if (n == -1) break;
            count += n;
        }
        return count;
    }

    static boolean sameBytes(File file, InputStream expected) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            InputStream other = new BufferedInputStream(expected);
            byte[] a = new byte[64 * 1024], b = new byte[a.length];
            long checked = 0;
            for (;;) {
                int count = readBlock(input, a);
                int expectedCount = readBlock(other, b);
                if (count != expectedCount) return false;
                for (int i = 0; i < count; i++) if (a[i] != b[i]) return false;
                if (count == 0) return true;
                checked += count;
                if (checked > MAX_RESOURCE) throw new IOException("WALLPAPER_TOO_LARGE");
            }
        }
    }

    /** Preserve the original inode. The OEM process can read the candidate with the original mode. */
    synchronized void replace(String id, InputStream expected, InputStream candidate) throws IOException {
        File slot = slot(id);
        requireRearResource(slot);
        File resource = child(slot, "rearscreen");
        File preimage = new File(slot, PREIMAGE);
        if (!Files.notExists(preimage.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("RESTORE_WALLPAPER_FIRST");
        File staged = Files.createTempFile(slot.toPath(), ".remrss-", ".tmp").toFile();
        try {
            try (FileOutputStream sink = new FileOutputStream(staged)) {
                transfer(candidate, sink, MAX_RESOURCE);
                sink.getFD().sync();
            }
            checkArchive(staged);
            PosixFileAttributes before = Files.readAttributes(resource.toPath(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes candidateMode = Files.readAttributes(staged.toPath(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // Compare against the untouched OEM metadata owner. A previous Shell-owned 0600
            // resource is not a readable baseline just because Shell also owns the new candidate.
            var themeOwner = Files.getOwner(child(slot, "description.xml").toPath(), LinkOption.NOFOLLOW_LINKS);
            if (!candidateMode.owner().equals(themeOwner) && !before.permissions().contains(PosixFilePermission.OTHERS_READ))
                throw new IOException("WALLPAPER_ACCESS_UNSUPPORTED");
            Files.setPosixFilePermissions(staged.toPath(), before.permissions());
            if (!Files.getPosixFilePermissions(staged.toPath()).equals(before.permissions()))
                throw new IOException("WALLPAPER_ACCESS_UNVERIFIED");
            if (!sameBytes(resource, expected)) throw new IOException("WALLPAPER_CHANGED");
            PosixFileAttributes after = Files.readAttributes(child(slot(id), "rearscreen").toPath(),
                    PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (changed(before, after) || !before.owner().equals(after.owner()) ||
                    !before.permissions().equals(after.permissions()))
                throw new IOException("WALLPAPER_CHANGED");
            Files.move(resource.toPath(), preimage.toPath(), StandardCopyOption.ATOMIC_MOVE);
            try {
                if (!Files.notExists(resource.toPath(), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("WALLPAPER_CHANGED");
                Files.move(staged.toPath(), resource.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                // If an external writer is present, keep the preimage rather than overwriting that writer.
                if (Files.notExists(resource.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    try { Files.move(preimage.toPath(), resource.toPath(), StandardCopyOption.ATOMIC_MOVE); }
                    catch (IOException failedRollback) { failure.addSuppressed(failedRollback); }
                }
                throw failure;
            }
        } finally { Files.deleteIfExists(staged.toPath()); }
    }

    private static boolean changed(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
                !java.util.Objects.equals(before.fileKey(), after.fileKey());
    }

    /** Restore without rebuilding the old archive: the original mode, owner and inode travel with it. */
    synchronized boolean restore(String id, InputStream applied, InputStream original) throws IOException {
        File slot = slot(id);
        requireRearResource(slot);
        File resource = new File(slot, "rearscreen");
        File saved = new File(slot, PREIMAGE);
        if (Files.notExists(saved.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return Files.exists(resource.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    sameBytes(child(slot, "rearscreen"), original);
        }
        File preimage = child(slot, PREIMAGE);
        if (!sameBytes(preimage, original)) return false;
        if (!Files.notExists(resource.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File present = child(slot, "rearscreen");
            BasicFileAttributes before = Files.readAttributes(present.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameBytes(present, applied)) return false;
            if (changed(before, Files.readAttributes(present.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))
                return false;
        }
        Files.move(preimage.toPath(), resource.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
