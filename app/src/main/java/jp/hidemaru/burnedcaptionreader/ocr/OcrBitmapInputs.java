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
}
