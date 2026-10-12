package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;

/** Chooses text from a second OCR of the SAME frame, never from a later frame. */
public final class OcrRefinementSelector {
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
    private static final Pattern METADATA = Pattern.compile(
            "(?:チャンネル登録|登録者|視聴回数|再生回数|\\d+(?:[.,]\\d+)?[万億]?回(?:視聴|再生)?"
            + "|\\d+(?:分|時間|日|週間|週|か月|ヶ月|月|年)前|subscribe|views|comments)",
            Pattern.CASE_INSENSITIVE);

    public static final class Result {
        private final String text;
        private final double confidence;
        private final boolean refined;
        Result(String text, double confidence) {
            this(text, confidence, true);
        }
        Result(String text, double confidence, boolean refined) {
            this.text = text;
            this.confidence = confidence;
            this.refined = refined;
        }
        public String getText() { return text; }
        public double getConfidence() { return confidence; }
    }

    /** White-core OCR is a same-frame alternative, never permission to lose colored captions. */
    public Result select(String originalText, double originalConfidence, OcrResult refined, OcrResult whiteCore) {
        Result regular = select(originalText, originalConfidence, refined);
        if (whiteCore == null || !shouldTryWhiteCore(regular)) return regular;
        Result alternative = select(originalText, originalConfidence, whiteCore);
        if (!alternative.refined || alternative.confidence < Math.max(70, regular.confidence + 15)
                || alternative.text.split("\n").length != regular.text.split("\n").length
                || !preservesText(regular.text, alternative.text)) return regular;
        return alternative;
    }

    public boolean shouldTryWhiteCore(Result regular) {
        return regular.confidence < 75 && JAPANESE.matcher(regular.text).find();
    }

    public Result select(String originalText, double originalConfidence, OcrResult refined) {
        boolean trustedPp = "ppocrv5".equals(refined.getBackend());
        String original = SubtitleNormalizer.normalize(originalText);
        Result fallback = new Result(original, confidence(originalConfidence), false);
        if (original.isEmpty()) return fallback;
        String[] originalRows = original.split("\n");
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine line : refined.getLines()) {
            if (!SubtitleNormalizer.comparisonKey(line.getText()).isEmpty()
                    && line.getHeight() > 0 && line.getWidth() > 0) lines.add(line);
        }
        lines.sort(Comparator.comparingDouble(OcrLine::getTop)
                .thenComparingDouble(OcrLine::getLeft));

