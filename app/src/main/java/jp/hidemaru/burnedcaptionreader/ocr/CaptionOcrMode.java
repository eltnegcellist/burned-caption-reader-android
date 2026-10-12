package jp.hidemaru.burnedcaptionreader.ocr;
public enum CaptionOcrMode {
 BASELINE("hybrid",false,false), REC_V6("v6_rec",false,true),
 DET_V6("v6_det",true,false), BOTH_V6("v6_both",true,true);
 public final String storedValue,label,description;public final boolean v6Detection,v6Recognition;
 CaptionOcrMode(String id,boolean det,boolean rec){storedValue=id;v6Detection=det;v6Recognition=rec;label=(det?"PP-OCRv6 Small":"ML Kit")+" × "+(rec?"PP-OCRv6 Small":"PP-OCRv5 Mobile");description="検出："+(det?"v6 Small":"ML Kit")+"／認識："+(rec?"v6 Small":"v5 Mobile");}
 public static CaptionOcrMode of(boolean det,boolean rec){return det?(rec?BOTH_V6:DET_V6):(rec?REC_V6:BASELINE);}
 public static CaptionOcrMode fromStoredValue(String value){
  // An unset preference uses D; previously saved A/B/C/D selections stay unchanged.
  if(value==null)return BOTH_V6;
  for(CaptionOcrMode m:values())if(m.storedValue.equals(value))return m;
  // Unrecognized legacy values stay on the safe baseline A.
  return BASELINE;
 }
}
