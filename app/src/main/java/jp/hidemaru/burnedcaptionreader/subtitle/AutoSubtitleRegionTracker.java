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

/** Selects up to two recurring, changing subtitle-like text lanes from broad OCR results. */
public final class AutoSubtitleRegionTracker {
    public static final class Selection {
        private final int trackId;
        private final String text;
        private final double confidence;
        private final float top;
        private final float bottom;
        private final boolean locked;

        Selection(int trackId, String text, double confidence, float top, float bottom,
                  boolean locked) {
            this.trackId = trackId;
            this.text = text;
            this.confidence = confidence;
            this.top = top;
            this.bottom = bottom;
            this.locked = locked;
        }

        public int getTrackId() { return trackId; }
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
        int textLength;
        boolean japanese;
        double visualScore;
        double finalScore;

        float centerX() { return (left + right) / 2f; }
        float centerY() { return (top + bottom) / 2f; }
        float width() { return Math.max(0f, right - left); }
        float height() { return Math.max(0f, bottom - top); }
    }

    private static final class LaneState {
        int id;
        int lane;
        int observations;
        int stableObservations;
        int transitions;
        int distinctTexts;
        int geometrySamples;
        String lastText = "";
        String pendingTransitionText = "";
        int pendingTransitionObservations;
        long firstSeenAt;
        long textSince;
        long lastSeenAt;
        double visualTotal;
        float meanLeft;
        float meanCenterX;
        float meanRight;
        float meanBottom;
        float meanHeight;
        Candidate current;
        boolean seenThisFrame;

        double meanVisualScore() {
            return observations == 0 ? 0.0 : visualTotal / observations;
        }
    }

