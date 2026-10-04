package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;

public class EmbeddedUiTextFilterTest {
    private OcrLine row(int block, String text, float l, float t, float r, float b) {
        return new OcrLine(block, text, 90, l, t, r, b);
    }
    private OcrLine largeCaption() { return row(9, "本日の旅を楽しんでいます", .04f, .80f, .96f, .96f); }

    @Test public void ratingAndPriceRangeRejectAlignedDocumentIncludingHeading() {
        OcrLine caption = largeCaption();
        var rows = List.of(row(0, "景色を楽しめる新しいお店", .20f, .03f, .80f, .09f),
                row(1, "駅前店", .21f, .10f, .30f, .15f),
                row(2, "★★★★3.9 820人", .20f, .31f, .60f, .36f),
                row(3, "￥2,000〜￥2,999", .21f, .41f, .59f, .45f), caption);
        assertEquals(List.of(caption), EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void NumericRatingCountsAndRangesStillEstablishPanel() {
        OcrLine smallCaption = row(9, "うれしいです", .38f, .70f, .63f, .75f);
        var rows = List.of(row(0, "新しいレストラン", .20f, .03f, .70f, .09f),
                row(1, "3.82 500人 2000人", .33f, .31f, .63f, .36f),
                row(2, "￥2,000〜￥2,999", .21f, .41f, .59f, .45f), smallCaption);
        assertEquals(List.of(smallCaption), EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void oneQuotedReviewDoesNotEstablishReviewPanel() {
        var rows = List.of(row(0, "良いお店でした by writer(12)", .2f, .2f, .8f, .25f));
        assertEquals(rows, EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void repeatedReviewCreditsDoNotSuppressLargerNarration() {
        OcrLine caption = largeCaption();
        var rows = List.of(row(0, "料理がおすすめ by userA(9)", .22f, .15f, .70f, .20f),
                row(1, "雰囲気も良かった by userB(25)", .21f, .22f, .68f, .26f), caption);
        assertEquals(List.of(caption), EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void twoClocksAndFourAlignedMessageRowsRejectChatBodyOnly() {
        OcrLine top = row(0, "9:42", .18f, .01f, .25f, .05f);
        OcrLine bottom = row(6, "8:13", .66f, .55f, .70f, .58f);
        OcrLine caption = largeCaption();
        var rows = List.of(top, row(1, "週末はどう過ごしますか", .27f, .29f, .59f, .34f),
                row(2, "天気が良さそうですね", .27f, .37f, .54f, .41f),
                row(3, "みんなで出かけましょう", .27f, .44f, .57f, .48f),
                row(4, "また連絡しますね！", .27f, .51f, .52f, .55f), bottom, caption);
        assertEquals(List.of(caption), EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void ThreeCaptionRowsWithClocksArePreserved() {
        var rows = List.of(row(0, "9:42", .18f, .01f, .25f, .05f),
                row(1, "仕事が終わりました", .27f, .29f, .59f, .34f),
                row(2, "今日は出かけましょう", .27f, .37f, .54f, .41f),
                row(3, "楽しみですね！", .27f, .44f, .57f, .48f),
                row(4, "8:13", .66f, .55f, .70f, .58f));
        assertEquals(rows, EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void FourAlignedRowsWithoutTwoClocksArePreserved() {
        var rows = List.of(row(1, "これは字幕の一行目です", .27f, .29f, .59f, .34f),
                row(2, "これは二行目です", .27f, .37f, .54f, .41f),
                row(3, "三行目もあります", .27f, .44f, .57f, .48f),
                row(4, "最後の行です", .27f, .51f, .52f, .55f));
        assertEquals(rows, EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void RepeatedPriceCardsAreRemovedWithTheirLabels() {
        OcrLine caption = largeCaption();
        var rows = List.of(row(0, "180円", .10f, .12f, .23f, .26f),
                row(1, "お茶", .17f, .28f, .30f, .36f),
                row(2, "250円", .48f, .15f, .59f, .28f),
                row(3, "デザート", .50f, .30f, .68f, .39f), caption);
        assertEquals(List.of(caption), EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void LonePriceAndHeadingCaptionArePreserved() {
        var rows = List.of(row(0, "作業料金", .31f, .20f, .65f, .30f),
                row(1, "200円", .40f, .32f, .56f, .40f));
        assertEquals(rows, EmbeddedUiTextFilter.captionsOnly(rows));
    }
    @Test public void SmallSpokenReactionNearPriceIsPreserved() {
        OcrLine reaction = row(1, "安いですね！", .14f, .3f, .45f, .4f);
        OcrLine caption = largeCaption();
        var rows = List.of(row(0, "200円", .1f, .12f, .23f, .26f), reaction, caption);
        assertTrue(EmbeddedUiTextFilter.captionsOnly(rows).contains(reaction));
    }
    @Test public void TallConsistentTripleIsOneCaptionBand() {
        var lines = List.of(row(0, "今日も仕事が終わりました", .05f, .418f, .95f, .56f),
                row(0, "少し寄り道して帰ろうと思います", .07f, .613f, .94f, .749f),
                row(0, "夕食が楽しみです", .08f, .795f, .91f, .954f));
        var selected = new AutoSubtitleRegionTracker().selectForRecognition(0, new OcrResult("", 90, lines));
        assertEquals(1, selected.size());
        assertEquals(String.join("\n", lines.stream().map(OcrLine::getText).toArray(String[]::new)), selected.get(0).getText());
    }
    @Test public void ManualAndLegacySelectionKeepTheirOwnPolicy() {
        var rows = List.of(row(0, "★★★★3.9 820人", .20f, .31f, .60f, .36f),
                row(1, "￥2,000〜￥2,999", .21f, .41f, .59f, .45f));
        var raw = new OcrResult("", 90, rows);
        assertTrue(new AutoSubtitleRegionTracker().selectForRecognition(0, raw).isEmpty());
        var legacy = new AutoSubtitleRegionTracker();legacy.selectAll(0, raw);
        assertFalse(legacy.selectAll(500, raw).isEmpty());
    }
}
