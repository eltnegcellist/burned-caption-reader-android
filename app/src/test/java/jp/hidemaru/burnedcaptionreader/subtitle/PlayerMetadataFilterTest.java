package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

public final class PlayerMetadataFilterTest {
    @Test
    public void rejectsVideoTitleAndViewCountCluster() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult frame = result(
                line(1, "これは動画内の字幕です", 0.18f, 0.46f, 0.82f, 0.52f),
                line(2, "OCRを改善してみた結果を紹介します", 0.04f, 0.75f, 0.70f, 0.80f),
                line(3, "12万回視聴・3日前", 0.04f, 0.82f, 0.30f, 0.86f));

        tracker.selectAll(0L, frame, false, false);
        List<AutoSubtitleRegionTracker.Selection> selections =
                tracker.selectAll(400L, frame, false, false);

        assertEquals(1, selections.size());
        assertEquals("これは動画内の字幕です", selections.get(0).getText());
    }

    @Test
    public void keepsCenteredLowerBurnedInCaptionEvenWhenMetadataExistsBelowIt() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult frame = result(
                line(1, "画面の下側に出る本物の字幕です", 0.16f, 0.67f, 0.84f, 0.73f),
                line(2, "動画タイトルです", 0.04f, 0.78f, 0.42f, 0.82f),
                line(3, "3.4万回視聴", 0.04f, 0.84f, 0.25f, 0.88f));

        tracker.selectAll(0L, frame, false, false);
        List<AutoSubtitleRegionTracker.Selection> selections =
                tracker.selectAll(400L, frame, false, false);

        assertFalse(selections.isEmpty());
        assertTrue(selections.stream().anyMatch(
                item -> item.getText().contains("本物の字幕")));
        assertFalse(selections.stream().anyMatch(
                item -> item.getText().contains("動画タイトル")));
    }

    private static OcrResult result(OcrLine... lines) {
        StringBuilder text = new StringBuilder();
        for (OcrLine line : lines) {
            if (text.length() > 0) text.append('\n');
            text.append(line.getText());
        }
        return new OcrResult(text.toString(), 90.0, Arrays.asList(lines));
    }

    private static OcrLine line(int block, String text,
                                float left, float top, float right, float bottom) {
        return new OcrLine(block, text, 90.0, left, top, right, bottom);
    }
}
