package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Conservative mapping for one locked lane only. Unlike legacy strip rescue,
 * this accepts any vertical location: upper-center or lower subtitles alike.
 * No result bypasses the normal subtitle candidate tracker and TTS ledger.
 */
public final class LockedRegionRescue {
    private static final Pattern JAPANESE =
            Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private LockedRegionRescue() {}

    public static OcrResult merge(OcrResult raw, OcrResult crop, SubtitleCropPlan plan,
                                  int frameHeight, float trustedTop, float trustedBottom) {
        if (!raw.getLines().isEmpty() || frameHeight <= 0
                || !(trustedBottom > trustedTop)) return raw;
        List<OcrLine> added = new ArrayList<>();
        for (OcrLine row : crop.getLines()) {
            float scale = plan.height / (float) frameHeight;
            float top = plan.top / (float) frameHeight + row.getTop() * scale;
            float bottom = plan.top / (float) frameHeight + row.getBottom() * scale;
            float center = (top + bottom) * .5f;
            if (row.getConfidence() < 50 || !JAPANESE.matcher(row.getText()).find()
                    || center < trustedTop - .02f || center > trustedBottom + .02f
                    || row.getWidth() < .18f || bottom - top < .008f
                    || bottom - top > .12f) continue;
            added.add(new OcrLine(1000 + added.size(), row.getText(), row.getConfidence(),
                    row.getLeft(), top, row.getRight(), bottom,
                    java.util.Collections.emptyList(), row.getGlyphHeight() * scale,
                    row.getModelProbability()));
            if (added.size() == 2) break;
        }
        if (added.isEmpty()) return raw;
        StringBuilder text = new StringBuilder();
        double confidence = 0;
        for (OcrLine row : added) {
            if (text.length() > 0) text.append('\n');
            text.append(row.getText());
            confidence += row.getConfidence();
        }
        return new OcrResult(text.toString(), confidence / added.size(), added,
                "v6_region_rescue_debug", -1);
    }
}
