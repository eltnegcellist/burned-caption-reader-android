package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hidemaru.burnedcaptionreader.ocr.MlKitJapaneseOcrEngine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import jp.hidemaru.burnedcaptionreader.subtitle.Similarity;

/** Actual Android ML Kit image benchmark; intentionally does not simulate speech. */
public final class ImageEvaluationInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            File root = new File(getTargetContext().getFilesDir(), "image-evaluation");
            File manifest = new File(root, "dataset.json");
            JSONObject dataset = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            boolean synthetic = "synthetic_render_spec".equals(dataset.optString("ground_truth_source"))
                    && "synthetic".equals(dataset.optString("source"));
            if (!dataset.optBoolean("human_verified") && !synthetic)
                throw new IllegalArgumentException("Human ground truth required for real images");
            JSONArray frames = dataset.getJSONArray("frames");
            if (frames.length() == 0) throw new IllegalArgumentException("Empty dataset");
            JSONArray results = new JSONArray();
            long previous = -1;
            int edits = 0, characters = 0;
            MlKitJapaneseOcrEngine engine = new MlKitJapaneseOcrEngine();
            try {
                for (int i = 0; i < frames.length(); i++) {
                    JSONObject row = frames.getJSONObject(i);
                    long timestamp = row.getLong("mono_ms");
                    if (timestamp < 0 || timestamp < previous) throw new IllegalArgumentException("Unordered timestamp");
                    File source = new File(root, row.getString("image")).getCanonicalFile();
                    if (!source.toPath().startsWith(root.getCanonicalFile().toPath())) throw new IllegalArgumentException("Image outside dataset");
                    Bitmap bitmap = BitmapFactory.decodeFile(source.toString());
                    if (bitmap == null) throw new IllegalArgumentException("Unreadable image: " + source.getName());
                    OcrResult recognized;
                    long started = SystemClock.elapsedRealtime();
                    recognized = recognize(engine, bitmap);
                    bitmap.recycle();
                    String expected = row.getString("expected_text");
                    String actual = recognized.getText();
                    int distance = Similarity.levenshteinDistance(expected, actual);
                    int length = expected.codePointCount(0, expected.length());
                    edits += distance; characters += length;
                    results.put(new JSONObject().put("id", row.getString("id"))
                            .put("mono_ms", timestamp).put("gap_ms", previous < 0 ? JSONObject.NULL : timestamp - previous)
                            .put("expected_text", expected).put("ocr_text", actual)
                            .put("edit_distance", distance).put("reference_characters", length)
                            .put("ocr_processing_ms", SystemClock.elapsedRealtime() - started));
                    previous = timestamp;
                }
            } finally { engine.close(); }
            JSONObject output = new JSONObject().put("schema_version", 1).put("scope", "raw_image_ocr_only")
                    .put("label", arguments.getString("label", "unlabelled"))
                    .put("dataset", dataset.getString("id")).put("source", dataset.getString("source"))
                    .put("frames", results).put("edit_distance", edits).put("reference_characters", characters)
                    .put("cer", characters == 0 ? JSONObject.NULL : (double) edits / characters);
            Files.write(new File(root, "results.json").toPath(), output.toString(2).getBytes(StandardCharsets.UTF_8));
            report.putString("stream", "Image evaluation complete: frames=" + frames.length() + "\n");
            finish(-1, report);
        } catch (Exception error) {
            report.putString("stream", "Image evaluation FAILED: " + error + "\n");
            finish(1, report);
        }
    }
    private OcrResult recognize(MlKitJapaneseOcrEngine engine, Bitmap bitmap) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<OcrResult> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        engine.recognize(bitmap, value -> { result.set(value); done.countDown(); },
                error -> { failure.set(error); done.countDown(); });
        // On timeout, the process exits via finish; do not recycle a bitmap still in use.
        if (!done.await(60, TimeUnit.SECONDS)) {
            Bundle report = new Bundle(); report.putString("stream", "Image evaluation FAILED: OCR timeout\n");
            finish(1, report);
            throw new IllegalStateException("OCR timeout");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
}
