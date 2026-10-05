package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleNormalizer;

/** Additional geometry evidence; never rewrites the recognizer's line text. */
public final class OcrLineSegmenter {
    private OcrLineSegmenter() {}

    public static List<OcrLine> separatedParts(String original, List<OcrLine> symbols,
                                               float imageAspectRatio) {
        if (symbols.size() < 2 || !Float.isFinite(imageAspectRatio) || imageAspectRatio <= 0)
            return Collections.emptyList();
        List<OcrLine> ordered = new ArrayList<>(symbols);
        ordered.sort(Comparator.comparingDouble(OcrLine::getLeft));
        List<List<OcrLine>> groups = new ArrayList<>();
        List<OcrLine> group = new ArrayList<>();
        groups.add(group);
        StringBuilder complete = new StringBuilder();
        OcrLine previous = null;
        for (OcrLine symbol : ordered) {
            if (symbol.getWidth() <= 0 || symbol.getHeight() <= 0 || symbol.getText().isEmpty())
                return Collections.emptyList();
            if (previous != null) {
                float height = Math.max(previous.getHeight(), symbol.getHeight());
                float gap = symbol.getLeft() - previous.getRight();
                if (gap > height * .60f / imageAspectRatio) {
                    float overlap = Math.min(previous.getBottom(), symbol.getBottom())
                            - Math.max(previous.getTop(), symbol.getTop());
                    if (overlap < Math.min(previous.getHeight(), symbol.getHeight()) * .40f)
                        return Collections.emptyList();
                    group = new ArrayList<>();
                    groups.add(group);
                }
            }
            group.add(symbol);
            complete.append(symbol.getText());
            previous = symbol;
        }
        // Missing symbols, vertical text, or changed reading order are not gap evidence.
        if (groups.size() < 2 || !SubtitleNormalizer.comparisonKey(original)
                .equals(SubtitleNormalizer.comparisonKey(complete.toString()))) return Collections.emptyList();
        List<OcrLine> parts = new ArrayList<>();
        for (List<OcrLine> values : groups) {
            StringBuilder text = new StringBuilder();
            float left = 1, top = 1, right = 0, bottom = 0;
            double confidence = 0;
            for (OcrLine value : values) {
                text.append(value.getText()); confidence += value.getConfidence();
                left = Math.min(left, value.getLeft()); top = Math.min(top, value.getTop());
                right = Math.max(right, value.getRight()); bottom = Math.max(bottom, value.getBottom());
            }
            parts.add(new OcrLine(values.get(0).getBlockIndex(), text.toString(),
                    confidence / values.size(), left, top, right, bottom,
                    Collections.emptyList(), GlyphGeometry.medianHeight(values,
                            Collections.emptyList(), bottom - top)));
        }
        return parts;
    }
}
