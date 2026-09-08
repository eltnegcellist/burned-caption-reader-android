package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;

public final class SubtitleEventManager {
    private static final long STRONG_DUPLICATE_WINDOW_MS = 8_000L;
    private static final double NORMAL_DUPLICATE_THRESHOLD = 0.86;
    private static final double SHORT_WINDOW_DUPLICATE_THRESHOLD = 0.80;

    private final long recentDuplicateMs;
    private final int maxEntries;
    private final Deque<SubtitleEvent> recent = new ArrayDeque<>();

    public SubtitleEventManager(long recentDuplicateMs) {
        this(recentDuplicateMs, 64);
    }

    public SubtitleEventManager(long recentDuplicateMs, int maxEntries) {
        this.recentDuplicateMs = recentDuplicateMs;
        this.maxEntries = Math.max(1, maxEntries);
    }

    public synchronized SubtitleEvent accept(SubtitleEvent event) {
        while (!recent.isEmpty()
                && event.getCommittedAt() - recent.peekFirst().getCommittedAt() > recentDuplicateMs) {
            recent.removeFirst();
        }
        String remaining = event.getText();
        for (SubtitleEvent previous : recent) {
            long ageMs = Math.max(0L, event.getCommittedAt() - previous.getCommittedAt());
            String uncovered = ageMs <= STRONG_DUPLICATE_WINDOW_MS
                    ? uncoveredRows(remaining, previous.getText()) : null;
            if (uncovered != null) {
                if (uncovered.isEmpty()) return null;
                remaining = uncovered;
                continue;
            }
            if (isNearDuplicate(remaining, previous.getText(), ageMs)) {
                return null;
            }
        }
        recent.addLast(event);
        while (recent.size() > maxEntries) recent.removeFirst();
        return remaining.equals(event.getText()) ? event : new SubtitleEvent(event.getId(),
                remaining, event.getDetectedAt(), event.getCommittedAt(), event.getConfidence());
    }

    public synchronized void reset() {
        recent.clear();
    }

    /** Recognize row reorder/split variants without discarding a newly recovered row. */
    private String uncoveredRows(String current, String previous) {
        String[] rows = SubtitleNormalizer.normalize(current).split("\n");
        String[] old = SubtitleNormalizer.normalize(previous).split("\n");
        if (old.length < 2 || rows.length > 3 || old.length > 3) return null;
        boolean[] used = new boolean[old.length];
        List<String> uncovered = new ArrayList<>();
        int matches = 0;
        for (String row : rows) {
            int best = -1;
            double similarity = .84;
            for (int i = 0; i < old.length; i++) {
                if (used[i]) continue;
                double score = Similarity.textSimilarity(row, old[i]);
                // A changed amount/date is content, not an OCR punctuation wobble.
                String digits = SubtitleNormalizer.comparisonKey(row).replaceAll("[^0-9]", "");
                String oldDigits = SubtitleNormalizer.comparisonKey(old[i]).replaceAll("[^0-9]", "");
                if (!digits.equals(oldDigits)) continue;
                if (score > similarity) { similarity = score; best = i; }
            }
            if (best >= 0) { used[best] = true; matches++; }
            else uncovered.add(row);
        }
        if (matches >= 2) return String.join("\n", uncovered);
        // Single-row fragments require an exact, nontrivial match.
        if (rows.length == 1 && matches == 1) {
            String key = SubtitleNormalizer.comparisonKey(rows[0]);
            if (key.codePointCount(0, key.length()) >= 6) {
                for (String line : old) {
                    if (key.equals(SubtitleNormalizer.comparisonKey(line))) return "";
                }
            }
        }
        return null;
    }

    private boolean isNearDuplicate(String left, String right, long ageMs) {
        if (Similarity.areEquivalent(left, right, NORMAL_DUPLICATE_THRESHOLD)) return true;
        if (Similarity.isMultilineVariant(left, right, 0.50)
                || Similarity.isMultilineVariant(right, left, 0.50)) {
            return true;
        }

        String a = SubtitleNormalizer.comparisonKey(left);
        String b = SubtitleNormalizer.comparisonKey(right);
        int aLength = a.codePointCount(0, a.length());
        int bLength = b.codePointCount(0, b.length());
        int shortLength = Math.min(aLength, bLength);
        int longLength = Math.max(aLength, bLength);
        if (longLength == 0) return true;

        double lengthRatio = shortLength / (double) longLength;
        if (shortLength >= Math.ceil(longLength * 0.70)
                && Similarity.isPrefixRelation(left, right)) {
            return true;
        }

        // The same burned-in caption can be re-detected with several wrong glyphs
        // after a track reset. Be deliberately more tolerant only for a few seconds;
        // the normal 60 s history keeps the stricter threshold so genuinely similar
        // later captions are not accidentally hidden.
        return ageMs <= STRONG_DUPLICATE_WINDOW_MS
                && lengthRatio >= 0.78
                && Similarity.textSimilarity(left, right) >= SHORT_WINDOW_DUPLICATE_THRESHOLD;
    }
}
