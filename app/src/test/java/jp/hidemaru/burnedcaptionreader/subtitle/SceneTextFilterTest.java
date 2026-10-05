package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;

public class SceneTextFilterTest {
    private OcrLine row(int block, String text, float left, float top, float right, float bottom) {
        return new OcrLine(block, text, 90, left, top, right, bottom);
    }
    private OcrLine caption() {
        return row(0, "新しい字幕の内容を説明しています", .05f, .1f, .95f, .25f);
    }
    private OcrLine disconnected(String a, String b) {
        return new OcrLine(3, a + b, 90, .1f, .8f, .9f, .9f,
                List.of(row(3, a, .1f, .8f, .3f, .9f), row(3, b, .7f, .8f, .9f, .9f)));
    }

    @Test public void repeatedHeadingMeasurementLayoutDoesNotBecomeCaption() {
        OcrLine text = caption();
        OcrResult raw = new OcrResult("", 90, List.of(text,
                row(1, "検査(Inspection)", .61f, .4f, .8f, .44f),
                row(1, "35mins", .67f, .46f, .74f, .49f),
                row(2, "交換(Replacement)", .61f, .65f, .8f, .69f),
                row(2, "15mins", .67f, .71f, .74f, .74f)));
        var tracker = new AutoSubtitleRegionTracker();
        for (long time : new long[]{0, 1000, 4000}) {
            var selected = tracker.selectForRecognition(time, raw);
            assertEquals(1, selected.size());
            assertEquals(text.getText(), selected.get(0).getText());
        }
    }

    @Test public void loneNumericTwoRowCaptionIsPreserved() {
        List<OcrLine> lines = List.of(
                row(0, "必要な時間", .31f, .2f, .65f, .3f),
                row(0, "30分", .4f, .32f, .56f, .4f));
        assertEquals(lines, SceneTextFilter.captionsOnly(lines));
    }

    @Test public void twoLabelsAroundOneNumberAreNotRepeatedMetricPairs() {
        List<OcrLine> lines = List.of(
                row(0, "作業時間", .4f, .2f, .6f, .25f),
                row(0, "30分", .44f, .26f, .56f, .30f),
                row(0, "短時間", .42f, .31f, .58f, .35f));
        assertEquals(lines, SceneTextFilter.captionsOnly(lines));
    }

    @Test public void mergedSmallNameplatesAreRemovedWithoutDelayingMainCaption() {
        OcrLine text = caption();
        OcrLine names = disconnected("先方さん", "担当者");
        var selected = new AutoSubtitleRegionTracker().selectForRecognition(0,
                new OcrResult("", 90, List.of(text, names)));
        assertEquals(1, selected.size());
        assertEquals(text.getText(), selected.get(0).getText());
    }

    @Test public void spacedHeadlineWithoutAnotherCaptionIsPreserved() {
        OcrLine headline = disconnected("原因", "調査中");
        assertEquals(List.of(headline), SceneTextFilter.captionsOnly(List.of(headline)));
    }

    @Test public void separatedSpokenRepliesAndReactionPunctuationArePreserved() {
        for (OcrLine replies : List.of(disconnected("了解です", "任せてください"),
                disconnected("本当？", "意味不明！"), disconnected("そうだね", "それな"))) {
            List<OcrLine> lines = List.of(caption(), replies);
            assertEquals(lines, SceneTextFilter.captionsOnly(lines));
        }
    }

    @Test public void equallySizedDialogueDoesNotBecomeSmallNameplates() {
        OcrLine other = row(0, "字幕を読み上げています", .05f, .1f, .95f, .2f);
        List<OcrLine> lines = List.of(other, disconnected("原因", "調査中"));
        assertEquals(lines, SceneTextFilter.captionsOnly(lines));
    }
}
