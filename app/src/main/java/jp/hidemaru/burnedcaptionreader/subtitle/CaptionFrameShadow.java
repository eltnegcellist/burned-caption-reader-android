package jp.hidemaru.burnedcaptionreader.subtitle;

/**
 * P3/P4 diagnostic-only predictions. No OCR is skipped and no rescue is run.
 * Fingerprints use at most 16*9 + 48*12 pixels from the original frame.
 * This is deliberately conservative: single-digit transitions can evade a
 * thumbnail comparison, so any future actual skip needs separate evidence.
 */
public final class CaptionFrameShadow {
    public interface PixelSource { int rgb(int x, int y); }

    public static final class Prediction {
        public final boolean wouldSkip;
        public final String reason;
        public final double broadMeanDelta;
        public final double bandMeanDelta;
        public final double bandColorDelta;
        Prediction(boolean skip, String why, double broad, double band, double color) {
            wouldSkip = skip; reason = why; broadMeanDelta = broad; bandMeanDelta = band;
            bandColorDelta = color;
        }
    }

    public static final class RescuePrediction {
        public final boolean wouldRescue;
        public final String reason;
        public final int lumaRange;
        RescuePrediction(boolean rescue, String reason, int range) {
            wouldRescue = rescue; this.reason = reason; lumaRange = range;
        }
    }

    private static final int WHOLE_W = 16, WHOLE_H = 9, BAND_W = 48, BAND_H = 12;
    private static final long MAX_TRUST_AGE_MS = 2_000L;
    private static final long FORCE_EVERY_MS = 960L;
    private int trustedTrack = -1;
    private float bandTop, bandBottom;
    private long lastTrustedAt = -1;
    private int width = -1, height = -1, generation = Integer.MIN_VALUE;
    private int[] previousBroad, previousBand;
    private long lastWouldOcrAt = -1L;
    private long lastRescueAt = -1L;
    private int consecutiveWouldSkips;

    /** Only a tracker-locked lane can qualify, never an unconfirmed raw text line. */
    public void trust(int trackId, float top, float bottom, long now) {
        if (trackId < 0 || !(top >= 0 && bottom <= 1 && bottom > top)) {
            clearTrust(); return;
        }
        if (trustedTrack != trackId
                || Math.abs(bandTop - top) > .008f
                || Math.abs(bandBottom - bottom) > .008f) {
            clearFingerprints();
        }
        trustedTrack = trackId;
        bandTop = top;
        bandBottom = bottom;
        lastTrustedAt = now;
    }

    public void clearTrust() {
        trustedTrack = -1;
        lastTrustedAt = -1;
        clearFingerprints();
    }

    public void reset() {
        clearTrust();
        width = -1; height = -1; generation = Integer.MIN_VALUE;
        lastRescueAt = -1L;
    }

    public boolean hasTrust(long now) {
        return trustedTrack >= 0 && lastTrustedAt >= 0
                && now >= lastTrustedAt && now - lastTrustedAt <= MAX_TRUST_AGE_MS;
    }

