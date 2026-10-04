package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.List;

/** Robust letter size without treating a sloping line's union box as font size. */
public final class GlyphGeometry {
    private GlyphGeometry() {}

    public static float medianHeight(List<OcrLine> symbols, List<OcrLine> elements, float fallback) {
        List<Float> heights = heights(symbols);
        if (heights.isEmpty()) heights = heights(elements);
        if (heights.isEmpty()) return fallback;
        heights.sort(Float::compare);
        int middle = heights.size() / 2;
        return heights.size() % 2 == 0
                ? (heights.get(middle - 1) + heights.get(middle)) / 2 : heights.get(middle);
    }

    private static List<Float> heights(List<OcrLine> rows) {
        List<Float> result = new ArrayList<>();
        for (OcrLine row : rows) if (Float.isFinite(row.getHeight())
                && row.getHeight() > 0 && row.getWidth() > 0) result.add(row.getHeight());
        return result;
    }
}
