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
                .replace('⋯', '…')
                .replace('‥', '…')
                .replace('︙', '…')
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");

        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String clean = line
                    .replaceAll("[\\t \\u3000]+", "")
                    .replaceAll("[~～]+", "〜")
                    .replaceAll("^[|｜]+|[|｜]+$", "")
                    // ML Kit can alternate between …, ..., ・・・ and similar dot leaders.
                    // Collapse runs to a single semantic ellipsis before comparison/TTS.
                    .replaceAll("[.．・･·…]{2,}", "…")
                    .replaceAll("…{2,}", "…")
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
                // Punctuation, including ellipsis, must not make the same subtitle
                // look different just because OCR emitted a different dot form.
                .replaceAll("[、。,.，．・…⋯‥︙:：;；!?！？「」『』（）()\\[\\]【】]", "")
                .toLowerCase(Locale.JAPANESE);
    }

    public static String toSpeechText(String input) {
        // Treat ellipsis as a pause. Some TTS engines otherwise verbalize dot-like
        // OCR artifacts, which sounds like a recognition error to the listener.
        return normalize(input)
                .replaceAll("…+", "、")
                .replaceAll("\n+", "、");
    }
}
