package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

/** Repeated aligned numeric cells corroborate a table without relying on its vocabulary. */
final class TableTextFilter {
    private static final Pattern SENTENCE = Pattern.compile("(?:です|ます|でした|ました|ません|ない|たい|する|した|して|いる|ある|なる|った|って|[!?！？…])$");
    private static final Pattern NUMERIC_CELL = Pattern.compile("[\\dA-Za-z\\s.,+＋\\-−/／:：%％°▲△▼▽()（）日差秒分時時間円個人回倍度石振動数]+", Pattern.CASE_INSENSITIVE);
    static List<OcrLine> captionsOnly(List<OcrLine> rows) {
        Set<OcrLine> excluded = new HashSet<>();
        for (OcrLine seed : rows) {
            if (!numeric(seed)) continue;
            List<OcrLine> column = new ArrayList<>();
            float top = 1, bottom = 0, left = 1, right = 0;
            for (OcrLine row : rows) if (numeric(row)
                    && Math.abs(row.getCenterX() - seed.getCenterX()) <= .055f) {
                boolean overlap = false;
                for (OcrLine prior : column) if (Math.min(prior.getBottom(), row.getBottom()) > Math.max(prior.getTop(), row.getTop())) overlap = true;
                if (overlap) continue;
                column.add(row); top = Math.min(top, row.getTop()); bottom = Math.max(bottom, row.getBottom());
                left = Math.min(left, row.getLeft()); right = Math.max(right, row.getRight());
            }
            if (column.size() < 3 || bottom - top < .16f) continue;
            List<OcrLine> companions = new ArrayList<>();
            Set<OcrLine> matchedValues = new HashSet<>();
            for (OcrLine row : rows) if (compact(row) && !column.contains(row)) {
                for (OcrLine value : column) {
                    if (Math.abs(row.getCenterY() - value.getCenterY()) <= Math.max(row.getHeight(), value.getHeight()) * .8f
                            && Math.max(row.getLeft() - value.getRight(), value.getLeft() - row.getRight()) >= .025f) {
                        companions.add(row); matchedValues.add(value); break;
                    }
                }
            }
            // A column of three numeric subtitle/reaction rows is insufficient.
            if (companions.size() < 3 || matchedValues.size() < 3) continue;
            excluded.addAll(column); excluded.addAll(companions);
            for (OcrLine row : companions) { left = Math.min(left, row.getLeft()); right = Math.max(right, row.getRight()); }
            for (OcrLine row : rows) if (compact(row) && row.getLeft() >= left - .04f && row.getRight() <= right + .04f
                    && row.getTop() + .001f >= top - .16f && row.getBottom() <= bottom + .07f)
                excluded.add(row);
        }
        List<OcrLine> kept = new ArrayList<>();
        for (OcrLine row : rows) if (!excluded.contains(row)) kept.add(row);
        return kept;
    }
    private static boolean compact(OcrLine row) {
        String key = SubtitleNormalizer.comparisonKey(row.getText());
        return row.getBottom() < .86f && row.getWidth() <= .44f && row.getGlyphHeight() <= .075f
                && key.length() >= 2 && key.length() <= 24 && !SENTENCE.matcher(row.getText()).find();
    }
    private static boolean numeric(OcrLine row) {
        String key = SubtitleNormalizer.comparisonKey(row.getText());
        return compact(row) && key.length() <= 16 && key.chars().filter(Character::isDigit).count() >= 2
                && NUMERIC_CELL.matcher(key).matches();
    }
}
