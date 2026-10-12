package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

/**
 * Experimental same-observation visual-line composition.
 *
 * Inspired by Subtitle Edit's AppleVisionTextLayout.Compose (MIT), this Java
 * implementation groups nearby horizontal fragments of the SAME OCR block.
 * No inference, temporal voting or crop coordinate conversion is performed.
 * It is only used in diagnostics until controlled A/B proves it helpful.
 */
public final class OcrVisualLineComposer {
    private static final float MAX_CENTER_Y_IN_SHORTER_HEIGHT = 0.5f;
    private static final float MAX_HORIZONTAL_GAP = 0.065f;

    private OcrVisualLineComposer() {}

    private static final class VisualRow {
        final List<OcrLine> parts = new ArrayList<>();
        final int block;
        final float anchorY;
        final float anchorHeight;
        float left;
        float top;
        float right;
        float bottom;

        VisualRow(OcrLine first) {
            block = first.getBlockIndex();
            anchorY = first.getCenterY();
            anchorHeight = first.getHeight();
            left = first.getLeft();
            top = first.getTop();
            right = first.getRight();
            bottom = first.getBottom();
            parts.add(first);
        }

        boolean accepts(OcrLine next) {
            if (next.getBlockIndex() != block || next.getHeight() <= 0
                    || anchorHeight <= 0) return false;
            if (Math.abs(next.getCenterY() - anchorY)
                    >= MAX_CENTER_Y_IN_SHORTER_HEIGHT * Math.min(anchorHeight, next.getHeight()))
                return false;
            float horizontalGap = Math.max(0f,
                    Math.max(left, next.getLeft()) - Math.min(right, next.getRight()));
            // Separate speaker columns/labels rather than bridging a wide gap.
            return horizontalGap <= Math.max(MAX_HORIZONTAL_GAP,
                    Math.min(anchorHeight, next.getHeight()) * 1.5f);
        }

        void add(OcrLine line) {
            parts.add(line);
            left = Math.min(left, line.getLeft());
            top = Math.min(top, line.getTop());
            right = Math.max(right, line.getRight());
            bottom = Math.max(bottom, line.getBottom());
        }

        OcrLine output() {
            if (parts.size() == 1) return parts.get(0);
            parts.sort(Comparator.comparingDouble(OcrLine::getLeft));
            StringBuilder text = new StringBuilder();
            double weightedConfidence = 0;
            int totalWeight = 0;
            float glyph = 0;
            double modelProbability = 0;
            boolean allHaveProbability = true;
            for (OcrLine line : parts) {
                String next = SubtitleNormalizer.normalize(line.getText());
                if (next.isEmpty()) continue;
                if (text.length() > 0 && needsSpace(text, next)) text.append(' ');
                text.append(next);
                int weight = Math.max(1, next.codePointCount(0, next.length()));
                weightedConfidence += line.getConfidence() * weight;
                totalWeight += weight;
                glyph = Math.max(glyph, line.getGlyphHeight());
                if (line.getModelProbability() < 0) allHaveProbability = false;
                else modelProbability += line.getModelProbability() * weight;
            }
            return new OcrLine(block, text.toString(),
                    weightedConfidence / Math.max(1, totalWeight), left, top, right, bottom,
                    new ArrayList<>(parts), glyph,
                    allHaveProbability ? modelProbability / Math.max(1, totalWeight) : -1);
        }
    }

    private static boolean needsSpace(CharSequence previous, String next) {
        char a = previous.charAt(previous.length() - 1);
        char c = next.charAt(0);
        return ((a >= 'A' && a <= 'Z') || (a >= 'a' && a <= 'z')
                || (a >= '0' && a <= '9'))
                && ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9'));
    }

    public static List<OcrLine> compose(List<OcrLine> lines) {
        List<OcrLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(OcrLine::getTop)
                .thenComparingDouble(OcrLine::getLeft));
        List<VisualRow> rows = new ArrayList<>();
        for (OcrLine line : sorted) {
            VisualRow match = null;
            for (VisualRow row : rows) {
                if (row.accepts(line)) {
                    match = row;
                    break;
                }
            }
            if (match == null) rows.add(new VisualRow(line));
            else match.add(line);
        }
        List<OcrLine> output = new ArrayList<>();
        for (VisualRow row : rows) output.add(row.output());
        output.sort(Comparator.comparingDouble(OcrLine::getTop)
                .thenComparingDouble(OcrLine::getLeft));
        return output;
    }
}
