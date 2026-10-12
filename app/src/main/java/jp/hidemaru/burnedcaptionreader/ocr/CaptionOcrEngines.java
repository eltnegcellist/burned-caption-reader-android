package jp.hidemaru.burnedcaptionreader.ocr;
import android.content.Context;
public final class CaptionOcrEngines {
 private CaptionOcrEngines(){}
 public static OcrEngine create(Context context,CaptionOcrMode mode){
  return mode==CaptionOcrMode.BASELINE?new HybridCaptionOcrEngine(context,false):
   new V6ComparisonOcrEngine(context,mode.v6Detection,mode.v6Recognition);
 }
}
