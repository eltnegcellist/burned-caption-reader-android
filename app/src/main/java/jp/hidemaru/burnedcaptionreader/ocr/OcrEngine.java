package jp.hidemaru.burnedcaptionreader.ocr;

import android.graphics.Bitmap;
import java.util.function.Consumer;

public interface OcrEngine extends AutoCloseable {
    void recognize(Bitmap bitmap, Consumer<OcrResult> onSuccess, Consumer<Exception> onError);
    @Override void close();
}
