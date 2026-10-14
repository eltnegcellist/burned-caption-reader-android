package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
import android.content.SharedPreferences;
import jp.hidemaru.burnedcaptionreader.ocr.CaptionOcrMode;
import jp.hidemaru.burnedcaptionreader.tts.SpeechLevel;

public final class AppPreferences {
    public static final String PREFERENCES_FILE = "burned_caption_reader";
    public static final String OCR_MODE = "ocr_mode";
    public static final String SPEECH_VOLUME = "speech_volume";
    public static final String SPEECH_BOOST = "speech_boost";
    private static final String SPEECH_LEVEL = "speech_level_percent";

    private static final String SPEECH_RATE = "speech_rate";
    private static final String SPEECH_MODE = "speech_mode";
    private static final String STABLE_MS = "stable_ms";
    private static final String SUBTITLE_RECHECK_ENABLED = "subtitle_recheck_enabled";
    private static final String KEEP_SCREEN_ON = "keep_screen_on";
    private static final String HIDE_YOUTUBE_CC = "hide_youtube_cc";

    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_CONTINUOUS = "continuous";
    public static final String MODE_LATEST = "latest";

    private final SharedPreferences preferences;

    public AppPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Production OCR is fixed to E: ML Kit detection + PP-OCRv6 Small recognition and E selection.
     * Migrate EVERY previously saved A/B/C/legacy value (not just unset)
     * so an update does not silently retain an older engine.
     */
    public CaptionOcrMode getOcrMode() {
        if (!CaptionOcrMode.E.storedValue.equals(preferences.getString(OCR_MODE, null))) {
            preferences.edit().putString(OCR_MODE, CaptionOcrMode.E.storedValue).apply();
        }
        return CaptionOcrMode.E;
    }

    /**
     * Kept for compatibility with previous instrumentation call sites.
     * App/user preferences can no longer override the production OCR engine.
     * Explicit diagnostic engine construction remains available to development tests.
     */
    @Deprecated
    public void setOcrMode(CaptionOcrMode ignored) {
        preferences.edit().putString(OCR_MODE, CaptionOcrMode.E.storedValue).apply();
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

    /** Migrates the previous independent volume and +6/+12 dB controls on first use. */
    public int getSpeechLevel() {
        if (preferences.contains(SPEECH_LEVEL)) {
            return SpeechLevel.clamp(preferences.getInt(SPEECH_LEVEL, 100));
        }
        int migrated = SpeechLevel.fromLegacy(getSpeechVolume(), getSpeechBoost());
        preferences.edit().putInt(SPEECH_LEVEL, migrated).apply();
        return migrated;
    }

    public void setSpeechLevel(int percent) {
        preferences.edit().putInt(SPEECH_LEVEL, SpeechLevel.clamp(percent)).apply();
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

    public boolean isKeepScreenOn() {
        return preferences.getBoolean(KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean enabled) {
        preferences.edit().putBoolean(KEEP_SCREEN_ON, enabled).apply();
    }

    /** False for new and existing installations until explicitly configured. */
    public boolean isSubtitleRecheckEnabled() {
        return preferences.getBoolean(SUBTITLE_RECHECK_ENABLED, false);
    }

    public void setSubtitleRecheckEnabled(boolean enabled) {
        preferences.edit().putBoolean(SUBTITLE_RECHECK_ENABLED, enabled).apply();
    }

    public long getStableMs() {
        return preferences.getLong(STABLE_MS, 300L);
    }

    public void setStableMs(long stableMs) {
        preferences.edit().putLong(STABLE_MS, Math.max(250L, Math.min(1_000L, stableMs))).apply();
    }

    public boolean isYouTubeCcHidden() {
        return preferences.getBoolean(HIDE_YOUTUBE_CC, true);
    }

    public void setYouTubeCcHidden(boolean hidden) {
        preferences.edit().putBoolean(HIDE_YOUTUBE_CC, hidden).apply();
    }

}
