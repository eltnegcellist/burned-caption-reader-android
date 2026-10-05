package jp.hidemaru.burnedcaptionreader.subtitle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

public class TableTextFilterTest {
    private OcrLine row(String s, float l, float t, float r) { return new OcrLine(0, s, 90, l, t, r, t + .035f); }
    private List<OcrLine> table() {
        List<OcrLine> rows = new ArrayList<>();
        rows.add(row("計測条件の一覧", .30f, .20f, .68f));
        for (int i = 0; i < 3; i++) {
            rows.add(row((i + 1) + ".計測値", .25f, .36f + i * .09f, .42f));
            rows.add(row(new String[] {"+21s(243°)", "▲+14s(253°)", "▼-7s(222°%)"}[i], .65f, .36f + i * .09f, .78f));
        }
        return rows;
    }
    @Test public void tableCellsAndHeadingAreExcludedWhileNarrationRemains() {
        List<OcrLine> rows = table(); var caption = row("これは字幕として読み上げます", .03f, .91f, .98f); rows.add(caption);
        assertEquals(List.of(caption), TableTextFilter.captionsOnly(rows));
    }
    @Test public void threeNumericCaptionRowsAloneAreNotATable() {
        var rows = List.of(row("30秒", .4f, .2f, .6f), row("50秒", .4f, .3f, .6f), row("70秒", .4f, .4f, .6f));
        assertEquals(rows, TableTextFilter.captionsOnly(rows));
    }
    @Test public void twoRowCaptionWithValuesAndSpokenReactionsIsPreserved() {
        var rows = List.of(row("準備は30秒です", .1f, .2f, .5f), row("作業は50秒です", .1f, .3f, .5f), row("本当に！？", .6f, .36f, .8f));
        assertEquals(rows, TableTextFilter.captionsOnly(rows));
    }
    @Test public void tableDoesNotRemoveIndependentShortReactionOrBottomNumericCaption() {
        List<OcrLine> rows = table(); var reaction = row("本当！？", .2f, .15f, .4f);var bottom = row("30秒", .4f, .91f, .6f);rows.add(reaction);rows.add(bottom);
        assertEquals(List.of(reaction, bottom), TableTextFilter.captionsOnly(rows));
    }
}