    private static final int LANE_COUNT = 24;
    private static final int MAX_ACTIVE_LANES = 2;
    private static final long LOCK_MISSING_MS = 2_800L;
    private static final long LANE_EXPIRES_MS = 8_000L;
    private static final long PROVISIONAL_MAX_STATIC_MS = 2_500L;
    private static final Pattern ONLY_SYMBOLS_OR_NUMBERS = Pattern.compile(
            "^[\\d\\s:：%％+＋\\-−/／|｜・.,，。!?！？()（）]+$");
    private static final Pattern UI_TERMS = Pattern.compile(
            "(youtube|チャンネル登録|高評価|低評価|共有|保存|コメント|返信|回視聴|再生リスト|全画面|広告)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern JAPANESE_TEXT = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
    private static final Pattern SHORT_TECHNICAL_LABEL = Pattern.compile(
            "^[\\d\\s.,+-]+(?:bar|atm|mm|cm|km|hz|mah|wh|v|w|gb|tb)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final List<LaneState> lanes = new ArrayList<>();
    private final List<LaneState> lockedLanes = new ArrayList<>();
    private int nextTrackId = 1;

    public synchronized Selection select(long timestamp, OcrResult result) {
        return select(timestamp, result, false);
    }

    public synchronized Selection select(long timestamp, OcrResult result,
                                         boolean portraitVideoViewport) {
        return select(timestamp, result, portraitVideoViewport, false);
    }

    /**
     * Selects the most likely subtitle. A scene cut invalidates transition evidence:
     * unrelated object labels before and after a cut must not teach a subtitle lane.
     */
    public synchronized Selection select(long timestamp, OcrResult result,
                                         boolean portraitVideoViewport,
                                         boolean sceneChanged) {
        List<Selection> selections = selectAll(timestamp, result,
                portraitVideoViewport, sceneChanged);
        return selections.isEmpty() ? null : selections.get(0);
    }

    public synchronized List<Selection> selectAll(long timestamp, OcrResult result) {
        return selectAll(timestamp, result, false, false);
    }

    public synchronized List<Selection> selectAll(long timestamp, OcrResult result,
                                                  boolean portraitVideoViewport) {
        return selectAll(timestamp, result, portraitVideoViewport, false);
    }

    /**
     * Selects up to two independent screen-anchored subtitle bands. A scene cut
     * invalidates transition evidence so unrelated object labels are not joined.
     */
    public synchronized List<Selection> selectAll(long timestamp, OcrResult result,
                                                  boolean portraitVideoViewport,
                                                  boolean sceneChanged) {
        if (sceneChanged) {
            lanes.clear();
            lockedLanes.clear();
        }
        List<Candidate> candidates = buildCandidates(result, portraitVideoViewport);
        expireOldLanes(timestamp);
        for (LaneState lane : lanes) {
            lane.seenThisFrame = false;
            lane.current = null;
        }

        candidates.sort(Comparator.comparingDouble((Candidate value) -> value.visualScore).reversed());
        for (Candidate candidate : candidates) {
            LaneState lane = assignLane(timestamp, candidate);
            if (lane == null) continue;
            updateLane(timestamp, lane, candidate);
            candidate.finalScore = laneScore(timestamp, lane, candidate);
        }

        lockedLanes.removeIf(lane -> timestamp - lane.lastSeenAt > LOCK_MISSING_MS);

        List<LaneState> confirmed = confirmedLanes(timestamp);
        confirmed.sort(Comparator.comparingDouble(
                (LaneState lane) -> laneScore(timestamp, lane, lane.current)).reversed());
        for (LaneState lane : confirmed) {
            if (lockedLanes.size() >= MAX_ACTIVE_LANES) break;
            if (!lockedLanes.contains(lane) && isDistinctFrom(lane, lockedLanes)) {
                lockedLanes.add(lane);
            }
        }

        List<LaneState> selected = new ArrayList<>();
        for (LaneState lane : lockedLanes) {
            if (lane.seenThisFrame && lane.current != null && isDistinctFrom(lane, selected)) {
                selected.add(lane);
            }
        }

        for (LaneState lane : provisionalLanes(timestamp)) {
            if (selected.size() >= MAX_ACTIVE_LANES) break;
            if (!lockedLanes.contains(lane) && isDistinctFrom(lane, selected)) {
                selected.add(lane);
            }
        }

        selected.sort(Comparator.comparingDouble(lane -> lane.current.top));
        List<Selection> output = new ArrayList<>();
        for (LaneState lane : selected) {
            output.add(asSelection(lane, lockedLanes.contains(lane)));
        }
        return output;
    }

    public synchronized void reset() {
        lanes.clear();
        lockedLanes.clear();
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
            candidate.textLength = length;
            candidate.japanese = JAPANESE_TEXT.matcher(candidate.text).find();
            candidate.lane = laneFor(candidate.centerY());
            candidate.visualScore = visualScore(candidate, length);
            if (candidate.visualScore >= 0.50) output.add(candidate);
        }
        return output;
    }

    private double visualScore(Candidate candidate, int textLength) {
        double confidence = Math.max(0.0, Math.min(1.0, candidate.confidence / 100.0));
        double centered = 1.0 - Math.min(1.0, Math.abs(candidate.centerX() - 0.5f) / 0.5f);
        double usefulWidth = Math.min(1.0, candidate.width() / 0.55f);
        double compactHeight = 1.0 - Math.min(1.0, candidate.height() / 0.28f);
        double usefulLength = Math.min(1.0, textLength / 14.0);
        double weakLowerPrior = Math.max(0.0, candidate.centerY() - 0.55f) / 0.45f;
        double score = confidence * 0.90 + centered * 0.45 + usefulWidth * 0.75
                + compactHeight * 0.20 + usefulLength * 0.45
                + weakLowerPrior * 0.15;
        String text = candidate.text.toLowerCase(Locale.JAPANESE);
        if (candidate.japanese) score += 0.35;
        if (ONLY_SYMBOLS_OR_NUMBERS.matcher(text).matches()) score -= 2.20;
        if (UI_TERMS.matcher(text).find()) score -= 3.00;
        if (SHORT_TECHNICAL_LABEL.matcher(text).matches()) score -= 2.00;
        if (candidate.width() < 0.45f && !candidate.japanese) score -= 1.10;
        if (candidate.lines.size() > 3) score -= 0.60;
        if (candidate.centerX() < 0.15f || candidate.centerX() > 0.85f) score -= 0.40;
        return score;
    }

    private LaneState assignLane(long timestamp, Candidate candidate) {
        LaneState best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LaneState state : lanes) {
            if (state.seenThisFrame || !isGeometryCompatible(state, candidate)) continue;
            double current = geometryDistance(state, candidate);
            if (current < bestDistance) {
                best = state;
                bestDistance = current;
            }
        }
        if (best != null) return best;
        LaneState created = new LaneState();
        created.id = nextTrackId++;
        created.lane = candidate.lane;
        created.firstSeenAt = timestamp;
        created.textSince = timestamp;
        lanes.add(created);
        return created;
    }

