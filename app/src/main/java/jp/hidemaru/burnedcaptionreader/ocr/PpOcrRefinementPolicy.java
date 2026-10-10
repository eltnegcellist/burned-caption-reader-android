package jp.hidemaru.burnedcaptionreader.ocr;

import jp.hidemaru.burnedcaptionreader.subtitle.*;

/** PP probability is an absolute gate, never compared with ML Kit's confidence. */
public final class PpOcrRefinementPolicy {
    private PpOcrRefinementPolicy() {}
    public static boolean accepts(String detected, String recognized, double probability) {
        if (!Double.isFinite(probability) || probability < .65 || probability > 1) return false;
        String source = SubtitleNormalizer.comparisonKey(detected), candidate = SubtitleNormalizer.comparisonKey(recognized);
        if (source.isEmpty() || candidate.isEmpty() || recognized.contains("\n")) return false;
        int a = source.codePointCount(0, source.length()), b = candidate.codePointCount(0, candidate.length());
        return b >= Math.max(1, a * .50) && b <= a * 1.50
                && Similarity.textSimilarity(detected, recognized) >= .50;
    }
}
