package jp.hidemaru.burnedcaptionreader.subtitle;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import org.junit.Test;

public class OcrVisualLineComposerTest {
    @Test public void joinsSameRowJapaneseFragmentsInReadingOrder() {
        OcrLine right = new OcrLine(2, "世界", 88, .51f, .30f, .66f, .36f);
        OcrLine left = new OcrLine(2, "こんにちは", 90, .19f, .302f, .49f, .362f);
        List<OcrLine> result = OcrVisualLineComposer.compose(Arrays.asList(right, left));
        assertEquals(1, result.size());
        assertEquals("こんにちは世界", result.get(0).getText());
        assertEquals(2, result.get(0).getSeparatedParts().size());
    }

    @Test public void differentRowsAndBlocksAreNotMerged() {
        OcrLine a = new OcrLine(1, "一行目", 90, .2f, .20f, .42f, .25f);
        OcrLine b = new OcrLine(1, "二行目", 90, .2f, .28f, .42f, .33f);
        OcrLine c = new OcrLine(3, "別枠", 90, .44f, .205f, .60f, .255f);
        List<OcrLine> out = OcrVisualLineComposer.compose(Arrays.asList(b, c, a));
        assertEquals(3, out.size());
        assertEquals("一行目", out.get(0).getText());
        assertEquals("二行目", out.get(2).getText());
    }

    @Test public void farApartColumnsStaySeparate() {
        OcrLine left = new OcrLine(1, "左の字幕", 80, .05f, .2f, .25f, .25f);
        OcrLine right = new OcrLine(1, "右の字幕", 80, .65f, .2f, .85f, .25f);
        assertEquals(2, OcrVisualLineComposer.compose(Arrays.asList(right, left)).size());
    }

    @Test public void leavesSingleRowsUntouched() {
        OcrLine one = new OcrLine(1, "すでに行です", 95, .2f, .3f, .8f, .35f);
        assertSame(one, OcrVisualLineComposer.compose(List.of(one)).get(0));
    }
}
