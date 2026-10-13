package jp.hidemaru.burnedcaptionreader.ocr;
import android.content.Context;
public final class CaptionOcrEngines {
 private CaptionOcrEngines(){}
 /** Explicit mode creation is retained for offline diagnostics; production gets D from AppPreferences. */
 public static OcrEngine create(Context context,CaptionOcrMode mode){
  return mode==CaptionOcrMode.BASELINE?new HybridCaptionOcrEngine(context,false):
   new V6ComparisonOcrEngine(context,mode.v6Detection,mode.v6Recognition);
 }
}
