package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Observation characterConsensus = buildCharacterConsensus(values, timestamp);
        if (characterConsensus != null) values.add(characterConsensus);

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
            String key = SubtitleNormalizer.comparisonKey(candidate.text);
            int length = key.codePointCount(0, key.length());
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

    private Observation buildCharacterConsensus(List<Observation> values, long timestamp) {
        if (values.size() < 3) return null;
        Observation reference = values.get(values.size() - 1);
        int[] referencePoints = reference.text.codePoints().toArray();
        if (referencePoints.length == 0) return null;

        List<Observation> aligned = new ArrayList<>();
        for (Observation value : values) {
            int[] points = value.text.codePoints().toArray();
            if (points.length != referencePoints.length) continue;
            if (Similarity.textSimilarity(reference.text, value.text) < 0.60) continue;
            aligned.add(value);
        }
        if (aligned.size() < 3) return null;

        StringBuilder consensus = new StringBuilder();
        for (int index = 0; index < referencePoints.length; index++) {
            Map<Integer, Integer> counts = new HashMap<>();
            Map<Integer, Double> confidenceTotals = new HashMap<>();
            for (Observation value : aligned) {
                int point = value.text.codePoints().toArray()[index];
                counts.put(point, counts.getOrDefault(point, 0) + 1);
                confidenceTotals.put(point,
                        confidenceTotals.getOrDefault(point, 0.0) + value.confidence);
            }

            int winner = referencePoints[index];
            int winnerCount = -1;
            double winnerConfidence = -1.0;
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                int point = entry.getKey();
                int count = entry.getValue();
                double totalConfidence = confidenceTotals.getOrDefault(point, 0.0);
                if (count > winnerCount
                        || (count == winnerCount && totalConfidence > winnerConfidence)) {
                    winner = point;
                    winnerCount = count;
                    winnerConfidence = totalConfidence;
                }
            }
            consensus.appendCodePoint(winner);
        }

        String text = SubtitleNormalizer.normalize(consensus.toString());
        if (text.isEmpty()) return null;
        double confidenceTotal = 0.0;
        for (Observation value : aligned) confidenceTotal += value.confidence;
        return new Observation(timestamp, text, confidenceTotal / aligned.size());
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
