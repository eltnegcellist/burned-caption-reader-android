package jp.hidemaru.burnedcaptionreader.subtitle;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import jp.hidemaru.burnedcaptionreader.ocr.*;

public class TrailingPunctuationTest {
    @Test public void stableCommitUsesNewPunctuationEvidenceInsteadOfOldZeroVote() {
        SubtitleStabilizer.Config cfg = new SubtitleStabilizer.Config(); cfg.stableMs = 300;
        SubtitleStabilizer s = new SubtitleStabilizer(cfg);
        s.observe(0,"追加要望が出ていましたが0",55);
        assertEquals("追加要望が出ていましたが…",s.observe(500,"追加要望が出ていましたが…",50).getText());
    }
    @Test public void alreadySpokenZeroDoesNotRepeatWhenClearPunctuationAppears() {
        SubtitleEventManager m = new SubtitleEventManager(60000);
        m.accept(new SubtitleEvent("a","追加要望が出ていましたが0",0,500,60));
        assertNull(m.accept(new SubtitleEvent("b","追加要望が出ていましたが…",0,1000,60)));
    }
    @Test public void realZeroVoteIsRepairedWhenNextCoarseFrameSeesEllipsis() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        c.observe(0, "追加要望が出ていましたが0", 50, "追加要望が出ていましたが");
        assertEquals("追加要望が出ていましたが…", c.observe(500,
                "追加要望が出ていましたが。o", 50, "追加要望が出ていましたが…").getText());
    }
    @Test public void sameFrameCoarsePeriodProtectsMergedRows() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        assertEquals("前回の定例でお客様から\n追加要望が出ていましたが。", c.observe(0,
                "前回の定例でお客様から\n追加要望が出ていましたがo", 50,
                "前回の定例でお客様から\n追加要望が出ていましたが。").getText());
    }
    @Test public void noEvidenceDoesNotDeleteNumbersOrLetters() {
        for (String s : new String[]{"答えは0", "価格は100", "型番はABo", "追加要望が出ていましたが0"}) {
            assertEquals(s, new TemporalOcrConsensus().observe(0,s,80).getText());
        }
        assertEquals("答えは0", TrailingPunctuation.repair("答えは0", "答えは…"));
        assertEquals("金額は200円です", TrailingPunctuation.repair("金額は200円です", "金額は100円です…"));
    }
    @Test public void punctuationEvidenceExpiresAndResetClearsIt() {
        TemporalOcrConsensus c = new TemporalOcrConsensus();
        c.observe(0, "追加要望が出ていましたが…", 50);
        assertEquals("追加要望が出ていましたが0", c.observe(2500,"追加要望が出ていましたが0",50).getText());
        c.reset();
        assertEquals("追加要望が出ていましたが0", c.observe(2600,"追加要望が出ていましたが0",50).getText());
    }
    @Test public void splitAndMergedCorrectedReadingsAreNotSpokenTwice() {
        SubtitleEventManager m = new SubtitleEventManager(60000);
        m.accept(new SubtitleEvent("a","前回の定例でお客様から",0,500,60));
        m.accept(new SubtitleEvent("b","追加要望が出ていましたが…",0,500,60));
        assertNull(m.accept(new SubtitleEvent("c","前回の定例でお客様から\n追加要望が出ていましたが。",0,1000,60)));
    }
    @Test public void stableShortReactionIsSelectedButLabelIsNot() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult r = new OcrResult("",60,Arrays.asList(
                new OcrLine(0,"えええ00o",31.0546875,.21833648f,.88762885f,.44517958f,.94845361f),
                new OcrLine(1,"SE",90,.03f,.94f,.11f,.99f)));
        assertTrue(tracker.selectAll(0,r).isEmpty());
        assertTrue(tracker.selectAll(500,r).isEmpty());
        assertTrue(tracker.selectAll(1000,r).get(0).getText().startsWith("えええ"));
    }
}
