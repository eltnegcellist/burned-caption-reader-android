package jp.hidemaru.burnedcaptionreader.evaluation;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;

public class ExperimentalEPolicyTest {
    private OcrLine row(String text,float l,float t,float r,float b,double probability) {
        return new OcrLine(0,text,50,l,t,r,b,Collections.emptyList(),b-t,probability);
    }
    private OcrResult reading(OcrLine... rows) {
        List<String> text=new ArrayList<>();for(OcrLine row:rows)text.add(row.getText());
        return new OcrResult(String.join("\n",text),50,Arrays.asList(rows),"ppocrv6_small",.99);
    }
    private ExperimentalEPolicy.Choice choose(String coarse,OcrResult refined) {
        return ExperimentalEPolicy.choose(coarse,refined,coarse,50);
    }
    @Test public void acceptsRemovalOfSpuriousLatinDots() {
        String coarse="へえooo昔と違って";
        ExperimentalEPolicy.Choice x=choose(coarse,reading(row("へえ…昔と違って",.1f,.1f,.9f,.2f,.99)));
        assertEquals("へえ…昔と違って",x.text);assertTrue(x.changed);
    }
    @Test public void rejectsMissingSecondCaptionRow() {
        String coarse="説明が続きます\n次の字幕です";
        assertEquals(coarse,choose(coarse,reading(row("説明が続きます",.1f,.1f,.9f,.2f,.99))).text);
    }
    @Test public void unknownProbabilityDoesNotUseMlKitConfidence() {
        String coarse="訂正前の字幕です";
        assertFalse(choose(coarse,reading(row("訂正後の字幕です",.1f,.1f,.9f,.2f,-1))).changed);
    }
    @Test public void rejectsWeakRecognition() {
        String coarse="訂正前の字幕です";
        assertFalse(choose(coarse,reading(row("訂正後の字幕です",.1f,.1f,.9f,.2f,.60))).changed);
    }
    @Test public void weakNumericChangeIsRejected() {
        String coarse="価格は100円です";
        assertEquals(coarse,choose(coarse,reading(row("価格は1000円です",.1f,.1f,.9f,.2f,.85))).text);
    }
    @Test public void strongNumericReadingMayCorrectTheCoarseReading() {
        assertEquals("価格は1000円です",choose("価格は100円です",reading(row("価格は1000円です",.1f,.1f,.9f,.2f,.95))).text);
    }
    @Test public void weakNegationChangeIsRejected() {
        String coarse="変更できません";
        assertEquals(coarse,choose(coarse,reading(row("変更できます",.1f,.1f,.9f,.2f,.85))).text);
    }
    @Test public void unrelatedHighProbabilityReadingIsRejected() {
        String coarse="今日の字幕です";
        assertEquals(coarse,choose(coarse,reading(row("別人物発言内容",.1f,.1f,.9f,.2f,.99))).text);
    }
    @Test public void distantRowsDoNotJoin() {
        String coarse="説明が続きます\n次の字幕です";
        assertFalse(choose(coarse,reading(row("説明は続きます",.1f,.1f,.9f,.2f,.99),row("次の字幕です",.1f,.7f,.9f,.8f,.99))).changed);
    }
    @Test public void geometryAndCompleteRowsPermitCorrection() {
        String coarse="説明か続きます\n次の字幕です";
        assertEquals("説明が続きます\n次の字幕です",choose(coarse,reading(row("説明が続きます",.1f,.1f,.9f,.2f,.99),row("次の字幕です",.1f,.23f,.9f,.33f,.99))).text);
    }
    @Test public void removesMergedNounLabelsWithIndependentLargerCaption() {
        OcrResult r=reading(row("説明が続きます",.1f,.03f,.9f,.17f,-1),row("次の字幕です",.1f,.20f,.9f,.34f,-1),row("役員担当者",.55f,.88f,.97f,.98f,-1));
        assertEquals(1,ExperimentalEPolicy.filter(r).removed);
    }
    @Test public void nounWithoutIndependentCaptionIsPreserved() {
        assertEquals(0,ExperimentalEPolicy.filter(reading(row("役員担当者",.55f,.88f,.97f,.98f,-1))).removed);
    }
    @Test public void bottomSentenceIsPreserved() {
        OcrResult r=reading(row("説明が続きます",.1f,.03f,.9f,.17f,-1),row("次の字幕です",.1f,.20f,.9f,.34f,-1),row("担当します",.55f,.88f,.97f,.98f,-1));
        assertEquals(0,ExperimentalEPolicy.filter(r).removed);
    }
    @Test public void shortReactionIsPreserved() {
        OcrResult r=reading(row("説明が続きます",.1f,.03f,.9f,.17f,-1),row("次の字幕です",.1f,.20f,.9f,.34f,-1),row("そうなの？",.55f,.88f,.97f,.98f,-1));
        assertEquals(0,ExperimentalEPolicy.filter(r).removed);
    }
    @Test public void equallySizedDialogueIsPreserved() {
        OcrResult r=reading(row("説明が続きます",.1f,.03f,.9f,.13f,-1),row("次の字幕です",.1f,.16f,.9f,.26f,-1),row("役員担当者",.55f,.88f,.97f,.98f,-1));
        assertEquals(0,ExperimentalEPolicy.filter(r).removed);
    }
    @Test public void topLabelWithBottomCaptionIsSymmetric() {
        OcrResult r=reading(row("役員担当者",.55f,.03f,.97f,.13f,-1),row("説明が続きます",.1f,.65f,.9f,.79f,-1),row("次の字幕です",.1f,.82f,.9f,.96f,-1));
        assertEquals(1,ExperimentalEPolicy.filter(r).removed);
    }
}
