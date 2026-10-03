package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;

/** Candidate detection and speech commitment must not each consume a whole sample. */
public class RecognitionCandidateTest {
    private OcrResult frame(String text, float left, float top, float right, float bottom) {
        return new OcrResult(text, 90, List.of(new OcrLine(0, text, 90, left, top, right, bottom)));
    }
    @Test public void persistentCaptionFeedsStabilizerBeforeStaticLaneDeadline() {
        var tracker = new AutoSubtitleRegionTracker();
        var config = new SubtitleStabilizer.Config(); config.stableMs = 300;
        var stabilizer = new SubtitleStabilizer(config);
        var raw = frame("今回は時計の修理方法を説明します", .05f, .91f, .95f, .98f);
        var first = tracker.selectForRecognition(0, raw);
        assertEquals(1, first.size());
        assertNull("One image must never commit speech", stabilizer.observe(0, first.get(0).getText(), 90));
        var second = tracker.selectForRecognition(3000, raw);
        assertEquals(1, second.size());
        assertEquals(first.get(0).getTrackId(), second.get(0).getTrackId());
        assertNotNull("Slow OCR must still get a second observation", stabilizer.observe(3000, second.get(0).getText(), 90));
        assertNull("Continuous text must not commit twice", stabilizer.observe(5000,
                tracker.selectForRecognition(5000, raw).get(0).getText(), 90));
    }
    @Test public void isolatedOrGrowingCaptionStillCannotCommitEarly() {
        var tracker = new AutoSubtitleRegionTracker();
        var config = new SubtitleStabilizer.Config(); config.stableMs = 300;
        var stabilizer = new SubtitleStabilizer(config);
        assertNull(stabilizer.observe(0, tracker.selectForRecognition(0,
                frame("字幕はまだ", .1f,.2f,.9f,.3f)).get(0).getText(),90));
        assertNull(stabilizer.observe(500,tracker.selectForRecognition(500,
                frame("字幕はまだ途中です",.1f,.2f,.9f,.3f)).get(0).getText(),90));
        assertNotNull(stabilizer.observe(1000,tracker.selectForRecognition(1000,
                frame("字幕はまだ途中です",.1f,.2f,.9f,.3f)).get(0).getText(),90));
    }
    @Test public void candidatePathKeepsObjectAndMetadataFilters() {
        var tracker = new AutoSubtitleRegionTracker();
        for (long now : new long[]{0,500,3000}) {
            assertTrue(tracker.selectForRecognition(now,frame("防水性能",.4f,.48f,.6f,.55f)).isEmpty());
            assertTrue(tracker.selectForRecognition(now,frame("YouTube コメント",.25f,.75f,.75f,.81f)).isEmpty());
        }
    }
    @Test public void parallelNameplatesDoNotJoinReactionCaption() {
        var tracker = new AutoSubtitleRegionTracker();
        var raw = new OcrResult("", 59, List.of(
            new OcrLine(0, "現在は我々が運用マニアルに沿って", 50.8056640625, 0.016470588743686676f, 0.041753653436899185f, 0.974117636680603f, 0.16701461374759674f),
            new OcrLine(1, "バンチ当でたり再起動したりなど", 49.73958432674408, 0.10117647051811218f, 0.17536534368991852f, 0.8988234996795654f, 0.30480167269706726f),
            new OcrLine(2, "細々とやっている状況でして。。。oo.", 41.221216320991516, 0.025882352143526077f, 0.30897703766822815f, 0.9599999785423279f, 0.4384133517742157f),
            new OcrLine(4, "は、はあ·.", 59.63541865348816, 0.5647059082984924f, 0.7933194041252136f, 0.7905882596969604f, 0.8684759736061096f),
            new OcrLine(3, "お客様", 86.06770634651184, 0.10352940857410431f, 0.8893527984619141f, 0.2894117534160614f, 0.9895615577697754f),
            new OcrLine(5, "ベンダー", 67.1875, 0.7011764645576477f, 0.8893527984619141f, 0.9270588159561157f, 0.9895615577697754f)));
        for (long now : new long[]{0,1000,4000}) {
            var selected = tracker.selectForRecognition(now, raw);
            assertFalse(selected.isEmpty());
            for (var band : selected) {
                assertFalse(band.getText().contains("ベンダー"));
                assertFalse(band.getText().contains("お客様"));
            }
        }
    }
    @Test public void smallParallelDiagramHeadersAreNotImmediateCaptions() {
        var tracker = new AutoSubtitleRegionTracker();
        var raw = new OcrResult("", 85, List.of(
            new OcrLine(0,"新しい字幕の処理について説明します",85,.05f,.05f,.95f,.17f),
            new OcrLine(1,"新システム",90,.12f,.39f,.32f,.44f),
            new OcrLine(2,"現行システム",90,.62f,.39f,.86f,.44f)));
        for(long now : new long[]{0,1000,4000}) {
            var selected = tracker.selectForRecognition(now,raw);
            assertEquals(1,selected.size());
            assertEquals("新しい字幕の処理について説明します",selected.get(0).getText());
        }
    }
}
