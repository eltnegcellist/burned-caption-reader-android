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
    public void portraitVideoViewportKeepsCaptionAtBottomEdge() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult frame = portraitBrowserFrame("字幕だけを読み上げます");

        assertNull(tracker.select(0L, frame, true));
        AutoSubtitleRegionTracker.Selection selected = tracker.select(500L, frame, true);

        assertEquals("字幕だけを読み上げます", selected.getText());
    }

    @Test
    public void portraitVideoViewportPrefersCaptionOverWatchDialText() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult frame = watchFrame("比較する時計は最新のムーブメントを搭載しています");

        assertNull(tracker.select(0L, frame, true));
        AutoSubtitleRegionTracker.Selection selected = tracker.select(500L, frame, true);

        assertEquals("比較する時計は最新のムーブメントを搭載しています", selected.getText());
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
                0.06f, 0.91f, 0.94f, 0.98f);
        return new OcrResult(addressBar.getText() + "\n" + caption.getText(), 95,
                Arrays.asList(addressBar, caption));
    }

    private OcrResult watchFrame(String subtitle) {
        OcrLine brand = new OcrLine(0, "SEIKO", 99,
                0.41f, 0.52f, 0.59f, 0.58f);
        OcrLine specification = new OcrLine(1, "AUTOMATIC 3 DAYS", 99,
                0.38f, 0.76f, 0.62f, 0.82f);
        OcrLine pressure = new OcrLine(2, "20 BAR", 99,
                0.45f, 0.83f, 0.55f, 0.87f);
        OcrLine caption = new OcrLine(3, subtitle, 90,
                0.05f, 0.92f, 0.95f, 0.98f);
        return new OcrResult(brand.getText() + "\n" + specification.getText() + "\n"
                + pressure.getText() + "\n" + caption.getText(), 94,
                Arrays.asList(brand, specification, pressure, caption));
    }
}
