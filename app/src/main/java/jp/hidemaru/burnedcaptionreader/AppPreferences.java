package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

public final class AppPreferences {
    public static final String PREFERENCES_FILE = "burned_caption_reader";
    public static final String SPEECH_VOLUME = "speech_volume";
    public static final String SPEECH_BOOST = "speech_boost";

    private static final String ROI_SET = "roi_set";
    private static final String ROI_LEFT = "roi_left";
    private static final String ROI_TOP = "roi_top";
    private static final String ROI_RIGHT = "roi_right";
    private static final String ROI_BOTTOM = "roi_bottom";
    private static final String ROI_VERSION = "roi_version";
    private static final String REGION_MODE = "region_mode";
    private static final String SPEECH_RATE = "speech_rate";
    private static final String SPEECH_MODE = "speech_mode";
    private static final String STABLE_MS = "stable_ms";
    private static final String AUTO_PAUSE_BROWSER = "auto_pause_browser";
    private static final String KEEP_SCREEN_ON = "keep_screen_on";

    public static final String REGION_AUTO = "auto";
    public static final String REGION_MANUAL = "manual";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_CONTINUOUS = "continuous";
    public static final String MODE_LATEST = "latest";

    private final SharedPreferences preferences;

    public AppPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
    }

    public boolean hasRoi() {
        return preferences.getBoolean(ROI_SET, false);
    }

    public RectF getRoi() {
        return new RectF(
                preferences.getFloat(ROI_LEFT, 0.05f),
                preferences.getFloat(ROI_TOP, 0.55f),
                preferences.getFloat(ROI_RIGHT, 0.95f),
                preferences.getFloat(ROI_BOTTOM, 0.90f)
        );
    }

    public void setRoi(RectF roi) {
        int version = preferences.getInt(ROI_VERSION, 0) + 1;
        preferences.edit()
                .putBoolean(ROI_SET, true)
                .putFloat(ROI_LEFT, clamp(roi.left))
                .putFloat(ROI_TOP, clamp(roi.top))
                .putFloat(ROI_RIGHT, clamp(roi.right))
                .putFloat(ROI_BOTTOM, clamp(roi.bottom))
                .putString(REGION_MODE, REGION_MANUAL)
                .putInt(ROI_VERSION, version)
                .apply();
    }

    public boolean isAutoRegion() {
        return REGION_AUTO.equals(preferences.getString(REGION_MODE, REGION_AUTO));
    }

    public void setAutoRegion(boolean automatic) {
        if (isAutoRegion() == automatic) return;
        int version = preferences.getInt(ROI_VERSION, 0) + 1;
        preferences.edit()
                .putString(REGION_MODE, automatic ? REGION_AUTO : REGION_MANUAL)
                .putInt(ROI_VERSION, version)
                .apply();
    }

    public int getRoiVersion() {
        return preferences.getInt(ROI_VERSION, 0);
    }

    public float getSpeechRate() {
        return preferences.getFloat(SPEECH_RATE, 1.40f);
    }

    public void setSpeechRate(float rate) {
        preferences.edit().putFloat(SPEECH_RATE, Math.max(0.5f, Math.min(2.0f, rate))).apply();
    }

    public float getSpeechVolume() {
        return preferences.getFloat(SPEECH_VOLUME, 1.0f);
    }

    public void setSpeechVolume(float volume) {
        preferences.edit().putFloat(SPEECH_VOLUME, Math.max(0.0f, Math.min(1.0f, volume))).apply();
    }

    public int getSpeechBoost() {
        return Math.max(0, Math.min(2, preferences.getInt(SPEECH_BOOST, 0)));
    }

    public void setSpeechBoost(int level) {
        preferences.edit().putInt(SPEECH_BOOST, Math.max(0, Math.min(2, level))).apply();
    }

    public String getSpeechMode() {
        String mode = preferences.getString(SPEECH_MODE, MODE_BALANCED);
        // Preserve the stored policy.  Continuous mode is deliberately a
        // lossless FIFO policy; silently converting it to BALANCED caused
        // captions to be discarded whenever TTS lagged behind the video.
        if (MODE_CONTINUOUS.equals(mode) || MODE_LATEST.equals(mode)) return mode;
        return MODE_BALANCED;
    }

    public void setSpeechMode(String mode) {
        preferences.edit().putString(SPEECH_MODE, mode).apply();
    }

    public boolean isAutoPauseBrowser() {
        return preferences.getBoolean(AUTO_PAUSE_BROWSER, false);
    }

    public void setAutoPauseBrowser(boolean enabled) {
        preferences.edit().putBoolean(AUTO_PAUSE_BROWSER, enabled).apply();
    }

    public boolean isKeepScreenOn() {
        return preferences.getBoolean(KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean enabled) {
        preferences.edit().putBoolean(KEEP_SCREEN_ON, enabled).apply();
    }

    public long getStableMs() {
        return preferences.getLong(STABLE_MS, 300L);
    }

    public void setStableMs(long stableMs) {
        preferences.edit().putLong(STABLE_MS, Math.max(250L, Math.min(1_000L, stableMs))).apply();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
