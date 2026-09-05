package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class TemporalOcrConsensusTest {
    @Test
    public void prefersStableVariantAcrossSmallOcrWobble() {
        TemporalOcrConsensus consensus = new TemporalOcrConsensus();
        consensus.observe(0L, "今日は天気がいい", 72.0);
        consensus.observe(450L, "今日ほ天気がいい", 61.0);
        TemporalOcrConsensus.Result result = consensus.observe(900L, "今日は天気がいい", 79.0);

        assertEquals("今日は天気がいい", result.getText());
        assertTrue(result.getConfidence() >= 70.0);
    }

    @Test
    public void resetsForClearlyDifferentSubtitle() {
        TemporalOcrConsensus consensus = new TemporalOcrConsensus();
        consensus.observe(0L, "今日は天気がいい", 80.0);
        TemporalOcrConsensus.Result result = consensus.observe(500L, "次のニュースです", 75.0);

        assertEquals("次のニュースです", result.getText());
    }

    @Test
    public void expiresOldHistory() {
        TemporalOcrConsensus consensus = new TemporalOcrConsensus();
        consensus.observe(0L, "古い字幕です", 90.0);
        TemporalOcrConsensus.Result result = consensus.observe(2_500L, "新しい字幕です", 60.0);

        assertEquals("新しい字幕です", result.getText());
    }
}
