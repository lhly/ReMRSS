package top.zainzh.rearscreen;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class RearCastOrientationTest {
    @Test public void firstCastPreservesSourceAspectRatherThanRearPanelAspect() {
        assertEquals(1, RearCastOrientation.rotationFor(Configuration.ORIENTATION_PORTRAIT, 680, 596));
        assertEquals(0, RearCastOrientation.rotationFor(Configuration.ORIENTATION_LANDSCAPE, 680, 596));
        assertEquals(0, RearCastOrientation.rotationFor(Configuration.ORIENTATION_PORTRAIT, 596, 680));
        assertEquals(1, RearCastOrientation.rotationFor(Configuration.ORIENTATION_LANDSCAPE, 596, 680));
    }

    @Test public void ambiguousSourceOrRearGeometryDoesNotInventOrientation() {
        assertEquals(0, RearCastOrientation.rotationFor(Configuration.ORIENTATION_UNDEFINED, 680, 596));
        assertEquals(0, RearCastOrientation.rotationFor(Configuration.ORIENTATION_PORTRAIT, 596, 596));
        assertEquals(Configuration.ORIENTATION_UNDEFINED,
                RearCastOrientation.declaredOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR));
    }

    @Test public void coldStartUsesOnlyADeclaredAxis() {
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
                RearCastOrientation.declaredOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT));
        assertEquals(Configuration.ORIENTATION_LANDSCAPE,
                RearCastOrientation.declaredOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
    }
}
