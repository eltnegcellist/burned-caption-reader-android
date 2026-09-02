package jp.hidemaru.burnedcaptionreader.capture;

import java.util.Arrays;

/**
 * Detects abrupt, broad visual changes from a small luminance grid.
 *
 * This deliberately ignores ordinary local motion. A positive result is used only
 * to prevent OCR text on opposite sides of a scene cut from being interpreted as
 * two consecutive subtitles in one screen-anchored lane.
 */
public final class SceneChangeDetector {
    private static final double MIN_MEAN_DIFFERENCE = 38.0;
    private static final double MIN_CHANGED_RATIO = 0.48;
    private static final int CHANGED_PIXEL_DIFFERENCE = 30;
    private static final long CUT_COOLDOWN_MS = 1_000L;

    private int[] previous;
    private long lastCutAt = Long.MIN_VALUE;

    public synchronized boolean observe(long timestamp, int[] luminanceGrid) {
        if (luminanceGrid == null || luminanceGrid.length == 0) return false;
        int[] current = Arrays.copyOf(luminanceGrid, luminanceGrid.length);
        if (previous == null || previous.length != current.length) {
            previous = current;
            return false;
        }

        long totalDifference = 0L;
        int changed = 0;
        for (int index = 0; index < current.length; index++) {
            int difference = Math.abs(current[index] - previous[index]);
            totalDifference += difference;
            if (difference >= CHANGED_PIXEL_DIFFERENCE) changed++;
        }
        previous = current;

        double meanDifference = totalDifference / (double) current.length;
        double changedRatio = changed / (double) current.length;
        boolean outsideCooldown = lastCutAt == Long.MIN_VALUE
                || timestamp - lastCutAt >= CUT_COOLDOWN_MS;
        boolean changedScene = outsideCooldown
                && meanDifference >= MIN_MEAN_DIFFERENCE
                && changedRatio >= MIN_CHANGED_RATIO;
        if (changedScene) lastCutAt = timestamp;
        return changedScene;
    }

    public synchronized void reset() {
        previous = null;
        lastCutAt = Long.MIN_VALUE;
    }
}
