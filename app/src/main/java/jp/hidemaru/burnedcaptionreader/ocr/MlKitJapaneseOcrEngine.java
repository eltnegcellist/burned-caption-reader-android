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
    /** ML Kit may omit confidence for some Japanese elements; unknown is not certainty. */
    private static final double UNKNOWN_CONFIDENCE = 55.0;

    private final TextRecognizer recognizer = TextRecognition.getClient(
            new JapaneseTextRecognizerOptions.Builder().build()
    );

    @Override
    public void recognize(Bitmap bitmap, Consumer<OcrResult> onSuccess, Consumer<Exception> onError) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> onSuccess.accept(
                        toResult(result, bitmap.getWidth(), bitmap.getHeight())))
                .addOnFailureListener(onError::accept);
    }

    private OcrResult toResult(Text result, int imageWidth, int imageHeight) {
        final class IndexedLine {
            final int blockIndex;
            final Text.Line line;
            IndexedLine(int blockIndex, Text.Line line) {
                this.blockIndex = blockIndex;
                this.line = line;
            }
        }

        List<IndexedLine> lines = new ArrayList<>();
        List<Text.TextBlock> blocks = result.getTextBlocks();
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            for (Text.Line line : blocks.get(blockIndex).getLines()) {
                lines.add(new IndexedLine(blockIndex, line));
            }
        }
        lines.sort(Comparator
                .comparingInt((IndexedLine value) -> top(value.line.getBoundingBox()))
                .thenComparingInt(value -> left(value.line.getBoundingBox())));

        StringBuilder text = new StringBuilder();
        double confidenceTotal = 0.0;
        int confidenceCount = 0;
        List<OcrLine> recognizedLines = new ArrayList<>();
        for (IndexedLine indexed : lines) {
            Text.Line line = indexed.line;
            String value = line.getText().trim();
            if (value.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(value);
            double lineConfidenceTotal = 0.0;
            int lineConfidenceCount = 0;
            for (Text.Element element : line.getElements()) {
                Float confidence = element.getConfidence();
                if (confidence != null) {
                    confidenceTotal += confidence * 100.0;
                    confidenceCount++;
                    lineConfidenceTotal += confidence * 100.0;
                    lineConfidenceCount++;
                }
            }
            Rect box = line.getBoundingBox();
            if (box != null) {
                double lineConfidence = lineConfidenceCount == 0
                        ? UNKNOWN_CONFIDENCE : lineConfidenceTotal / lineConfidenceCount;
                recognizedLines.add(new OcrLine(indexed.blockIndex, value, lineConfidence,
                        box.left / (float) Math.max(1, imageWidth),
                        box.top / (float) Math.max(1, imageHeight),
                        box.right / (float) Math.max(1, imageWidth),
                        box.bottom / (float) Math.max(1, imageHeight)));
            }
        }
        double confidence = confidenceCount == 0
                ? UNKNOWN_CONFIDENCE : confidenceTotal / confidenceCount;
        return new OcrResult(text.toString(), confidence, recognizedLines);
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
