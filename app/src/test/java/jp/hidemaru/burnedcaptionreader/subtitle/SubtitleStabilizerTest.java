package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SubtitleStabilizerTest {
    private SubtitleStabilizer create() {
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config();
        config.stableMs = 300L;
        config.minConfidence = 40.0;
        config.minStableObservations = 2;
        return new SubtitleStabilizer(config);
    }

    @Test
    public void repeatedSubtitleCommitsOnce() {
        SubtitleStabilizer stabilizer = create();
        List<SubtitleEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SubtitleEvent event = stabilizer.observe(i * 100L, "今日は投資について説明します", 90);
            if (event != null) events.add(event);
        }
        assertEquals(1, events.size());
    }

    @Test
    public void growingSubtitleCommitsOnlyCompletedText() {
        SubtitleStabilizer stabilizer = create();
        assertNull(stabilizer.observe(0, "今日は", 90));
        assertNull(stabilizer.observe(100, "今日は投資", 90));
        assertNull(stabilizer.observe(200, "今日は投資について", 90));
        assertNull(stabilizer.observe(300, "今日は投資について説明します", 90));
        assertNull(stabilizer.observe(450, "今日は投資について説明します", 90));
        SubtitleEvent event = stabilizer.observe(650, "今日は投資について説明します", 90);
        assertEquals("今日は投資について説明します", event.getText());
    }

    @Test
    public void oneCharacterOcrWobbleUsesMajorityVariant() {
        SubtitleStabilizer stabilizer = create();
        stabilizer.observe(0, "今日は投資について説明します", 90);
        stabilizer.observe(150, "今日は投資について説明しま寸", 80);
        SubtitleEvent event = stabilizer.observe(350, "今日は投資について説明します", 90);
        assertEquals("今日は投資について説明します", event.getText());
        assertTrue(Similarity.textSimilarity("説明します", "説明しま寸") >= 0.8);
    }

    @Test
    public void lowConfidenceFrameDoesNotReplaceCandidate() {
        SubtitleStabilizer stabilizer = create();
        stabilizer.observe(0, "今日は投資について説明します", 90);
        stabilizer.observe(100, "誤字幕", 10);
        stabilizer.observe(200, "今日は投資について説明します", 90);
        SubtitleEvent event = stabilizer.observe(350, "今日は投資について説明します", 90);
        assertEquals("今日は投資について説明します", event.getText());
    }

    @Test
    public void intermittentBlankDoesNotCauseDuplicateReading() {
        SubtitleStabilizer stabilizer = create();
        stabilizer.observe(0, "繰り返す字幕", 90);
        stabilizer.observe(150, "繰り返す字幕", 90);
        assertTrue(stabilizer.observe(350, "繰り返す字幕", 90) != null);
        stabilizer.observe(500, "", 100);
        stabilizer.observe(1_200, "", 100);
        stabilizer.observe(1_300, "繰り返す字幕", 90);
        stabilizer.observe(1_500, "繰り返す字幕", 90);
        assertNull(stabilizer.observe(1_650, "繰り返す字幕", 90));
    }

    @Test
    public void sameTextMayReturnAfterLongAbsence() {
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config();
        config.stableMs = 300L;
        config.minStableObservations = 2;
        config.repeatAfterMs = 1_000L;
        SubtitleStabilizer stabilizer = new SubtitleStabilizer(config);
        stabilizer.observe(0, "後で繰り返す字幕", 90);
        stabilizer.observe(150, "後で繰り返す字幕", 90);
        assertTrue(stabilizer.observe(350, "後で繰り返す字幕", 90) != null);
        stabilizer.observe(500, "", 100);
        stabilizer.observe(1_200, "", 100);
        stabilizer.observe(1_500, "後で繰り返す字幕", 90);
        stabilizer.observe(1_700, "後で繰り返す字幕", 90);
        assertTrue(stabilizer.observe(1_850, "後で繰り返す字幕", 90) != null);
    }

    @Test
    public void multilineNormalizationPreservesOrder() {
        String normalized = SubtitleNormalizer.normalize(" これは非常に重要です \n なぜなら～～だからです ");
        assertEquals("これは非常に重要です\nなぜなら〜だからです", normalized);
        assertEquals("これは非常に重要です、なぜなら〜だからです", SubtitleNormalizer.toSpeechText(normalized));
    }
}
