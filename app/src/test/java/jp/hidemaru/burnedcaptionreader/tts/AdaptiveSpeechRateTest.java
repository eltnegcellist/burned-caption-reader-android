package jp.hidemaru.burnedcaptionreader.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdaptiveSpeechRateTest {
    private static final float EPS = 0.0001f;

    @Test public void startsAtConfiguredBaseRateAndWaitsForTwoIntervals() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        assertEquals(1.60f, controller.onCaption(0, "これは最初の字幕です", 1.60f), EPS);
        assertEquals(1.60f, controller.onCaption(4_000, "これは次の字幕です", 1.60f), EPS);
        assertEquals(1.60f, controller.getCurrentRate(1.60f), EPS);
    }

    @Test public void slowsForLongLivedShortCaptions() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        String caption = "字幕をゆっくり読みます";
        for (int i = 0; i < 6; i++) {
            controller.onCaption(i * 5_000L, caption, 1.60f);
        }
        assertTrue(controller.getCurrentRate(1.60f) < 1.60f);
        assertEquals(AdaptiveSpeechRate.MIN_RATE, controller.getCurrentRate(1.60f), EPS);
    }

    @Test public void speedsUpForRapidLongCaptionsWithoutExceedingLimit() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        String caption = "長い字幕が次から次へと現れる動画で読み終わることができません";
        for (int i = 0; i < 8; i++) {
            controller.onCaption(i * 1_500L, caption, 1.60f);
        }
        assertEquals(AdaptiveSpeechRate.MAX_RATE, controller.getCurrentRate(1.60f), EPS);
    }

    @Test public void rateChangesOnlyOncePerSubtitleAndInSmallSteps() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        String caption = "長い字幕が次から次へと現れる動画で読み終わることができません";
        float last = 1.60f;
        for (int i = 0; i < 8; i++) {
            float speed = controller.onCaption(i * 1_500L, caption, 1.60f);
            assertTrue(Math.abs(speed - last) <= 0.1701f);
            last = speed;
        }
    }

    @Test public void longPauseDoesNotMakeVideoLookSlow() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        String caption = "長い字幕が次から次へと現れる動画で読み終わることができません";
        for (int i = 0; i < 6; i++) controller.onCaption(i * 1_500L, caption, 1.60f);
        float learned = controller.getCurrentRate(1.60f);
        controller.onCaption(70_000L, caption, 1.60f);
        assertEquals(learned, controller.getCurrentRate(1.60f), EPS);
        controller.suspend();
        controller.onCaption(300_000L, caption, 1.60f);
        assertEquals(learned, controller.getCurrentRate(1.60f), EPS);
    }

    @Test public void veryCloseFragmentsAreGroupedIntoOneInterval() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        controller.onCaption(0, "最初の字幕です", 1.60f);
        controller.onCaption(250, "次の字幕です", 1.60f);
        controller.onCaption(500, "続きの字幕です", 1.60f);
        assertEquals(0, controller.getValidIntervals());
        controller.onCaption(2_500, "あとの字幕です", 1.60f);
        assertEquals(1, controller.getValidIntervals());
    }

    @Test public void backlogOnlyIncreasesLearnedSpeedGently() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        controller.onSpeechStartDelay(3_000);
        assertEquals(1.60f, controller.getCurrentRate(1.60f), EPS);
        String caption = "長い字幕が次から次へと現れる動画で読み終わることができません";
        for (int i = 0; i < 4; i++) controller.onCaption(i * 1_500L, caption, 1.60f);
        float before = controller.getCurrentRate(1.60f);
        controller.onSpeechStartDelay(3_000);
        assertTrue(controller.getCurrentRate(1.60f) >= before);
        assertTrue(controller.getCurrentRate(1.60f) <= AdaptiveSpeechRate.MAX_RATE);
    }

    @Test public void resetResetsOnlyCurrentVideoLearning() {
        AdaptiveSpeechRate controller = new AdaptiveSpeechRate();
        String caption = "長い字幕が次から次へと現れる動画で読み終わることができません";
        for (int i = 0; i < 5; i++) controller.onCaption(i * 1_500L, caption, 1.60f);
        assertTrue(controller.getCurrentRate(1.60f) > 1.60f);
        controller.reset();
        assertEquals(0, controller.getValidIntervals());
        assertEquals(1.60f, controller.onCaption(100_000L, caption, 1.60f), EPS);
    }

    @Test public void excludesPunctuationFromCharacterDemand() {
        assertEquals(5, AdaptiveSpeechRate.characterCount("今日も晴れ！…"));
        assertEquals(10, AdaptiveSpeechRate.characterCount("Hello, world!"));
    }
}
