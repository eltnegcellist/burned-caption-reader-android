package jp.hidemaru.burnedcaptionreader.ocr;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionStripRecoveryTest {
    private int[] image(int background, int ink) {
        int[] p = new int[400 * 200]; Arrays.fill(p, background);
        for (int y = 183; y < 190; y++) for (int x = 30; x < 370; x += 12)
            for (int i = 0; i < 3; i++) p[y * 400 + x + i] = ink;
        return p;
    }
    @Test public void findsSmallGrayLettersWithoutBinarizingTheirAntialiasing() {
        var r = CaptionStripRecovery.find(image(0xff000000, 0xffaaaaaa), 400, 200);
        assertNotNull(r); assertEquals(.915f, r.top, .001f); assertEquals(.95f, r.bottom, .001f);
        assertTrue(CaptionStripRecovery.needsRecovery(r, new OcrResult("", 90)));
    }
    @Test public void denseLongSubtitleSurvivesWhileHigherGraphTicksAreIgnored() {
        int[] p = new int[400 * 200]; Arrays.fill(p, 0xff000000);
        for (int y = 183; y < 190; y++) for (int x = 10; x < 390; x += 4)
            for (int i = 0; i < 2; i++) p[y * 400 + x + i] = 0xffcccccc;
        assertNotNull(CaptionStripRecovery.find(p, 400, 200));
        Arrays.fill(p, 0xff000000);
        for (int y = 163; y < 170; y++) for (int x = 30; x < 370; x += 12)
            for (int i = 0; i < 3; i++) p[y * 400 + x + i] = 0xffcccccc;
        assertNull(CaptionStripRecovery.find(p, 400, 200));
    }
    @Test public void blanksBrightScenesAndColoredMarksDoNotTriggerExtraOcr() {
        int[] blank = new int[400 * 200]; Arrays.fill(blank, 0xff000000);
        assertNull(CaptionStripRecovery.find(blank, 400, 200));
        assertNull(CaptionStripRecovery.find(image(0xff777777, 0xffffffff), 400, 200));
        assertNull(CaptionStripRecovery.find(image(0xff000000, 0xffff0000), 400, 200));
    }
    @Test public void alreadyDetectedSubtitleDoesNotDuplicateOcr() {
        var r = CaptionStripRecovery.find(image(0xff000000, 0xffaaaaaa), 400, 200);
        assertFalse(CaptionStripRecovery.needsRecovery(r, new OcrResult("", 90,
                List.of(new OcrLine(0, "既存の字幕", 60, .1f, .915f, .9f, .95f)))));
    }
    @Test public void remapsCropGeometryAndKeepsCoarseRowsAndParts() {
        var plan = SubtitleCropPlan.create(400, 200, .915f, .95f);
        var old = new OcrLine(2, "SEIKO", 80, .3f, .3f, .6f, .4f);
        var part = new OcrLine(0, "新しい", 90, .1f, .3f, .3f, .7f);
        var row = new OcrLine(0, "新しい字幕です", 90, .1f, .3f, .9f, .7f, List.of(part), .4f);
        var merged = CaptionStripRecovery.merge(new OcrResult("SEIKO", 80, List.of(old)),
                new OcrResult(row.getText(), 90, List.of(row)), plan, 200);
        assertEquals(2, merged.getLines().size()); assertSame(old, merged.getLines().get(0));
        var mapped = merged.getLines().get(1); assertEquals(3, mapped.getBlockIndex());
        assertEquals((plan.top + .3f * plan.height) / 200, mapped.getTop(), .0001f);
        assertEquals(.4f * plan.height / 200, mapped.getGlyphHeight(), .0001f);
        assertEquals(mapped.getTop(), mapped.getSeparatedParts().get(0).getTop(), .0001f);
    }
    @Test public void emptyEnglishAndLowConfidenceRecoveryKeepOriginalResult() {
        var plan = SubtitleCropPlan.create(400, 200, .915f, .95f);
        var old = new OcrLine(0, "背景", 80, .2f, .2f, .5f, .3f);
        var raw = new OcrResult(old.getText(), 80, List.of(old));
        for (OcrResult crop : List.of(new OcrResult("", 90), new OcrResult("", 90,
                List.of(new OcrLine(0, "English title", 90, .1f, .3f, .9f, .7f))), new OcrResult("", 90,
                List.of(new OcrLine(0, "誤った字幕", 20, .1f, .3f, .9f, .7f)))))
            assertEquals(raw.getLines(), CaptionStripRecovery.merge(raw, crop, plan, 200).getLines());
    }
}
