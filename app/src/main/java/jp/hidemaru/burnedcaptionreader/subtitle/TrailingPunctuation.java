package jp.hidemaru.burnedcaptionreader.subtitle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repairs only a terminal OCR tail backed by another reading of the same row. */
public final class TrailingPunctuation {
    private static final Pattern EVIDENCE = Pattern.compile("^(.+?)([…。.!！?？・]+)$");
    private static final Pattern NOISE = Pattern.compile("[0oObd8…。.・!！?？\\-]{1,6}");
    private static final Pattern ENDING = Pattern.compile(".*(?:が|から|です|ます|よ|か|ね|し|[えあうお]{2,})$");

    private TrailingPunctuation() {}

    public static String repair(String text, String evidence) {
        String[] rows = SubtitleNormalizer.normalize(text).split("\n");
        for (int i = 0; i < rows.length; i++) {
            for (String reference : SubtitleNormalizer.normalize(evidence).split("\n")) {
                Matcher m = EVIDENCE.matcher(reference);
                if (!m.matches()) continue;
                String stem = m.group(1);
                if (stem.codePointCount(0, stem.length()) < 3 || !ENDING.matcher(stem).matches()) continue;
                if (!rows[i].startsWith(stem)) continue;
                String tail = rows[i].substring(stem.length());
                if (!tail.isEmpty() && NOISE.matcher(tail).matches()) {
                    rows[i] = reference;
                    break;
                }
            }
        }
        return String.join("\n", rows);
    }
}
