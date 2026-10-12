package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;

public class CaptionFrameShadowTest {
    private static CaptionFrameShadow.PixelSource frame(int value) {
        return (x, y) -> 0xff000000 | (value << 16) | (value << 8) | value;
    }
    @Test public void untrustedFrameCannotBeSkipped() {
        CaptionFrameShadow shadow = new CaptionFrameShadow();
        assertFalse(shadow.probe(frame(12), 640, 360, 1, 0).wouldSkip);
        assertEquals("no_recent_locked_lane", shadow.probe(frame(12), 640, 360, 1, 480).reason);
    }
    @Test public void shadowSkipsAtMostOneThenForcesOcr() {
        CaptionFrameShadow s = new CaptionFrameShadow();
        s.trust(1, .6f, .8f, 0);
        assertFalse(s.probe(frame(24), 640, 360, 1, 0).wouldSkip);
        s.trust(1, .6f, .8f, 480);
        assertTrue(s.probe(frame(24), 640, 360, 1, 480).wouldSkip);
        assertFalse(s.probe(frame(24), 640, 360, 1, 960).wouldSkip);
    }
    @Test public void generationAndGeometryResetReference() {
        CaptionFrameShadow s = new CaptionFrameShadow();
        s.trust(1, .60f, .75f, 0);
        s.probe(frame(12), 640, 360, 1, 0);
        assertFalse(s.probe(frame(12), 640, 360, 2, 480).wouldSkip);
        s.trust(2, .10f, .25f, 500);
        assertFalse(s.probe(frame(12), 640, 360, 2, 510).wouldSkip);
    }
    @Test public void scenePixelChangeForcesOcr() {
        CaptionFrameShadow s = new CaptionFrameShadow();
        s.trust(1, .6f, .8f, 0);
        s.probe(frame(12), 640, 360, 1, 0);
        assertFalse(s.probe(frame(245), 640, 360, 1, 480).wouldSkip);
    }
    @Test public void equalBrightnessDifferentColorMustNotBeSkipped() {
        CaptionFrameShadow s = new CaptionFrameShadow();
        // Red(100) and green(51) have near-identical quantized luma.
        CaptionFrameShadow.PixelSource red = (x, y) -> 0xff640000;
        CaptionFrameShadow.PixelSource green = (x, y) -> 0xff003300;
        s.trust(1, .60f, .80f, 0);
        s.probe(red, 640, 360, 1, 0);
        CaptionFrameShadow.Prediction observed = s.probe(green, 640, 360, 1, 480);
        assertTrue(observed.bandMeanDelta <= 3.0);
        assertTrue(observed.bandColorDelta > 4.0);
        assertFalse(observed.wouldSkip);
    }

    @Test public void changedSingleSampledCellCannotBeHiddenByLowMean() {
        CaptionFrameShadow s = new CaptionFrameShadow();
        CaptionFrameShadow.PixelSource dark = (x, y) -> 0xff000000;
        CaptionFrameShadow.PixelSource changed = (x, y) ->
                x >= 135 && x <= 145 && y >= 232 && y <= 239
                        ? 0xffee0000 : 0xff000000;
        s.trust(1, .60f, .80f, 0);
        s.probe(dark, 640, 360, 1, 0);
        CaptionFrameShadow.Prediction result = s.probe(changed, 640, 360, 1, 480);
        assertTrue("Small-area color delta should be diluted in the mean",
                result.bandColorDelta < 4.0);
        assertTrue(result.bandPeakChannelDelta >= 200);
        assertFalse("One changed sampled digit must force OCR", result.wouldSkip);
    }

    @Test public void rescueProbeNeedsTrustedBandContrastAndNoExistingStrip() {
        CaptionFrameShadow s = new CaptionFrameShadow();
        CaptionFrameShadow.PixelSource contrast = (x, y) -> ((x / 10) % 2 == 0) ? 0xffeeeeee : 0xff111111;
        assertFalse(s.inspectMissing(contrast, 480, 270, 0, true, false).wouldRescue);
        s.trust(1, .60f, .8f, 0);
        assertFalse(s.inspectMissing(contrast, 480, 270, 0, true, true).wouldRescue);
        assertTrue(s.inspectMissing(contrast, 480, 270, 0, true, false).wouldRescue);
        assertFalse(s.inspectMissing(contrast, 480, 270, 480, true, false).wouldRescue);
        assertTrue(s.inspectMissing(contrast, 480, 270, 960, true, false).wouldRescue);
    }
}
