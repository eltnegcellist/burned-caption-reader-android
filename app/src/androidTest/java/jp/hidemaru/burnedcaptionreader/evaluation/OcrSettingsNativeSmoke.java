package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hidemaru.burnedcaptionreader.AppPreferences;
import jp.hidemaru.burnedcaptionreader.SettingsActivity;
import jp.hidemaru.burnedcaptionreader.SharedPlayerActivity;
import jp.hidemaru.burnedcaptionreader.ocr.*;

/**
 * User-facing OCR is fixed at D even for users with saved A/B/C settings.
 * Four explicit backends are still exercised here to retain regression coverage
 * for independent offline comparisons; none can be selected by the app user.
 */
final class OcrSettingsNativeSmoke {
    static JSONObject run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences stored = context.getSharedPreferences(AppPreferences.PREFERENCES_FILE, 0);
        String original = stored.getString(AppPreferences.OCR_MODE, null);
        AppPreferences preferences = new AppPreferences(context);
        Activity settings = null;
        SharedPlayerActivity player = null;
        Bitmap bitmap = sample();
        try {
            for (String legacy : new String[]{null, "hybrid", "v6_rec", "v6_det", "v6_both",
                    "mlkit", "pp_detection", "unknown-value"}) {
                if (legacy == null) stored.edit().remove(AppPreferences.OCR_MODE).commit();
                else stored.edit().putString(AppPreferences.OCR_MODE, legacy).commit();
                check(preferences.getOcrMode() == CaptionOcrMode.BOTH_V6,
                        "Persisted OCR value escaped D: " + legacy);
                check(CaptionOcrMode.BOTH_V6.storedValue.equals(stored.getString(AppPreferences.OCR_MODE, null)),
                        "OCR setting was not migrated to D: " + legacy);
                check(new AppPreferences(context).getOcrMode() == CaptionOcrMode.BOTH_V6,
                        "D selection lost on recreation");
            }
            preferences.setOcrMode(CaptionOcrMode.BASELINE);
            check(preferences.getOcrMode() == CaptionOcrMode.BOTH_V6,
                    "Deprecated writer reverted D");
            settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            check(settings.findViewById(android.R.id.content)
                    .findViewWithTag("ocr_detector_v6") == null, "Detection switch remains visible");
            check(settings.findViewById(android.R.id.content)
                    .findViewWithTag("ocr_recognizer_v6") == null, "Recognition switch remains visible");

            JSONArray explicitDiagnostics = new JSONArray();
            for (CaptionOcrMode mode : CaptionOcrMode.values()) {
                check(preferences.getOcrMode() == CaptionOcrMode.BOTH_V6,
                        "User preferences became configurable");
                try (OcrEngine engine = CaptionOcrEngines.create(context, mode)) {
                    OcrResult raw = read(engine, bitmap, false);
                    OcrResult refined = read(engine, bitmap, true);
                    check(raw != null && refined != null, "Diagnostic OCR failed: " + mode);
                    if (mode.v6Detection) {
                        check(raw.getBackend().startsWith("v6det_"), "v6 path unexpectedly used ML Kit");
                        check(field(engine, "mlkit").get(engine) == null, "v6 path instantiated ML Kit");
                        check(((V6ComparisonOcrEngine) engine).getMetrics().get("mlkit_calls") == 0,
                                "v6 detection executed ML Kit");
                    } else check("mlkit".equals(raw.getBackend()), "Legacy diagnostic path failed");
                    explicitDiagnostics.put(new JSONObject()
                            .put("mode", mode.storedValue)
                            .put("raw_backend", raw.getBackend())
                            .put("refined_backend", refined.getBackend()));
                }
            }
            Activity oldSettings = settings;
            instrumentation.runOnMainSync(oldSettings::finish);
            settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            check(settings.findViewById(android.R.id.content)
                    .findViewWithTag("ocr_detector_v6") == null, "Detector control returned on recreation");
            check(settings.findViewById(android.R.id.content)
                    .findViewWithTag("ocr_recognizer_v6") == null, "Recognizer control returned on recreation");
            Activity newSettings = settings;
            instrumentation.runOnMainSync(newSettings::finish);
            settings = null;

            player = (SharedPlayerActivity) instrumentation.startActivitySync(
                    new Intent(context, SharedPlayerActivity.class)
                            .putExtra(SharedPlayerActivity.EXTRA_VIDEO_ID, "X115n3Sn5pc")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SharedPlayerActivity current = player;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try {
                    ((WebView)field(current,"player").get(current)).stopLoading();
                    field(current,"sampling").setBoolean(current, false);
                    ((Handler)field(current,"main").get(current)).removeCallbacks(
                            (Runnable)field(current,"sampler").get(current));
                    Method apply = SharedPlayerActivity.class.getDeclaredMethod("applyOcrModeIfIdle");
                    apply.setAccessible(true);
                    check(field(current,"ocrMode").get(current) == CaptionOcrMode.BOTH_V6,
                            "Player started with non-v6 OCR");
                    OcrEngine existing = (OcrEngine)field(current,"ocr").get(current);
                    check(existing instanceof V6ComparisonOcrEngine,
                            "Production OCR engine not v6");
                    check(field(existing,"mlkit").get(existing) == null,
                            "Production v6 engine instantiated ML Kit");
                    field(current,"busy").setBoolean(current,true);
                    preferences.setOcrMode(CaptionOcrMode.BASELINE);
                    apply.invoke(current);
                    check(field(current,"ocr").get(current) == existing,
                            "OCR changed during active frame");
                    field(current,"busy").setBoolean(current,false);
                    apply.invoke(current);
                    check(field(current,"ocrMode").get(current) == CaptionOcrMode.BOTH_V6,
                            "Old preference restored after idle");
                    check(field(current,"ocr").get(current) == existing,
                            "OCR switched despite fixed production mode");
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new IllegalStateException("Fixed OCR lifecycle test failed", failure.get());
            return new JSONObject().put("user_mode","v6_both")
                    .put("model_selection_controls",0)
                    .put("legacy_mode_migration",true)
                    .put("explicit_diagnostics", explicitDiagnostics)
                    .put("scope","Emulator/bitmap and UI smoke; Galaxy S25/audio latency not measured");
        } finally {
            if (settings != null) {
                Activity closing = settings;
                instrumentation.runOnMainSync(closing::finish);
            }
            if (player != null) {
                Activity closing = player;
                instrumentation.runOnMainSync(closing::finish);
            }
            if (original == null) stored.edit().remove(AppPreferences.OCR_MODE).commit();
            else stored.edit().putString(AppPreferences.OCR_MODE, original).commit();
            bitmap.recycle();
        }
    }
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static OcrResult read(OcrEngine engine, Bitmap bitmap, boolean refine) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<OcrResult> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        java.util.function.Consumer<OcrResult> ok = value -> { result.set(value); done.countDown(); };
        java.util.function.Consumer<Exception> bad = value -> { failure.set(value); done.countDown(); };
        if (refine) engine.refine(bitmap, ok, bad); else engine.recognize(bitmap, ok, bad);
        if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("OCR timeout");
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private static Bitmap sample() {
        Bitmap image = Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.DKGRAY);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        paint.setTextSize(52);
        String[] content = {"動画の字幕です","文字を読み取ります","最後の字幕です"};
        for (int i=0;i<content.length;i++) {
            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(8);
            canvas.drawText(content[i],100,100+130*i,paint);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
            canvas.drawText(content[i],100,100+130*i,paint);
        }
        return image;
    }
}
