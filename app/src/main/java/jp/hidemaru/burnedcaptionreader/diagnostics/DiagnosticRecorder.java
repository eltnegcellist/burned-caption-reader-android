package jp.hidemaru.burnedcaptionreader.diagnostics;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import jp.hidemaru.burnedcaptionreader.ocr.OcrLine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;

public final class DiagnosticRecorder {
    private static DiagnosticRecorder instance;
    public static synchronized DiagnosticRecorder get(Context context) {
        if (instance == null) instance = new DiagnosticRecorder(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final DiagnosticStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Semaphore tasks = new Semaphore(128);
    private final Semaphore images = new Semaphore(2);
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean recording;
    private volatile boolean exporting;
    private volatile String error = "";
    private long epoch;

    private DiagnosticRecorder(Context context) {
        this.context = context;
        store = new DiagnosticStore(new File(context.getNoBackupFilesDir(), "caption-diagnostics"),
                120_000, 64L * 1024 * 1024, 2000);
    }
    public boolean isRecording() { return recording; }
    public boolean isExporting() { return exporting; }
    public String status() {
        if (!error.isEmpty()) return error;
        return (exporting ? "ZIPを書き出し中" : recording ? "診断記録中" : "診断記録は停止中")
                + (dropped.get() > 0 ? "（負荷による省略 " + dropped.get() + " 件）" : "");
    }
    public synchronized void start() {
        if (exporting || recording) return;
        epoch++;
        error = "";
        dropped.set(0);
        worker.execute(() -> { try { store.clear(); } catch (Exception e) { fail(e); } });
        recording = true;
        event("session_start", "device", Build.MANUFACTURER + " " + Build.MODEL,
                "android", Build.VERSION.RELEASE, "app_version", version());
    }
    public synchronized void stop() {
        event("session_stop");
        recording = false;
    }
    public synchronized void clear() {
        if (exporting) return;
        stop(); epoch++;
        worker.execute(() -> { try { store.clear(); error = ""; dropped.set(0); } catch (Exception e) { fail(e); } });
    }
    public synchronized void event(String type, Object... pairs) { record(type, null, pairs); }
    public synchronized void image(String type, Bitmap bitmap, Object... pairs) { record(type, bitmap, pairs); }

    private void record(String type, Bitmap bitmap, Object... pairs) {
        if (!recording) return;
        if (!tasks.tryAcquire()) { dropped.incrementAndGet(); return; }
        Bitmap copy = null;
        boolean imagePermit = false;
        try {
            String id = String.format(Locale.ROOT, "%020d", sequence.incrementAndGet());
            long wall = System.currentTimeMillis();
            JSONObject data = new JSONObject();
            data.put("record_id", id).put("type", type).put("mono_ms", SystemClock.elapsedRealtime())
                    .put("wall_ms", wall).put("session", epoch);
            for (int i = 0; i + 1 < pairs.length; i += 2) data.put(String.valueOf(pairs[i]), pairs[i + 1]);
            if (bitmap != null) {
                data.put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
                if ((long) bitmap.getWidth() * bitmap.getHeight() <= 8_000_000 && images.tryAcquire()) {
                    imagePermit = true;
                    copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                }
                if (copy != null) data.put("image", "images/" + id + ".png").put("image_status", "saved");
                else { data.put("image_status", "omitted_under_load"); dropped.incrementAndGet(); }
            }
            Bitmap owned = copy;
            boolean releaseImage = imagePermit;
            worker.execute(() -> {
                try {
                    byte[] png = null;
                    if (owned != null) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        if (!owned.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new java.io.IOException("PNG保存失敗");
                        png = bytes.toByteArray();
                    }
                    store.write(id, wall, data.toString(), png);
                } catch (Exception e) { fail(e); }
                finally {
                    if (owned != null) owned.recycle();
                    if (releaseImage) images.release();
                    tasks.release();
                }
            });
        } catch (Exception | OutOfMemoryError e) {
            if (copy != null) copy.recycle();
            if (imagePermit) images.release();
            tasks.release();
            dropped.incrementAndGet();
            error = "診断記録を一部省略しました: " + e.getClass().getSimpleName();
        }
    }

    public void ocr(String stage, long frameId, int trackId, OcrResult result) {
        if (!recording) return;
        JSONArray rows = new JSONArray();
        try {
            for (OcrLine row : result.getLines()) rows.put(new JSONObject()
                    .put("text", row.getText()).put("confidence", finite(row.getConfidence()))
                    .put("block", row.getBlockIndex()).put("left", row.getLeft()).put("top", row.getTop())
                    .put("right", row.getRight()).put("bottom", row.getBottom()));
            event(stage, "frame_id", frameId, "track_id", trackId, "text", result.getText(),
                    "confidence", finite(result.getConfidence()), "rows", rows);
        } catch (Exception e) { dropped.incrementAndGet(); }
    }
    private Object finite(double number) { return Double.isFinite(number) ? number : JSONObject.NULL; }
    private String version() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception e) { return "unknown"; }
    }
    private void fail(Exception e) {
        recording = false;
        error = "診断データの保存に失敗: " + e.getMessage();
    }
    public synchronized void export(Uri uri, Consumer<String> completion) {
        if (exporting) { completion.accept("書き出し中です"); return; }
        stop(); exporting = true;
        worker.execute(() -> {
            String result = null;
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new java.io.IOException("保存先を開けません");
                String manifest = new JSONObject().put("schema_version", 1).put("app_version", version())
                        .put("dropped_records_or_images", dropped.get()).put("last_error", error)
                        .put("max_age_ms", 120000).put("max_bytes", 64L * 1024 * 1024)
                        .put("exported_wall_ms", System.currentTimeMillis()).toString();
                store.export(output, manifest);
            } catch (Exception e) { result = "書き出し失敗: " + e.getMessage(); }
            exporting = false;
            String message = result;
            new Handler(Looper.getMainLooper()).post(() -> completion.accept(message));
        });
    }
}
