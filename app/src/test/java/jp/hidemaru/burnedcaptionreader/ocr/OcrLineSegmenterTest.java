package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OcrLineSegmenterTest {
    private List<OcrLine> symbols(String text, float left, float top) {
        List<OcrLine> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) result.add(new OcrLine(0,
                text.substring(i, i + 1), 90, left + i * .04f, top,
                left + i * .04f + .035f, top + .08f));
        return result;
    }

    @Test public void recoversDisconnectedNamesWithoutTextSpaces() {
        List<OcrLine> boxes = symbols("先方さん", .1f, .8f);
        boxes.addAll(symbols("担当者", .6f, .8f));
        List<OcrLine> parts = OcrLineSegmenter.separatedParts("先方さん担当者", boxes, 1.8f);
        assertEquals(2, parts.size());
        assertEquals("先方さん", parts.get(0).getText());
        assertEquals("担当者", parts.get(1).getText());
    }

    @Test public void ordinaryCharacterSpacingIsNotSegmentationEvidence() {
        assertTrue(OcrLineSegmenter.separatedParts("字幕を読みます",
                symbols("字幕を読みます", .1f, .3f), 1.8f).isEmpty());
    }

    @Test public void missingBoxesCannotTurnPartialTextIntoTwoLabels() {
        List<OcrLine> boxes = symbols("先方", .1f, .8f);
        boxes.addAll(symbols("担当者", .6f, .8f));
        assertTrue(OcrLineSegmenter.separatedParts("先方さん担当者", boxes, 1.8f).isEmpty());
    }

    @Test public void imageAspectRatioMakesSamePixelGapDecision() {
        List<OcrLine> wide = symbols("先方", .1f, .3f);
        wide.addAll(symbols("担当者", .24f, .3f));
        List<OcrLine> tall = new ArrayList<>();
        for (OcrLine s : wide) tall.add(new OcrLine(0, s.getText(), 90,
                s.getLeft(), s.getTop() / 3, s.getRight(), s.getBottom() / 3));
        assertEquals(2, OcrLineSegmenter.separatedParts("先方担当者", wide, 1.8f).size());
        assertEquals(2, OcrLineSegmenter.separatedParts("先方担当者", tall, .6f).size());
    }
}
