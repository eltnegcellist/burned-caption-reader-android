package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

public class ThreeLineCaptionRescueTest {
    @Test
    public void keepsLowConfidenceTopRowWhenAdjacentRowsAreStrong() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrLine top = new OcrLine(0, "ところが", 24,
                0.34f, 0.20f, 0.66f, 0.24f);
        OcrLine middle = new OcrLine(1, "私にも責任のある仕事が任されました", 96,
                0.10f, 0.30f, 0.90f, 0.35f);
        OcrLine bottom = new OcrLine(2, "自分の判断で行動する必要があります", 96,
                0.10f, 0.41f, 0.90f, 0.46f);
        String complete = top.getText() + "\n" + middle.getText() + "\n" + bottom.getText();
        OcrResult frame = new OcrResult(complete, 72, Arrays.asList(top, middle, bottom));

        assertTrue(tracker.selectAll(0L, frame, false).isEmpty());
        List<AutoSubtitleRegionTracker.Selection> selected =
                tracker.selectAll(500L, frame, false);

        assertEquals(1, selected.size());
        assertEquals(complete, selected.get(0).getText());
    }

    @Test
    public void permitsThirdNearbyRowWhenSpatialGroupingBarelyFails() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrLine top = new OcrLine(0, "最初の字幕行です", 92,
                0.22f, 0.18f, 0.78f, 0.23f);
        OcrLine middle = new OcrLine(1, "二番目の字幕行です", 92,
                0.22f, 0.30f, 0.78f, 0.35f);
        OcrLine bottom = new OcrLine(2, "三番目の字幕行です", 92,
                0.22f, 0.42f, 0.78f, 0.47f);
        OcrResult frame = new OcrResult("", 92, Arrays.asList(top, middle, bottom));

        tracker.selectAll(0L, frame, false);
        List<AutoSubtitleRegionTracker.Selection> selected =
                tracker.selectAll(500L, frame, false);

        assertEquals(3, selected.size());
        assertEquals(top.getText(), selected.get(0).getText());
        assertEquals(middle.getText(), selected.get(1).getText());
        assertEquals(bottom.getText(), selected.get(2).getText());
    }
}
