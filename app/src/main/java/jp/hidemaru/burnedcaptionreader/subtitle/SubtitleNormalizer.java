package jp.hidemaru.burnedcaptionreader.subtitle;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SubtitleNormalizer {
    private SubtitleNormalizer() {}

    public static String normalize(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");

        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String clean = line
                    .replaceAll("[\\t \\u3000]+", "")
                    .replaceAll("[~～]+", "〜")
                    .replaceAll("^[|｜]+|[|｜]+$", "")
                    .replaceAll("。{2,}", "。")
                    .replaceAll("、{2,}", "、")
                    .trim();
            if (!clean.isEmpty()) {
                lines.add(clean);
            }
        }
        return String.join("\n", lines).replaceAll("[。．.]$", "。");
    }

    public static String comparisonKey(String input) {
        return normalize(input)
                .replace("\n", "")
                .replaceAll("[、。,.，．・:：;；!?！？「」『』（）()\\[\\]【】]", "")
                .toLowerCase(Locale.JAPANESE);
    }

    public static String toSpeechText(String input) {
        return normalize(input).replaceAll("\n+", "、");
    }
}
