package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SubtitleNormalizerTest {
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
