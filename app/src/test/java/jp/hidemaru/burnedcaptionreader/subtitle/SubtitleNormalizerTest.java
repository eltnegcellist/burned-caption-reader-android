package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SubtitleNormalizerTest {
    @Test public void displayJoinsJapaneseSubtitleRowsWithoutForcingLineBreaks() {
        assertEquals("今回は絶対に失敗できない何としても漏れなく洗い出さないと…",
                SubtitleNormalizer.toDisplayText(
                        "今回は絶対に失敗できない\n何としても漏れなく\n洗い出さないと…"));
    }

    @Test public void displayPreservesEnglishWordSeparation() {
        assertEquals("Hello world! Let's go.",
                SubtitleNormalizer.toDisplayText("Hello\nworld!\r\nLet's go."));
    }

    @Test public void displayIgnoresBlankLinesAndNormalizesCrLf() {
        assertEquals("最初の行次の行",
                SubtitleNormalizer.toDisplayText(" 最初の行 \r\n  \r\n 次の行 "));
        assertEquals("", SubtitleNormalizer.toDisplayText(null));
    }

    @Test public void displayOnlyDoesNotChangeSpeechPauseHandling() {
        String rows = "今日は晴れ\n良い日ですね";
        assertEquals("今日は晴れ良い日ですね", SubtitleNormalizer.toDisplayText(rows));
        assertEquals("今日は晴れ、良い日ですね", SubtitleNormalizer.toSpeechText(rows));
    }

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
