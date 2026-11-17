package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RearFreezeStateTransferTest {
    @Test public void oneJournaledGuardCanAddAndRollbackOnlyTheSecondExactOwner() throws Exception {
        AtomicReference<String> setting = new AtomicReference<>("user.pkg, keep.pkg");
        java.io.File dir = Files.createTempDirectory("remrss-freeze-transfer").toFile();
        try (RearFreezeState state = new RearFreezeState(new RearFreezeState.Store() {
            @Override public String read() { return setting.get(); }
            @Override public void write(String value) { setting.set(value); }
        }, dir)) {
            state.ensureAll(new String[]{"owner.a"});
            assertEquals("user.pkg, keep.pkg, owner.a", setting.get());
            state.ensureAll(new String[]{"owner.a", "owner.b"});
            assertEquals("user.pkg, keep.pkg, owner.a, owner.b", setting.get());
            state.ensureAll(new String[]{"owner.a"});
            assertEquals("user.pkg, keep.pkg, owner.a", setting.get());
            assertThrows(Exception.class, () -> state.ensureAll(new String[]{"owner.a", "owner.c", "owner.d"}));
            state.restore();
            assertEquals("user.pkg, keep.pkg", setting.get());
        } finally {
            for (java.io.File file : java.util.Objects.requireNonNull(dir.listFiles())) file.delete();
            dir.delete();
        }
    }
}