    public Prediction probe(PixelSource pixels, int w, int h, int currentGeneration, long now) {
        if (w <= 0 || h <= 0) return new Prediction(false, "invalid_dimensions", -1, -1, -1);
        if (width != w || height != h || generation != currentGeneration) {
            clearFingerprints();
            width = w; height = h; generation = currentGeneration;
        }
        if (!hasTrust(now)) {
            clearFingerprints();
            return new Prediction(false, "no_recent_locked_lane", -1, -1, -1);
        }
        int[] broad = sample(pixels, w, h, WHOLE_W, WHOLE_H, 0, 1);
        int[] band = sample(pixels, w, h, BAND_W, BAND_H,
                Math.max(0f, bandTop - .01f), Math.min(1f, bandBottom + .01f));
        double wholeDelta = previousBroad == null ? -1 : meanDelta(previousBroad, broad);
        double bandDelta = previousBand == null ? -1 : meanDelta(previousBand, band);
        // Luma alone misses hue changes (e.g. a red digit becoming green)
        // when their weighted brightness is approximately equal.
        double bandColorDelta = previousBand == null ? -1 : meanColorDelta(previousBand, band);
        boolean force = lastWouldOcrAt >= 0 && now - lastWouldOcrAt >= FORCE_EVERY_MS;
        boolean skip = previousBroad != null && previousBand != null
                && !force && consecutiveWouldSkips < 1
                && wholeDelta <= 4.0 && bandDelta <= 3.0
                && bandColorDelta <= 4.0;
        String reason = previousBroad == null ? "first_with_trust"
                : force ? "forced_periodic_ocr"
                : consecutiveWouldSkips >= 1 ? "max_one_skip"
                : skip ? "similar_images" : "image_changed";
        if (skip) {
            consecutiveWouldSkips++;
        } else {
            previousBroad = broad;
            previousBand = band;
            consecutiveWouldSkips = 0;
            lastWouldOcrAt = now;
        }
        return new Prediction(skip, reason, wholeDelta, bandDelta, bandColorDelta);
    }

    /** P3 candidate only. Shadow does not invoke another detector. */
    public RescuePrediction inspectMissing(PixelSource pixels, int w, int h,
                                           long now, boolean rawEmpty, boolean stripAlreadyTried) {
        if (!rawEmpty) return new RescuePrediction(false, "raw_present", -1);
        if (stripAlreadyTried) return new RescuePrediction(false, "strip_used", -1);
        if (!hasTrust(now)) return new RescuePrediction(false, "no_recent_locked_lane", -1);
        if (lastRescueAt >= 0 && now - lastRescueAt < FORCE_EVERY_MS)
            return new RescuePrediction(false, "cooldown", -1);
        int[] band = sample(pixels, w, h, BAND_W, BAND_H,
                Math.max(0, bandTop - .01f), Math.min(1, bandBottom + .01f));
        int low = 255, high = 0, bright = 0, dark = 0;
        for (int rgb : band) {
            int y = luminance(rgb);
            low = Math.min(low, y); high = Math.max(high, y);
            if (y >= 190) bright++;
            if (y <= 80) dark++;
        }
        boolean evidence = high - low >= 90 && bright >= 3 && dark >= 3;
        if (evidence) lastRescueAt = now;
        return new RescuePrediction(evidence, evidence ? "contrast_in_locked_lane" : "no_text_evidence",
                high - low);
    }

    private void clearFingerprints() {
        previousBroad = null; previousBand = null;
        consecutiveWouldSkips = 0;
        lastWouldOcrAt = -1;
    }

    private static int[] sample(PixelSource src, int width, int height,
                                int w, int h, float top, float bottom) {
        int[] data = new int[w * h];
        for (int y = 0; y < h; y++) {
            int iy = Math.max(0, Math.min(height - 1,
                    (int) (height * (top + (y + .5) / h * (bottom - top)))));
            for (int x = 0; x < w; x++) {
                int ix = Math.max(0, Math.min(width - 1, (int) (width * (x + .5) / w)));
                data[y * w + x] = src.rgb(ix, iy) & 0x00ffffff;
            }
        }
        return data;
    }

    private static int luminance(int rgb) {
        return (77 * ((rgb >> 16) & 255) + 150 * ((rgb >> 8) & 255)
                + 29 * (rgb & 255)) >> 8;
    }

    private static double meanDelta(int[] a, int[] b) {
        int total = 0;
        for (int i = 0; i < a.length; i++)
            total += Math.abs(luminance(a[i]) - luminance(b[i]));
        return total / (double) a.length;
    }

    private static double meanColorDelta(int[] a, int[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) {
            total += Math.abs(((a[i] >> 16) & 255) - ((b[i] >> 16) & 255));
            total += Math.abs(((a[i] >> 8) & 255) - ((b[i] >> 8) & 255));
            total += Math.abs((a[i] & 255) - (b[i] & 255));
        }
        return total / (3.0 * a.length);
    }
}
