package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

public final class AppPreferences {
    private static final String FILE = "burned_caption_reader";
    private static final String ROI_SET = "roi_set";
    private static final String ROI_LEFT = "roi_left";
    private static final String ROI_TOP = "roi_top";
    private static final String ROI_RIGHT = "roi_right";
    private static final String ROI_BOTTOM = "roi_bottom";
    private static final String ROI_VERSION = "roi_version";
    private static final String SPEECH_RATE = "speech_rate";
    private static final String SPEECH_MODE = "speech_mode";
    private static final String STABLE_MS = "stable_ms";

    public static final String MODE_CONTINUOUS = "continuous";
    public static final String MODE_LATEST = "latest";

    private final SharedPreferences preferences;

    public AppPreferences(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
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
                .putInt(ROI_VERSION, version)
                .apply();
    }

    public int getRoiVersion() {
        return preferences.getInt(ROI_VERSION, 0);
    }

    public float getSpeechRate() {
        return preferences.getFloat(SPEECH_RATE, 1.0f);
    }

    public void setSpeechRate(float rate) {
        preferences.edit().putFloat(SPEECH_RATE, Math.max(0.5f, Math.min(2.0f, rate))).apply();
    }

    public String getSpeechMode() {
        return preferences.getString(SPEECH_MODE, MODE_CONTINUOUS);
    }

    public void setSpeechMode(String mode) {
        preferences.edit().putString(SPEECH_MODE, mode).apply();
    }

    public long getStableMs() {
        return preferences.getLong(STABLE_MS, 450L);
    }

    public void setStableMs(long stableMs) {
        preferences.edit().putLong(STABLE_MS, Math.max(250L, Math.min(1_000L, stableMs))).apply();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
