package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Keeps subtitle de-duplication at row granularity.
 *
 * A subtitle may be observed as separate rows first and as a multiline block
 * later.  Text-only whole-event de-duplication cannot represent that transition:
 * this ledger reserves and completes individual rows instead.  A reservation is
 * only marked spoken after the TTS layer confirms completion; releasing it
 * on cancellation makes a failed utterance eligible for a later retry.
 */
public final class RowSpeechLedger {
    private static final int MAX_COMPLETED_ROWS = 256;
    public static final class Reservation {
        private final String id;
        private final String text;
        private final List<String> rows;

        private Reservation(String id, String text, List<String> rows) {
            this.id = id;
            this.text = text;
            this.rows = rows;
        }

        public String getId() { return id; }
        public String getText() { return text; }
        public List<String> getRows() { return new ArrayList<>(rows); }
    }

    private static final class Entry {
        final String row;
        final long time;
        Entry(String row, long time) { this.row = row; this.time = time; }
    }

    private static final class Pending {
        final Reservation reservation;
        Pending(Reservation reservation) { this.reservation = reservation; }
    }

    private static final class InFlight {
        final Reservation reservation;
        InFlight(Reservation reservation) { this.reservation = reservation; }
    }

    private final long historyMs;
    private final List<Entry> spoken = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private final List<InFlight> inFlight = new ArrayList<>();

    public RowSpeechLedger(long historyMs) {
        this.historyMs = Math.max(1L, historyMs);
    }

    /**
     * Reserves only rows that are neither spoken nor already reserved.
     * Matching is conservative: numeric and negation changes are always new
     * content, even if the surrounding OCR text is very similar.
     */
    public synchronized Reservation reserve(SubtitleEvent event) {
        return reserve(event, false, false);
    }

    /**
     * Reserves rows for a queue policy that may replace waiting or speaking
     * utterances. When a fuller caption contains rows from an utterance that
     * will be replaced, those rows move into the new reservation instead of
     * disappearing with the old queue item.
     */
    public synchronized Reservation reserve(SubtitleEvent event,
                                            boolean replacePending,
                                            boolean replaceInFlight) {
        if (event == null) return null;
        purge(event.getCommittedAt());
        List<String> incoming = rows(event.getText());
        if (incoming.isEmpty()) return null;

        List<String> allCovered = coveredRows(false, false, incoming);
        if (uncovered(incoming, allCovered).isEmpty()) return null;

        List<String> covered = coveredRows(replacePending, replaceInFlight, incoming);
        List<String> fresh = uncovered(incoming, covered);
        if (fresh.isEmpty()) return null;

        if (replacePending) {
            pending.removeIf(value -> overlaps(value.reservation.rows, incoming));
        }
        if (replaceInFlight) {
            inFlight.removeIf(value -> overlaps(value.reservation.rows, incoming));
        }
        Reservation reservation = new Reservation(
                UUID.randomUUID().toString(), String.join("\n", fresh), fresh);
        pending.add(new Pending(reservation));
        return reservation;
    }

    private List<String> coveredRows(boolean replacePending, boolean replaceInFlight,
                                     List<String> incoming) {
        List<String> covered = new ArrayList<>();
        for (Entry entry : spoken) covered.add(entry.row);
        for (Pending value : pending) {
            if (!replacePending || !overlaps(value.reservation.rows, incoming)) {
                covered.addAll(value.reservation.rows);
            }
        }
        for (InFlight value : inFlight) {
            if (!replaceInFlight || !overlaps(value.reservation.rows, incoming)) {
                covered.addAll(value.reservation.rows);
            }
        }
        return covered;
    }

    private List<String> uncovered(List<String> incoming, List<String> covered) {
        List<String> fresh = new ArrayList<>();
        for (String row : incoming) {
            int match = -1;
            for (int i = 0; i < covered.size(); i++) {
                if (equivalent(row, covered.get(i))) { match = i; break; }
            }
            // One-to-one coverage preserves intentional repeated dialogue rows.
            // Duplicate OCR boxes must be resolved upstream using their geometry.
            if (match >= 0) covered.remove(match);
            else fresh.add(row);
        }
        return fresh;
    }

    private boolean overlaps(List<String> reserved, List<String> incoming) {
        for (String oldRow : reserved) {
            for (String newRow : incoming) {
                if (equivalent(oldRow, newRow)) return true;
            }
        }
        return false;
    }

    /** Moves a reservation to TTS in-flight state without marking it spoken. */
    public synchronized boolean markInFlight(String reservationId) {
        Pending found = removePending(reservationId);
        if (found == null) return false;
        inFlight.add(new InFlight(found.reservation));
        return true;
    }

    /** Completes only after TTS onDone. */
    public synchronized boolean markSpoken(String reservationId, long timestamp) {
        InFlight found = removeInFlight(reservationId);
        if (found == null) return false;
        for (String row : found.reservation.rows) spoken.add(new Entry(row, timestamp));
        purge(timestamp);
        while (spoken.size() > MAX_COMPLETED_ROWS) spoken.remove(0);
        return true;
    }

    /** Releases a cancelled or failed pending/in-flight reservation for retry. */
    public synchronized boolean release(String reservationId) {
        return removePending(reservationId) != null || removeInFlight(reservationId) != null;
    }

    public synchronized void reset() {
        spoken.clear();
        pending.clear();
        inFlight.clear();
    }

    public synchronized int spokenRowCount() { return spoken.size(); }
    public synchronized int pendingReservationCount() { return pending.size(); }
    public synchronized int inFlightReservationCount() { return inFlight.size(); }

    private Pending removePending(String id) {
        if (id == null) return null;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
            Pending value = it.next();
            if (id.equals(value.reservation.id)) { it.remove(); return value; }
        }
        return null;
    }

    private InFlight removeInFlight(String id) {
        if (id == null) return null;
        for (Iterator<InFlight> it = inFlight.iterator(); it.hasNext();) {
            InFlight value = it.next();
            if (id.equals(value.reservation.id)) { it.remove(); return value; }
        }
        return null;
    }

    private void purge(long timestamp) {
        for (Iterator<Entry> it = spoken.iterator(); it.hasNext();) {
            if (timestamp - it.next().time > historyMs) it.remove();
        }
    }

    private boolean equivalent(String left, String right) {
        String a = SubtitleNormalizer.comparisonKey(left);
        String b = SubtitleNormalizer.comparisonKey(right);
        if (a.isEmpty() || b.isEmpty()) return a.equals(b);
        String aDigits = a.replaceAll("[^0-9]", "");
        String bDigits = b.replaceAll("[^0-9]", "");
        if (!aDigits.equals(bDigits)) return false;
        for (String marker : new String[]{"ない", "ません", "禁止", "不可", "不要", "無効"}) {
            if (a.contains(marker) != b.contains(marker)) return false;
        }
        if (a.equals(b)) return true;
        int length = Math.max(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        // OCR wobble is limited to one glyph on a sufficiently long row. Short
        // captions and broad similarity remain distinct content.
        return length >= 8 && Similarity.levenshteinDistance(a, b) <= 1;
    }

    private List<String> rows(String text) {
        List<String> result = new ArrayList<>();
        String normalized = SubtitleNormalizer.normalize(text);
        if (normalized.isEmpty()) return result;
        for (String row : normalized.split("\\n")) {
            if (!SubtitleNormalizer.comparisonKey(row).isEmpty()) result.add(row);
        }
        return result;
    }
}
