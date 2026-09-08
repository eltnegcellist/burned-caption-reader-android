package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;

public class OcrRefinementSelectorTest {
    private static final String TOP = "いやー凡人会社員の私には";
    private static final String MIDDLE = "縁が無い世界だなー・・・";
    private static final String BOTTOM = "と思いきや";
    private final OcrRefinementSelector selector = new OcrRefinementSelector();

    private OcrLine row(String text, double confidence, float top) {
        return new OcrLine(0, text, confidence, .20f, top, .80f, top + .12f);
    }
    private String choose(String original, OcrLine... rows) {
        return selector.select(original, 85, new OcrResult("", 85, Arrays.asList(rows))).getText();
    }
    private String normalized(String text) { return SubtitleNormalizer.normalize(text); }
    private String full() { return normalized(TOP + "\n" + MIDDLE + "\n" + BOTTOM); }

    @Test public void restoresLowConfidenceTopRowDespiteExactTwoRowMatch() {
        assertEquals(full(), choose(MIDDLE + "\n" + BOTTOM,
                row(TOP, 24, .1f), row(MIDDLE, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void restoresBottomRow() {
        assertEquals(full(), choose(TOP + "\n" + MIDDLE,
                row(TOP, 90, .1f), row(MIDDLE, 90, .27f), row(BOTTOM, 80, .44f)));
    }
    @Test public void restoresMissingMiddleRow() {
        assertEquals(full(), choose(TOP + "\n" + BOTTOM,
                row(TOP, 90, .1f), row(MIDDLE, 80, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void sortsRowsIndependentlyOfBlockAndInputOrder() {
        assertEquals(full(), choose(MIDDLE + "\n" + BOTTOM,
                row(BOTTOM, 90, .44f), row(TOP, 80, .1f), row(MIDDLE, 90, .27f)));
    }
    @Test public void toleratesOneCharacterErrorInMatchedRow() {
        String changed = MIDDLE.replace("世界", "世畀");
        assertEquals(normalized(TOP + "\n" + changed + "\n" + BOTTOM),
                choose(MIDDLE + "\n" + BOTTOM,
                        row(TOP, 80, .1f), row(changed, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void doesNotRestoreVeryLowConfidenceRow() {
        assertEquals(normalized(MIDDLE + "\n" + BOTTOM), choose(MIDDLE + "\n" + BOTTOM,
                row(TOP, 10, .1f), row(MIDDLE, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void excludesDistantJapaneseLabel() {
        assertEquals(normalized(MIDDLE + "\n" + BOTTOM), choose(MIDDLE + "\n" + BOTTOM,
                row("防水性能について", 95, .01f), row(MIDDLE, 90, .5f), row(BOTTOM, 90, .67f)));
    }
    @Test public void excludesSmallProductLabel() {
        assertEquals(normalized(MIDDLE + "\n" + BOTTOM), choose(MIDDLE + "\n" + BOTTOM,
                new OcrLine(0, "防水性能", 95, .20f, .20f, .80f, .23f),
                row(MIDDLE, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void excludesEnglishObjectText() {
        assertEquals(normalized(MIDDLE + "\n" + BOTTOM), choose(MIDDLE + "\n" + BOTTOM,
                row("20 BAR", 95, .1f), row(MIDDLE, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void excludesMetadataEvenWhenAdjacentAndSameSize() {
        assertEquals(normalized(TOP + "\n" + MIDDLE), choose(TOP + "\n" + MIDDLE,
                row(TOP, 90, .1f), row(MIDDLE, 90, .27f), row("3万回視聴", 95, .44f)));
    }
    @Test public void excludesHorizontallySeparateText() {
        assertEquals(normalized(MIDDLE + "\n" + BOTTOM), choose(MIDDLE + "\n" + BOTTOM,
                new OcrLine(0, "防水性能について", 95, .01f, .1f, .15f, .22f),
                row(MIDDLE, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void retainsOriginalWhenRefinementLosesOneRow() {
        assertEquals(full(), choose(full(), row(MIDDLE, 99, .27f), row(BOTTOM, 99, .44f)));
    }
    @Test public void retainsOriginalWhenRefinementIsOnlyPrefix() {
        assertEquals(TOP, choose(TOP, row("いやー凡人会社員", 99, .1f)));
    }
    @Test public void acceptsCharacterCorrectionWithoutAddingRows() {
        assertEquals(TOP, choose(TOP.replace("社員", "杜員"), row(TOP, 95, .1f)));
    }
    @Test public void wholeTextCannotBypassGeometry() {
        String original = MIDDLE + "\n" + BOTTOM;
        OcrResult refined = new OcrResult(full(), 99, Arrays.asList(
                row(TOP, 90, .01f), row(MIDDLE, 90, .5f), row(BOTTOM, 90, .67f)));
        assertEquals(normalized(original), selector.select(original, 85, refined).getText());
    }
    @Test public void noBoxesCannotAddUnverifiedRows() {
        String original = MIDDLE + "\n" + BOTTOM;
        assertEquals(normalized(original), selector.select(original, 85,
                new OcrResult(full(), 99, Collections.emptyList())).getText());
    }
    @Test public void noBoxesMayStillCorrectText() {
        assertEquals(TOP, selector.select(TOP.replace("社員", "杜員"), 85,
                new OcrResult(TOP, 95)).getText());
    }
    @Test public void doesNotAppendDuplicatedOcrRow() {
        String original = TOP + "\n" + MIDDLE;
        assertEquals(normalized(original), choose(original,
                row(TOP, 90, .1f), row(MIDDLE, 90, .27f), row(MIDDLE, 90, .44f)));
    }
    @Test public void doesNotRestoreWhenOriginalRowsDoNotMatch() {
        String original = "これは異なる字幕です\n読み違いを防ぎます";
        assertEquals(original, choose(original,
                row(TOP, 90, .1f), row(MIDDLE, 90, .27f), row(BOTTOM, 90, .44f)));
    }
    @Test public void emptyRefinementKeepsOriginal() {
        assertEquals(TOP, choose(TOP));
    }
}
