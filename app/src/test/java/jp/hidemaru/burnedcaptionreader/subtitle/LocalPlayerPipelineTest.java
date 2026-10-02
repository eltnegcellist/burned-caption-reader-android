package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;

/** OCR result fixtures, not a claim of device OCR accuracy or Activity coverage. */
public final class LocalPlayerPipelineTest {
    @Test public void refinedThreeRowsReachSpeechInTopToBottomOrderOnlyOnce() {
        String top = "いやー凡人会社員の私には";
        String middle = "縁が無い世界だなー…";
        String bottom = "と思いきや";
        String coarse = middle + "\n" + bottom;
        OcrResult result = new OcrResult("", 85, Arrays.asList(
                new OcrLine(0, bottom, 90, .2f, .44f, .8f, .56f),
                new OcrLine(0, top, 24, .2f, .10f, .8f, .22f),
                new OcrLine(0, middle, 90, .2f, .27f, .8f, .39f)));
        OcrRefinementSelector.Result refined = new OcrRefinementSelector().select(coarse, 85, result);
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config(); config.stableMs = 300;
        SubtitleStabilizer s = new SubtitleStabilizer(config);
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        for (long now = 0; now <= 960; now += 480) {
            TemporalOcrConsensus.Result text = c.observe(now, refined.getText(), refined.getConfidence(), coarse);
            SubtitleEvent event = s.observe(now, text.getText(), text.getConfidence());
            if (now == 480) {
                assertNotNull(event);
                RowSpeechLedger.Reservation r = ledger.reserve(event);
                assertEquals(SubtitleNormalizer.normalize(top + "\n" + middle + "\n" + bottom), r.getText());
                assertTrue(ledger.complete(r.getId(), 500));
            } else assertNull(event);
        }
    }
    @Test public void numberAndNegationChangesSurviveFusionAndStabilization() {
        verifyChange("本日の価格は100円です", "本日の価格は200円です");
        verifyChange("このサービスを利用できます", "このサービスを利用できません");
    }
    private void verifyChange(String first, String next) {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config(); config.stableMs = 300;
        SubtitleStabilizer s = new SubtitleStabilizer(config);
        for (int n=0; n<4; n++) {
            String raw = n<2 ? first : next;
            TemporalOcrConsensus.Result r = c.observe(n*480, raw, 85);
            SubtitleEvent event = s.observe(n*480, r.getText(), r.getConfidence());
            if (n%2==1) { assertNotNull(event); assertEquals(raw, event.getText()); }
            else assertNull(event);
        }
    }
}
