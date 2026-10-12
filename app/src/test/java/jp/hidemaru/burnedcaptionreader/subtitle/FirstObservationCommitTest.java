package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;

public class FirstObservationCommitTest {
    private SubtitleStabilizer first() {
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config();
        config.commitMode = SubtitleStabilizer.CommitMode.FIRST_OBSERVATION;
        config.stableMs = 1000;
        return new SubtitleStabilizer(config);
    }

    @Test public void firstValidFrameCommitsWithoutWaiting() {
        SubtitleStabilizer s = first();
        assertNull(s.observe(0, "", 100));
        assertNull(s.observe(100, "字幕です", 20));
        SubtitleEvent e = s.observe(200, "字幕です", 90);
        assertNotNull(e);
        assertEquals("字幕です", e.getText());
        assertEquals(1, s.getLastCommitObservations());
        for (int t = 300; t < 1300; t += 100) assertNull(s.observe(t, "字幕です", 90));
    }

    @Test public void singleFrameNeverUsesOldNumberOrNegationVotes() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        c.observe(0, "今日は大丈夫です", 95);
        c.observe(480, "今日は大丈夫です", 95);
        TemporalOcrConsensus.Result result = TemporalOcrConsensus.singleFrame(
                "今日は大丈夫ではありません", 60, "今日は大丈夫ではありません");
        assertEquals("今日は大丈夫ではありません", result.getText());
        assertEquals(60.0, result.getConfidence(), .001);
    }

    @Test public void changingNumberCommitsNewEvent() {
        SubtitleStabilizer s = first();
        assertNotNull(s.observe(0, "料金は100円", 90));
        assertNull(s.observe(480, "料金は100円", 90));
        assertNotNull(s.observe(960, "料金は200円", 90));
    }

    @Test public void switchingPreservesCommittedDeduplication() {
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config();
        config.stableMs = 300;
        SubtitleStabilizer s = new SubtitleStabilizer(config);
        assertNull(s.observe(0, "字幕Aです", 90));
        config.commitMode = SubtitleStabilizer.CommitMode.FIRST_OBSERVATION;
        s.clearPendingCandidate();
        assertNotNull(s.observe(100, "字幕Aです", 90));
        config.commitMode = SubtitleStabilizer.CommitMode.REOBSERVE;
        s.clearPendingCandidate();
        assertNull(s.observe(150, "字幕Aです", 90));
        assertNull(s.observe(500, "字幕Aです", 90));
        assertNull(s.observe(1000, "字幕Aです", 90));
        assertNull(s.observe(1100, "次の字幕です", 90));
        assertNotNull(s.observe(1500, "次の字幕です", 90));
    }
}
