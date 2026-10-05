package jp.hidemaru.burnedcaptionreader.ocr;

import android.graphics.Bitmap;

/** Shared same-frame pixel preparation for the player and image evaluator. */
public final class OcrBitmapInputs {
    private OcrBitmapInputs() {}
    public static Bitmap coarse(Bitmap frame) {
        float scale = frame.getWidth() > 1100 ? 1100f / frame.getWidth()
                : Math.min(2f, 850f / frame.getWidth());
        scale = Math.min(scale, 2000f / frame.getHeight());
        return Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                Math.max(1, Math.round(frame.getHeight() * scale)), true);
    }
    /** One extra detection call only for pixel-supported lower-strip misses. */
    public static SubtitleCropPlan stripRecoveryPlan(Bitmap frame, OcrResult raw) {
        int[] pixels = new int[frame.getWidth() * frame.getHeight()];
        frame.getPixels(pixels, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
        CaptionStripRecovery.Region region = CaptionStripRecovery.find(pixels, frame.getWidth(), frame.getHeight());
        return CaptionStripRecovery.needsRecovery(region, raw)
                ? SubtitleCropPlan.create(frame.getWidth(), frame.getHeight(), region.top, region.bottom) : null;
    }
    public static Bitmap refined(Bitmap frame, SubtitleCropPlan plan) {
        Bitmap crop = Bitmap.createBitmap(frame, 0, plan.top, frame.getWidth(), plan.height);
        Bitmap output = null;
        try {
            output = Bitmap.createScaledBitmap(crop, plan.outputWidth, plan.outputHeight, true);
            return output;
        } finally {
            if (crop != output && crop != frame) crop.recycle();
        }
    }

    /** Alternative pixels for an already bounded refinement crop. Input remains owned by caller. */
    public static Bitmap whiteCore(Bitmap input) {
        int[] pixels = new int[input.getWidth() * input.getHeight()];
        input.getPixels(pixels, 0, input.getWidth(), 0, 0, input.getWidth(), input.getHeight());
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i], red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
            int low = Math.min(red, Math.min(green, blue)), high = Math.max(red, Math.max(green, blue));
            pixels[i] = low >= 210 && high - low <= 45 ? 0xff000000 : 0xffffffff;
        }
        return Bitmap.createBitmap(pixels, input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
    }
}
