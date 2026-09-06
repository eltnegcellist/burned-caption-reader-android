package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayDeque;
import java.util.Deque;

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

    public SubtitleEvent accept(SubtitleEvent event) {
        while (!recent.isEmpty()
                && event.getCommittedAt() - recent.peekFirst().getCommittedAt() > recentDuplicateMs) {
            recent.removeFirst();
        }
        for (SubtitleEvent previous : recent) {
            long ageMs = Math.max(0L, event.getCommittedAt() - previous.getCommittedAt());
            if (isNearDuplicate(event.getText(), previous.getText(), ageMs)) {
                return null;
            }
        }
        recent.addLast(event);
        while (recent.size() > maxEntries) recent.removeFirst();
        return event;
    }

    public void reset() {
        recent.clear();
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
