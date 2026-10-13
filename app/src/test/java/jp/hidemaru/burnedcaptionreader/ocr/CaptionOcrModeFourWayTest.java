package jp.hidemaru.burnedcaptionreader.ocr;
import org.junit.Test;
import static org.junit.Assert.*;
public class CaptionOcrModeFourWayTest {
 @Test public void fourCombinationsAreDistinctAndRoundTrip() {
  assertEquals(5,CaptionOcrMode.values().length);
  for(boolean d:new boolean[]{false,true}) for(boolean r:new boolean[]{false,true}){
   CaptionOcrMode mode=CaptionOcrMode.of(d,r);
   assertEquals(d,mode.v6Detection);assertEquals(r,mode.v6Recognition);
   assertEquals(mode,CaptionOcrMode.fromStoredValue(mode.storedValue));
  }
 }
 @Test public void adoptedEModeHasMlKitDetectionAndV6Recognition() {
  assertFalse(CaptionOcrMode.E.v6Detection);assertTrue(CaptionOcrMode.E.v6Recognition);
  assertEquals(CaptionOcrMode.E,CaptionOcrMode.fromStoredValue("e_mlkit_v6"));
  assertNotEquals(CaptionOcrMode.REC_V6,CaptionOcrMode.E);
 }
 @Test public void previousSettingsMigrateSafely() {
  assertEquals(CaptionOcrMode.BASELINE,CaptionOcrMode.fromStoredValue("mlkit"));
  assertEquals(CaptionOcrMode.BASELINE,CaptionOcrMode.fromStoredValue("pp_detection"));
  assertEquals(CaptionOcrMode.E,CaptionOcrMode.fromStoredValue(null));
  assertEquals(CaptionOcrMode.BASELINE,CaptionOcrMode.fromStoredValue("invalid_legacy_mode"));
  assertEquals(CaptionOcrMode.BASELINE,CaptionOcrMode.fromStoredValue("hybrid"));
 }
}
