package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SubtitleEventManager {
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
            if (isNearDuplicate(event.getText(), previous.getText())) {
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

    private boolean isNearDuplicate(String left, String right) {
        if (Similarity.areEquivalent(left, right, 0.86)) return true;
        if (left.length() <= right.length()
                && Similarity.isMultilineVariant(left, right, 0.50)) return true;
        String a = SubtitleNormalizer.comparisonKey(left);
        String b = SubtitleNormalizer.comparisonKey(right);
        int shortLength = Math.min(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        int longLength = Math.max(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        return longLength > 0 && shortLength >= Math.ceil(longLength * 0.70)
                && Similarity.isPrefixRelation(left, right);
    }
}
