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
                .replace('⋮', '…')
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");

        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String clean = line
                    .replaceAll("[\\t \\u3000]+", "")
                    .replaceAll("[~～]+", "〜")
                    .replaceAll("^[|｜]+|[|｜]+$", "")
                    // ML Kit can alternate between …, ..., ・・・ and similar dot leaders.
                    // Collapse runs to a single semantic ellipsis before comparison/TTS.
                    .replaceAll("[.．・･·•‧∙⋅◦…]{2,}", "…")
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

    /**
     * Formats recognized subtitle rows as a compact paragraph for the player UI.
     * Japanese lines are joined without an artificial gap; Latin-script lines
     * retain a separating space so that words do not run together.
     * This is intentionally display-only: row boundaries used for speech and
     * duplicate detection must remain untouched.
     */
    public static String toDisplayText(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        String[] lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            String part = line.trim();
            if (part.isEmpty()) continue;
            if (result.length() > 0) {
                int before = Character.codePointBefore(result, result.length());
                int after = part.codePointAt(0);
                if (!isJapaneseBoundary(before) && !isJapaneseBoundary(after)) {
                    result.append(' ');
                }
            }
            result.append(part);
        }
        return result.toString();
    }

    private static boolean isJapaneseBoundary(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0xFF01 && codePoint <= 0xFF65);
    }

    public static String toSpeechText(String input) {
        // Treat ellipsis as a pause. Some TTS engines otherwise verbalize dot-like
        // OCR artifacts, which sounds like a recognition error to the listener.
        String speech = normalize(input)
                .replaceAll("…+", "、")
                .replaceAll("\n+", "、")
                .replaceAll("[、。,.・·•‧∙⋅◦]{2,}", "、")
                .replaceAll("^[、。,.・·•‧∙⋅◦]+|[、,.・·•‧∙⋅◦]+$", "");
        // Never ask a TTS engine to pronounce a punctuation-only utterance.
        return speech.codePoints().anyMatch(Character::isLetterOrDigit) ? speech : "";
    }
}
