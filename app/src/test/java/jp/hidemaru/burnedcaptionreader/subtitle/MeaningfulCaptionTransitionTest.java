package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.*;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

public final class MeaningfulCaptionTransitionTest {
    private static OcrResult centered(String text) {
        OcrLine line = new OcrLine(5, text, 94, .22f, .70f, .78f, .755f);
        return new OcrResult(text, 94, java.util.List.of(line));
    }

    private static AutoSubtitleRegionTracker prepared(String initial) {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        for (int i = 0; i < 7; i++) {
            tracker.selectAll(i * 480L, centered(initial), false);
        }
        return tracker;
    }

    @Test public void changingNumberBecomesLockedAfterTwoFrames() {
        AutoSubtitleRegionTracker t = prepared("本日の価格は100円です");
        // Static provisional caption expires and ordinary similarity would
        // otherwise keep treating the new number as the same old caption.
        assertTrue(t.selectAll(3360L, centered("本日の価格は100円です"), false).isEmpty());
        assertTrue(t.selectAll(3840L, centered("本日の価格は200円です"), false).isEmpty());
        List<AutoSubtitleRegionTracker.Selection> selected =
                t.selectAll(4320L, centered("本日の価格は200円です"), false);
        assertEquals(1, selected.size());
        assertEquals("本日の価格は200円です", selected.get(0).getText());
        assertTrue(selected.get(0).isLocked());
    }

    @Test public void changedNegationIsRecognizedAfterSecondFrame() {
        AutoSubtitleRegionTracker t = prepared("本日はサービスを利用できます");
        assertTrue(t.selectAll(3360L, centered("本日はサービスを利用できます"), false).isEmpty());
        assertTrue(t.selectAll(3840L, centered("本日はサービスを利用できません"), false).isEmpty());
        List<AutoSubtitleRegionTracker.Selection> selected =
                t.selectAll(4320L, centered("本日はサービスを利用できません"), false);
        assertEquals(1, selected.size());
        assertEquals("本日はサービスを利用できません", selected.get(0).getText());
        assertTrue(selected.get(0).isLocked());
    }

    @Test public void transientWrongNumberDoesNotConfirmTransition() {
        AutoSubtitleRegionTracker t = prepared("本日の価格は100円です");
        t.selectAll(3360L, centered("本日の価格は100円です"), false);
        assertTrue(t.selectAll(3840L, centered("本日の価格は200円です"), false).isEmpty());
        assertTrue(t.selectAll(4320L, centered("本日の価格は100円です"), false).isEmpty());
        assertTrue(t.selectAll(4800L, centered("本日の価格は100円です"), false).isEmpty());
    }

    @Test public void fixedShortPriceLabelIsNotTurnedIntoSubtitle() {
        AutoSubtitleRegionTracker t = new AutoSubtitleRegionTracker();
        OcrResult first = new OcrResult("価格100円", 96,
                java.util.List.of(new OcrLine(1, "価格100円", 96, .40f, .50f, .60f, .545f)));
        OcrResult second = new OcrResult("価格200円", 96,
                java.util.List.of(new OcrLine(1, "価格200円", 96, .40f, .50f, .60f, .545f)));
        for (int i = 0; i < 6; i++) assertTrue(t.selectAll(480L * i, first, false).isEmpty());
        for (int i = 6; i < 12; i++) assertTrue(t.selectAll(480L * i, second, false).isEmpty());
    }

    @Test public void staticUnchangedLongCaptionDoesNotAcquireLock() {
        AutoSubtitleRegionTracker t = prepared("本日の価格は100円です");
        for (int i = 7; i <= 10; i++)
            assertTrue(t.selectAll(i * 480L, centered("本日の価格は100円です"), false).isEmpty());
    }
}
