package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SubtitleEventManager {
    private final long recentDuplicateMs;
    private final Deque<SubtitleEvent> recent = new ArrayDeque<>();

    public SubtitleEventManager(long recentDuplicateMs) {
        this.recentDuplicateMs = recentDuplicateMs;
    }

    public SubtitleEvent accept(SubtitleEvent event) {
        while (!recent.isEmpty()
                && event.getCommittedAt() - recent.peekFirst().getCommittedAt() > recentDuplicateMs) {
            recent.removeFirst();
        }
        for (SubtitleEvent previous : recent) {
            if (Similarity.areEquivalent(event.getText(), previous.getText(), 0.92)) {
                return null;
            }
        }
        recent.addLast(event);
        return event;
    }

    public void reset() {
        recent.clear();
    }
}
