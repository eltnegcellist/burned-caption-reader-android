package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SubtitleNormalizerTest {
    @Test public void punctuationOnlyIsNeverSpoken() {
        for (String value : new String[]{"…", "・・・", "⋮", "•••", "…\n…", "!?", "・"}) {
            assertEquals("", SubtitleNormalizer.toSpeechText(value));
        }
    }

    @Test public void removesEdgeEllipsesAndCollapsesPauses() {
        assertEquals("そうなの", SubtitleNormalizer.toSpeechText("…そうなの…"));
        assertEquals("でも、本当?", SubtitleNormalizer.toSpeechText("でも•••\n…本当?"));
        assertEquals("価格は3.5ドル", SubtitleNormalizer.toSpeechText("価格は3.5ドル"));
    }

    @Test
    public void normalizesCommonEllipsisOcrVariants() {
        assertEquals("でも…本当にいいの?",
                SubtitleNormalizer.normalize("でも・・・本当にいいの?"));
        assertEquals("でも…本当にいいの?",
                SubtitleNormalizer.normalize("でも...本当にいいの?"));
        assertEquals("でも…本当にいいの?",
                SubtitleNormalizer.normalize("でも⋯本当にいいの?"));
    }

    @Test
    public void ellipsisDoesNotAffectDuplicateComparison() {
        assertEquals(
                SubtitleNormalizer.comparisonKey("でも…本当にいいの?"),
                SubtitleNormalizer.comparisonKey("でも・・・本当にいいの?"));
    }

    @Test
    public void speechTreatsEllipsisAsPause() {
        assertEquals("でも、本当にいいの?",
                SubtitleNormalizer.toSpeechText("でも・・・本当にいいの?"));
    }
}
