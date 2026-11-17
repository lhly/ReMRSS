package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Test;

public class RearInputProxyInstalledReaderTest {
    private static final String APP = "top.zainzh.rearscreen.debug";
    private static final String LIB = "libremrss_input_proxy.so";
    private static final String INSTALLED = "/data/app/~~test/" + APP + "-install/lib/arm64/";

    @Test public void acceptsTheLibraryPackagedAndRequestedByTheHost() {
        assertTrue(RearInputProxy.isInstalledReader(new File(INSTALLED, LIB), APP, true));
    }

    @Test public void stillRejectsWrongNameOwnerLocationAndNonExecutableFiles() {
        assertFalse(RearInputProxy.isInstalledReader(new File(INSTALLED, "libremss_input_proxy.so"), APP, true));
        assertFalse(RearInputProxy.isInstalledReader(new File(INSTALLED, LIB), APP, false));
        assertFalse(RearInputProxy.isInstalledReader(
                new File("/data/app/~~test/top.zainzh.rearscreen-install/lib/arm64/", LIB), APP, true));
        assertFalse(RearInputProxy.isInstalledReader(new File("/data/local/tmp/" + APP + "-install/", LIB), APP, true));
    }
}
