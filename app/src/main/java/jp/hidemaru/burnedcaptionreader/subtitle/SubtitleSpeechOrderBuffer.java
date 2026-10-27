package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Delay a ready lower caption only while a nearby upper fragment is uncertain.
 * Production still uses 700ms. Research variants 300ms and 0ms are opted into
 * from the debug-only activity intent.
 */
public final class SubtitleSpeechOrderBuffer {
    public static final long MAX_WAIT_MS = 700L;
    public static final long SHORT_WAIT_MS = 300L;
    public static final long NO_WAIT_MS = 0L;

    public static long experimentWaitMs(int requestedMs, boolean debugBuild) {
        if (!debugBuild) return MAX_WAIT_MS;
        return requestedMs == SHORT_WAIT_MS || requestedMs == NO_WAIT_MS
                ? requestedMs : MAX_WAIT_MS;
    }

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
    /**
     * Single-use metadata for a committed caption's release from the buffer.
     * heldMs measures real elapsed time in this buffer (not OCR or TTS).
     * reason is "no_upper", "upper_cleared", or "deadline".
     */
    public static final class Release {
        public final long heldMs;
        public final boolean wasBlockedByUpper;
        public final String reason;
        public Release(long heldMs, boolean wasBlockedByUpper, String reason) {
            this.heldMs = heldMs;
            this.wasBlockedByUpper = wasBlockedByUpper;
            this.reason = reason;
        }
    }
    private static final class Pending {
        final Entry entry;
        final long deadline;
        final long enteredAtMs;
        boolean blockedByUpper;
        Pending(Entry entry, long deadline, long enteredAtMs, boolean blockedByUpper) {
            this.entry = entry; this.deadline = deadline;
            this.enteredAtMs = enteredAtMs; this.blockedByUpper = blockedByUpper;
        }
    }

    private long maxWaitMs;
    private final List<Pending> pending = new ArrayList<>();
    private final List<Release> lastReleases = new ArrayList<>();
    private List<Band> waiting = new ArrayList<>();

    public SubtitleSpeechOrderBuffer() { this(MAX_WAIT_MS); }
    public SubtitleSpeechOrderBuffer(long maxWaitMs) {
        setMaxWaitMs(maxWaitMs);
    }
    /** New entries use the updated wait; entries already pending retain their deadline. */
    public synchronized void setMaxWaitMs(long nextWaitMs) {
        if (nextWaitMs != MAX_WAIT_MS && nextWaitMs != SHORT_WAIT_MS && nextWaitMs != NO_WAIT_MS) {
            throw new IllegalArgumentException("Unsupported order wait experiment: " + nextWaitMs);
        }
        this.maxWaitMs = nextWaitMs;
    }
    public synchronized long getMaxWaitMs() { return maxWaitMs; }

    /** Historical API: logical timestamp is also the observed timestamp. */
    public synchronized List<Entry> offer(long now, List<Entry> committed, List<Band> stabilizing) {
        return offer(now, committed, stabilizing, now);
    }

    /**
     * now is the frame-start timestamp used by the existing deadline logic.
     * observedAtMs is the actual insertion time, after OCR has completed;
     * keeping both prevents changing the established 700ms behaviour.
     */
    public synchronized List<Entry> offer(long now, List<Entry> committed,
            List<Band> stabilizing, long observedAtMs) {
        waiting = new ArrayList<>(stabilizing);
        for (Entry entry : committed) {
            long deadline = now + maxWaitMs;
            long enteredAt = observedAtMs;
            boolean inheritedBlocked = false;
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
                        enteredAt = Math.min(enteredAt, previous.enteredAtMs);
                        inheritedBlocked |= previous.blockedByUpper;
                        pending.remove(i);
                    } else { duplicate = true; break; }
                }
            }
            if (!duplicate) pending.add(new Pending(entry, deadline, enteredAt, inheritedBlocked));
        }
        return drainAt(now, observedAtMs);
    }

    private boolean sameCaptionArea(Entry a, Entry b) {
        float gap = Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom);
        return gap <= .035f;
    }

    public synchronized List<Entry> drain(long now) { return drainAt(now, now); }

    private List<Entry> drainAt(long now, long observedAtMs) {
        List<Entry> ready = new ArrayList<>();
        List<Release> releases = new ArrayList<>();
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
            value.blockedByUpper |= blocked;
            if (!blocked || now >= value.deadline) {
                ready.add(value.entry);
                releases.add(new Release(Math.max(0L, observedAtMs - value.enteredAtMs),
                        value.blockedByUpper, blocked ? "deadline"
                                : value.blockedByUpper ? "upper_cleared" : "no_upper"));
                return true;
            }
            return false;
        });
        // Diagnostic records and spoken rows must use the same top-to-bottom order.
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < ready.size(); i++) indices.add(i);
        indices.sort(Comparator.comparingDouble(i -> ready.get(i).top));
        List<Entry> ordered = new ArrayList<>();
        lastReleases.clear();
        for (int index : indices) {
            ordered.add(ready.get(index));
            lastReleases.add(releases.get(index));
        }
        return ordered;
    }

    /** Called immediately after offer/drain for the same single-threaded UI event. */
    public synchronized List<Release> takeReleases() {
        List<Release> taken = new ArrayList<>(lastReleases);
        lastReleases.clear();
        return taken;
    }
    public synchronized int pendingCount() { return pending.size(); }

    public synchronized long nextDeadline() {
        long deadline = Long.MAX_VALUE;
        for (Pending value : pending) deadline = Math.min(deadline, value.deadline);
        return deadline;
    }

    public synchronized void reset() {
        pending.clear();
        waiting.clear();
        lastReleases.clear();
    }
}
