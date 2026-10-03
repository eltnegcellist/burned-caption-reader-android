package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
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
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleNormalizer;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticRecorder;

/** Android ML Kit benchmark and timestamp replay; never measures audible speech. */
public final class ImageEvaluationInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            if ("capture".equals(arguments.getString("mode"))) {
                captureLive(report);
                return;
            }
            File root = new File(getTargetContext().getFilesDir(), "image-evaluation");
            File manifest = new File(root, "dataset.json");
            JSONObject dataset = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            boolean synthetic = "synthetic_render_spec".equals(dataset.optString("ground_truth_source"))
                    && "synthetic".equals(dataset.optString("source"));
            boolean visualReview = "codex_visual_review".equals(dataset.optString("ground_truth_source"))
                    && dataset.optBoolean("reference_verified_from_pixels");
            if (!dataset.optBoolean("human_verified") && !synthetic && !visualReview)
                throw new IllegalArgumentException("Independent image review required");
            JSONArray frames = dataset.getJSONArray("frames");
            if (frames.length() == 0) throw new IllegalArgumentException("Empty dataset");
            JSONArray results = new JSONArray();
            long previous = -1;
            int edits = 0, characters = 0;
            MlKitJapaneseOcrEngine engine = new MlKitJapaneseOcrEngine();
            boolean playerMode = "player-replay".equals(arguments.getString("mode"));
            PlayerFrameReplay replay = new PlayerFrameReplay();
            try {
                for (int i = 0; i < frames.length(); i++) {
                    JSONObject row = frames.getJSONObject(i);
                    long timestamp = row.getLong("mono_ms");
                    if (timestamp < 0 || timestamp < previous) throw new IllegalArgumentException("Unordered timestamp");
                    File source = new File(root, row.getString("image")).getCanonicalFile();
                    if (!source.toPath().startsWith(root.getCanonicalFile().toPath())) throw new IllegalArgumentException("Image outside dataset");
                    Bitmap bitmap = BitmapFactory.decodeFile(source.toString());
                    if (bitmap == null) throw new IllegalArgumentException("Unreadable image: " + source.getName());
                    long started = SystemClock.elapsedRealtime();
                    JSONObject stages;
                    if (playerMode) stages = replay.process(bitmap, timestamp, input -> recognize(engine, input));
                    else stages = new JSONObject().put("ocr_text", recognize(engine, bitmap).getText());
                    bitmap.recycle();
                    String expected = row.getString("expected_text");
                    String actual = stages.getString("ocr_text");
                    String referenceKey = playerMode ? SubtitleNormalizer.comparisonKey(expected) : expected;
                    String actualKey = playerMode ? SubtitleNormalizer.comparisonKey(actual) : actual;
                    int distance = Similarity.levenshteinDistance(referenceKey, actualKey);
                    int length = referenceKey.codePointCount(0, referenceKey.length());
                    edits += distance; characters += length;
                    results.put(new JSONObject().put("id", row.getString("id"))
                            .put("mono_ms", timestamp).put("gap_ms", previous < 0 ? JSONObject.NULL : timestamp - previous)
                            .put("expected_text", expected).put("ocr_text", actual).put("stages", stages)
                            .put("edit_distance", distance).put("reference_characters", length)
                            .put("ocr_processing_ms", SystemClock.elapsedRealtime() - started));
                    previous = timestamp;
                }
            } finally { engine.close(); }
            replay.finish(previous);
            JSONObject output = new JSONObject().put("schema_version", 1)
                    .put("scope", playerMode ? "player_components_immediate_tts" : "raw_image_ocr_only")
                    .put("metric", playerMode ? "caption_comparison_key_cer" : "raw_codepoint_cer")
                    .put("ground_truth_source", dataset.optString("ground_truth_source", "human_review"))
                    .put("human_verified", dataset.optBoolean("human_verified"))
                    .put("speech", playerMode ? replay.speech : new JSONArray())
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
    private void captureLive(Bundle report) throws Exception {
        String video = arguments.getString("video_id", "");
        if (!video.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid video ID");
        int seconds = Math.max(20, Math.min(90, Integer.parseInt(arguments.getString("seconds", "60"))));
        DiagnosticRecorder recorder = DiagnosticRecorder.get(getTargetContext());
        recorder.start();
        Intent launch = new Intent(Intent.ACTION_SEND).setClassName(getTargetContext(),
                "jp.hidemaru.burnedcaptionreader.MainActivity").setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(launch);
        Thread.sleep(seconds * 1000L);
        recorder.stop();
        File directory = new File(getTargetContext().getNoBackupFilesDir(), "caption-diagnostics");
        // session_stop is queued after all image jobs. Its atomic JSON publication
        // confirms that the recorder worker finished preceding writes before exit.
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean flushed = false;
        while (!flushed && SystemClock.elapsedRealtime() < deadline) {
            File[] records = directory.listFiles((dir, name) -> name.endsWith(".json"));
            if (records != null) for (File file : records) {
                if (new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("\"type\":\"session_stop\"")) {
                    flushed = true; break;
                }
            }
            if (!flushed) Thread.sleep(100);
        }
        if (!flushed) throw new IllegalStateException("Diagnostic flush timeout");
        report.putString("stream", "Capture complete: " + video + " seconds=" + seconds + "\n");
        finish(-1, report);
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
