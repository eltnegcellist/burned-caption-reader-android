package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pixel evidence for a missed horizontal caption in a dark lower strip. */
public final class CaptionStripRecovery {
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private CaptionStripRecovery() {}
    public static final class Region {
        public final float top, bottom;
        Region(float top, float bottom) { this.top = top; this.bottom = bottom; }
    }

    public static Region find(int[] pixels, int width, int height) {
        if (width < 80 || height < 80 || pixels.length != width * height) return null;
        int stride = Math.max(1, width / 640), count = (width + stride - 1) / stride;
        int first = (int) (height * .88f), start = -1, last = -1;
        int gap = Math.max(1, Math.round(height * .012f));
        Region best = null;
        for (int y = first; y <= height; y++) {
            int dark = 0, bright = 0, bins = 0;
            if (y < height) for (int x = 0; x < width; x += stride) {
                int c = pixels[y * width + x];
                int lo = Math.min((c >> 16) & 255, Math.min((c >> 8) & 255, c & 255));
                int hi = Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255));
                if (hi <= 48) dark++;
                if (lo >= 96 && hi - lo <= 55) { bright++; bins |= 1 << Math.min(15, x * 16 / width); }
            }
            boolean ink = dark >= count * .40 && bright >= 3 && bright <= count * .50
                    && Integer.bitCount(bins) >= 4;
            if (ink) { if (start < 0) start = y; last = y; }
            if (start >= 0 && (y == height || y - last > gap)) {
                int glyphHeight = last - start + 1;
                int pad = Math.max(2, Math.round(height * .012f));
                if (glyphHeight >= height * .008f && glyphHeight <= height * .085f
                        && darkMargin(pixels, width, height, start - pad, start, stride)
                        && darkMargin(pixels, width, height, last + 1, last + pad + 1, stride))
                    best = new Region(start / (float) height, (last + 1) / (float) height);
                start = -1; last = -1;
            }
        }
        return best;
    }

    private static boolean darkMargin(int[] pixels, int width, int height, int first, int end, int stride) {
        if (first < 0 || end > height || first >= end) return false;
        int dark = 0, total = 0;
        for (int y = first; y < end; y++) for (int x = 0; x < width; x += stride) {
            int c = pixels[y * width + x]; total++;
            if (((c >> 16) & 255) <= 48 && ((c >> 8) & 255) <= 48 && (c & 255) <= 48) dark++;
        }
        return dark >= total * .96;
    }

    public static boolean needsRecovery(Region region, OcrResult raw) {
        if (region == null) return false;
        for (OcrLine row : raw.getLines()) if (row.getConfidence() >= 20
                && JAPANESE.matcher(row.getText()).find()
                && row.getCenterY() >= region.top - .015f && row.getCenterY() <= region.bottom + .015f)
            return false;
        return true;
    }

    /** Keep coarse rows intact; accept only localized Japanese rescue rows. */
    public static OcrResult merge(OcrResult raw, OcrResult crop, SubtitleCropPlan plan, int frameHeight) {
        List<OcrLine> lines = new ArrayList<>(raw.getLines());
        int nextBlock = 0;
        for (OcrLine row : lines) nextBlock = Math.max(nextBlock, row.getBlockIndex() + 1);
        for (OcrLine row : crop.getLines()) {
            OcrLine mapped = map(row, plan, frameHeight, nextBlock + row.getBlockIndex());
            if (row.getConfidence() < 45 || !JAPANESE.matcher(row.getText()).find()
                    || mapped.getWidth() < .20f || mapped.getHeight() < .008f || mapped.getHeight() > .12f
                    || mapped.getCenterY() < .78f) continue;
            boolean duplicate = false;
            for (OcrLine old : raw.getLines()) if (JAPANESE.matcher(old.getText()).find()
                    && Math.min(old.getBottom(), mapped.getBottom()) > Math.max(old.getTop(), mapped.getTop()))
                duplicate = true;
            if (!duplicate) lines.add(mapped);
        }
        StringBuilder text = new StringBuilder();
        double confidence = 0;
        for (OcrLine row : lines) { if (text.length() > 0) text.append('\n'); text.append(row.getText()); confidence += row.getConfidence(); }
        return new OcrResult(text.toString(), lines.isEmpty() ? raw.getConfidence() : confidence / lines.size(), lines);
    }

    private static OcrLine map(OcrLine row, SubtitleCropPlan plan, int frameHeight, int block) {
        List<OcrLine> parts = new ArrayList<>();
        for (OcrLine part : row.getSeparatedParts()) parts.add(map(part, plan, frameHeight, block));
        float scale = plan.height / (float) frameHeight;
        return new OcrLine(block, row.getText(), row.getConfidence(), row.getLeft(),
                plan.top / (float) frameHeight + row.getTop() * scale, row.getRight(),
                plan.top / (float) frameHeight + row.getBottom() * scale, parts, row.getGlyphHeight() * scale);
    }
}
