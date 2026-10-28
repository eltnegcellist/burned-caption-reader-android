package jp.hidemaru.burnedcaptionreader;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VideoFrameCapturePolicyTest {
    @Test public void fallbackPollWhenNoFrameSignalsArrive() {
        assertTrue(VideoFrameCapturePolicy.needsPeriodicCapture(0, -1));
        assertFalse(VideoFrameCapturePolicy.needsPeriodicCapture(1499, 500));
        assertTrue(VideoFrameCapturePolicy.needsPeriodicCapture(1500, 500));
    }
    @Test public void callbacksAreThrottledAndNeverRunWhileBusy() {
        assertTrue(VideoFrameCapturePolicy.acceptsFrameSignal(500, -1, false));
        assertFalse(VideoFrameCapturePolicy.acceptsFrameSignal(679, 500, false));
        assertFalse(VideoFrameCapturePolicy.acceptsFrameSignal(680, 500, true));
        assertTrue(VideoFrameCapturePolicy.acceptsFrameSignal(680, 500, false));
    }
    @Test public void productionCadenceIsFixed() {
        assertEquals(480L, VideoFrameCapturePolicy.FALLBACK_POLL_MS);
        assertEquals(180L, VideoFrameCapturePolicy.SIGNAL_THROTTLE_MS);
        assertEquals(1000L, VideoFrameCapturePolicy.FALLBACK_AFTER_MS);
    }
}
