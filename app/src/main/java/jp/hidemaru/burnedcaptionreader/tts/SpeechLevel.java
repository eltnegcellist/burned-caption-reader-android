package jp.hidemaru.burnedcaptionreader.tts;

/** One UI scale for engine volume below 100% and session-only gain above it. */
public final class SpeechLevel {
    public static final int MAX_PERCENT = 400;

    private SpeechLevel() {}

    public static int clamp(int percent) { return Math.max(0, Math.min(MAX_PERCENT, percent)); }

    public static float engineVolume(int percent) {
        return Math.min(1f, clamp(percent) / 100f);
    }

    public static int gainMillibels(int percent) {
        int value = clamp(percent);
        if (value <= 100) return 0;
        // LoudnessEnhancer uses hundredths of a dB; 200% ~= +6 dB.
        return (int) Math.round(2000d * Math.log10(value / 100d));
    }

    public static int fromLegacy(float volume, int boostLevel) {
        float safeVolume = Float.isFinite(volume) ? Math.max(0f, Math.min(1f, volume)) : 1f;
        int safeBoost = Math.max(0, Math.min(2, boostLevel));
        return clamp(Math.round(safeVolume * (1 << safeBoost) * 100f));
    }
}
