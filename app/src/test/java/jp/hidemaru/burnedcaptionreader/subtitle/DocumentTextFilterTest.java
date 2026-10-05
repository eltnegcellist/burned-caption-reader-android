package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

public class DocumentTextFilterTest {
    private OcrLine row(String text,float left,float top,float width,float boxHeight,float glyphHeight) {
        return new OcrLine(0,text,90,left,top,left+width,top+boxHeight,List.of(),glyphHeight);
    }
    private OcrLine mainCaption() { return row("今日のお店を紹介します",.03f,.80f,.94f,.16f,.15f); }
    private List<OcrLine> paragraph() {
        List<OcrLine> rows=new ArrayList<>();
        rows.add(row("Original dessert with drink",.23f,.16f,.35f,.07f,.035f));
        for(int i=0;i<7;i++) rows.add(row("印刷された説明文が続いています",.23f-i*.008f,.25f+i*.05f,.32f,.04f,.035f));
        rows.add(row("Please select your favorite drink",.17f,.66f,.42f,.05f,.035f));
        return rows;
    }
    @Test public void denseTranslatedParagraphIsRemovedBesideLargeCaption() {
        var rows=paragraph(); OcrLine caption=mainCaption(); rows.add(caption);
        assertEquals(List.of(caption),DocumentTextFilter.captionsOnly(rows));
    }
    @Test public void tiltedUnionBoxesStillUseActualLetterSize() {
        var rows=paragraph(); rows.set(0,row("日本語の見出し Original dessert with drink",.23f,.16f,.40f,.12f,.035f));
        OcrLine caption=mainCaption();rows.add(caption);
        assertEquals(List.of(caption),DocumentTextFilter.captionsOnly(rows));
    }
    @Test public void bilingualCellsInSeparateColumnsAreDocumentEvidence() {
        OcrLine caption=mainCaption();
        var rows=List.of(row("季節のデザート",.20f,.25f,.20f,.035f,.03f),
                row("Seasonal dessert",.20f,.29f,.22f,.035f,.03f),
                row("おすすめのお茶",.55f,.25f,.20f,.035f,.03f),
                row("Recommended tea",.55f,.29f,.22f,.035f,.03f),caption);
        assertEquals(List.of(caption),DocumentTextFilter.captionsOnly(rows));
    }
    @Test public void shortBilingualDialogueInOneLaneIsPreserved() {
        var rows=List.of(row("今日は楽しかったです",.10f,.30f,.65f,.04f,.04f),
                row("We had a lovely day",.10f,.35f,.65f,.04f,.04f),mainCaption());
        assertEquals(rows,DocumentTextFilter.captionsOnly(rows));
    }
    @Test public void smallSupplementWithDifferentSizeOrOutsideDocumentIsPreserved() {
        var rows=paragraph(); OcrLine note=row("補足の説明です",.27f,.50f,.21f,.065f,.06f);
        OcrLine outside=row("楽しみですね！",.72f,.4f,.22f,.035f,.03f);
        OcrLine caption=mainCaption();rows.add(note);rows.add(outside);rows.add(caption);
        assertEquals(List.of(note,outside,caption),DocumentTextFilter.captionsOnly(rows));
    }
    @Test public void ordinaryThreeRowsAndLongJapaneseNarrationArePreserved() {
        for(int count:new int[]{3,6}) {
            List<OcrLine> rows=new ArrayList<>();
            for(int i=0;i<count;i++) rows.add(row("これは読み上げる字幕の説明です",.08f,.1f+i*.10f,.80f,.08f,.07f));
            assertEquals(rows,DocumentTextFilter.captionsOnly(rows));
        }
    }
    @Test public void isolatedPricesAndOneQuoteDoNotEstablishDocument() {
        var rows=List.of(row("今日は二千円でした",.2f,.3f,.40f,.04f,.04f),
                row("Lunch was 2000 yen",.2f,.35f,.40f,.04f,.04f),mainCaption());
        assertEquals(rows,DocumentTextFilter.captionsOnly(rows));
    }
    @Test public void paragraphWithoutLargeNarrationAndRepeatedPricesIsPreserved() {
        var rows=paragraph();assertEquals(rows,DocumentTextFilter.captionsOnly(rows));
    }
}
