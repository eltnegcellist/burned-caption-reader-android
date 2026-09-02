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
        int lane;
        int observations;
        int stableObservations;
        int transitions;
        int distinctTexts;
        String lastText = "";
        long firstSeenAt;
        long textSince;
        long lastSeenAt;
        double visualTotal;
        Candidate current;
        boolean seenThisFrame;

        double meanVisualScore() {
            return observations == 0 ? 0.0 : visualTotal / observations;
        }
    }

    private static final int LANE_COUNT = 24;
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
    private LaneState lockedLane;

    public synchronized Selection select(long timestamp, OcrResult result) {
        return select(timestamp, result, false);
    }

    public synchronized Selection select(long timestamp, OcrResult result,
                                         boolean portraitVideoViewport) {
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

        if (lockedLane != null) {
            if (lockedLane.seenThisFrame && lockedLane.current != null) {
                return asSelection(lockedLane.current, true);
            }
            LaneState replacement = bestConfirmedLane(timestamp);
            if (replacement != null && timestamp - lockedLane.lastSeenAt > 1_200L) {
                lockedLane = replacement;
                return asSelection(replacement.current, true);
            }
            if (timestamp - lockedLane.lastSeenAt <= LOCK_MISSING_MS) return null;
            lockedLane = null;
        }

        LaneState confirmed = bestConfirmedLane(timestamp);
        if (confirmed != null) {
            lockedLane = confirmed;
            return asSelection(confirmed.current, true);
        }

        Candidate provisional = bestProvisionalCandidate(timestamp);
        return provisional == null ? null : asSelection(provisional, false);
    }

    public synchronized void reset() {
        lanes.clear();
        lockedLane = null;
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
        int bestDistance = Integer.MAX_VALUE;
        for (LaneState state : lanes) {
            int current = distance(candidate.lane, state.lane);
            if (current < bestDistance) {
                best = state;
                bestDistance = current;
            }
        }
        if (best != null && bestDistance <= 1) {
            return best.seenThisFrame ? null : best;
        }
        LaneState created = new LaneState();
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
        } else {
            double similarity = Similarity.textSimilarity(state.lastText, candidate.text);
            if (isEquivalentOcr(state.lastText, candidate.text, similarity)) {
                state.stableObservations++;
            } else if (Similarity.isPrefixRelation(state.lastText, candidate.text)) {
                state.stableObservations++;
                state.textSince = timestamp;
            } else if (isRealTransition(state.lastText, candidate.text, similarity)) {
                state.transitions++;
                state.distinctTexts++;
                state.stableObservations = 1;
                state.textSince = timestamp;
            } else {
                // Ambiguous OCR movement is neither proof of a new subtitle nor a
                // reason to discard the lane. It must repeat before it can matter.
                state.stableObservations++;
            }
        }
        state.observations++;
        state.lastText = candidate.text;
        state.lastSeenAt = timestamp;
        state.visualTotal += candidate.visualScore;
        state.current = candidate;
        state.seenThisFrame = true;
    }

    private LaneState bestConfirmedLane(long timestamp) {
        LaneState best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LaneState lane : lanes) {
            if (!lane.seenThisFrame || lane.current == null || lane.transitions < 1) continue;
            boolean languageOrShapeEvidence = lane.current.japanese
                    || isStrongCaptionShape(lane.current) || lane.transitions >= 2;
            if (!languageOrShapeEvidence || lane.meanVisualScore() < 1.20) continue;
            double score = laneScore(timestamp, lane, lane.current);
            if (score > bestScore) {
                best = lane;
                bestScore = score;
            }
        }
        return best;
    }

    private Candidate bestProvisionalCandidate(long timestamp) {
        Candidate best = null;
        Candidate second = null;
        for (LaneState lane : lanes) {
            Candidate candidate = lane.current;
            if (!lane.seenThisFrame || candidate == null || lane.transitions > 0) continue;
            if (lane.observations < 2 || lane.stableObservations < 2) continue;
            if (timestamp - lane.textSince < 300L
                    || timestamp - lane.firstSeenAt > PROVISIONAL_MAX_STATIC_MS) continue;
            if (!isStrongCaptionShape(candidate)) continue;
            candidate.finalScore = laneScore(timestamp, lane, candidate);
            if (best == null || candidate.finalScore > best.finalScore) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.finalScore > second.finalScore) {
                second = candidate;
            }
        }
        if (best == null) return null;
        if (second != null && best.finalScore - second.finalScore < 0.35
                && best.finalScore < 3.00) return null;
        return best;
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
        lanes.removeIf(lane -> lane != lockedLane
                && timestamp - lane.lastSeenAt > LANE_EXPIRES_MS);
        if (lockedLane != null && timestamp - lockedLane.lastSeenAt > LANE_EXPIRES_MS) {
            lanes.remove(lockedLane);
            lockedLane = null;
        }
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
