package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RearCastRenderDensityTest {
    @Test public void defaultVirtualCastUsesSourceDensityWithoutChangingAppLayout() {
        RearCastViewport safe = RearCastViewport.safe(976, 596, 0, 450, 296, 0, 0, 0);
        int layout = RearCastCompatibility.layoutDensity(false, 200, 240, false);
        int render = RearCastCompatibility.automaticRenderDensity(layout, 480, safe);
        RearCastViewport output = RearCastCompatibility.viewport(safe, layout, render);

        assertEquals(200, layout);
        assertEquals(480, render);
        assertEquals(1632, output.bufferWidth);
        assertEquals(1430, output.bufferHeight);
        assertEquals(544, Math.round(output.bufferWidth * 160f / render));
        assertEquals(477, Math.round(output.bufferHeight * 160f / render));
    }

    @Test public void autoProfileDoesNotDownsampleHigherRequestedDensity() {
        RearCastViewport safe = RearCastViewport.safe(976, 596, 0, 450, 296, 0, 0, 0);
        assertEquals(520, RearCastCompatibility.automaticRenderDensity(520, 480, safe));
        assertEquals(480, RearCastCompatibility.density(true, 520, 480));
    }

    @Test public void autoProfileKeepsFullAreaAccessibleWhenSourceBufferWouldBeTooLarge() {
        RearCastViewport narrowArea = RearCastViewport.safe(1200, 600, 0, 450, 600, 0, 0, 0);
        assertEquals(72, RearCastCompatibility.automaticRenderDensity(72, 720, narrowArea));
        RearCastViewport full = RearCastCompatibility.viewport(narrowArea.full(), 72, 72);
        assertEquals(1200, full.bufferWidth);
        assertEquals(600, full.bufferHeight);
    }
}
