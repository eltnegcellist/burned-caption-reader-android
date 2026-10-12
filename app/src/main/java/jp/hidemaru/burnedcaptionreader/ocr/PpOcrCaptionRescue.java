package jp.hidemaru.burnedcaptionreader.ocr;
import java.util.*;import java.util.regex.Pattern;import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleNormalizer;
/** Repairs and adds outlined horizontal captions; keeps existing row block identity.
 * Image style is independent of words, channel names, or hard-coded screen positions.
 * Plain document/board regions and short labels are deliberately not rescued here.
 */
public final class PpOcrCaptionRescue {
 public static final int MAX_READINGS=6;
 private static final Pattern JAPANESE=Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
 public static boolean eligibleCaptionShape(PpOcrDbPostProcessor.Box b,int width,int height){
  return b.width()>=.25f&&b.height()>=.025f&&b.height()<=.20f&&b.width()*width>=2*b.height()*height
   &&Math.abs(Math.toDegrees(Math.atan2((b.points[3]-b.points[1])*height,(b.points[2]-b.points[0])*width)))<=12;
 }
 private static boolean overlaps(PpOcrDbPostProcessor.Box b,OcrLine row){
  float i=Math.max(0,Math.min(b.right,row.getRight())-Math.max(b.left,row.getLeft()))*Math.max(0,Math.min(b.bottom,row.getBottom())-Math.max(b.top,row.getTop()));
  return i>=b.width()*b.height()*.20f;
 }
 public static boolean missingCaptionShape(PpOcrDbPostProcessor.Box b,List<OcrLine>rows,int width,int height){
  if(!eligibleCaptionShape(b,width,height))return false;
  for(OcrLine row:rows)if(overlaps(b,row))return false;return true;
 }
 /** -1 missing, -2 ambiguous/multiline; otherwise the unique existing line index. */
 public static int matchingRow(PpOcrDbPostProcessor.Box b,List<OcrLine>rows){
  int found=-1;
  for(int i=0;i<rows.size();i++)if(overlaps(b,rows.get(i))){
   if(found!=-1||rows.get(i).getHeight()>b.height()*1.5f)return -2;found=i;
  }return found;
 }
 public static boolean acceptsRepair(OcrLine source,String text,double probability){
  if(!acceptsReading(text,probability)||probability<.90)return false;
  String key=SubtitleNormalizer.comparisonKey(source.getText());
  return PpOcrRefinementPolicy.accepts(source.getText(),text,probability)
   ||(key.codePointCount(0,key.length())<=3&&probability>=.98);
 }
 public static OcrLine repaired(OcrLine source,PpOcrDbPostProcessor.Box box,String text,float glyph,double probability){
  boolean same=SubtitleNormalizer.comparisonKey(source.getText()).equals(SubtitleNormalizer.comparisonKey(text));
  return new OcrLine(source.getBlockIndex(),text,Math.max(55,source.getConfidence()),box.left,box.top,box.right,box.bottom,
   same?source.getSeparatedParts():Collections.emptyList(),glyph,probability);
 }
 public static OcrResult apply(OcrResult original,Map<Integer,OcrLine>repairs,List<OcrLine>added){
  if(repairs.isEmpty())return append(original,added);
  List<OcrLine>rows=new ArrayList<>();
  for(int i=0;i<original.getLines().size();i++)rows.add(repairs.getOrDefault(i,original.getLines().get(i)));
  OcrResult replaced=result(original,rows);return append(replaced,added);
 }
 private static OcrResult result(OcrResult original,List<OcrLine>rows){
  rows.sort(Comparator.comparingDouble(OcrLine::getTop).thenComparingDouble(OcrLine::getLeft));
  List<String>texts=new ArrayList<>();for(OcrLine row:rows)texts.add(row.getText());
  return new OcrResult(String.join("\n",texts),original.getConfidence(),rows,"ppocrv5_detection_rescue",-1);
 }
 public static boolean acceptsReading(String text,double probability){return Double.isFinite(probability)&&probability>=.80&&probability<=1&&SubtitleNormalizer.comparisonKey(text).codePointCount(0,SubtitleNormalizer.comparisonKey(text).length())>=6&&JAPANESE.matcher(text).find();}
 /** Returns visible lettering height, or0 when no contrasting outline/core is present. */
 public static float outlinedGlyphHeight(int[]pixels,int w,int h,PpOcrDbPostProcessor.Box b){
  if((long)w*h!=pixels.length)throw new IllegalArgumentException("Invalid style pixels");int l=Math.max(0,(int)(b.left*w)),t=Math.max(0,(int)(b.top*h)),r=Math.min(w,(int)Math.ceil(b.right*w)),bottom=Math.min(h,(int)Math.ceil(b.bottom*h));if(l>=r||t>=bottom)return 0;int radius=Math.max(1,Math.min(4,(bottom-t)/10)),count=0,core=0,dark=0,rim=0,minY=bottom,maxY=t;
  for(int y=t;y<bottom;y+=2)for(int x=l;x<r;x+=2){int p=pixels[y*w+x],red=(p>>16)&255,green=(p>>8)&255,blue=p&255,max=Math.max(red,Math.max(green,blue)),min=Math.min(red,Math.min(green,blue));count++;if(max<100)dark++;boolean isCore=min>=205||(max>=180&&max-min>=70&&min<=160);if(!isCore)continue;core++;boolean edge=false;
   for(int dy=-radius;dy<=radius&&!edge;dy+=radius)for(int dx=-radius;dx<=radius;dx+=radius){int xx=x+dx,yy=y+dy;if(xx<l||xx>=r||yy<t||yy>=bottom)continue;int q=pixels[yy*w+xx];if(((q>>16)&255)<100&&((q>>8)&255)<100&&(q&255)<100){edge=true;break;}}
   if(edge){rim++;minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
  }
  if(count==0||core<count*.04||core>count*.60||dark<count*.12||rim<core*.35||maxY<minY)return 0;return(maxY-minY+2)/(float)h;
 }
 public static OcrResult append(OcrResult original,List<OcrLine>rescued){if(rescued.isEmpty())return original;List<OcrLine>rows=new ArrayList<>(original.getLines());rows.addAll(rescued);rows.sort(Comparator.comparingDouble(OcrLine::getTop).thenComparingDouble(OcrLine::getLeft));List<String>texts=new ArrayList<>();double confidence=0;for(OcrLine row:rows){texts.add(row.getText());confidence+=row.getConfidence();}return new OcrResult(String.join("\n",texts),confidence/rows.size(),rows,"ppocrv5_detection_rescue",-1);}
}
