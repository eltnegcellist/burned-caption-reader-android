package jp.hidemaru.burnedcaptionreader.ocr;
import org.junit.Test;
import java.nio.FloatBuffer;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PpOcrTest {
    @Test public void blankSeparatesRepeatedCharacters() {
        float[] p = {.1f,.9f, .1f,.9f, .9f,.1f, .1f,.9f};
        assertEquals("字字", PpOcrCtcDecoder.decode(FloatBuffer.wrap(p),4,Arrays.asList("","字")).text);
    }
    @Test public void allBlankIsEmptyWithoutNan() {
        PpOcrCtcDecoder.Reading r = PpOcrCtcDecoder.decode(FloatBuffer.wrap(new float[]{1,0}),1,Arrays.asList("","字"));
        assertEquals("",r.text); assertEquals(0,r.probability,0);
    }
    @Test public void supplementaryUnicodeTokenIsPreserved() {
        assertEquals("𠮷", PpOcrCtcDecoder.decode(FloatBuffer.wrap(new float[]{0,1}),1,Arrays.asList("","𠮷")).text);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsCorruptShape() {
        PpOcrCtcDecoder.decode(FloatBuffer.wrap(new float[]{1}),1,Arrays.asList("","字"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsNanOutput() {
        PpOcrCtcDecoder.decode(FloatBuffer.wrap(new float[]{Float.NaN,1}),1,Arrays.asList("","字"));
    }
    @Test public void rgbIsConvertedToBgrWithNeutralPadding() {
        PpOcrInput in = PpOcrInput.fromArgb(new int[]{0xffff0000},1,1);
        assertEquals(320,in.width); assertEquals(-1,in.values[0],0);
        assertEquals(-1,in.values[48*320],0); assertEquals(1,in.values[48*320*2],0);
        assertEquals(0,in.values[48],0); // neutral padding, not white nor black
    }
    @Test public void extremeAspectIsBounded() {
        assertEquals(3200,PpOcrInput.fromArgb(new int[9000],9000,1).width);
    }
    @Test public void confidenceIsIndependentAndTextMustBeCorroborated() {
        assertTrue(PpOcrRefinementPolicy.accepts("プロジ近クト","プロジェクト",.95));
        assertFalse(PpOcrRefinementPolicy.accepts("プロジェクト","プロジェクト",.40));
        assertFalse(PpOcrRefinementPolicy.accepts("字幕です","別の全く無関係な文章です",.99));
        assertFalse(PpOcrRefinementPolicy.accepts("字幕です","字幕です\n広告です",.99));
        assertFalse(PpOcrRefinementPolicy.accepts("字幕です","",.99));
    }
}
