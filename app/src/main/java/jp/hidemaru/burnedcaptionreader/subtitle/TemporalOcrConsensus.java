package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Smooths small frame-to-frame OCR wobble without hiding genuine subtitle changes.
 * The history is intentionally short and is reset when the newest observation is
 * too different from the previous one.
 */
public final class TemporalOcrConsensus {
    public static final class Result {
        private final String text;
        private final double confidence;

        Result(String text, double confidence) {
            this.text = text;
            this.confidence = confidence;
        }

        public String getText() { return text; }
        public double getConfidence() { return confidence; }
    }

    private static final class Observation {
        final long timestamp;
        final String text;
        final double confidence;

        Observation(long timestamp, String text, double confidence) {
            this.timestamp = timestamp;
            this.text = text;
            this.confidence = confidence;
        }
    }

    private static final int MAX_OBSERVATIONS = 4;
    private static final long MAX_HISTORY_MS = 1_800L;
    private static final double SAME_CAPTION_THRESHOLD = 0.72;

    private final Deque<Observation> history = new ArrayDeque<>();

    public synchronized Result observe(long timestamp, String rawText, double confidence) {
        String text = SubtitleNormalizer.normalize(rawText);
        if (text.isEmpty()) {
            expire(timestamp);
            return new Result("", confidence);
        }

        expire(timestamp);
        Observation previous = history.peekLast();
        if (previous != null && !sameCaption(previous.text, text)) {
            history.clear();
        }

        history.addLast(new Observation(timestamp, text, confidence));
        while (history.size() > MAX_OBSERVATIONS) history.removeFirst();

        List<Observation> values = new ArrayList<>(history);
        Observation best = values.get(values.size() - 1);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Observation candidate : values) {
            double similarityTotal = 0.0;
            int related = 0;
            for (Observation other : values) {
                double similarity = Similarity.textSimilarity(candidate.text, other.text);
                if (similarity >= 0.60 || Similarity.isPrefixRelation(candidate.text, other.text)
                        || Similarity.isMultilineVariant(candidate.text, other.text, 0.50)) {
                    similarityTotal += similarity;
                    related++;
                }
            }
            double meanSimilarity = related == 0 ? 0.0 : similarityTotal / related;
            int length = SubtitleNormalizer.comparisonKey(candidate.text)
                    .codePointCount(0, SubtitleNormalizer.comparisonKey(candidate.text).length());
            double score = meanSimilarity * 2.0
                    + Math.max(0.0, Math.min(100.0, candidate.confidence)) / 100.0 * 0.20
                    + Math.min(1.0, length / 24.0) * 0.08;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        double confidenceTotal = 0.0;
        int confidenceCount = 0;
        for (Observation value : values) {
            if (Similarity.textSimilarity(best.text, value.text) >= 0.70
                    || Similarity.isPrefixRelation(best.text, value.text)
                    || Similarity.isMultilineVariant(best.text, value.text, 0.50)) {
                confidenceTotal += value.confidence;
                confidenceCount++;
            }
        }
        double meanConfidence = confidenceCount == 0
                ? best.confidence : confidenceTotal / confidenceCount;
        return new Result(best.text, meanConfidence);
    }

    public synchronized void reset() {
        history.clear();
    }

    private void expire(long timestamp) {
        while (!history.isEmpty()
                && timestamp - history.peekFirst().timestamp > MAX_HISTORY_MS) {
            history.removeFirst();
        }
    }

    private boolean sameCaption(String previous, String current) {
        return Similarity.textSimilarity(previous, current) >= SAME_CAPTION_THRESHOLD
                || Similarity.isPrefixRelation(previous, current)
                || Similarity.isMultilineVariant(previous, current, 0.50);
    }
}
