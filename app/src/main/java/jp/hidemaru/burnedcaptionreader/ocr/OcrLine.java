package jp.hidemaru.burnedcaptionreader.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A recognized horizontal text line with coordinates normalized to the OCR input image. */
public final class OcrLine {
    private final int blockIndex;
    private final String text;
    private final double confidence;
    private final float left;
    private final float top;
    private final float right;
    private final float bottom;
    private final List<OcrLine> separatedParts;

    public OcrLine(int blockIndex, String text, double confidence,
                   float left, float top, float right, float bottom) {
        this(blockIndex, text, confidence, left, top, right, bottom, Collections.emptyList());
    }

    public OcrLine(int blockIndex, String text, double confidence,
                   float left, float top, float right, float bottom, List<OcrLine> separatedParts) {
        this.blockIndex = blockIndex;
        this.text = text == null ? "" : text;
        this.confidence = confidence;
        this.left = clamp(left);
        this.top = clamp(top);
        this.right = clamp(right);
        this.bottom = clamp(bottom);
        this.separatedParts = Collections.unmodifiableList(new ArrayList<>(separatedParts));
    }

    public int getBlockIndex() { return blockIndex; }
    public String getText() { return text; }
    public double getConfidence() { return confidence; }
    public float getLeft() { return left; }
    public float getTop() { return top; }
    public float getRight() { return right; }
    public float getBottom() { return bottom; }
    public float getCenterX() { return (left + right) / 2f; }
    public float getCenterY() { return (top + bottom) / 2f; }
    public float getWidth() { return Math.max(0f, right - left); }
    public float getHeight() { return Math.max(0f, bottom - top); }
    /** Character boxes reveal gaps that ML Kit's line text may omit entirely. */
    public List<OcrLine> getSeparatedParts() { return separatedParts; }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
