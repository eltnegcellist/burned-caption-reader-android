package jp.hidemaru.burnedcaptionreader.ocr;

import android.content.Context;

/** Shared by the player and screen-capture path, so a stored choice has one meaning. */
public final class CaptionOcrEngines {
    private CaptionOcrEngines() {}

    public static OcrEngine create(Context context, CaptionOcrMode mode) {
        if (mode == CaptionOcrMode.ML_KIT) return new MlKitJapaneseOcrEngine();
        return new HybridCaptionOcrEngine(context, mode == CaptionOcrMode.PP_DETECTION);
    }
}