        Result best = null;
        Result restored = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double restoredScore = Double.NEGATIVE_INFINITY;
        for (int start = 0; start < lines.size(); start++) {
            List<OcrLine> group = new ArrayList<>();
            for (int end = start; end < lines.size() && end < start + 3; end++) {
                OcrLine line = lines.get(end);
                if (!group.isEmpty() && !adjacent(group.get(group.size() - 1), line)) break;
                group.add(line);
                String candidate = text(group);
                double meanConfidence = group.stream()
                        .mapToDouble(row -> confidence(row.getConfidence())).average().orElse(0);
                double score = score(original, candidate, meanConfidence);
                if (restoresRow(originalRows, group, originalConfidence)) {
                    // Completeness is a separate decision, not a small length bonus
                    // overwhelmed by the exact match of the incomplete first OCR.
                    if (score > restoredScore) {
                        restored = new Result(candidate, meanConfidence);
                        restoredScore = score;
                    }
                } else if (preservesText(original, candidate, trustedPp
                        && group.stream().allMatch(row -> row.getModelProbability() >= .65))
                        && meanConfidence >= Math.max(20, confidence(originalConfidence) - 25)
                        && score > bestScore) {
                    best = new Result(candidate, meanConfidence);
                    bestScore = score;
                }
            }
        }
        if (restored != null) return restored;
        if (best != null) return best;
        // Engines without boxes may correct text, but may not add unchecked rows.
        if (refined.getLines().isEmpty()) {
            String candidate = SubtitleNormalizer.normalize(refined.getText());
            if (candidate.split("\n").length == originalRows.length
                    && preservesText(original, candidate)
                    && confidence(refined.getConfidence()) >= Math.max(20, confidence(originalConfidence) - 25)) {
                return new Result(candidate, confidence(refined.getConfidence()));
            }
        }
        return fallback;
    }

    private boolean restoresRow(String[] originalRows, List<OcrLine> group, double originalConfidence) {
        if (originalRows.length != 2 || group.size() != 3) return false;
        for (int missing = 0; missing < 3; missing++) {
            boolean matches = true;
            int source = 0;
            for (int row = 0; row < 3; row++) {
                if (row == missing) continue;
                OcrLine line = group.get(row);
                if (Similarity.textSimilarity(originalRows[source++], line.getText()) < 0.80
                        || confidence(line.getConfidence()) < Math.max(20, confidence(originalConfidence) - 25)) {
                    matches = false;
                    break;
                }
            }
            OcrLine added = group.get(missing);
            String extra = SubtitleNormalizer.normalize(added.getText());
            if (matches && confidence(added.getConfidence()) >= 20
                    && JAPANESE.matcher(extra).find() && !METADATA.matcher(extra).find()
                    && !duplicatesRow(added, group)) return true;
        }
        return false;
    }

    private boolean duplicatesRow(OcrLine added, List<OcrLine> group) {
        for (OcrLine line : group) {
            if (line != added && Similarity.textSimilarity(added.getText(), line.getText()) >= 0.95) {
                return true;
            }
        }
        return false;
    }

    private boolean adjacent(OcrLine upper, OcrLine lower) {
        float height = Math.max(upper.getHeight(), lower.getHeight());
        if (Math.min(upper.getHeight(), lower.getHeight()) < height * 0.45f) return false;
        float gap = lower.getTop() - upper.getBottom();
        // Coordinates belong to a tightly cropped band, so use character height,
        // not the full-video normalized thresholds from the tracking stage.
        if (gap < -height * 0.20f || gap > height * 1.35f) return false;
        float overlap = Math.max(0, Math.min(upper.getRight(), lower.getRight())
                - Math.max(upper.getLeft(), lower.getLeft()));
        float ratio = overlap / Math.max(0.001f, Math.min(upper.getWidth(), lower.getWidth()));
        float alignment = Math.min(Math.abs(upper.getLeft() - lower.getLeft()),
                Math.min(Math.abs(upper.getCenterX() - lower.getCenterX()),
                        Math.abs(upper.getRight() - lower.getRight())));
        return ratio >= 0.30f && alignment <= 0.12f;
    }

    private boolean preservesText(String original, String candidate) {
        return preservesText(original, candidate, false);
    }

    private boolean preservesText(String original, String candidate, boolean trustedPp) {
        String a = SubtitleNormalizer.comparisonKey(original);
        String b = SubtitleNormalizer.comparisonKey(candidate);
        if (b.isEmpty()) return false;
        int aLength = a.codePointCount(0, a.length());
        int bLength = b.codePointCount(0, b.length());
        // Same-frame OCR must not turn an incomplete prefix into spoken content.
        if (!a.equals(b) && a.contains(b)) return false;
        if (!a.equals(b) && candidate.split("\n").length < original.split("\n").length) return false;
        if (candidate.split("\n").length > original.split("\n").length) return false;
        // PP already passed per-line agreement against ML Kit on the high-resolution input.
        // A coarse, corrupted caption must not veto those corrections using the old OCR gate.
        return bLength >= Math.ceil(aLength * (trustedPp ? 0.50 : 0.85))
                && bLength <= (trustedPp ? Math.floor(aLength * 1.50) : Math.ceil(aLength * 1.20))
                && Similarity.textSimilarity(original, candidate) >= (trustedPp ? 0.50 : 0.80);
    }

    private String text(List<OcrLine> lines) {
        List<String> rows = new ArrayList<>();
        for (OcrLine line : lines) rows.add(SubtitleNormalizer.normalize(line.getText()));
        return String.join("\n", rows);
    }

    private double score(String original, String candidate, double confidence) {
        return Similarity.textSimilarity(original, candidate) * 2.0 + confidence / 400.0;
    }

    private static double confidence(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 55;
    }
}
