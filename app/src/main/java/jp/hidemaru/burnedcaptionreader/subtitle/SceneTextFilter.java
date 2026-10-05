package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

/** Reject structural scene labels before they can teach or join a subtitle lane. */
final class SceneTextFilter {
    private static final Pattern COMPACT_JAPANESE = Pattern.compile("^[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]{2,12}$");
    private static final Pattern HAN_OR_KATAKANA = Pattern.compile("[\\p{IsHan}\\p{IsKatakana}]");
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?:です|ます|でした|ました|ません|ない|たい|する|した|して|いる|ある|なる|った|って|[はがをにへとでもねよだかうる])$");
    private static final Pattern REACTION_PUNCTUATION = Pattern.compile("[!?！？…]");
    private static final Pattern MEASURE = Pattern.compile(
            "^(?:[¥￥$]?\\d+(?:[.,]\\d+)?(?:ms|secs?|seconds?|s|mins?|minutes?|m|hrs?|hours?|h|bar|atm|mm|cm|km|hz|mah|wh|v|w|gb|tb|円|秒|分|時間|個|人|回|倍|度|%)){1,3}$",
            Pattern.CASE_INSENSITIVE);

    static List<OcrLine> captionsOnly(List<OcrLine> lines) {
        Set<OcrLine> metricPairs = new HashSet<>();
        Set<OcrLine> pairedLabels = new HashSet<>();
        Set<OcrLine> pairedValues = new HashSet<>();
        for (OcrLine label : lines) {
            String key = SubtitleNormalizer.comparisonKey(label.getText());
            if (label.getWidth() > .36f || label.getHeight() > .12f || key.length() < 2
                    || key.length() > 32 || MEASURE.matcher(key).matches()
                    || SENTENCE_END.matcher(key).find() || REACTION_PUNCTUATION.matcher(label.getText()).find()) continue;
            if (!key.codePoints().anyMatch(Character::isLetter)) continue;
            for (OcrLine value : lines) {
                if (value == label || !MEASURE.matcher(SubtitleNormalizer.comparisonKey(value.getText())).matches()) continue;
                float height = Math.max(label.getHeight(), value.getHeight());
                if (Math.min(label.getHeight(), value.getHeight()) < height * .35f) continue;
                float gap = Math.max(label.getTop() - value.getBottom(), value.getTop() - label.getBottom());
                if (gap < -height * .20f || gap > Math.min(.065f, height * 1.5f)) continue;
                float overlap = Math.min(label.getRight(), value.getRight()) - Math.max(label.getLeft(), value.getLeft());
                if (overlap < Math.min(label.getWidth(), value.getWidth()) * .65f
                        || Math.abs(label.getCenterX() - value.getCenterX()) > Math.max(.025f, label.getWidth() * .25f)) continue;
                metricPairs.add(label);
                pairedLabels.add(label);
                pairedValues.add(value);
                metricPairs.add(value);
            }
        }
        List<OcrLine> result = new ArrayList<>();
        for (OcrLine line : lines) {
            // A lone two-row caption with a number/unit is not a table.
            if (pairedLabels.size() >= 2 && pairedValues.size() >= 2 && metricPairs.contains(line)) continue;
            if (isSeparatedNameplateRow(line, lines)) continue;
            result.add(line);
        }
        return DocumentTextFilter.captionsOnly(TableTextFilter.captionsOnly(EmbeddedUiTextFilter.captionsOnly(result)));
    }

    private static boolean isSeparatedNameplateRow(OcrLine line, List<OcrLine> all) {
        List<OcrLine> parts = line.getSeparatedParts();
        if (parts.size() < 2 || parts.size() > 4) return false;
        float height = 0;
        for (OcrLine part : parts) {
            String key = SubtitleNormalizer.comparisonKey(part.getText());
            if (part.getWidth() > .34f || part.getHeight() > .12f
                    || !COMPACT_JAPANESE.matcher(key).matches() || !HAN_OR_KATAKANA.matcher(key).find()
                    || SENTENCE_END.matcher(key).find() || REACTION_PUNCTUATION.matcher(part.getText()).find()) return false;
            height = Math.max(height, part.getGlyphHeight());
        }
        // Separate small labels from an independently visible, larger caption.
        // Do not suppress a sole spaced headline or equally sized dialogue row.
        for (OcrLine other : all) {
            if (other == line || other.getWidth() < .50f || other.getGlyphHeight() < height * 1.25f
                    || !HAN_OR_KATAKANA.matcher(other.getText()).find()) continue;
            float overlap = Math.min(line.getBottom(), other.getBottom()) - Math.max(line.getTop(), other.getTop());
            if (overlap <= 0) return true;
        }
        return false;
    }
}
