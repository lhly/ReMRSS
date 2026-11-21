package top.zainzh.rearscreen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;

/** Identifies the reversible overlay produced by ReMRSS, not just a similarly named asset. */
final class AiRearWallpaperLegacy {
    private static final String MANIFEST = "manifest.xml";
    private static final String ASSETS = "remrss_ai/";
    private static final String OVERLAY_START = "<Var name=\"remrssAiPressedSlot\"";
    private static final String WIDGET_END = "</Widget>";
    private static final int MANIFEST_LIMIT = 1024 * 1024;
    private static final String PACKAGE = "top.zainzh.rearscreen";
    private static final String ACTIVATION = PACKAGE + ".AiRearWallpaperActivationActivity";
    private static final Set<String> OWNERS = Set.of(PACKAGE, PACKAGE + ".debug",
            PACKAGE + ".compat", PACKAGE + ".rootdebug");
    private static final Pattern TARGET = Pattern.compile("remrss-ai-activation://([0-9a-f]{32})/([0-5])");

    private AiRearWallpaperLegacy() {}

    /** null is an untouched carrier. Neither the old overlay nor its assets are used as render input. */
    static String owner(File live, File backup) throws IOException {
        try (ZipFile modified = new ZipFile(live)) {
            String markup = manifest(modified);
            boolean looksModified = markup.contains(OVERLAY_START) || modified.stream()
                    .anyMatch(entry -> entry.getName().startsWith(ASSETS));
            if (!looksModified && backup == null) return null;
            if (!looksModified) throw new IOException("WALLPAPER_LEGACY_UNVERIFIED");

            if (backup == null) throw new IOException("WALLPAPER_LEGACY_BACKUP_INVALID");
            AiRearWallpaperFiles.checkArchive(live);
            try (ZipFile original = new ZipFile(backup)) {
                AiRearWallpaperFiles.checkArchive(backup);
                if (!file(original, "var_config.xml") || !file(original, "assets/ai/ai.mp4"))
                    throw new IOException("WALLPAPER_LEGACY_BACKUP_INVALID");
                String clean = manifest(original).stripTrailing();
                if (!clean.endsWith(WIDGET_END) || clean.contains(OVERLAY_START))
                    throw new IOException("WALLPAPER_LEGACY_BACKUP_INVALID");
                verifyOriginalEntries(original, modified);
                String prefix = clean.substring(0, clean.length() - WIDGET_END.length());
                if (!markup.startsWith(prefix + OVERLAY_START))
                    throw new IOException("WALLPAPER_LEGACY_UNVERIFIED");
                return activationOwner(AiRearWallpaperFiles.document(
                        ("<Widget>" + markup.substring(prefix.length())).getBytes(StandardCharsets.UTF_8)));
            } catch (IOException failure) {
                if ("WALLPAPER_LEGACY_UNVERIFIED".equals(failure.getMessage()) ||
                        "WALLPAPER_LEGACY_BACKUP_INVALID".equals(failure.getMessage())) throw failure;
                throw new IOException("WALLPAPER_LEGACY_BACKUP_INVALID", failure);
            }
        }
    }

    private static boolean file(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        return entry != null && !entry.isDirectory();
    }

    private static String manifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST);
        if (entry == null || entry.isDirectory() || entry.getSize() > MANIFEST_LIMIT)
            throw new IOException("NO_WALLPAPER_MANIFEST");
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MANIFEST_LIMIT + 1);
            if (bytes.length > MANIFEST_LIMIT) throw new IOException("UNSUPPORTED_WALLPAPER_MANIFEST");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void verifyOriginalEntries(ZipFile original, ZipFile modified) throws IOException {
        for (var entries = original.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(ASSETS)) throw new IOException("WALLPAPER_LEGACY_BACKUP_INVALID");
            if (MANIFEST.equals(name)) continue;
            ZipEntry counterpart = modified.getEntry(name);
            if (counterpart == null || counterpart.getSize() != entry.getSize() || counterpart.getCrc() != entry.getCrc())
                throw new IOException("WALLPAPER_LEGACY_UNVERIFIED");
        }
        if (modified.stream().anyMatch(entry ->
                !entry.getName().startsWith(ASSETS) && original.getEntry(entry.getName()) == null))
            throw new IOException("WALLPAPER_LEGACY_UNVERIFIED");
    }

    private static String activationOwner(Element overlay) throws IOException {
        var commands = overlay.getElementsByTagName("IntentCommand");
        if (commands.getLength() < 1 || commands.getLength() > 6)
            throw new IOException("WALLPAPER_LEGACY_UNVERIFIED");
        String owner = null;
        String token = null;
        Set<String> slots = new HashSet<>();
        for (int i = 0; i < commands.getLength(); i++) {
            Element command = (Element) commands.item(i);
            String target = command.getAttribute("package");
            var uri = TARGET.matcher(command.getAttribute("uri"));
            if (!OWNERS.contains(target) || !ACTIVATION.equals(command.getAttribute("class")) ||
                    !"android.intent.action.VIEW".equals(command.getAttribute("action")) ||
                    !"aiBundle".equals(command.getAttribute("activityOption")) || !uri.matches() ||
                    (owner != null && !owner.equals(target)) || (token != null && !token.equals(uri.group(1))) ||
                    !slots.add(uri.group(2)))
                throw new IOException("WALLPAPER_LEGACY_UNVERIFIED");
            owner = target;
            token = uri.group(1);
        }
        return owner;
    }
}
