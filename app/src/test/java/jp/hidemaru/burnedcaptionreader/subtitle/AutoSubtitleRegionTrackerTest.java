package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

public class AutoSubtitleRegionTrackerTest {
    @Test
    public void learnsCenteredChangingSubtitleInsteadOfCornerLabel() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult first = frame("今日は投資について説明します");
        assertNull(tracker.select(0L, first));
        AutoSubtitleRegionTracker.Selection selected = tracker.select(500L, first);
        assertEquals("今日は投資について説明します", selected.getText());

        AutoSubtitleRegionTracker.Selection changed = tracker.select(1_000L,
                frame("次に債券について説明します"));
        assertEquals("次に債券について説明します", changed.getText());
        assertTrue(changed.isLocked());
    }

    @Test
    public void rejectsBrowserUiOnlyFrame() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrLine ui = new OcrLine(0, "YouTube コメント", 95,
                0.25f, 0.75f, 0.75f, 0.81f);
        OcrResult frame = new OcrResult(ui.getText(), 95, Arrays.asList(ui));
        assertNull(tracker.select(0L, frame));
        assertNull(tracker.select(500L, frame));
    }

    private OcrResult frame(String subtitle) {
        OcrLine corner = new OcrLine(0, "番組ロゴ", 90,
                0.02f, 0.08f, 0.18f, 0.13f);
        OcrLine caption = new OcrLine(1, subtitle, 90,
                0.18f, 0.72f, 0.82f, 0.80f);
        return new OcrResult(corner.getText() + "\n" + caption.getText(), 90,
                Arrays.asList(corner, caption));
    }
}
