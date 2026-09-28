package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Wait only for a visible nearby upper fragment, with a bounded deadline. */
public final class SubtitleSpeechOrderBuffer {
    public static final long MAX_WAIT_MS = 700;
    public static final class Band {
        public final float top;
        public final float bottom;
        public Band(float top, float bottom) { this.top = top; this.bottom = bottom; }
    }
    public static final class Entry {
        public final SubtitleEvent event;
        public final float top;
        public final float bottom;
        public Entry(SubtitleEvent event, float top, float bottom) {
            this.event = event; this.top = top; this.bottom = bottom;
        }
    }
    private static final class Pending {
        final Entry entry;
        final long deadline;
        Pending(Entry entry, long deadline) { this.entry = entry; this.deadline = deadline; }
    }
    private final List<Pending> pending = new ArrayList<>();
    private List<Band> waiting = new ArrayList<>();

    public synchronized List<Entry> offer(long now, List<Entry> committed, List<Band> stabilizing) {
        waiting = new ArrayList<>(stabilizing);
        for (Entry entry : committed) {
            long deadline = now + MAX_WAIT_MS;
            boolean duplicate = false;
            for (int i = pending.size() - 1; i >= 0; i--) {
                Pending previous = pending.get(i);
                if (!sameCaptionArea(previous.entry, entry)) continue;
                String a = previous.entry.event.getText();
                String b = entry.event.getText();
                if (SubtitleNormalizer.comparisonKey(a).equals(SubtitleNormalizer.comparisonKey(b))) {
                    duplicate = true;
                    break;
                }
                if (Similarity.isMultilineVariant(a, b, .50)) {
                    if (b.length() > a.length()) {
                        deadline = Math.min(deadline, previous.deadline);
                        pending.remove(i);
                    } else { duplicate = true; break; }
                }
            }
            if (!duplicate) pending.add(new Pending(entry, deadline));
        }
        return drain(now);
    }

    private boolean sameCaptionArea(Entry a, Entry b) {
        float gap = Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom);
        return gap <= .035f;
    }

    public synchronized List<Entry> drain(long now) {
        List<Entry> ready = new ArrayList<>();
        pending.removeIf(value -> {
            boolean blocked = false;
            for (Band upper : waiting) {
                float gap = value.entry.top - upper.bottom;
                float height = Math.max(upper.bottom - upper.top,
                        value.entry.bottom - value.entry.top);
                if (upper.top < value.entry.top && gap >= -height * .2f
                        && gap <= Math.max(.035f, height * 1.5f)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked || now >= value.deadline) {
                ready.add(value.entry);
                return true;
            }
            return false;
        });
        ready.sort(Comparator.comparingDouble(entry -> entry.top));
        return ready;
    }

    public synchronized long nextDeadline() {
        long deadline = Long.MAX_VALUE;
        for (Pending value : pending) deadline = Math.min(deadline, value.deadline);
        return deadline;
    }

    public synchronized void reset() { pending.clear(); waiting.clear(); }
}