    private void updateLane(long timestamp, LaneState state, Candidate candidate) {
        if (state.observations == 0) {
            state.distinctTexts = 1;
            state.stableObservations = 1;
            clearPendingTransition(state);
        } else {
            double similarity = Similarity.textSimilarity(state.lastText, candidate.text);
            if (isEquivalentOcr(state.lastText, candidate.text, similarity)) {
                state.stableObservations++;
                clearPendingTransition(state);
            } else if (Similarity.isPrefixRelation(state.lastText, candidate.text)) {
                state.stableObservations++;
                state.textSince = timestamp;
                clearPendingTransition(state);
            } else if (isRealTransition(state.lastText, candidate.text, similarity)) {
                observePendingTransition(timestamp, state, candidate);
            } else {
                // Ambiguous OCR movement is neither proof of a new subtitle nor a
                // reason to discard the lane. It must repeat before it can matter.
                state.stableObservations++;
                clearPendingTransition(state);
            }
        }
        state.observations++;
        // Keep the previous accepted text while a possible replacement is being
        // verified. Otherwise the second observation would look "unchanged" and
        // a one-frame object/OCR change could never be distinguished correctly.
        if (state.pendingTransitionObservations == 0) state.lastText = candidate.text;
        state.lastSeenAt = timestamp;
        state.visualTotal += candidate.visualScore;
        state.current = candidate;
        state.seenThisFrame = true;
        updateGeometry(state, candidate);
    }

    private void observePendingTransition(long timestamp, LaneState state, Candidate candidate) {
        double pendingSimilarity = state.pendingTransitionText.isEmpty()
                ? 0.0
                : Similarity.textSimilarity(state.pendingTransitionText, candidate.text);
        if (!state.pendingTransitionText.isEmpty()
                && isEquivalentOcr(state.pendingTransitionText, candidate.text, pendingSimilarity)) {
            state.pendingTransitionObservations++;
        } else {
            state.pendingTransitionText = candidate.text;
            state.pendingTransitionObservations = 1;
        }
        state.stableObservations = 1;
        state.textSince = timestamp;
        if (state.pendingTransitionObservations < 2) return;

        state.transitions++;
        state.distinctTexts++;
        state.lastText = candidate.text;
        clearPendingTransition(state);
    }

    private void clearPendingTransition(LaneState state) {
        state.pendingTransitionText = "";
        state.pendingTransitionObservations = 0;
    }

    private boolean isGeometryCompatible(LaneState state, Candidate candidate) {
        if (state.geometrySamples == 0) return distance(candidate.lane, state.lane) <= 1;
        float baselineTolerance = Math.max(0.030f, state.meanHeight * 0.55f);
        float heightTolerance = Math.max(0.018f, state.meanHeight * 0.42f);
        if (Math.abs(candidate.bottom - state.meanBottom) > baselineTolerance) return false;
        if (Math.abs(candidate.height() - state.meanHeight) > heightTolerance) return false;

        // Caption width changes with sentence length. Match whichever horizontal
        // anchor remains fixed: left-aligned, centered, or right-aligned.
        float anchorShift = Math.min(Math.abs(candidate.left - state.meanLeft),
                Math.min(Math.abs(candidate.centerX() - state.meanCenterX),
                        Math.abs(candidate.right - state.meanRight)));
        return anchorShift <= 0.050f;
    }

    private double geometryDistance(LaneState state, Candidate candidate) {
        if (state.geometrySamples == 0) return distance(candidate.lane, state.lane);
        float anchorShift = Math.min(Math.abs(candidate.left - state.meanLeft),
                Math.min(Math.abs(candidate.centerX() - state.meanCenterX),
                        Math.abs(candidate.right - state.meanRight)));
        float heightScale = Math.max(0.02f, state.meanHeight);
        return Math.abs(candidate.bottom - state.meanBottom) / heightScale
                + Math.abs(candidate.height() - state.meanHeight) / heightScale
                + anchorShift / 0.05f;
    }

    private void updateGeometry(LaneState state, Candidate candidate) {
        // A bounded running mean preserves the screen anchor instead of following
        // a slowly moving product label indefinitely.
        int previousWeight = Math.min(7, state.geometrySamples);
        int totalWeight = previousWeight + 1;
        state.meanLeft = weightedMean(state.meanLeft, candidate.left, previousWeight, totalWeight);
        state.meanCenterX = weightedMean(state.meanCenterX, candidate.centerX(), previousWeight, totalWeight);
        state.meanRight = weightedMean(state.meanRight, candidate.right, previousWeight, totalWeight);
        state.meanBottom = weightedMean(state.meanBottom, candidate.bottom, previousWeight, totalWeight);
        state.meanHeight = weightedMean(state.meanHeight, candidate.height(), previousWeight, totalWeight);
        state.geometrySamples++;
    }

    private float weightedMean(float previous, float current, int previousWeight, int totalWeight) {
        return (previous * previousWeight + current) / totalWeight;
    }

    private List<LaneState> confirmedLanes(long timestamp) {
        List<LaneState> confirmed = new ArrayList<>();
        for (LaneState lane : lanes) {
            if (!lane.seenThisFrame || lane.current == null || lane.transitions < 1) continue;
            boolean languageOrShapeEvidence = lane.current.japanese
                    || isStrongCaptionShape(lane.current) || lane.transitions >= 2;
            if (!languageOrShapeEvidence || lane.meanVisualScore() < 1.20) continue;
            confirmed.add(lane);
        }
        return confirmed;
    }

