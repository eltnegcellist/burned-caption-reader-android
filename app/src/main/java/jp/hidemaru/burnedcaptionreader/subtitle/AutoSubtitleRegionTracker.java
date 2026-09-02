package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;

/** Selects a recurring, changing, subtitle-like text lane from broad OCR results. */
public final class AutoSubtitleRegionTracker {
    public static final class Selection {
        private final String text;
        private final double confidence;
        private final float top;
        private final float bottom;
        private final boolean locked;

        Selection(String text, double confidence, float top, float bottom, boolean locked) {
            this.text = text;
            this.confidence = confidence;
            this.top = top;
            this.bottom = bottom;
            this.locked = locked;
        }

        public String getText() { return text; }
        public double getConfidence() { return confidence; }
        public float getTop() { return top; }
        public float getBottom() { return bottom; }
        public boolean isLocked() { return locked; }
    }

    private static final class Candidate {
        final List<OcrLine> lines = new ArrayList<>();
        String text;
        double confidence;
        float left = 1f;
        float top = 1f;
        float right;
        float bottom;
        int lane;
        double visualScore;
        double finalScore;

        float centerX() { return (left + right) / 2f; }
        float centerY() { return (top + bottom) / 2f; }
        float width() { return Math.max(0f, right - left); }
        float height() { return Math.max(0f, bottom - top); }
    }

    private static final class LaneState {
        int lane;
        int observations;
        int transitions;
        String lastText = "";
        long lastSeenAt;
        double score;
    }

