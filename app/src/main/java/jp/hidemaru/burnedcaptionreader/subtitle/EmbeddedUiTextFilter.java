package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;

/** Recognize embedded document layouts, keeping independently larger caption rows. */
final class EmbeddedUiTextFilter {
    private static final Pattern RATING = Pattern.compile("[★☆*]{2,}.*\\d");
    private static final Pattern RATING_COUNTS = Pattern.compile("^\\s*[0-5][.,]\\d{1,2}\\s+\\d+\\s*[人A].*\\d+\\s*人\\s*$");
    private static final Pattern PRICE_RANGE = Pattern.compile("[¥￥$\\\\Y]\\s*\\d[\\d,.]*\\s*[~〜～-]\\s*[¥￥$\\\\Y]?\\s*\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern REVIEW = Pattern.compile("by\\s*[^()（）]+[（(]\\s*\\d+\\s*[)）]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("^\\d{1,2}[:：]\\d{2}$");
    private static final Pattern PRICE = Pattern.compile("^\\d+(?:[.,]\\d+)?円$");
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern CLAUSE_OR_REACTION = Pattern.compile("(?:です|ます|でした|ました|ない|たい|する|した|して|いる|ある|なる|った|って|[はがをにへとでもねよだかうる])$|[!?！？…]");

    static List<OcrLine> captionsOnly(List<OcrLine> lines) {
        Set<OcrLine> excluded = new HashSet<>();
        excludeReviewPanel(lines, excluded);
        excludeChatPanel(lines, excluded);
        excludePriceCards(lines, excluded);
        List<OcrLine> kept = new ArrayList<>();
        for (OcrLine line : lines) if (!excluded.contains(line)) kept.add(line);
        return kept;
    }

    private static void excludeReviewPanel(List<OcrLine> lines, Set<OcrLine> excluded) {
        List<OcrLine> anchors = new ArrayList<>();
        int ratings = 0, ranges = 0, reviews = 0;
        for (OcrLine line : lines) {
            if (line.getHeight() > .075f) continue;
            String text = line.getText();
            boolean rating = RATING.matcher(text).find() || RATING_COUNTS.matcher(text).matches();
            boolean range = PRICE_RANGE.matcher(text).find();
            boolean review = REVIEW.matcher(text).find();
            if (rating) ratings++;
            if (range) ranges++;
            if (review) reviews++;
            if (rating || range || review) anchors.add(line);
        }
        // A quoted review or a spoken price alone does not establish a document.
        if (!(ratings > 0 && ranges > 0 || reviews >= 2 || reviews > 0 && ranges > 0)) return;
        for (OcrLine line : lines) {
            if (line.getHeight() > .075f || line.getWidth() > .85f) continue;
            for (OcrLine anchor : anchors) {
                // Follow the document's left margins even when it scrolls;
                // a narrow centered caption does not inherit an overlapping panel.
                boolean aligned = Math.abs(line.getLeft() - anchor.getLeft()) <= .045f;
                if (aligned) {
                    excluded.add(line); break;
                }
            }
        }
    }

    private static void excludeChatPanel(List<OcrLine> lines, Set<OcrLine> excluded) {
        List<OcrLine> clocks = new ArrayList<>();
        for (OcrLine line : lines) if (line.getHeight() <= .06f && line.getWidth() <= .17f
                && CLOCK.matcher(line.getText().trim()).matches()) clocks.add(line);
        for (OcrLine headerClock : clocks) {
            if (headerClock.getTop() > .18f) continue;
            for (OcrLine messageClock : clocks) {
                if (messageClock.getTop() < .25f || messageClock.getTop() > .85f
                        || messageClock.getTop() - headerClock.getBottom() < .25f) continue;
                for (OcrLine seed : lines) {
                    List<OcrLine> body = new ArrayList<>();
                    for (OcrLine line : lines) {
                        if (line.getTop() < .20f || line.getBottom() > messageClock.getBottom() + .02f
                                || line.getHeight() > .07f || line.getWidth() < .15f
                                || line.getWidth() > .65f || !JAPANESE.matcher(line.getText()).find()) continue;
                        if (Math.abs(line.getLeft() - seed.getLeft()) <= .035f) body.add(line);
                    }
                    // Four aligned body rows plus separate header/message times;
                    // a normal one-to-three-row caption with a clock is preserved.
                    if (body.size() >= 4) {
                        excluded.addAll(body); excluded.addAll(clocks);
                        for (OcrLine header : lines) if (header.getTop() <= .20f
                                && header.getHeight() <= .07f && header.getWidth() <= .55f
                                && Math.abs(header.getLeft() - headerClock.getLeft()) <= .10f)
                            excluded.add(header);
                    }
                }
            }
        }
    }

    private static void excludePriceCards(List<OcrLine> lines, Set<OcrLine> excluded) {
        List<OcrLine> prices = new ArrayList<>();
        for (OcrLine line : lines) if (line.getWidth() <= .25f && line.getHeight() <= .18f
                && PRICE.matcher(SubtitleNormalizer.comparisonKey(line.getText())).matches()) prices.add(line);
        for (OcrLine price : prices) {
            List<OcrLine> labels = new ArrayList<>();
            for (OcrLine line : lines) {
                String key = SubtitleNormalizer.comparisonKey(line.getText());
                if (line == price || line.getWidth() > .38f || line.getHeight() > .145f
                        || key.length() < 2 || key.length() > 16 || !JAPANESE.matcher(key).find()
                        || PRICE.matcher(key).matches() || (CLAUSE_OR_REACTION.matcher(key).find() || CLAUSE_OR_REACTION.matcher(line.getText()).find())) continue;
                if (line.getTop() < price.getTop() + .02f || line.getTop() > price.getBottom() + .27f) continue;
                float overlap = Math.min(line.getRight(), price.getRight()) - Math.max(line.getLeft(), price.getLeft());
                if (overlap >= Math.min(line.getWidth(), price.getWidth()) * .10f
                        || Math.abs(line.getLeft() - price.getLeft()) <= .10f) labels.add(line);
            }
            if (labels.isEmpty()) continue;
            boolean repeatedCard = false;
            for (OcrLine other : prices) if (other != price
                    && (Math.abs(price.getCenterX() - other.getCenterX()) >= .20f
                        || Math.abs(price.getCenterY() - other.getCenterY()) >= .22f)) repeatedCard = true;
            boolean largerCaption = false;
            for (OcrLine line : lines) if (line.getWidth() >= .60f && JAPANESE.matcher(line.getText()).find()
                    && line.getHeight() >= labels.get(0).getHeight() * 1.6f
                    && (line.getTop() >= price.getBottom() || line.getBottom() <= price.getTop())) largerCaption = true;
            if (repeatedCard || largerCaption) { excluded.add(price); excluded.addAll(labels); }
        }
    }
}
