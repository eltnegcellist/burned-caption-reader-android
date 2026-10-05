package jp.hidemaru.burnedcaptionreader;

/** Local debug capture offsets; an empty legacy gate still means the beginning. */
final class EvaluationWindow {
    static int startSeconds(String gate) {
        String text = gate.trim();
        if (text.isEmpty()) return 0;
        if (!text.matches("\\d{1,5}")) throw new IllegalArgumentException("Invalid evaluation offset");
        int seconds = Integer.parseInt(text);
        if (seconds > 86400) throw new IllegalArgumentException("Evaluation offset exceeds one day");
        return seconds;
    }
}
