package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SubtitleEventManagerTest {
    @Test
    public void suppressesOcrVariantWithinHistoryWindow() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("1", "今日は投資について説明します", 1_000L)));
        assertNull(manager.accept(event("2", "今日は投資について説明しま寸", 15_000L)));
    }

    @Test
    public void allowsSameTextAfterHistoryWindow() {
        SubtitleEventManager manager = new SubtitleEventManager(5_000L);
        assertNotNull(manager.accept(event("1", "繰り返す字幕", 1_000L)));
        assertNotNull(manager.accept(event("2", "繰り返す字幕", 7_000L)));
    }

    private SubtitleEvent event(String id, String text, long committedAt) {
        return new SubtitleEvent(id, text, committedAt - 300L, committedAt, 90.0);
    }
}
