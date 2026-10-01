package jp.hidemaru.burnedcaptionreader.ocr;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SubtitleCropPlanTest {
    @Test public void missingUpperRowFitsPaddedCrop() {
        SubtitleCropPlan p = SubtitleCropPlan.create(1080, 1920, .25f, .35f);
        assertTrue(p.top < .20f * 1920);
        assertTrue(p.top + p.height > .35f * 1920);
    }
    @Test public void topAndBottomAreClampedInsideVideo() {
        SubtitleCropPlan p = SubtitleCropPlan.create(720, 405, 0, 1);
        assertEquals(0, p.top); assertEquals(405, p.height);
        assertTrue(p.outputWidth <= 2000); assertTrue(p.outputHeight <= 2000);
    }
    @Test public void largeFrameDoesNotCreateUnboundedOcrInput() {
        SubtitleCropPlan p = SubtitleCropPlan.create(3840, 2160, .8f, .95f);
        assertTrue(p.outputWidth <= 2000); assertTrue(p.outputHeight <= 2000);
    }
    @Test(expected=IllegalArgumentException.class) public void invalidBoundsRejected() {
        SubtitleCropPlan.create(720, 405, .9f, .8f);
    }
}
