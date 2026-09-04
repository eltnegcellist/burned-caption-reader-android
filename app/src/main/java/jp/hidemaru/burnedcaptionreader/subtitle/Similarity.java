package jp.hidemaru.burnedcaptionreader.subtitle;

public final class Similarity {
    private Similarity() {}

    public static int levenshteinDistance(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (a.length == 0) return b.length;
        if (b.length == 0) return a.length;

        int[] previous = new int[b.length + 1];
        for (int i = 0; i <= b.length; i++) previous[i] = i;

        for (int row = 1; row <= a.length; row++) {
            int[] current = new int[b.length + 1];
            current[0] = row;
            for (int column = 1; column <= b.length; column++) {
                int substitution = a[row - 1] == b[column - 1] ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitution
                );
            }
            previous = current;
        }
        return previous[b.length];
    }

    public static double textSimilarity(String left, String right) {
        String a = SubtitleNormalizer.comparisonKey(left);
        String b = SubtitleNormalizer.comparisonKey(right);
        if (a.equals(b)) return 1.0;
        int longest = Math.max(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        if (longest == 0) return 1.0;
        return 1.0 - (double) levenshteinDistance(a, b) / longest;
    }

    public static boolean isPrefixRelation(String left, String right) {
        String a = SubtitleNormalizer.comparisonKey(left);
        String b = SubtitleNormalizer.comparisonKey(right);
        int shortLength = Math.min(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        return shortLength >= 2 && (a.startsWith(b) || b.startsWith(a));
    }

    /**
     * Returns true when OCR temporarily drops one complete edge row from a multiline
     * caption. Requiring both variants to remain multiline avoids treating an
     * ordinary one-line subtitle which happens to share a phrase as OCR wobble.
     */
    public static boolean isMultilineVariant(String left, String right, double minCoverage) {
        String normalizedLeft = SubtitleNormalizer.normalize(left);
        String normalizedRight = SubtitleNormalizer.normalize(right);
        if (!normalizedLeft.contains("\n") || !normalizedRight.contains("\n")) return false;

        String[] a = normalizedLeft.split("\n");
        String[] b = normalizedRight.split("\n");
        String[] shorterRows = a.length < b.length ? a : b;
        String[] longerRows = a.length < b.length ? b : a;
        if (longerRows.length != shorterRows.length + 1) return false;
        boolean matchingRows = false;
        for (int offset = 0; offset <= 1; offset++) {
            boolean match = true;
            for (int row = 0; row < shorterRows.length; row++) {
                if (!SubtitleNormalizer.comparisonKey(shorterRows[row]).equals(
                        SubtitleNormalizer.comparisonKey(longerRows[row + offset]))) {
                    match = false;
                    break;
                }
            }
            matchingRows |= match;
        }
        if (!matchingRows) return false;
        String shorter = SubtitleNormalizer.comparisonKey(String.join("\n", shorterRows));
        String longer = SubtitleNormalizer.comparisonKey(String.join("\n", longerRows));
        int shortLength = shorter.codePointCount(0, shorter.length());
        int longLength = longer.codePointCount(0, longer.length());
        return longLength > 0 && shortLength >= Math.ceil(longLength * minCoverage);
    }

    public static boolean areEquivalent(String left, String right, double threshold) {
        return textSimilarity(left, right) >= threshold;
    }
}
