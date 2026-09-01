package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OcrResult {
    private final String text;
    private final double confidence;
    private final List<OcrLine> lines;

    public OcrResult(String text, double confidence) {
        this(text, confidence, Collections.emptyList());
    }

    public OcrResult(String text, double confidence, List<OcrLine> lines) {
        this.text = text;
        this.confidence = confidence;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public String getText() { return text; }
    public double getConfidence() { return confidence; }
    public List<OcrLine> getLines() { return lines; }
}
