package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

/**
 * Replays recorded coarse OCR rows, geometry and timestamps (not reconstructed
 * images) from PR #32 results/android-hybrid-dense.json, control-1 frames:
 * 0030 (100yen), 0090 (blank), 0130 (200yen). Ground truth is the independent
 * synthetic render specification. Actual OCR and refinement are NOT replayed.
 */
public final class RecordedPriceBandRegressionTest {
    private static final String PRICE_100 = "本日の価格は100円です";
    private static final String PRICE_200 = "本日の価格は200円です";

    private static OcrResult frame(String price) {
        OcrLine header = new OcrLine(0, "検証用の固定見出し", 81.72743,
                .027058823f, .048117153f, .25647059f, .09205021f);
        if (price == null) {
            return new OcrResult(header.getText(), 81.72743, Arrays.asList(header));
        }
        OcrLine caption = new OcrLine(1, price, 84.798175,
                .30588236f, .86820084f, .6870588f, .92677826f);
        return new OcrResult(header.getText() + "\n" + price, 83.2628,
                Arrays.asList(header, caption));
    }

    @Test public void knownRawCorrectSelectionLossIsRecoveredWithoutSelectingFixedHeader() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        int found100 = 0, found200 = 0, extras = 0;

        // Original frame timestamps (ms) from Mac's already-public synthetic
        // diagnostic: eight repeated image observations, five blank observations.
        for (int i = 0; i < 8; i++) {
            List<AutoSubtitleRegionTracker.Selection> selections =
                    tracker.selectForRecognition(1000 + 480L * i, frame(PRICE_100));
            for (AutoSubtitleRegionTracker.Selection s : selections) {
                if (PRICE_100.equals(s.getText())) found100++;
                else extras++;
            }
        }
        for (int i = 0; i < 5; i++) {
            List<AutoSubtitleRegionTracker.Selection> selections =
                    tracker.selectForRecognition(7000 + 480L * i, frame(null));
            extras += selections.size();
        }
        for (int i = 0; i < 8; i++) {
            List<AutoSubtitleRegionTracker.Selection> selections =
                    tracker.selectForRecognition(11000 + 480L * i, frame(PRICE_200));
            for (AutoSubtitleRegionTracker.Selection s : selections) {
                if (PRICE_200.equals(s.getText())) found200++;
                else extras++;
            }
        }
        assertTrue("Existing 100-yen caption must not regress", found100 >= 5);
        assertEquals("Recorded 200-yen caption must be selected in all 7 post-corroboration frames", 7, found200);
        assertEquals("Fixed header and blank frames must never be selected", 0, extras);
    }

    @Test public void meaningfulTokenChangeRequiresTwoCorroboratingFrames() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        for (int i = 0; i < 8; i++)
            tracker.selectForRecognition(1000 + 480L * i, frame(PRICE_100));
        for (int i = 0; i < 5; i++)
            tracker.selectForRecognition(7000 + 480L * i, frame(null));
        List<AutoSubtitleRegionTracker.Selection> first =
                tracker.selectForRecognition(11000, frame(PRICE_200));
        assertTrue(first.isEmpty());
        List<AutoSubtitleRegionTracker.Selection> second =
                tracker.selectForRecognition(11480, frame(PRICE_200));
        assertFalse(second.isEmpty());
        assertEquals(PRICE_200, second.get(0).getText());
        assertTrue(second.get(0).isLocked());
    }
}
