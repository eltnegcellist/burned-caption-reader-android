package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class TemporalOcrConsensusTest {
    @Test public void changedNumberIsNeverReplacedByOldVotes() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        c.observe(0, "価格は100円です", 90);
        c.observe(480, "価格は100円です", 90);
        assertEquals("価格は200円です", c.observe(960, "価格は200円です", 55).getText());
    }
    @Test public void changedNegationIsNeverReplacedByOldVotes() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        c.observe(0, "本日は利用できます", 90);
        c.observe(480, "本日は利用できます", 90);
        assertEquals("本日は利用できません", c.observe(960, "本日は利用できません", 55).getText());
    }
    @Test public void typewriterGrowthKeepsNewestCompleteText() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        c.observe(0, "今日は投資について", 90);
        c.observe(480, "今日は投資について", 90);
        assertEquals("今日は投資について説明します", c.observe(960, "今日は投資について説明します", 55).getText());
    }
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
    public void characterVoteCanRecoverTextEvenWhenEveryFrameHasDifferentError() {
        TemporalOcrConsensus consensus = new TemporalOcrConsensus();
        consensus.observe(0L, "今日ほ天気がいい", 72.0);
        consensus.observe(450L, "今日は天汽がいい", 74.0);
        TemporalOcrConsensus.Result result = consensus.observe(900L, "今日は天気がいぃ", 76.0);

        assertEquals("今日は天気がいい", result.getText());
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
