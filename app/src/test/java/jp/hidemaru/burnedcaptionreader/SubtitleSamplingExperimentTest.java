package jp.hidemaru.burnedcaptionreader;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleSamplingExperimentTest {
    @Test public void onlyExplicitDebugLaunchCanOverrideProduct() {
        assertEquals(SubtitleSamplingExperiment.POLL_480, SubtitleSamplingExperiment.select(null,true));
        assertEquals(SubtitleSamplingExperiment.POLL_480, SubtitleSamplingExperiment.select("frame",false));
        assertEquals(SubtitleSamplingExperiment.POLL_480, SubtitleSamplingExperiment.select("unknown",true));
        assertEquals(SubtitleSamplingExperiment.POLL_240, SubtitleSamplingExperiment.select("poll240",true));
        assertEquals(SubtitleSamplingExperiment.VIDEO_FRAME, SubtitleSamplingExperiment.select("frame",true));
    }
    @Test public void callbackFallsBackToPeriodicWhenNoFramesArrive() {
        SubtitleSamplingExperiment mode=SubtitleSamplingExperiment.VIDEO_FRAME;
        assertTrue(mode.needsPeriodicCapture(0,-1));
        assertFalse(mode.needsPeriodicCapture(1490,500));
        assertTrue(mode.needsPeriodicCapture(1500,500));
        assertTrue(SubtitleSamplingExperiment.POLL_480.needsPeriodicCapture(2500,2400));
        assertTrue(SubtitleSamplingExperiment.POLL_240.needsPeriodicCapture(2500,2400));
    }
    @Test public void neverCaptureConcurrentlyOrTooFrequently() {
        SubtitleSamplingExperiment mode=SubtitleSamplingExperiment.VIDEO_FRAME;
        assertTrue(mode.acceptsFrameSignal(500,-1,false));
        assertFalse(mode.acceptsFrameSignal(680,500,true));
        assertFalse(mode.acceptsFrameSignal(679,500,false));
        assertTrue(mode.acceptsFrameSignal(680,500,false));
        assertFalse(SubtitleSamplingExperiment.POLL_240.acceptsFrameSignal(680,500,false));
    }
}
