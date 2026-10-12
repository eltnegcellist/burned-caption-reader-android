package jp.hidemaru.burnedcaptionreader.ocr;

import android.graphics.Bitmap;
import java.util.function.Consumer;

public interface OcrEngine extends AutoCloseable {
    void recognize(Bitmap bitmap, Consumer<OcrResult> onSuccess, Consumer<Exception> onError);
    default void refine(Bitmap bitmap, Consumer<OcrResult> onSuccess, Consumer<Exception> onError) {
        recognize(bitmap, onSuccess, onError);
    }
    default void recognizeRegion(Bitmap bitmap, boolean subtitleRegion, Consumer<OcrResult> onSuccess, Consumer<Exception> onError) {
        if (subtitleRegion) refine(bitmap, onSuccess, onError); else recognize(bitmap, onSuccess, onError);
    }
    @Override void close();
}
