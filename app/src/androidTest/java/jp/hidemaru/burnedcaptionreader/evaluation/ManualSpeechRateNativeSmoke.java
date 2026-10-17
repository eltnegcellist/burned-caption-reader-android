package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import jp.hidemaru.burnedcaptionreader.AppPreferences;
import jp.hidemaru.burnedcaptionreader.SettingsActivity;
import jp.hidemaru.burnedcaptionreader.SharedPlayerActivity;
import jp.hidemaru.burnedcaptionreader.subtitle.RowSpeechLedger;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleEvent;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleSpeechOrderBuffer;
import jp.hidemaru.burnedcaptionreader.tts.SpeechEngine;

/** Upgrade/UI/player regression: former auto flags must never override manual rates. */
final class ManualSpeechRateNativeSmoke {
    private static final class RecordingSpeaker implements SpeechEngine {
        final List<Float> rates = new ArrayList<>();
        int stops;
        public void speak(String text, Mode mode, float rate) { rates.add(rate); }
        public void speak(String text, Mode mode, float rate, Completion completion) {
            rates.add(rate);
            completion.onStart();
            completion.onDone();
        }
        public void stop() { stops++; }
        public void close() {}
    }

    static JSONObject run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences stored = context.getSharedPreferences(AppPreferences.PREFERENCES_FILE, 0);
        Map<String, ?> original = stored.getAll();
        Activity settings = null;
        SharedPlayerActivity player = null;
        try {
            // A previously enabled feature must not change the default or reset
            // a saved speed, including values below the former adaptive minimum.
            stored.edit().putBoolean("auto_speech_rate", true).remove("speech_rate").commit();
            check(new AppPreferences(context).getSpeechRate() == 1.60f, "Default changed");
            new AppPreferences(context).setSpeechRate(0.75f);
            check(new AppPreferences(context).getSpeechRate() == 0.75f, "Manual speed lost on recreation");

            settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity currentSettings = settings;
            instrumentation.runOnMainSync(() -> checkSettings(currentSettings.findViewById(android.R.id.content), 25));
            instrumentation.runOnMainSync(currentSettings::finish);
            settings = null;

            player = (SharedPlayerActivity) instrumentation.startActivitySync(
                    new Intent(context, SharedPlayerActivity.class)
                            .putExtra(SharedPlayerActivity.EXTRA_VIDEO_ID, "X115n3Sn5pc")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SharedPlayerActivity current = player;
            RecordingSpeaker recording = new RecordingSpeaker();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try {
                    ((WebView) field(current, "player").get(current)).stopLoading();
                    field(current, "sampling").setBoolean(current, false);
                    ((Handler) field(current, "main").get(current)).removeCallbacks(
                            (Runnable) field(current, "sampler").get(current));
                    ((SpeechEngine) field(current, "speaker").get(current)).close();
                    field(current, "speaker").set(current, recording);
                    AppPreferences preferences = new AppPreferences(context);
                    RowSpeechLedger ledger = (RowSpeechLedger) field(current, "ledger").get(current);
                    Method speak = method("speakReady", long.class, long.class, List.class);
                    int id = 0;
                    for (Boolean oldAuto : new Boolean[]{null, true, false}) {
                        if (oldAuto == null) stored.edit().remove("auto_speech_rate").commit();
                        else stored.edit().putBoolean("auto_speech_rate", oldAuto).commit();
                        for (float rate : new float[]{0.75f, 1.30f, 1.60f, 2.00f}) {
                            preferences.setSpeechRate(rate);
                            method("applyLiveSettings").invoke(current);
                            ledger.reset();
                            SubtitleEvent event = new SubtitleEvent("manual-" + id,
                                    "手動速度で読む字幕" + id++, 0, 0, 100);
                            speak.invoke(current, 0L, -1L, Collections.singletonList(
                                    new SubtitleSpeechOrderBuffer.Entry(event, .6f, .7f)));
                            check(recording.rates.get(recording.rates.size() - 1) == rate,
                                    "Saved auto flag overrode manual rate: " + oldAuto + "/" + rate);
                        }
                    }
                    // A change while the same player is open reaches the next
                    // request, without a video reset or a cached learned value.
                    preferences.setSpeechRate(1.30f);
                    method("applyLiveSettings").invoke(current);
                    method("showSettings").invoke(current);
                    checkSettings((View) field(current, "settingsPanel").get(current), 80);
                    method("closeSettings").invoke(current);
                    for (int i = 0; i < 8; i++) {
                        SubtitleEvent event = new SubtitleEvent("repeat-" + i,
                                (i % 2 == 0 ? "短い字幕" : "長い字幕を順番に最後まで読み上げます") + id++,
                                i * 1500L, i * 1500L, 100);
                        speak.invoke(current, i * 1500L, -1L, Collections.singletonList(
                                new SubtitleSpeechOrderBuffer.Entry(event, .6f, .7f)));
                        check(recording.rates.get(recording.rates.size() - 1) == 1.30f, "Rate drifted between captions");
                    }
                    int stops = recording.stops;
                    method("setReadingPaused", boolean.class).invoke(current, true);
                    check(recording.stops == stops + 1, "Reading pause did not stop speech");
                    method("setReadingPaused", boolean.class).invoke(current, false);
                    check(!(boolean) field(current, "readingPaused").get(current), "Reading resume failed");
                    check((boolean) method("updateVideoContext", String.class).invoke(current,
                            "https://m.youtube.com/watch?v=I5U-NPzXCxs"), "Video change not detected");
                    check(ledger.spokenRowCount() == 0, "Video change retained previous rows");
                    check(preferences.getSpeechRate() == 1.30f, "Video change reset manual speed");
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new IllegalStateException("Manual speech rate integration failed", failure.get());
            return new JSONObject().put("default_rate", 1.60).put("legacy_auto_values_ignored", true)
                    .put("manual_rates", "0.75/1.30/1.60/2.00, each with absent/true/false old auto flag")
                    .put("settings_and_player_toggle_removed", true).put("player_requests_checked", recording.rates.size())
                    .put("live_rate_change", true).put("pause_resume_and_video_reset", true)
                    .put("scope", "Native settings and production player path with recording SpeechEngine; audible timing not measured");
        } finally {
            if (settings != null) { Activity closing = settings; instrumentation.runOnMainSync(closing::finish); }
            if (player != null) { Activity closing = player; instrumentation.runOnMainSync(closing::finish); }
            restore(stored, original, "speech_rate");
            restore(stored, original, "auto_speech_rate");
            restore(stored, original, "speech_mode");
        }
    }

    private static void restore(SharedPreferences stored, Map<String, ?> original, String key) {
        SharedPreferences.Editor edit = stored.edit();
        Object value = original.get(key);
        if (value instanceof Float) edit.putFloat(key, (Float) value);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof String) edit.putString(key, (String) value);
        else edit.remove(key);
        edit.commit();
    }
    private static void checkSettings(View root, int progress) {
        List<SeekBar> sliders = new ArrayList<>();
        inspect(root, sliders);
        check(sliders.stream().anyMatch(s -> s.getMax() == 150 && s.getProgress() == progress), "Manual speed slider missing/wrong");
    }
    private static void inspect(View view, List<SeekBar> sliders) {
        if (view instanceof TextView) check(!((TextView) view).getText().toString().contains("自動調整"), "Adaptive control remains");
        if (view instanceof SeekBar) sliders.add((SeekBar) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) inspect(((ViewGroup) view).getChildAt(i), sliders);
    }
    private static Method method(String name, Class<?>... arguments) throws Exception {
        Method method = SharedPlayerActivity.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
