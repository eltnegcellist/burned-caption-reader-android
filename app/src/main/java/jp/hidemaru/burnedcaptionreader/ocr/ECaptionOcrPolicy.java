package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.*;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.*;
import jp.hidemaru.burnedcaptionreader.subtitle.*;

/** Adopted E policy. No expected text, dataset ID, D result or v6 detector is accepted. */
public final class ECaptionOcrPolicy {
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
    private static final Pattern NOUN = Pattern.compile("^[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]{2,12}$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?:です|ます|でした|ました|ません|ない|たい|する|した|して|いる|ある|なる|った|って|[はがをにへとでもねよだかうる])$");
    public static final class Filtered {
        public final OcrResult result;
        public final int removed;
        Filtered(OcrResult result,int removed) { this.result=result; this.removed=removed; }
    }
    /** Require an independent larger, multirow caption before excluding a small noun label.
     * A position alone never rejects a row. Merged nameplates are allowed width <= .50.
     */
    public static Filtered filter(OcrResult source) {
        List<OcrLine> kept=new ArrayList<>(); int removed=0;
        for(OcrLine line:source.getLines()) {
            boolean reject=false;
            String text=SubtitleNormalizer.normalize(line.getText());
            if(line.getWidth()<=.50f && line.getHeight()<=.12f && NOUN.matcher(text).matches()
                    && !SENTENCE_END.matcher(text).find()) {
                for(OcrLine a:source.getLines()) {
                    if(a==line || a.getWidth()<.50f || a.getConfidence()<20 || !JAPANESE.matcher(a.getText()).find()) continue;
                    for(OcrLine b:source.getLines()) {
                        if(b==line || b==a || b.getConfidence()<20 || !JAPANESE.matcher(b.getText()).find()) continue;
                        OcrLine upper=a.getTop()<b.getTop()?a:b,lower=upper==a?b:a;
                        if(!adjacent(upper,lower)) continue;
                        float height=Math.min(a.getGlyphHeight(),b.getGlyphHeight());
                        if(height<line.getGlyphHeight()*1.25f) continue;
                        float top=Math.min(a.getTop(),b.getTop()),bottom=Math.max(a.getBottom(),b.getBottom());
                        float gap=Math.max(line.getTop()-bottom,top-line.getBottom());
                        if(gap>Math.max(.10f,height*3f)) { reject=true; break; }
                    }
                    if(reject)break;
                }
            }
            if(reject)removed++;else kept.add(line);
        }
        List<String> text=new ArrayList<>(); for(OcrLine line:kept)text.add(line.getText());
        return new Filtered(new OcrResult(String.join("\n",text),source.getConfidence(),kept,source.getBackend(),source.getModelProbability()),removed);
    }
    public static final class Choice {
        public final String text;
        public final double confidence;
        public final boolean changed;
        Choice(String text,double confidence,boolean changed) { this.text=text;this.confidence=confidence;this.changed=changed; }
    }
    /** Prefer a complete, geometrically contiguous v6 reading already accepted per-line.
     * Unknown ML Kit confidence never stands in for PP modelProbability.
     * Numeric/negation changes need every PP row probability >= .90.
     */
    public static Choice choose(String coarse,OcrResult refined,String legacyText,double legacyConfidence) {
        Choice fallback=new Choice(legacyText,legacyConfidence,false);
        if(!"ppocrv6_small".equals(refined.getBackend()))return fallback;
        String original=SubtitleNormalizer.normalize(coarse);
        int rows=original.isEmpty()?0:original.split("\n").length;
        if(rows<1 || rows>3)return fallback;
        List<OcrLine> lines=new ArrayList<>(refined.getLines());
        lines.sort(Comparator.comparingDouble(OcrLine::getTop).thenComparingDouble(OcrLine::getLeft));
        Choice best=null;double bestScore=-1;
        for(int start=0;start+rows<=lines.size();start++) {
            List<String> text=new ArrayList<>();double minimum=1,sum=0;boolean valid=true;
            for(int j=start;j<start+rows;j++) {
                OcrLine line=lines.get(j);double probability=line.getModelProbability();
                if(probability<.65 || line.getWidth()<=0 || line.getHeight()<=0
                        || SubtitleNormalizer.comparisonKey(line.getText()).isEmpty()
                        || j>start && !adjacent(lines.get(j-1),line)) { valid=false;break; }
                minimum=Math.min(minimum,probability);sum+=probability;
                text.add(SubtitleNormalizer.normalize(line.getText()));
            }
            if(!valid)continue;
            String candidate=String.join("\n",text);
            String a=SubtitleNormalizer.comparisonKey(original),b=SubtitleNormalizer.comparisonKey(candidate);
            int al=a.codePointCount(0,a.length()),bl=b.codePointCount(0,b.length());
            double similarity=Similarity.textSimilarity(original,candidate);
            if(bl<Math.ceil(al*.50) || bl>Math.floor(al*1.50) || similarity<.50)continue;
            if(Similarity.hasMeaningfulTokenChange(original,candidate) && minimum<.90)continue;
            double score=minimum+similarity;
            if(score>bestScore) {
                bestScore=score;best=new Choice(candidate,sum/rows*100,
                        !SubtitleNormalizer.comparisonKey(candidate).equals(SubtitleNormalizer.comparisonKey(legacyText)));
            }
        }
        return best==null?fallback:best;
    }
    private static boolean adjacent(OcrLine upper,OcrLine lower) {
        float height=Math.max(upper.getHeight(),lower.getHeight());
        if(Math.min(upper.getHeight(),lower.getHeight())<height*.45f)return false;
        float gap=lower.getTop()-upper.getBottom();
        if(gap<-height*.20f || gap>height*1.35f)return false;
        float overlap=Math.max(0,Math.min(upper.getRight(),lower.getRight())-Math.max(upper.getLeft(),lower.getLeft()));
        float ratio=overlap/Math.max(.001f,Math.min(upper.getWidth(),lower.getWidth()));
        float alignment=Math.min(Math.abs(upper.getLeft()-lower.getLeft()),Math.min(Math.abs(upper.getCenterX()-lower.getCenterX()),Math.abs(upper.getRight()-lower.getRight())));
        return ratio>=.30f && alignment<=.12f;
    }
}
