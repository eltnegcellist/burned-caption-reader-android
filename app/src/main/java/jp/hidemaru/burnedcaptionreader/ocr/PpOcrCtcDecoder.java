package jp.hidemaru.burnedcaptionreader.ocr;

import java.nio.FloatBuffer;
import java.util.List;

/** Greedy CTC decoding: collapse adjacent tokens, then remove blank (index 0). */
public final class PpOcrCtcDecoder {
    public static final class Reading {
        public final String text;
        public final double probability;
        Reading(String text, double probability) { this.text = text; this.probability = probability; }
    }
    private PpOcrCtcDecoder() {}
    public static Reading decode(FloatBuffer scores, int steps, List<String> characters) {
        int classes = characters.size();
        if (steps < 1 || classes < 2 || (long) steps * classes != scores.remaining())
            throw new IllegalArgumentException("Invalid CTC output shape");
        StringBuilder text = new StringBuilder();
        double total = 0; int count = 0, previous = -1;
        for (int t = 0; t < steps; t++) {
            int token = -1; float best = -Float.MAX_VALUE;
            for (int c = 0; c < classes; c++) {
                float score = scores.get();
                if (!Float.isFinite(score)) throw new IllegalArgumentException("Nonfinite CTC score");
                if (score > best) { best = score; token = c; }
            }
            if (best < 0 || best > 1.001f) throw new IllegalArgumentException("CTC probabilities required");
            if (token != 0 && token != previous) { text.append(characters.get(token)); total += best; count++; }
            previous = token;
        }
        return new Reading(text.toString(), count == 0 ? 0 : total / count);
    }
}
