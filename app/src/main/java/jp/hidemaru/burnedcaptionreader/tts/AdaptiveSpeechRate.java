package jp.hidemaru.burnedcaptionreader.tts;

/**
 * Learns the subtitle pace of the current video from successive spoken captions.
 * It changes the requested speech rate only between utterances; it never modifies
 * the text, replay policy, or the speed of YouTube playback.
 *
 * Detection timestamps are monotonic clock times, not media timestamps. Long gaps
 * (pause, seeking, ads, inactivity) are ignored rather than interpreted as
 * very slow subtitle pacing.
 */
public final class AdaptiveSpeechRate {
    public static final float MIN_RATE = 1.15f;
    public static final float MAX_RATE = 2.00f;
    private static final float REFERENCE_CHARS_PER_SECOND = 8.0f;
    private static final float SMOOTHING = 0.45f;
    private static final float MAX_CHANGE_PER_CAPTION = 0.17f;
    private static final long MIN_INTERVAL_MS = 700L;
    private static final long MAX_INTERVAL_MS = 12_000L;

    private long lastTimestamp = -1L;
    private int lastCharacters;
    private int validIntervals;
    private float averageDensity = Float.NaN;
    private float currentRate = Float.NaN;

    /**
     * Returns a stable requested TTS speed for the next utterance.
     * User-selected speed is the starting point and reference rate.
     */
    public synchronized float onCaption(long timestamp, String text, float baseRate) {
        baseRate = clamp(baseRate);
        if (Float.isNaN(currentRate)) currentRate = baseRate;
        int characters = characterCount(text);
        if (characters == 0 || timestamp < 0) return currentRate;
        if (lastTimestamp >= 0) {
            long interval = timestamp - lastTimestamp;
            if (interval >= MIN_INTERVAL_MS && interval <= MAX_INTERVAL_MS
                    && lastCharacters >= 4) {
                float density = Math.max(1.0f,
                        Math.min(30.0f, lastCharacters * 1000f / interval));
                averageDensity = Float.isNaN(averageDensity) ? density
                        : averageDensity * (1.0f - SMOOTHING) + density * SMOOTHING;
                validIntervals++;
                // Two usable transitions guard against an unusual opening caption.
                if (validIntervals >= 2) {
                    float target = clamp(baseRate * averageDensity / REFERENCE_CHARS_PER_SECOND);
                    currentRate = clamp(currentRate + Math.max(-MAX_CHANGE_PER_CAPTION,
                            Math.min(MAX_CHANGE_PER_CAPTION, target - currentRate)));
                }
            } else if (interval >= 0 && interval < MIN_INTERVAL_MS) {
                // Closely timed OCR fragments are one caption interval.
                lastCharacters = Math.min(200, lastCharacters + characters);
                return currentRate;
            }
            // Skip pauses/seek discontinuities; retain video-level learned pace.
        }
        lastTimestamp = timestamp;
        lastCharacters = characters;
        return currentRate;
    }

    /**
     * A persistent delay between queueing and audible start indicates that
     * speech is falling behind. Accelerate gently; the engine's existing
     * per-utterance catch-up correction remains in place.
     */
    public synchronized void onSpeechStartDelay(long delayMs) {
        if (validIntervals < 2 || delayMs < 900 || Float.isNaN(currentRate)) return;
        currentRate = clamp(currentRate + Math.min(0.10f, delayMs / 20_000f));
    }

    /** A pause/seek should not count as subtitle display time. */
    public synchronized void suspend() {
        lastTimestamp = -1L;
        lastCharacters = 0;
    }

    /** Called at a video change or when changing adaptive-mode settings. */
    public synchronized void reset() {
        suspend();
        validIntervals = 0;
        averageDensity = Float.NaN;
        currentRate = Float.NaN;
    }

    public synchronized float getCurrentRate(float baseRate) {
        return Float.isNaN(currentRate) ? clamp(baseRate) : currentRate;
    }

    public synchronized int getValidIntervals() { return validIntervals; }

    static int characterCount(String text) {
        if (text == null) return 0;
        return (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    private static float clamp(float rate) {
        if (!Float.isFinite(rate)) return 1.60f;
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
    }
}
