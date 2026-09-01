package jp.hidemaru.burnedcaptionreader.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class MlKitJapaneseOcrEngine implements OcrEngine {
    private final TextRecognizer recognizer = TextRecognition.getClient(
            new JapaneseTextRecognizerOptions.Builder().build()
    );

    @Override
    public void recognize(Bitmap bitmap, Consumer<OcrResult> onSuccess, Consumer<Exception> onError) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> onSuccess.accept(toResult(result)))
                .addOnFailureListener(onError::accept);
    }

    private OcrResult toResult(Text result) {
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            lines.addAll(block.getLines());
        }
        lines.sort(Comparator
                .comparingInt((Text.Line line) -> top(line.getBoundingBox()))
                .thenComparingInt(line -> left(line.getBoundingBox())));

        StringBuilder text = new StringBuilder();
        double confidenceTotal = 0.0;
        int confidenceCount = 0;
        for (Text.Line line : lines) {
            String value = line.getText().trim();
            if (value.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(value);
            for (Text.Element element : line.getElements()) {
                Float confidence = element.getConfidence();
                if (confidence != null) {
                    confidenceTotal += confidence * 100.0;
                    confidenceCount++;
                }
            }
        }
        double confidence = confidenceCount == 0 ? 100.0 : confidenceTotal / confidenceCount;
        return new OcrResult(text.toString(), confidence);
    }

    private int top(Rect rect) {
        return rect == null ? Integer.MAX_VALUE : rect.top;
    }

    private int left(Rect rect) {
        return rect == null ? Integer.MAX_VALUE : rect.left;
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
