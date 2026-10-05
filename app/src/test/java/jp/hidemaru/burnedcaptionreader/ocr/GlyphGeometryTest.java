package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlyphGeometryTest {
    private OcrLine glyph(float top, float height) {
        return new OcrLine(0, "字", 90, .1f, top, .13f, top + height);
    }
    @Test public void slopeOfWholeLineDoesNotBecomeLetterHeight() {
        var letters = List.of(glyph(.1f,.04f),glyph(.14f,.04f),glyph(.18f,.04f));
        assertEquals(.04f, GlyphGeometry.medianHeight(letters,List.of(),.12f),.00001f);
    }
    @Test public void tinyPunctuationAndTallOutlierDoNotDominateMedian() {
        var letters = List.of(glyph(.1f,.01f),glyph(.1f,.04f),glyph(.1f,.04f),
                glyph(.1f,.04f),glyph(.1f,.2f));
        assertEquals(.04f,GlyphGeometry.medianHeight(letters,List.of(),.2f),.00001f);
    }
    @Test public void missingSymbolsUseElementsThenOriginalHeight() {
        assertEquals(.05f,GlyphGeometry.medianHeight(List.of(),List.of(glyph(.1f,.05f)),.15f),.00001f);
        assertEquals(.15f,GlyphGeometry.medianHeight(List.of(),List.of(),.15f),.00001f);
    }
    @Test public void absentOrInvalidMetadataPreservesOldLineHeight() {
        for (float size : new float[]{0,Float.NaN,Float.POSITIVE_INFINITY}) {
            OcrLine row = new OcrLine(0,"字幕",90,.1f,.1f,.9f,.2f,List.of(),size);
            assertEquals(.1f,row.getGlyphHeight(),.00001f);
        }
    }
}
