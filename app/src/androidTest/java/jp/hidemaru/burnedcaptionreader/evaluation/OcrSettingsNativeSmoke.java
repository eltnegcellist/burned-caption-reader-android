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
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.Spinner;
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

/** Exercises real settings UI, persistence, model dispatch, and the player idle boundary. */
final class OcrSettingsNativeSmoke {
    static JSONObject run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences stored = context.getSharedPreferences(AppPreferences.PREFERENCES_FILE, 0);
        String original = stored.getString(AppPreferences.OCR_MODE, null);
        AppPreferences preferences = new AppPreferences(context);
        Activity settings = null;
        SharedPlayerActivity player = null;
        Bitmap bitmap = sample();
        JSONObject output = new JSONObject();
        try {
            stored.edit().remove(AppPreferences.OCR_MODE).commit();
            check(preferences.getOcrMode() == CaptionOcrMode.HYBRID, "Existing default changed");
            stored.edit().putString(AppPreferences.OCR_MODE, "unknown-test-value").commit();
            check(preferences.getOcrMode() == CaptionOcrMode.HYBRID, "Invalid value did not fall back");
            stored.edit().remove(AppPreferences.OCR_MODE).commit();
            settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity settingsView = settings;
            instrumentation.waitForIdleSync();
            Spinner choice = settings.findViewById(android.R.id.content).findViewWithTag("ocr_mode_selector");
            check(choice != null && choice.getSelectedItemPosition() == CaptionOcrMode.HYBRID.ordinal(), "Default UI selection");
            JSONArray modes = new JSONArray();
            for (CaptionOcrMode mode : CaptionOcrMode.values()) {
                instrumentation.runOnMainSync(() -> choice.setSelection(mode.ordinal()));
                long deadline = SystemClock.elapsedRealtime() + 3000;
                while (preferences.getOcrMode() != mode && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30);
                check(new AppPreferences(context).getOcrMode() == mode, "UI selection not persisted: " + mode);
                try (OcrEngine engine = CaptionOcrEngines.create(context, preferences.getOcrMode())) {
                    OcrResult raw = read(engine, bitmap, false);
                    OcrResult refined = read(engine, bitmap, true);
                    check(raw.getText().contains("字幕") && refined.getText().contains("字幕"), "OCR result empty: " + mode);
                    if (mode == CaptionOcrMode.ML_KIT) {
                        check(engine instanceof MlKitJapaneseOcrEngine && "mlkit".equals(refined.getBackend()), "ML Kit unexpectedly uses PP");
                    } else {
                        check("ppocrv5".equals(refined.getBackend()), "PP recognition did not execute");
                        String status = ((HybridCaptionOcrEngine) engine).getDetectionStatus();
                        check(mode == CaptionOcrMode.PP_DETECTION ? status.startsWith("native:") : "not_run".equals(status), "Detection dispatch mismatch");
                    }
                    modes.put(new JSONObject().put("mode", mode.storedValue).put("raw", raw.getText())
                            .put("refined", refined.getText()).put("raw_backend", raw.getBackend())
                            .put("refinement_backend", refined.getBackend()).put("engine", engine.getClass().getSimpleName())
                            .put("detector_status", engine instanceof HybridCaptionOcrEngine
                                    ? ((HybridCaptionOcrEngine) engine).getDetectionStatus() : "mlkit_only"));
                }
            }
            instrumentation.runOnMainSync(settingsView::finish);
            settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            Spinner recreated = settings.findViewById(android.R.id.content).findViewWithTag("ocr_mode_selector");
            check(recreated.getSelectedItemPosition() == CaptionOcrMode.PP_DETECTION.ordinal(), "Selection lost after activity recreation");
            final Activity recreatedView = settings;
            instrumentation.runOnMainSync(recreatedView::finish);
            settings = null;

            player = (SharedPlayerActivity) instrumentation.startActivitySync(new Intent(context, SharedPlayerActivity.class)
                    .putExtra(SharedPlayerActivity.EXTRA_VIDEO_ID, "X115n3Sn5pc").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final SharedPlayerActivity playerView = player;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try {
                    ((WebView) field(playerView, "player").get(playerView)).stopLoading();
                    field(playerView, "sampling").setBoolean(playerView, false);
                    ((Handler) field(playerView, "main").get(playerView)).removeCallbacks((Runnable) field(playerView, "sampler").get(playerView));
                    Method apply = SharedPlayerActivity.class.getDeclaredMethod("applyOcrModeIfIdle");
                    apply.setAccessible(true);
                    OcrEngine previous = (OcrEngine) field(playerView, "ocr").get(playerView);
                    check(field(playerView, "ocrMode").get(playerView) == CaptionOcrMode.PP_DETECTION, "Player startup ignores preference");
                    field(playerView, "busy").setBoolean(playerView, true);
                    preferences.setOcrMode(CaptionOcrMode.ML_KIT);
                    apply.invoke(playerView);
                    check(field(playerView, "ocr").get(playerView) == previous, "Closed engine during active frame");
                    check(!field(previous, "closed").getBoolean(previous), "Busy engine closed");
                    field(playerView, "busy").setBoolean(playerView, false);
                    apply.invoke(playerView);
                    check(field(playerView, "ocr").get(playerView) instanceof MlKitJapaneseOcrEngine, "Idle switch failed");
                    check(field(previous, "closed").getBoolean(previous), "Old engine not closed");
                    for (CaptionOcrMode mode : CaptionOcrMode.values()) {
                        preferences.setOcrMode(mode);
                        apply.invoke(playerView);
                        check(field(playerView, "ocrMode").get(playerView) == mode, "Player mode mismatch");
                    }
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new IllegalStateException("Player switching failed", failure.get());
            output.put("modes", modes).put("default_and_invalid_preserve_existing", true)
                    .put("settings_persistence_and_recreation", true).put("player_busy_deferral", true)
                    .put("player_old_engine_closed", true).put("player_live_modes", 3)
                    .put("scope", "Native models and real settings/player lifecycle; synthetic captions only, not physical phone accuracy or audible speech");
            return output;
        } finally {
            if (settings != null) { final Activity ending = settings; instrumentation.runOnMainSync(ending::finish); }
            if (player != null) { final Activity ending = player; instrumentation.runOnMainSync(ending::finish); }
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

    private static void check(boolean passed, String reason) {
        if (!passed) throw new IllegalStateException(reason);
    }

    private static OcrResult read(OcrEngine engine, Bitmap bitmap, boolean refine) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<OcrResult> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        java.util.function.Consumer<OcrResult> success = value -> { result.set(value); done.countDown(); };
        java.util.function.Consumer<Exception> error = value -> { failure.set(value); done.countDown(); };
        if (refine) engine.refine(bitmap, success, error); else engine.recognize(bitmap, success, error);
        if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("OCR choice timeout");
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static Bitmap sample() {
        Bitmap bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.DKGRAY);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(52);
        String[] texts = {"動画の字幕です", "文字を読み取ります", "最後の字幕です"};
        for (int i = 0; i < texts.length; i++) {
            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(8);
            canvas.drawText(texts[i], 100, 100 + 130 * i, paint);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
            canvas.drawText(texts[i], 100, 100 + 130 * i, paint);
        }
        return bitmap;
    }
}
