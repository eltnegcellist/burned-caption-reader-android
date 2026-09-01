package jp.hidemaru.burnedcaptionreader;

import android.graphics.Bitmap;

public final class AppState {
    private static Bitmap latestFrame;
    private static volatile boolean running;
    private static volatile String status = "停止中";
    private static volatile String lastOcr = "（まだ認識していません）";
    private static volatile String lastSpoken = "（まだ読み上げていません）";

    private AppState() {}

    public static synchronized void setLatestFrame(Bitmap bitmap) {
        Bitmap previous = latestFrame;
        latestFrame = bitmap;
        if (previous != null && previous != bitmap && !previous.isRecycled()) {
            previous.recycle();
        }
    }

    public static synchronized Bitmap copyLatestFrame() {
        if (latestFrame == null || latestFrame.isRecycled()) {
            return null;
        }
        return latestFrame.copy(Bitmap.Config.ARGB_8888, false);
    }

    public static synchronized void clearFrame() {
        if (latestFrame != null && !latestFrame.isRecycled()) {
            latestFrame.recycle();
        }
        latestFrame = null;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setRunning(boolean value) {
        running = value;
    }

    public static String getStatus() {
        return status;
    }

    public static void setStatus(String value) {
        status = value;
    }

    public static String getLastOcr() {
        return lastOcr;
    }

    public static void setLastOcr(String value) {
        lastOcr = value == null || value.trim().isEmpty() ? "（文字を検出できません）" : value;
    }

    public static String getLastSpoken() {
        return lastSpoken;
    }

    public static void setLastSpoken(String value) {
        lastSpoken = value;
    }
}
