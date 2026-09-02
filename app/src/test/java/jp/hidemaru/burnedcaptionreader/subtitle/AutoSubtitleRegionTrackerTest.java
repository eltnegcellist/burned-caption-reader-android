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

    @Test
    public void portraitVideoViewportRejectsTitleAndCommentsOutsidePlayer() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult frame = portraitBrowserFrame("字幕だけを読み上げます");

        assertNull(tracker.select(0L, frame, true));
        AutoSubtitleRegionTracker.Selection selected = tracker.select(500L, frame, true);

        assertEquals("字幕だけを読み上げます", selected.getText());
    }

    private OcrResult frame(String subtitle) {
        OcrLine corner = new OcrLine(0, "番組ロゴ", 90,
                0.02f, 0.08f, 0.18f, 0.13f);
        OcrLine caption = new OcrLine(1, subtitle, 90,
                0.18f, 0.72f, 0.82f, 0.80f);
        return new OcrResult(corner.getText() + "\n" + caption.getText(), 90,
                Arrays.asList(corner, caption));
    }

    private OcrResult portraitBrowserFrame(String subtitle) {
        OcrLine addressBar = new OcrLine(0, "youtube.com", 98,
                0.20f, 0.04f, 0.80f, 0.10f);
        OcrLine caption = new OcrLine(1, subtitle, 92,
                0.16f, 0.64f, 0.84f, 0.74f);
        OcrLine videoTitle = new OcrLine(2, "知っておきたい投資の基本を完全解説", 98,
                0.05f, 0.90f, 0.95f, 0.95f);
        OcrLine comment = new OcrLine(3, "とても参考になりました。ありがとうございます", 98,
                0.05f, 0.96f, 0.95f, 0.99f);
        return new OcrResult(addressBar.getText() + "\n" + caption.getText() + "\n"
                + videoTitle.getText() + "\n" + comment.getText(), 95,
                Arrays.asList(addressBar, caption, videoTitle, comment));
    }
}
