package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.HashMap;
import java.util.Map;

public final class SubtitleStabilizer {
    public enum State { EMPTY, CANDIDATE, STABILIZING, COMMITTED, WAITING_CHANGE }

    public static final class Config {
        public long stableMs = 450L;
        public long blankResetMs = 650L;
        public double minConfidence = 35.0;
        public double similarityThreshold = 0.86;
        public double committedSimilarity = 0.92;
        public int minStableObservations = 2;
    }

    private static final class Variant {
        int count;
        double confidenceTotal;
    }

    private static final class Candidate {
        String text;
        long detectedAt;
        long changedAt;
        int observations;
        final Map<String, Variant> variants = new HashMap<>();
    }

    private final Config config;
    private State state = State.EMPTY;
    private Candidate candidate;
    private SubtitleEvent lastCommitted;
    private long blankSince = -1L;
    private long sequence;

    public SubtitleStabilizer(Config config) {
        this.config = config;
    }

    public synchronized SubtitleEvent observe(long timestamp, String rawText, double confidence) {
        String text = SubtitleNormalizer.normalize(rawText);
        if (text.isEmpty()) {
            observeBlank(timestamp);
            return null;
        }
        blankSince = -1L;
        if (confidence < config.minConfidence) {
            return null;
        }

        if (lastCommitted != null && matchesCommitted(text)) {
            state = State.WAITING_CHANGE;
            candidate = null;
            return null;
        }

        if (candidate == null) {
            startCandidate(text, confidence, timestamp, timestamp);
            return null;
        }

        String current = candidate.text;
        if (text.equals(current)) {
            recordVariant(text, confidence);
            candidate.observations++;
            state = State.STABILIZING;
        } else if (Similarity.isPrefixRelation(current, text)) {
            if (isGrowth(current, text)) {
                long detectedAt = candidate.detectedAt;
                startCandidate(text, confidence, timestamp, detectedAt);
            } else {
                candidate.observations++;
                state = State.STABILIZING;
            }
        } else if (Similarity.areEquivalent(current, text, config.similarityThreshold)) {
            recordVariant(text, confidence);
            candidate.observations++;
            state = State.STABILIZING;
        } else {
            startCandidate(text, confidence, timestamp, timestamp);
            return null;
        }

        if (timestamp - candidate.changedAt < config.stableMs
                || candidate.observations < config.minStableObservations) {
            return null;
        }

        String preferred = preferredVariant();
        Variant stats = candidate.variants.get(preferred);
        double meanConfidence = stats.confidenceTotal / stats.count;
        SubtitleEvent event = new SubtitleEvent(
                timestamp + "-" + (++sequence),
                preferred,
                candidate.detectedAt,
                timestamp,
                Math.round(meanConfidence * 10.0) / 10.0
        );
        lastCommitted = event;
        candidate = null;
        state = State.COMMITTED;
        return event;
    }

    private void observeBlank(long timestamp) {
        if (blankSince < 0L) blankSince = timestamp;
        if (timestamp - blankSince >= config.blankResetMs) {
            candidate = null;
            lastCommitted = null;
            state = State.EMPTY;
        }
    }

    public synchronized void reset() {
        state = State.EMPTY;
        candidate = null;
        lastCommitted = null;
        blankSince = -1L;
    }

    public synchronized State getState() {
        return state;
    }

    private void startCandidate(String text, double confidence, long timestamp, long detectedAt) {
        candidate = new Candidate();
        candidate.text = text;
        candidate.detectedAt = detectedAt;
        candidate.changedAt = timestamp;
        candidate.observations = 1;
        recordVariant(text, confidence);
        state = State.CANDIDATE;
    }

    private void recordVariant(String text, double confidence) {
        Variant variant = candidate.variants.computeIfAbsent(text, ignored -> new Variant());
        variant.count++;
        variant.confidenceTotal += confidence;
    }

    private String preferredVariant() {
        String best = candidate.text;
        Variant bestStats = candidate.variants.get(best);
        for (Map.Entry<String, Variant> entry : candidate.variants.entrySet()) {
            Variant stats = entry.getValue();
            double mean = stats.confidenceTotal / stats.count;
            double bestMean = bestStats.confidenceTotal / bestStats.count;
            int length = entry.getKey().codePointCount(0, entry.getKey().length());
            int bestLength = best.codePointCount(0, best.length());
            if (stats.count > bestStats.count
                    || (stats.count == bestStats.count && mean > bestMean)
                    || (stats.count == bestStats.count && mean == bestMean && length > bestLength)) {
                best = entry.getKey();
                bestStats = stats;
            }
        }
        return best;
    }

    private boolean isGrowth(String current, String next) {
        String a = SubtitleNormalizer.comparisonKey(current);
        String b = SubtitleNormalizer.comparisonKey(next);
        return b.startsWith(a) && b.codePointCount(0, b.length()) > a.codePointCount(0, a.length());
    }

    private boolean matchesCommitted(String text) {
        return Similarity.areEquivalent(text, lastCommitted.getText(), config.committedSimilarity)
                || Similarity.isPrefixRelation(text, lastCommitted.getText());
    }
}
