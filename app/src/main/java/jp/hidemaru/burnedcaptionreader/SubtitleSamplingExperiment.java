package jp.hidemaru.burnedcaptionreader;

/**
 * Research-only sampling modes. Production remains the unchanged 480 ms poll.
 * Modes are accepted only for debuggable builds and an explicit launch extra.
 */
public enum SubtitleSamplingExperiment {
    POLL_480("poll480", 480L),
    POLL_240("poll240", 240L),
    VIDEO_FRAME("frame", 480L);

    public final String value;
    public final long pollMs;
    public static final long FRAME_SIGNAL_THROTTLE_MS = 180L;
    public static final long FRAME_FALLBACK_AFTER_MS = 1000L;

    SubtitleSamplingExperiment(String value, long pollMs) {
        this.value = value;
        this.pollMs = pollMs;
    }

    public static SubtitleSamplingExperiment select(String raw, boolean debuggable) {
        if (!debuggable || raw == null) return POLL_480;
        for (SubtitleSamplingExperiment mode : values()) {
            if (mode.value.equals(raw)) return mode;
        }
        return POLL_480;
    }

    /** Polling fallback also runs while a video is paused or callbacks are unavailable. */
    public boolean needsPeriodicCapture(long nowMs, long latestVideoFrameAtMs) {
        return this != VIDEO_FRAME || latestVideoFrameAtMs < 0
                || nowMs - latestVideoFrameAtMs >= FRAME_FALLBACK_AFTER_MS;
    }

    public boolean acceptsFrameSignal(long nowMs, long lastRequestAtMs, boolean busy) {
        return this == VIDEO_FRAME && !busy
                && (lastRequestAtMs < 0 || nowMs - lastRequestAtMs >= FRAME_SIGNAL_THROTTLE_MS);
    }
}
