package jp.hidemaru.burnedcaptionreader.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** ML Kit locates lines; PP-OCR re-reads bounded subtitle lines on a single worker. */
public final class HybridCaptionOcrEngine implements OcrEngine {
    private final OcrEngine detector;
    private final Context context;
    private final Handler callbacks = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PpOcrRecognizer ppocr;
    private boolean unavailable;
    private volatile boolean closed;

    public HybridCaptionOcrEngine(Context context) { this(context, new MlKitJapaneseOcrEngine()); }
    public HybridCaptionOcrEngine(Context context, OcrEngine detector) {
        this.context = context.getApplicationContext(); this.detector = detector;
    }
    @Override public void recognize(Bitmap bitmap, Consumer<OcrResult> success, Consumer<Exception> error) {
        if (closed) { error.accept(new IllegalStateException("OCR closed")); return; }
        detector.recognize(bitmap, success, error);
    }
    @Override public void refine(Bitmap bitmap, Consumer<OcrResult> success, Consumer<Exception> error) {
        recognize(bitmap, detected -> {
            if (closed) { error.accept(new IllegalStateException("OCR closed")); return; }
            try {
                worker.execute(() -> {
                    OcrResult result;
                    try { result = refineDetected(bitmap, detected); }
                    catch (Exception | LinkageError | OutOfMemoryError failure) { result = detected; }
                    OcrResult completed = result;
                    callbacks.post(() -> { if (closed) error.accept(new IllegalStateException("OCR closed")); else success.accept(completed); });
                });
            } catch (RejectedExecutionException closing) { error.accept(closing); }
        }, error);
    }
    private OcrResult refineDetected(Bitmap bitmap, OcrResult detected) throws Exception {
        if (closed || unavailable || detected.getLines().isEmpty() || detected.getLines().size() > 8) return detected;
        if (ppocr == null) {
            try { ppocr = new PpOcrRecognizer(context); }
            catch (Exception | LinkageError | OutOfMemoryError failure) { unavailable = true; return detected; }
        }
        List<OcrLine> rows = new ArrayList<>(); List<String> texts = new ArrayList<>();
        int accepted = 0; double probability = 0;
        for (OcrLine row : detected.getLines()) {
            if (closed) return detected;
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            // Recognition needs surrounding pixels, including outlines and missed edge glyphs.
            // ML Kit line bounds are tighter than the padded images used by PP-OCR.
            double rowHeight = row.getHeight() * h;
            double horizontalPad = Math.max(8, rowHeight * .50);
            int left = Math.max(0, (int) Math.floor(row.getLeft() * w - horizontalPad));
            int right = Math.min(w, (int) Math.ceil(row.getRight() * w + horizontalPad));
            double pad = Math.max(2, rowHeight * .35);
            int top = Math.max(0, (int) Math.floor(row.getTop() * h - pad));
            int bottom = Math.min(h, (int) Math.ceil(row.getBottom() * h + pad));
            // Neighboring rows bound padding so another subtitle cannot enter this line input.
            for (OcrLine other : detected.getLines()) {
                if (other == row) continue;
                if (other.getBottom() <= row.getTop()) top = Math.max(top, (int) Math.floor((other.getBottom() + row.getTop()) * .5 * h));
                if (other.getTop() >= row.getBottom()) bottom = Math.min(bottom, (int) Math.ceil((other.getTop() + row.getBottom()) * .5 * h));
            }
            String text = row.getText();
            boolean replaced = false; double rowProbability = -1;
            if (right > left && bottom > top) {
                Bitmap crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
                try {
                    PpOcrCtcDecoder.Reading reading = ppocr.recognizeLine(crop);
                    if (PpOcrRefinementPolicy.accepts(text, reading.text, reading.probability)) {
                        text = reading.text; replaced = true; rowProbability = reading.probability; accepted++; probability += reading.probability;
                    }
                } finally { if (crop != bitmap) crop.recycle(); }
            }
            texts.add(text);
            // Confidence remains ML Kit's spatial/detection evidence for legacy voting.
            // PP's probability is recorded separately, not substituted into that scale.
            rows.add(new OcrLine(row.getBlockIndex(), text, row.getConfidence(), row.getLeft(), row.getTop(),
                    row.getRight(), row.getBottom(), replaced ? Collections.emptyList() : row.getSeparatedParts(), row.getGlyphHeight(), rowProbability));
        }
        return accepted == 0 ? detected : new OcrResult(String.join("\n", texts), detected.getConfidence(), rows,
                "ppocrv5", probability / accepted);
    }
    @Override public void close() {
        if (closed) return; closed = true; detector.close();
        worker.execute(() -> { if (ppocr != null) ppocr.close(); }); worker.shutdown();
    }
}
