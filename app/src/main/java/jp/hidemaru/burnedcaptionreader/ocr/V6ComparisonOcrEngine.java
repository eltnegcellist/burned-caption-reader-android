package jp.hidemaru.burnedcaptionreader.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Strict factorial comparison. The detection branch never runs ML Kit; the
 * recognition branch always uses the explicitly chosen PP-OCR model.
 * Missing or broken v6 models are errors, not silently substituted baselines.
 */
public final class V6ComparisonOcrEngine implements OcrEngine {
    private final Context context;
    private final boolean v6Detection, v6Recognition;
    private final MlKitJapaneseOcrEngine mlkit;
    private final Handler callback = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PpOcrTextDetector detection;
    private PpOcrRecognizer recognition;
    private volatile boolean closed;

    public V6ComparisonOcrEngine(Context context, boolean v6Detection, boolean v6Recognition) {
        this.context = context.getApplicationContext();
        this.v6Detection = v6Detection;
        this.v6Recognition = v6Recognition;
        this.mlkit = v6Detection ? null : new MlKitJapaneseOcrEngine();
    }
    private interface Task { OcrResult run() throws Exception; }
    private void submit(Task task, Consumer<OcrResult> success, Consumer<Exception> error) {
        if (closed) { error.accept(new IllegalStateException("OCR closed")); return; }
        try {
            executor.execute(() -> {
                try {
                    OcrResult value = task.run();
                    callback.post(() -> { if (!closed) success.accept(value); });
                } catch (Exception exception) {
                    callback.post(() -> { if (!closed) error.accept(exception); });
                } catch (LinkageError | OutOfMemoryError exception) {
                    callback.post(() -> { if (!closed) error.accept(new IllegalStateException("ONNX unavailable", exception)); });
                }
            });
        } catch (RejectedExecutionException e) { error.accept(e); }
    }
    @Override public void recognize(Bitmap bitmap, Consumer<OcrResult> success, Consumer<Exception> error) {
        if (v6Detection) submit(() -> detectAndRead(bitmap), success, error);
        else mlkit.recognize(bitmap, success, error);
    }
    @Override public void refine(Bitmap bitmap, Consumer<OcrResult> success, Consumer<Exception> error) {
        if (v6Detection) {
            submit(() -> detectAndRead(bitmap), success, error);
        } else {
            mlkit.recognize(bitmap, detected ->
                submit(() -> refineRecognized(bitmap, detected), success, error), error);
        }
    }
    private PpOcrRecognizer recognizer() throws Exception {
        if (recognition == null) recognition = new PpOcrRecognizer(context, v6Recognition);
        return recognition;
    }
    private OcrResult detectAndRead(Bitmap bitmap) throws Exception {
        if (detection == null) detection = new PpOcrTextDetector(context, true);
        List<PpOcrDbPostProcessor.Box> detected = detection.detect(bitmap);
        List<PpOcrDbPostProcessor.Box> candidates = new ArrayList<>();
        for (PpOcrDbPostProcessor.Box b : detected)
            if (b.width() >= .055f && b.height() >= .008f && b.height() <= .25f)
                candidates.add(b);
        // Bounding work controls latency but is common to both recognition options.
        candidates.sort(Comparator.comparingDouble((PpOcrDbPostProcessor.Box b) -> b.score * b.width()).reversed());
        List<OcrLine> lines = new ArrayList<>();
        double sum = 0;
        int n = 0;
        for (PpOcrDbPostProcessor.Box b : candidates) {
            if (closed || n >= 24) break;
            Bitmap crop = PpOcrTextDetector.crop(bitmap, b);
            try {
                PpOcrCtcDecoder.Reading read = recognizer().recognizeLine(crop);
                if (read.text == null || read.text.trim().isEmpty()) continue;
                double confidence = Math.max(0, Math.min(100, read.probability * 100));
                lines.add(new OcrLine(n++, read.text.trim(), confidence,
                        b.left, b.top, b.right, b.bottom));
                sum += confidence;
            } finally { crop.recycle(); }
        }
        lines.sort(Comparator.comparingDouble(OcrLine::getTop).thenComparingDouble(OcrLine::getLeft));
        List<String> texts = new ArrayList<>();
        for (OcrLine line : lines) texts.add(line.getText());
        return new OcrResult(String.join("\n", texts), lines.isEmpty() ? 0 : sum / lines.size(),
                lines, v6Recognition ? "v6det_v6rec" : "v6det_v5rec", -1);
    }
    private OcrResult refineRecognized(Bitmap bitmap, OcrResult detected) throws Exception {
        if (detected.getLines().isEmpty() || detected.getLines().size() > 8) return detected;
        List<OcrLine> output = new ArrayList<>();
        int accepted = 0;
        double total = 0;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        for (OcrLine row : detected.getLines()) {
            if (closed) break;
            double rowHeight = row.getHeight() * height;
            int left = Math.max(0, (int) Math.floor(row.getLeft()*width - Math.max(8,rowHeight*.5)));
            int right = Math.min(width, (int) Math.ceil(row.getRight()*width + Math.max(8,rowHeight*.5)));
            int top = Math.max(0, (int) Math.floor(row.getTop()*height - Math.max(2,rowHeight*.35)));
            int bottom = Math.min(height, (int) Math.ceil(row.getBottom()*height + Math.max(2,rowHeight*.35)));
            for (OcrLine other : detected.getLines()) {
                if (other == row) continue;
                if (other.getBottom() <= row.getTop())
                    top = Math.max(top, (int) Math.floor((other.getBottom()+row.getTop())*.5*height));
                if (other.getTop() >= row.getBottom())
                    bottom = Math.min(bottom, (int) Math.ceil((other.getTop()+row.getBottom())*.5*height));
            }
            String text = row.getText(); double probability = -1; boolean replaced = false;
            if (left < right && top < bottom) {
                Bitmap crop = Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top);
                try {
                    PpOcrCtcDecoder.Reading read = recognizer().recognizeLine(crop);
                    if (PpOcrRefinementPolicy.accepts(text, read.text, read.probability)) {
                        text = read.text; probability = read.probability;
                        replaced = true; total += probability; accepted++;
                    }
                } finally { if (crop != bitmap) crop.recycle(); }
            }
            output.add(new OcrLine(row.getBlockIndex(), text, row.getConfidence(),
                    row.getLeft(),row.getTop(),row.getRight(),row.getBottom(),
                    replaced ? java.util.Collections.emptyList() : row.getSeparatedParts(),
                    row.getGlyphHeight(),probability));
        }
        if (accepted == 0) return detected;
        List<String> texts = new ArrayList<>();
        for (OcrLine line : output) texts.add(line.getText());
        return new OcrResult(String.join("\n",texts), detected.getConfidence(),
                output,"ppocrv6_small",total/accepted);
    }
    @Override public void close() {
        if (closed) return; closed = true;
        if (mlkit != null) mlkit.close();
        executor.execute(() -> {
            if (recognition != null) recognition.close();
            if (detection != null) detection.close();
        });
        executor.shutdown();
    }
}
