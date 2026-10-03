package jp.hidemaru.burnedcaptionreader.subtitle;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;

/** Exact ML Kit row boxes from unobscured PixelCopy video2 frame 125. */
public class TallThreeRowDiagnosticTest {
    @Test public void tallThreeRowsAreOneBandNotTwoPlusOne() {
        OcrResult raw = new OcrResult("", 58, List.of(
            new OcrLine(0, "-ええと総点検よりは絞った方が、", 43.65234375, 0.08235294371843338f, 0.037578288465738297f, 0.910588264465332f, 0.16701461374759674f),
            new OcrLine(1, "いいかなと思ったのですが、", 57.8125, 0.1411764770746231f, 0.17536534368991852f, 0.8235294222831726f, 0.30480167269706726f),
            new OcrLine(2, "マスカったですかね?", 46.875, 0.22588235139846802f, 0.30480167269706726f, 0.7599999904632568f, 0.4425887167453766f),
            new OcrLine(4, "協力会社さん", 77.79948115348816, 0.6870588064193726f, 0.8851774334907532f, 0.9858823418617249f, 0.9895615577697754f),
            new OcrLine(3, "プロマネコ", 65.625, 0.05058823525905609f, 0.893528163433075f, 0.26941177248954773f, 0.9895615577697754f)));
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        tracker.selectAll(0, raw);
        var selected = tracker.selectAll(500, raw);
        assertEquals("Three aligned dialogue rows must not become overlapping refinement jobs", 1, selected.size());
        assertEquals(3, selected.get(0).getText().split("\n").length);
        assertFalse(selected.get(0).getText().contains("協力会社さん"));
    }
    @Test public void sameFontAndTopAnchorKeepTrackAcrossTwoThreeTwoRows() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        tracker.selectAll(0, frame15());
        int id = tracker.selectAll(1000, frame15()).get(0).getTrackId();
        tracker.selectAll(2000, frame15());
        tracker.selectAll(4000, frame19());
        var threeRows = tracker.selectAll(5000, frame19());
        assertEquals(1, threeRows.size());
        assertEquals(id, threeRows.get(0).getTrackId());
        assertEquals(id, tracker.selectAll(7000, frame19()).get(0).getTrackId());
        var twoRows = tracker.selectAll(8000, frame23());
        assertEquals(1, twoRows.size());
        assertEquals(id, twoRows.get(0).getTrackId());
        assertEquals(2, twoRows.get(0).getText().split("\n").length);
    }

    @Test public void documentLabelMustNotFillTheThirdRowBudget() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        tracker.selectAll(0, frame31());
        var selected = tracker.selectAll(1000, frame31());
        assertFalse(selected.isEmpty());
        var dialogue = selected.stream().filter(band -> band.getText().contains("直接原因"))
                .findFirst().orElseThrow();
        assertEquals(2, dialogue.getText().split("\n").length);
        assertTrue("Document text below dialogue must not expand its crop", dialogue.getBottom() < .5f);
    }

    // Original video4 ML Kit row boxes; text is diagnostic output, not ground truth.
    private OcrResult frame15() {
        return new OcrResult("", 54.833441227674484, List.of(
            new OcrLine(0, "をの度はシステム障害を起こしてしまい", 50.49912929534912, 0.016470588743686676f, 0.041753653436899185f, 0.9764705896377563f, 0.17118997871875763f),
            new OcrLine(1, "大変申し駅ございませんoodo", 33.85416567325592, 0.13411764800548553f, 0.17536534368991852f, 0.8564705848693848f, 0.30480167269706726f),
            new OcrLine(3, "お客様!", 70.21484375, 0.7435294389724731f, 0.8789144158363342f, 0.9423529505729675f, 0.9874739050865173f),
            new OcrLine(2, "プロマネコ", 64.7656261920929, 0.05058823525905609f, 0.8914405107498169f, 0.2647058963775635f, 0.9895615577697754f)));
    }
    private OcrResult frame19() {
        return new OcrResult("", 56.661133766174316, List.of(
            new OcrLine(0, "-謝って流む問題じゃないんだよ!", 34.8876953125, 0.0752941146492958f, 0.041753653436899185f, 0.8988234996795654f, 0.17118997871875763f),
            new OcrLine(0, "どれだけ業務影響があったか", 58.984375, 0.13647058606147766f, 0.17536534368991852f, 0.8611764907836914f, 0.30480167269706726f),
            new OcrLine(0, "-わかってるのが『!", 52.77343988418579, 0.24705882370471954f, 0.30897703766822815f, 0.722352921962738f, 0.4425887167453766f),
            new OcrLine(1, "プロマネコ", 65.07812738418579, 0.05058823525905609f, 0.8914405107498169f, 0.2647058963775635f, 0.9895615577697754f),
            new OcrLine(2, "|お客様", 71.58203125, 0.7458823323249817f, 0.893528163433075f, 0.9270588159561157f, 0.9853861927986145f)));
    }
    private OcrResult frame23() {
        return new OcrResult("", 49.76859241724014, List.of(
            new OcrLine(0, "-はい00。", 30.59895932674408, 0.3835294246673584f, 0.045929018408060074f, 0.6023529171943665f, 0.15448851883411407f),
            new OcrLine(1, "本当に申し駅ございません00000", 28.768381476402283, 0.0658823549747467f, 0.16701461374759674f, 0.929411768913269f, 0.30480167269706726f),
            new OcrLine(3, "|お客様", 74.31640625, 0.748235285282135f, 0.8893527984619141f, 0.9270588159561157f, 0.9853861927986145f),
            new OcrLine(2, "プロマネコ", 65.39062261581421, 0.05058823525905609f, 0.8914405107498169f, 0.2647058963775635f, 0.9895615577697754f)));
    }
    private OcrResult frame31() {
        return new OcrResult("", 56.264439721902214, List.of(
            new OcrLine(0, "直接原因はテストパターンの", 48.25721085071564, 0.13647058606147766f, 0.17118997871875763f, 0.8588235378265381f, 0.30062630772590637f),
            new OcrLine(2, "-網羅性不足??", 29.296875, 0.2705882489681244f, 0.30897703766822815f, 0.703529417514801f, 0.4384133517742157f),
            new OcrLine(3, "テスト", 51.69270634651184, 0.548235297203064f, 0.5657619833946228f, 0.6517646908760071f, 0.6805845499038696f),
            new OcrLine(4, "報告書仕様書", 66.2109375, 0.36352941393852234f, 0.5782880783081055f, 0.6600000262260437f, 0.7724425792694092f),
            new OcrLine(1, "プロマネコ", 68.98437738418579, 0.05058823525905609f, 0.893528163433075f, 0.2670588195323944f, 0.9895615577697754f),
            new OcrLine(5, "|お客様", 73.14453125, 0.748235285282135f, 0.893528163433075f, 0.9247058629989624f, 0.9812108278274536f)));
    }
}
