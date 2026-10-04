package jp.hidemaru.burnedcaptionreader;

import org.junit.Test;
import static org.junit.Assert.*;

public class EvaluationWindowTest {
    @Test public void legacyEmptyGateStartsAtBeginning() {
        assertEquals(0,EvaluationWindow.startSeconds("\n"));
    }
    @Test public void requestedMiddleWindowUsesExactSeconds() {
        assertEquals(600,EvaluationWindow.startSeconds("600\n"));
    }
    @Test public void malformedOrOutOfRangeGateIsRejected() {
        for(String input:new String[]{"-1","0;alert(1)","NaN","600.5","99999"}) {
            try { EvaluationWindow.startSeconds(input);fail(input); }
            catch(IllegalArgumentException expected) { }
        }
    }
}
