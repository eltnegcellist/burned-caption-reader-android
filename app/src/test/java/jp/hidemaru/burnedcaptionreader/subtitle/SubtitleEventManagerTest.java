package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SubtitleEventManagerTest {
    @Test public void reorderedThreeRowsAreNotReadTwice() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("a", "最初の字幕です\n次の行を読みます\nこれが最後です", 1000)));
        assertNull(manager.accept(event("b", "これが最後です\n最初の字幕です\n次の行を読みます", 1500)));
    }

    @Test public void fuzzyTwoRowFragmentDoesNotRepeatFullCaption() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("a", "会社員の私には\n縁がない世界だと\n思っていました", 1000)));
        assertNull(manager.accept(event("b", "縁がない世畀だと\n思っていました", 1500)));
    }

    @Test public void recoveredTopRowIsReadWithoutRepeatingAlreadySpokenRows() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("a", "縁がない世界だと\n思っていました", 1000)));
        org.junit.Assert.assertEquals("会社員の私には", manager.accept(event("b",
                "会社員の私には\n縁がない世界だと\n思っていました", 1500)).getText());
        assertNull(manager.accept(event("c", "会社員の私には\n縁がない世界だと\n思っていました", 2000)));
    }

    @Test public void changedNumericRowIsNotDiscardedWithStableContextRows() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        manager.accept(event("a", "商品の説明です\n価格は100円です\n詳しく説明します", 1000));
        org.junit.Assert.assertEquals("価格は200円です", manager.accept(event("b",
                "商品の説明です\n価格は200円です\n詳しく説明します", 1500)).getText());
    }

    @Test public void singleExactRowFragmentIsNotRepeated() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        manager.accept(event("a", "会社員の私には\n縁がない世界だと\n思っていました", 1000));
        assertNull(manager.accept(event("b", "思っていました", 1500)));
    }

    @Test
    public void droppedTopRowIsNotReadAgainWhenTrackIdChanges() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("1", "会社員の私には\n縁がない世界だと\n思っていました", 1_000L)));
        assertNull(manager.accept(event("2", "縁がない世界だと\n思っていました", 2_000L)));
    }

    @Test
    public void suppressesOcrVariantWithinHistoryWindow() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("1", "今日は投資について説明します", 1_000L)));
        assertNull(manager.accept(event("2", "今日は投資について説明しま寸", 15_000L)));
    }

    @Test
    public void suppressesMoreDamagedOcrVariantImmediatelyAfterReading() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("1", "今日は天気が良いですね", 1_000L)));
        assertNull(manager.accept(event("2", "今日は天汽が良いで寸ね", 5_000L)));
    }

    @Test
    public void similarButDifferentLaterCaptionIsNotOverSuppressed() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("1", "今日は天気が良いですね", 1_000L)));
        assertNotNull(manager.accept(event("2", "今日は天汽が良いで寸ね", 12_000L)));
    }

    @Test
    public void ellipsisVariantDoesNotCauseDuplicateReading() {
        SubtitleEventManager manager = new SubtitleEventManager(60_000L);
        assertNotNull(manager.accept(event("1", "でも…本当にいいの？", 1_000L)));
        assertNull(manager.accept(event("2", "でも・・・本当にいいの?", 3_000L)));
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