    private List<LaneState> provisionalLanes(long timestamp) {
        List<LaneState> provisional = new ArrayList<>();
        for (LaneState lane : lanes) {
            Candidate candidate = lane.current;
            if (!lane.seenThisFrame || candidate == null || lane.transitions > 0) continue;
            if (lane.observations < 2 || lane.stableObservations < 2) continue;
            if (timestamp - lane.textSince < 300L
                    || timestamp - lane.firstSeenAt > PROVISIONAL_MAX_STATIC_MS) continue;
            if (!isStrongCaptionShape(candidate)) continue;
            candidate.finalScore = laneScore(timestamp, lane, candidate);
            provisional.add(lane);
        }
        provisional.sort(Comparator.comparingDouble(
                (LaneState lane) -> lane.current.finalScore).reversed());
        return provisional;
    }

    private boolean isDistinctFrom(LaneState candidate, List<LaneState> selected) {
        if (candidate.current == null) return false;
        for (LaneState existing : selected) {
            if (existing.current != null && isDuplicateCandidate(candidate.current, existing.current)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDuplicateCandidate(Candidate left, Candidate right) {
        float verticalOverlap = Math.max(0f,
                Math.min(left.bottom, right.bottom) - Math.max(left.top, right.top));
        float horizontalOverlap = Math.max(0f,
                Math.min(left.right, right.right) - Math.max(left.left, right.left));
        float verticalRatio = verticalOverlap / Math.max(0.001f,
                Math.min(left.height(), right.height()));
        float horizontalRatio = horizontalOverlap / Math.max(0.001f,
                Math.min(left.width(), right.width()));
        if (verticalRatio < 0.55f || horizontalRatio < 0.55f) return false;

        String a = SubtitleNormalizer.comparisonKey(left.text);
        String b = SubtitleNormalizer.comparisonKey(right.text);
        return a.contains(b) || b.contains(a)
                || Similarity.areEquivalent(left.text, right.text, 0.84);
    }

    private boolean isStrongCaptionShape(Candidate candidate) {
        if (candidate.visualScore < 2.05) return false;
        if (candidate.japanese) {
            return candidate.width() >= 0.35f || candidate.textLength >= 9
                    || candidate.lines.size() >= 2;
        }
        return candidate.width() >= 0.60f && candidate.textLength >= 12;
    }

    private double laneScore(long timestamp, LaneState lane, Candidate candidate) {
        double transitionEvidence = Math.min(6.0, lane.transitions * 2.40);
        double diversityEvidence = Math.min(1.20, Math.max(0, lane.distinctTexts - 1) * 0.40);
        double staticPenalty = lane.transitions == 0 && timestamp - lane.firstSeenAt > 2_000L
                ? Math.min(2.0, (timestamp - lane.firstSeenAt - 2_000L) / 2_000.0)
                : 0.0;
        return candidate.visualScore + transitionEvidence + diversityEvidence - staticPenalty;
    }

    private boolean isEquivalentOcr(String previous, String current, double similarity) {
        int longest = Math.max(codePointLength(previous), codePointLength(current));
        double threshold = longest <= 6 ? 0.66 : longest <= 12 ? 0.76 : 0.84;
        return similarity >= threshold;
    }

    private boolean isRealTransition(String previous, String current, double similarity) {
        int longest = Math.max(codePointLength(previous), codePointLength(current));
        double threshold = longest <= 6 ? 0.40 : longest <= 12 ? 0.58 : 0.72;
        return similarity < threshold;
    }

    private int codePointLength(String value) {
        String key = SubtitleNormalizer.comparisonKey(value);
        return key.codePointCount(0, key.length());
    }

    private void expireOldLanes(long timestamp) {
        lockedLanes.removeIf(lane -> timestamp - lane.lastSeenAt > LANE_EXPIRES_MS);
        lanes.removeIf(lane -> timestamp - lane.lastSeenAt > LANE_EXPIRES_MS);
    }

    private Selection asSelection(LaneState lane, boolean locked) {
        Candidate candidate = lane.current;
        return new Selection(lane.id, candidate.text, candidate.confidence,
                candidate.top, candidate.bottom, locked);
    }

    private static int laneFor(float centerY) {
        return Math.max(0, Math.min(LANE_COUNT - 1, Math.round(centerY * (LANE_COUNT - 1))));
    }

    private static int distance(int left, int right) { return Math.abs(left - right); }
}
