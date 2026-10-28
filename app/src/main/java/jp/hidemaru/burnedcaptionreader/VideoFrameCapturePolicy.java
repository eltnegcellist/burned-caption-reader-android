package jp.hidemaru.burnedcaptionreader;

/**
 * Frame-driven capture is the production default. If WebView cannot provide
 * rendered video-frame notifications, keep the previous 480ms PixelCopy poll.
 * This policy never reads video bytes, decodes streams or seeks ahead.
 */
public final class VideoFrameCapturePolicy {
    public static final long FALLBACK_POLL_MS = 480L;
    public static final long SIGNAL_THROTTLE_MS = 180L;
    public static final long FALLBACK_AFTER_MS = 1000L;

    private VideoFrameCapturePolicy() {}

    public static boolean needsPeriodicCapture(long nowMs, long latestFrameSignalMs) {
        return latestFrameSignalMs < 0 || nowMs - latestFrameSignalMs >= FALLBACK_AFTER_MS;
    }

    public static boolean acceptsFrameSignal(long nowMs, long lastRequestAtMs, boolean busy) {
        return !busy && (lastRequestAtMs < 0
                || nowMs - lastRequestAtMs >= SIGNAL_THROTTLE_MS);
    }
}
