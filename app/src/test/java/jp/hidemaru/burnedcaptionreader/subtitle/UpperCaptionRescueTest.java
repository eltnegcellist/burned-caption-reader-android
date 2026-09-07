package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

public class UpperCaptionRescueTest {
    @Test
    public void rescuesShortNarrowUpperJapaneseCaptionAfterTwoStableObservations() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrLine caption = new OcrLine(0, "ここです", 92,
                0.36f, 0.24f, 0.64f, 0.31f);
        OcrResult frame = new OcrResult(caption.getText(), 92, Arrays.asList(caption));

        assertNull(tracker.select(0L, frame, false));
        AutoSubtitleRegionTracker.Selection selected = tracker.select(500L, frame, false);

        assertEquals("ここです", selected.getText());
    }

    @Test
    public void stillRejectsShortStaticMiddleObjectLabel() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrLine label = new OcrLine(0, "防水性能", 96,
                0.40f, 0.48f, 0.60f, 0.55f);
        OcrResult frame = new OcrResult(label.getText(), 96, Arrays.asList(label));

        assertNull(tracker.select(0L, frame, false));
        assertNull(tracker.select(500L, frame, false));
        assertNull(tracker.select(1_500L, frame, false));
    }

    @Test
    public void doesNotRescueVeryLowConfidenceUpperText() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrLine noisy = new OcrLine(0, "ここです", 45,
                0.36f, 0.24f, 0.64f, 0.31f);
        OcrResult frame = new OcrResult(noisy.getText(), 45, Arrays.asList(noisy));

        assertNull(tracker.select(0L, frame, false));
        assertNull(tracker.select(500L, frame, false));
    }
}