    private static final int LANE_COUNT = 24;
    private static final long LOCK_MISSING_MS = 2_800L;
    private static final Pattern ONLY_SYMBOLS_OR_NUMBERS = Pattern.compile(
            "^[\\d\\s:：%％+＋\\-−/／|｜・.,，。!?！？()（）]+$");
    private static final Pattern UI_TERMS = Pattern.compile(
            "(youtube|チャンネル登録|高評価|低評価|共有|保存|コメント|返信|回視聴|再生リスト|全画面|広告)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern JAPANESE_TEXT = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
    private static final Pattern SHORT_TECHNICAL_LABEL = Pattern.compile(
            "^[\\d\\s.,+-]+(?:bar|atm|mm|cm|km|hz|mah|wh|v|w|gb|tb)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Map<Integer, LaneState> lanes = new HashMap<>();
    private Integer lockedLane;
    private long lockedLastSeenAt;

    public synchronized Selection select(long timestamp, OcrResult result) {
        return select(timestamp, result, false);
    }

    public synchronized Selection select(long timestamp, OcrResult result,
                                         boolean portraitVideoViewport) {
        List<Candidate> candidates = buildCandidates(result, portraitVideoViewport);
        if (candidates.isEmpty()) {
            unlockIfStale(timestamp);
            return null;
        }

        for (LaneState lane : lanes.values()) {
            if (timestamp - lane.lastSeenAt > 1_000L) lane.score *= 0.90;
        }

        Candidate lockedCandidate = null;
        for (Candidate candidate : candidates) {
            LaneState lane = updateLane(timestamp, candidate);
            candidate.finalScore = candidate.visualScore
                    + Math.min(3.0, lane.score)
                    + Math.min(2.0, lane.transitions * 0.55);
            if (lockedLane != null && distance(candidate.lane, lockedLane) <= 1) {
                candidate.finalScore += 3.0;
                if (lockedCandidate == null || candidate.finalScore > lockedCandidate.finalScore) {
                    lockedCandidate = candidate;
                }
            }
        }

        if (lockedLane != null) {
            if (lockedCandidate != null) {
                lockedLastSeenAt = timestamp;
                return asSelection(lockedCandidate, true);
            }
            if (timestamp - lockedLastSeenAt <= LOCK_MISSING_MS) return null;
            lockedLane = null;
        }

        Candidate best = candidates.stream()
                .max(Comparator.comparingDouble(value -> value.finalScore))
                .orElse(null);
        if (best == null) return null;
        LaneState lane = nearestLane(best.lane);
        if (lane == null || lane.observations < 2 || best.finalScore < 3.25) return null;
        if (lane.observations >= 3 || lane.transitions >= 1) {
            lockedLane = lane.lane;
            lockedLastSeenAt = timestamp;
        }
        return asSelection(best, lockedLane != null);
    }

    public synchronized void reset() {
        lanes.clear();
        lockedLane = null;
        lockedLastSeenAt = 0L;
    }

    private List<Candidate> buildCandidates(OcrResult result, boolean portraitVideoViewport) {
        Map<Integer, Candidate> grouped = new HashMap<>();
        for (OcrLine line : result.getLines()) {
            String text = SubtitleNormalizer.normalize(line.getText());
            if (text.isEmpty() || line.getConfidence() < 30.0 || line.getHeight() < 0.008f) continue;
            // The portrait crop ends at the bottom of the 16:9 player. Burned-in
            // subtitles can therefore sit at y=0.90..1.00; only discard browser
            // chrome above the player, not the lower edge where captions live.
            if (portraitVideoViewport && line.getCenterY() < 0.18f) continue;
            Candidate candidate = grouped.computeIfAbsent(line.getBlockIndex(), ignored -> new Candidate());
            candidate.lines.add(line);
            candidate.left = Math.min(candidate.left, line.getLeft());
            candidate.top = Math.min(candidate.top, line.getTop());
            candidate.right = Math.max(candidate.right, line.getRight());
            candidate.bottom = Math.max(candidate.bottom, line.getBottom());
        }

        List<Candidate> candidates = new ArrayList<>(grouped.values());
        for (Candidate groupedCandidate : grouped.values()) {
            if (groupedCandidate.lines.size() <= 1) continue;
            for (OcrLine line : groupedCandidate.lines) {
                Candidate singleLine = new Candidate();
                singleLine.lines.add(line);
                singleLine.left = line.getLeft();
                singleLine.top = line.getTop();
                singleLine.right = line.getRight();
                singleLine.bottom = line.getBottom();
                candidates.add(singleLine);
            }
        }

        List<Candidate> output = new ArrayList<>();
        for (Candidate candidate : candidates) {
            candidate.lines.sort(Comparator.comparingDouble(OcrLine::getTop)
                    .thenComparingDouble(OcrLine::getLeft));
            StringBuilder text = new StringBuilder();
            double confidenceTotal = 0.0;
            for (OcrLine line : candidate.lines) {
                String value = SubtitleNormalizer.normalize(line.getText());
                if (value.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                text.append(value);
                confidenceTotal += line.getConfidence();
            }
            candidate.text = SubtitleNormalizer.normalize(text.toString());
            candidate.confidence = confidenceTotal / Math.max(1, candidate.lines.size());
            String key = SubtitleNormalizer.comparisonKey(candidate.text);
            int length = key.codePointCount(0, key.length());
            if (length < 2 || length > 140 || candidate.height() > 0.34f) continue;
            candidate.lane = laneFor(candidate.centerY());
            candidate.visualScore = visualScore(candidate, length, portraitVideoViewport);
            if (candidate.visualScore >= 1.0) output.add(candidate);
        }
        return output;
    }

    private double visualScore(Candidate candidate, int textLength,
                               boolean portraitVideoViewport) {
        double confidence = Math.max(0.0, Math.min(1.0, candidate.confidence / 100.0));
        double centered = 1.0 - Math.min(1.0, Math.abs(candidate.centerX() - 0.5f) / 0.5f);
        double usefulWidth = Math.min(1.0, candidate.width() / 0.38f);
        double compactHeight = 1.0 - Math.min(1.0, candidate.height() / 0.28f);
        float expectedY = portraitVideoViewport ? 0.94f : 0.72f;
        float verticalRange = portraitVideoViewport ? 0.22f : 0.72f;
        double lowerLane = 1.0 - Math.min(1.0,
                Math.abs(candidate.centerY() - expectedY) / verticalRange);
        double usefulLength = Math.min(1.0, textLength / 14.0);
        double score = confidence * 1.25 + centered * 1.05 + usefulWidth * 0.65
                + compactHeight * 0.25
                + lowerLane * (portraitVideoViewport ? 1.35 : 0.50)
                + usefulLength * 0.45;
        String text = candidate.text.toLowerCase(Locale.JAPANESE);
        if (ONLY_SYMBOLS_OR_NUMBERS.matcher(text).matches()) score -= 2.0;
        if (UI_TERMS.matcher(text).find()) score -= 2.2;
        if (portraitVideoViewport && SHORT_TECHNICAL_LABEL.matcher(text).matches()) score -= 1.6;
        if (portraitVideoViewport && candidate.width() < 0.45f
                && !JAPANESE_TEXT.matcher(text).find()) score -= 1.0;
        if (candidate.lines.size() > 3) score -= 0.8;
        if (candidate.centerX() < 0.15f || candidate.centerX() > 0.85f) score -= 0.7;
        return score;
    }

    private LaneState updateLane(long timestamp, Candidate candidate) {
        LaneState state = nearestLane(candidate.lane);
        if (state == null || distance(state.lane, candidate.lane) > 1
                || timestamp - state.lastSeenAt > 8_000L) {
            state = new LaneState();
            state.lane = candidate.lane;
            lanes.put(candidate.lane, state);
        }
        // Timestamp 0 is valid in unit tests and can also occur when a session clock
        // is measured from capture start.  A newly-created lane must record that
        // first observation instead of mistaking the default lastSeenAt value for
        // an already-processed frame.
        if (state.observations > 0 && state.lastSeenAt == timestamp) return state;
        if (state.observations > 0 && timestamp - state.lastSeenAt <= 5_000L) {
            if (Similarity.areEquivalent(state.lastText, candidate.text, 0.90)) {
                state.score += 0.38;
            } else if (Similarity.isPrefixRelation(state.lastText, candidate.text)) {
                state.score += 0.55;
            } else {
                state.transitions++;
                state.score += 1.75;
            }
        } else {
            state.score += 0.28;
        }
        state.observations++;
        state.lastText = candidate.text;
        state.lastSeenAt = timestamp;
        return state;
    }

    private LaneState nearestLane(int lane) {
        LaneState best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (LaneState state : lanes.values()) {
            int current = distance(lane, state.lane);
            if (current < bestDistance) {
                best = state;
                bestDistance = current;
            }
        }
        return best;
    }

    private void unlockIfStale(long timestamp) {
        if (lockedLane != null && timestamp - lockedLastSeenAt > LOCK_MISSING_MS) lockedLane = null;
    }

    private Selection asSelection(Candidate candidate, boolean locked) {
        return new Selection(candidate.text, candidate.confidence,
                candidate.top, candidate.bottom, locked);
    }

    private static int laneFor(float centerY) {
        return Math.max(0, Math.min(LANE_COUNT - 1, Math.round(centerY * (LANE_COUNT - 1))));
    }

    private static int distance(int left, int right) { return Math.abs(left - right); }
}
