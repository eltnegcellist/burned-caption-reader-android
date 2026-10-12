package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import org.junit.Test;

/** Diagnostics must not change the tracker decisions or consume extra frames. */
public class SelectionDecisionTraceTest {
    @Test public void tracedAndUntracedTrackersAgreeAcrossFrames() {
        AutoSubtitleRegionTracker ordinary = new AutoSubtitleRegionTracker();
        AutoSubtitleRegionTracker traced = new AutoSubtitleRegionTracker();
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OcrLine caption = new OcrLine(1, i < 3 ? "今日は新しい発見です" : "次に説明します",
                    90, .15f, .75f, .85f, .81f);
            OcrLine small = new OcrLine(2, "小", 90, .02f, .08f, .04f, .083f);
            OcrResult frame = new OcrResult("", 90, Arrays.asList(caption, small));
            List<AutoSubtitleRegionTracker.Selection> baseline =
                    ordinary.selectForRecognition(i * 480L, frame);
            List<AutoSubtitleRegionTracker.Selection> withTrace =
                    traced.selectForRecognition(i * 480L, frame,
                            (stage, reason, text, top, bottom, conf) ->
                                    reasons.add(stage + ":" + reason));
            assertEquals(baseline.size(), withTrace.size());
            for (int j = 0; j < baseline.size(); j++) {
                assertEquals(baseline.get(j).getText(), withTrace.get(j).getText());
                assertEquals(baseline.get(j).getTrackId(), withTrace.get(j).getTrackId());
                assertEquals(baseline.get(j).isLocked(), withTrace.get(j).isLocked());
            }
        }
        assertTrue(reasons.stream().anyMatch(reason -> reason.contains("empty_or_small_height")));
    }

    @Test public void nullTraceIsEquivalentToOrdinaryOverload() {
        OcrResult frame = new OcrResult("", 70, Arrays.asList(
                new OcrLine(1, "字幕を確認します", 70, .2f, .7f, .8f, .76f)));
        AutoSubtitleRegionTracker a = new AutoSubtitleRegionTracker();
        AutoSubtitleRegionTracker b = new AutoSubtitleRegionTracker();
        assertEquals(a.selectForRecognition(0, frame).size(),
                b.selectForRecognition(0, frame, null).size());
    }
}
