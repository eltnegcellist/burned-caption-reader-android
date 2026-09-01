package jp.hidemaru.burnedcaptionreader.ocr;

/** A recognized horizontal text line with coordinates normalized to the OCR input image. */
public final class OcrLine {
    private final int blockIndex;
    private final String text;
    private final double confidence;
    private final float left;
    private final float top;
    private final float right;
    private final float bottom;

    public OcrLine(int blockIndex, String text, double confidence,
                   float left, float top, float right, float bottom) {
        this.blockIndex = blockIndex;
        this.text = text == null ? "" : text;
        this.confidence = confidence;
        this.left = clamp(left);
        this.top = clamp(top);
        this.right = clamp(right);
        this.bottom = clamp(bottom);
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

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
