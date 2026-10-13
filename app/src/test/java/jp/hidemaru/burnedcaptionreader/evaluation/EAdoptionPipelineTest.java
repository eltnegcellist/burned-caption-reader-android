package jp.hidemaru.burnedcaptionreader.evaluation;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Collections;
import jp.hidemaru.burnedcaptionreader.ocr.*;
import jp.hidemaru.burnedcaptionreader.subtitle.*;

public class EAdoptionPipelineTest {
    private OcrResult reading() {
        String text="へえ…昔と違って";
        OcrLine line=new OcrLine(0,text,50,.1f,.1f,.9f,.2f,Collections.emptyList(),.1f,.99);
        return new OcrResult(text,50,Collections.singletonList(line),"ppocrv6_small",.99);
    }
    @Test public void adoptedSelectorRetainsQualifiedV6Correction() {
        OcrRefinementSelector selector=new OcrRefinementSelector();String coarse="へえooo昔と違って";
        assertEquals(coarse,selector.selectForMode(CaptionOcrMode.REC_V6,coarse,50,reading()).getText());
        assertEquals("へえ…昔と違って",selector.selectForMode(CaptionOcrMode.E,coarse,50,reading()).getText());
    }
    @Test public void otherDiagnosticModesKeepLegacySelection() {
        OcrRefinementSelector selector=new OcrRefinementSelector();String coarse="へえooo昔と違って";
        for(CaptionOcrMode mode:new CaptionOcrMode[]{CaptionOcrMode.BASELINE,CaptionOcrMode.REC_V6,CaptionOcrMode.DET_V6,CaptionOcrMode.BOTH_V6})
            assertEquals(selector.select(coarse,50,reading()).getText(),selector.selectForMode(mode,coarse,50,reading()).getText());
    }
    @Test public void EWithFirstObservationCommitsWithoutASecondFrame() {
        String text=new OcrRefinementSelector().selectForMode(CaptionOcrMode.E,"へえooo昔と違って",50,reading()).getText();
        SubtitleStabilizer.Config config=new SubtitleStabilizer.Config();config.commitMode=SubtitleStabilizer.CommitMode.FIRST_OBSERVATION;
        SubtitleStabilizer stabilizer=new SubtitleStabilizer(config);
        TemporalOcrConsensus.Result fused=TemporalOcrConsensus.singleFrame(text,99,"へえooo昔と違って");
        SubtitleEvent event=stabilizer.observe(1000,fused.getText(),fused.getConfidence());
        assertNotNull(event);assertEquals(1,stabilizer.getLastCommitObservations());
        assertNull(stabilizer.observe(1500,fused.getText(),fused.getConfidence()));
    }
    @Test public void OptionalRecheckStillWaitsForAnotherObservation() {
        SubtitleStabilizer.Config config=new SubtitleStabilizer.Config();config.commitMode=SubtitleStabilizer.CommitMode.REOBSERVE;config.stableMs=300;
        SubtitleStabilizer stabilizer=new SubtitleStabilizer(config);
        assertNull(stabilizer.observe(1000,"説明が続きます",99));
        assertNotNull(stabilizer.observe(1400,"説明が続きます",99));assertEquals(2,stabilizer.getLastCommitObservations());
    }
}
