package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Copies only synthetic test pixels into the owned emulator's shell capture directory. */
final class VisualAuditBitmap {
    private VisualAuditBitmap() {}
    static void write(Instrumentation instrumentation, String group, String name, Bitmap frame) throws IOException {
        if (!group.matches("[a-z0-9-]+") || !name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Unsafe visual capture name");
        String path = "/data/local/tmp/remrss-visual-audit/" + group;
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("mkdir -p " + path))) {
            in.readAllBytes();
        }
        ParcelFileDescriptor[] pipe = instrumentation.getUiAutomation().executeShellCommandRw("dd of=" + path + "/" + name + ".jpg");
        try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
            if (!frame.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("JPEG capture write failed");
        }
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) { in.readAllBytes(); }
    }
}
