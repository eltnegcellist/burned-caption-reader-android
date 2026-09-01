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

    public static boolean areEquivalent(String left, String right, double threshold) {
        return textSimilarity(left, right) >= threshold;
    }
}
