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
            if ("capture-full".equals(arguments.getString("mode")) || "capture-window".equals(arguments.getString("mode"))) {
                captureFull(report); return;
            }
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
    private void captureFull(Bundle report) throws Exception {
        String video = arguments.getString("video_id", "");
        if (!video.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid video ID");
        int limit = Math.max(120, Math.min(3600, Integer.parseInt(arguments.getString("max_seconds", "1800"))));
        File root = new File(getTargetContext().getFilesDir(), "full-evaluation"); root.mkdirs();
        for (File f : root.listFiles()) Files.delete(f.toPath());
        DiagnosticRecorder recorder = DiagnosticRecorder.get(getTargetContext());
        recorder.startFullEvaluation();
        getTargetContext().startActivity(new Intent(Intent.ACTION_SEND).setClassName(getTargetContext(),
                "jp.hidemaru.burnedcaptionreader.MainActivity").setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,"https://www.youtube.com/watch?v=" + video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline = SystemClock.elapsedRealtime() + limit * 1000L;
        int windowSeconds = "capture-window".equals(arguments.getString("mode"))
                ? Math.max(300, Math.min(1800, Integer.parseInt(arguments.getString("window_seconds", "300")))) : 0;
        int windowStartSeconds = Math.max(0, Math.min(86400,
                Integer.parseInt(arguments.getString("start_seconds", "0"))));
        JSONObject last = new JSONObject(); boolean ended = false; boolean windowComplete = false;
        while (SystemClock.elapsedRealtime() < deadline && recorder.isRecording()) {
            Thread.sleep(1000);
            File state = new File(root, "state.json");
            if (!state.isFile()) continue;
            last = new JSONObject(new String(Files.readAllBytes(state.toPath()),StandardCharsets.UTF_8));
            double target = last.optDouble("target_duration_ms");
            if (windowSeconds > 0 && last.optBoolean("evaluation_started")
                    && last.optLong("window_start_ms") == windowStartSeconds * 1000L
                    && !last.optBoolean("ad_visible") && last.optLong("media_ms") >= (windowStartSeconds + windowSeconds) * 1000L
                    && Math.abs(last.optLong("duration_ms") - target) < 2000) { windowComplete = true; break; }
            JSONObject terminal = last.optJSONObject("ended_event");
            if (last.optBoolean("evaluation_started") && terminal != null && target > 60000
                    && Math.abs(terminal.optLong("duration_ms")-target)<2000
                    && terminal.optLong("media_ms")>=target-1000) { ended=true; break; }
            if (last.optBoolean("evaluation_started") && last.optBoolean("ended")
                    && !last.optBoolean("ad_visible") && target > 60000
                    && Math.abs(last.optLong("duration_ms")-target) < 2000
                    && last.optLong("media_ms") >= target-1000) { ended = true; break; }
        }
        boolean completed = windowSeconds > 0 ? windowComplete : ended;
        recorder.event("evaluation_end", "completed", completed, "scope", windowSeconds > 0 ? "window" : "full", "last_state", last,
                "dropped",recorder.droppedCount(),"recorder_status",recorder.status());
        recorder.stop();
        awaitRecorderStop();
        JSONObject summary = new JSONObject().put("video_id",video).put("ended",ended)
                .put("scope", windowSeconds > 0 ? "window" : "full").put("window_seconds",windowSeconds)
                .put("window_start_seconds",windowStartSeconds).put("completed",completed)
                .put("last_state",last).put("dropped",recorder.droppedCount()).put("recorder_status",recorder.status());
        Files.write(new File(root,"summary.json").toPath(),summary.toString(2).getBytes(StandardCharsets.UTF_8));
        report.putString("stream", "Evaluation capture " + (completed ? "complete" : "INCOMPLETE") + ": " + video + "\n");
        finish(completed && recorder.droppedCount()==0 ? -1 : 1,report);
    }
    private void awaitRecorderStop() throws Exception {
        File root = new File(getTargetContext().getNoBackupFilesDir(),"caption-diagnostics");
        long deadline = SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline) {
            File[] files=root.listFiles((d,n)->n.endsWith(".json"));
            if(files!=null) {
                java.util.Arrays.sort(files,java.util.Comparator.comparing(File::getName).reversed());
                for(int i=0;i<Math.min(16,files.length);i++)
                    if(new String(Files.readAllBytes(files[i].toPath()),StandardCharsets.UTF_8).contains("\"type\":\"session_stop\"")) return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Full evaluation diagnostic flush timeout");
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
