package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

/** Corroborated document flow, rather than a particular site's menu vocabulary. */
final class DocumentTextFilter {
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern MONEY = Pattern.compile("(?:[\\p{Sc}YyVv\\\\]\\s*\\d[\\d, .]*|\\d[\\d, .]*円)");

    static List<OcrLine> captionsOnly(List<OcrLine> lines) {
        float captionHeight = 0;
        for (OcrLine line : lines) if (line.getWidth() >= .45f && line.getGlyphHeight() >= .09f
                && JAPANESE.matcher(line.getText()).find())
            captionHeight = Math.max(captionHeight, line.getGlyphHeight());
        // A normal subtitle alone is never evidence of a document.
        List<OcrLine> small = new ArrayList<>();
        for (OcrLine line : lines) if (line.getWidth() <= .65f && line.getGlyphHeight() <= .075f
                && line.getGlyphHeight() > 0
                && (captionHeight == 0 || line.getGlyphHeight() <= captionHeight * .68f))
            small.add(line);
        Set<OcrLine> excluded = new HashSet<>();
        for (OcrLine seed : small) {
            List<OcrLine> column = new ArrayList<>();
            int latin = 0, prices = 0, prose = 0;
            float top = 1, bottom = 0;
            for (OcrLine row : small) {
                // Allow perspective drift in a page, but not another distant column.
                if (Math.abs(row.getLeft() - seed.getLeft()) > .09f) continue;
                column.add(row);
                if (latinLetters(row.getText()) >= 8) latin++;
                if (MONEY.matcher(row.getText()).find()) prices++;
                else if (row.getText().codePointCount(0, row.getText().length()) >= 5) prose++;
                top = Math.min(top, row.getTop()); bottom = Math.max(bottom, row.getBottom());
            }
            boolean dense = column.size() >= 6 && prose >= 4 && bottom - top >= .20f;
            boolean documentCues = latin >= 2 || prices >= 3;
            if (dense && documentCues && (captionHeight > 0 || prices >= 3))
                excludeRegion(column, small, excluded);
        }
        // Repeated bilingual cells in separate columns also establish a document.
        // Two translated subtitle rows sharing one lane do not.
        List<OcrLine> bilingualCells = new ArrayList<>();
        for (OcrLine japanese : small) {
            if (japanese.getWidth() > .38f || !JAPANESE.matcher(japanese.getText()).find()) continue;
            for (OcrLine translation : small) {
                if (translation == japanese || latinLetters(translation.getText()) < 8
                        || JAPANESE.matcher(translation.getText()).find()) continue;
                float gap = translation.getTop() - japanese.getBottom();
                if (gap >= -japanese.getGlyphHeight() * .5f && gap <= .055f
                        && Math.abs(translation.getLeft() - japanese.getLeft()) <= .035f) {
                    bilingualCells.add(japanese); break;
                }
            }
        }
        if (captionHeight > 0) for (OcrLine first : bilingualCells) for (OcrLine second : bilingualCells) {
            if (Math.abs(first.getLeft() - second.getLeft()) < .14f
                    || Math.abs(first.getTop() - second.getTop()) > .10f) continue;
            List<OcrLine> cells = new ArrayList<>();
            for (OcrLine row : small) if (nearCell(row, first) || nearCell(row, second)) cells.add(row);
            excludeRegion(cells, small, excluded);
        }
        List<OcrLine> kept = new ArrayList<>();
        for (OcrLine line : lines) if (!excluded.contains(line)) kept.add(line);
        return kept;
    }

    private static boolean nearCell(OcrLine row, OcrLine cell) {
        return Math.abs(row.getLeft() - cell.getLeft()) <= .045f
                && row.getTop() >= cell.getTop() - .01f && row.getBottom() <= cell.getBottom() + .10f;
    }

    private static void excludeRegion(List<OcrLine> column, List<OcrLine> small, Set<OcrLine> excluded) {
        if (column.isEmpty()) return;
        List<Float> sizes = new ArrayList<>();
        float left = 1, right = 0, top = 1, bottom = 0;
        for (OcrLine row : column) {
            sizes.add(row.getGlyphHeight()); left = Math.min(left, row.getLeft());
            right = Math.max(right, row.getRight()); top = Math.min(top, row.getTop());
            bottom = Math.max(bottom, row.getBottom());
        }
        sizes.sort(Float::compare);
        float documentHeight = sizes.get(sizes.size() / 2);
        for (OcrLine row : small) if (row.getGlyphHeight() <= documentHeight * 1.65f
                && row.getLeft() >= left - .035f && row.getRight() <= right + .07f
                && row.getTop() >= top - .10f && row.getBottom() <= bottom + .10f)
            excluded.add(row);
    }

    private static int latinLetters(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') count++;
        }
        return count;
    }
}
