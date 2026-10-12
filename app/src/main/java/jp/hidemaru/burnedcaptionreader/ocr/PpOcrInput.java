package jp.hidemaru.burnedcaptionreader.ocr;

/** Official recognition input: BGR, height48, dynamic width320..3200, normalized to[-1,1]. */
public final class PpOcrInput {
    public final int width;
    public final float[] values;
    private PpOcrInput(int width, float[] values) { this.width = width; this.values = values; }
    public static PpOcrInput fromArgb(int[] pixels, int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1 || (long) sourceWidth * sourceHeight != pixels.length)
            throw new IllegalArgumentException("Invalid pixels");
        int width = Math.min(3200, Math.max(320, (int) (48.0 * sourceWidth / sourceHeight)));
        int resizedWidth = Math.min(width, (int) Math.ceil(48.0 * sourceWidth / sourceHeight));
        int plane = 48 * width; float[] output = new float[plane * 3];
        for (int y = 0; y < 48; y++) {
            double sy = Math.max(0, Math.min(sourceHeight - 1, (y + .5) * sourceHeight / 48 - .5));
            int y0 = (int) sy, y1 = Math.min(sourceHeight - 1, y0 + 1); double fy = sy - y0;
            for (int x = 0; x < resizedWidth; x++) {
                double sx = Math.max(0, Math.min(sourceWidth - 1, (x + .5) * sourceWidth / resizedWidth - .5));
                int x0 = (int) sx, x1 = Math.min(sourceWidth - 1, x0 + 1); double fx = sx - x0;
                for (int c = 0; c < 3; c++) {
                    int shift = c * 8; // blue, green, red
                    double upper = ((pixels[y0 * sourceWidth + x0] >> shift) & 255) * (1 - fx)
                            + ((pixels[y0 * sourceWidth + x1] >> shift) & 255) * fx;
                    double lower = ((pixels[y1 * sourceWidth + x0] >> shift) & 255) * (1 - fx)
                            + ((pixels[y1 * sourceWidth + x1] >> shift) & 255) * fx;
                    output[c * plane + y * width + x] = Math.round(upper * (1 - fy) + lower * fy) / 127.5f - 1;
                }
            }
        }
        return new PpOcrInput(width, output);
    }
}
