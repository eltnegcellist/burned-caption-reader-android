package jp.hidemaru.burnedcaptionreader.ocr;

/** Pixel crop of the same captured frame; padding can recover a missing row. */
public final class SubtitleCropPlan {
    public final int top;
    public final int height;
    public final int outputWidth;
    public final int outputHeight;

    private SubtitleCropPlan(int top, int height, int width, int outputHeight) {
        this.top = top; this.height = height;
        this.outputWidth = width; this.outputHeight = outputHeight;
    }

    public static SubtitleCropPlan create(int width, int height, float top, float bottom) {
        if (width <= 0 || height <= 0 || !Float.isFinite(top) || !Float.isFinite(bottom)
                || bottom <= top) throw new IllegalArgumentException("Invalid subtitle crop");
        float pad = Math.max(.018f, (bottom - top) * .65f);
        int first = Math.max(0, Math.min(height - 1, (int) Math.floor((top - pad) * height)));
        int last = Math.max(first + 1, Math.min(height, (int) Math.ceil((bottom + pad) * height)));
        int cropHeight = last - first;
        double scale = Math.min(3.0, 2000.0 / Math.max(width, cropHeight));
        return new SubtitleCropPlan(first, cropHeight,
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(cropHeight * scale)));
    }
}
