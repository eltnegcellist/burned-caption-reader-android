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

    @Test
    public void transientMissingTopRowKeepsCompleteThreeLineCaption() {
        SubtitleStabilizer stabilizer = create();
        String complete = "会社員の私には\n縁がない世界だと\n思っていました";
        String missingTop = "縁がない世界だと\n思っていました";

        assertNull(stabilizer.observe(0L, complete, 90));
        assertNull(stabilizer.observe(150L, missingTop, 92));
        SubtitleEvent event = stabilizer.observe(350L, missingTop, 92);

        assertEquals(complete, event.getText());
        assertNull(stabilizer.observe(500L, missingTop, 92));
        assertNull(stabilizer.observe(850L, missingTop, 92));
        assertNull(stabilizer.observe(1_200L, complete, 90));
    }

    @Test
    public void higherConfidencePartialDoesNotWinATieAgainstCompleteCaption() {
        SubtitleStabilizer stabilizer = create();
        String complete = "会社員の私には\n縁がない世界だと\n思っていました";
        stabilizer.observe(0L, complete, 85);
        SubtitleEvent event = stabilizer.observe(350L, "縁がない世界だと\n思っていました", 95);
        assertEquals(complete, event.getText());
    }

    @Test
    public void growingMultilineCaptionRestartsTheStabilityTimer() {
        SubtitleStabilizer stabilizer = create();
        String partial = "会社員の私には\n縁がない世界だと";
        String complete = partial + "\n思っていました";
        assertNull(stabilizer.observe(0L, partial, 90));
        assertNull(stabilizer.observe(200L, partial, 90));
        assertNull(stabilizer.observe(350L, complete, 90));
        assertNull(stabilizer.observe(500L, complete, 90));
        assertEquals(complete, stabilizer.observe(700L, complete, 90).getText());
    }

    @Test
    public void changingTopRowStillProducesANewSubtitle() {
        SubtitleStabilizer stabilizer = create();
        String first = "これは最初の長い説明になります\n条件を確認して\n判断してください";
        String second = "明日は別の場所に全員集合します\n条件を確認して\n判断してください";
        stabilizer.observe(0L, first, 90);
        assertEquals(first, stabilizer.observe(350L, first, 90).getText());
        assertNull(stabilizer.observe(500L, second, 90));
        assertEquals(second, stabilizer.observe(850L, second, 90).getText());
    }
}
