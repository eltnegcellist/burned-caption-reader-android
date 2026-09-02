package jp.hidemaru.burnedcaptionreader.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class SceneChangeDetectorTest {
    @Test
    public void broadAbruptChangeIsSceneCut() {
        SceneChangeDetector detector = new SceneChangeDetector();

        assertFalse(detector.observe(0L, filled(192, 20)));
        assertTrue(detector.observe(1_000L, filled(192, 190)));
    }

    @Test
    public void localMotionIsNotSceneCut() {
        SceneChangeDetector detector = new SceneChangeDetector();
        int[] first = filled(192, 80);
        int[] localChange = filled(192, 80);
        Arrays.fill(localChange, 0, 40, 180);

        assertFalse(detector.observe(0L, first));
        assertFalse(detector.observe(1_000L, localChange));
    }

    @Test
    public void resetForgetsPreviousFrame() {
        SceneChangeDetector detector = new SceneChangeDetector();
        detector.observe(0L, filled(192, 10));
        detector.reset();

        assertFalse(detector.observe(1_000L, filled(192, 240)));
    }

    private int[] filled(int size, int value) {
        int[] output = new int[size];
        Arrays.fill(output, value);
        return output;
    }
}
