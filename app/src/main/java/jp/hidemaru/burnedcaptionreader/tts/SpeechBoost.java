package jp.hidemaru.burnedcaptionreader.tts;

/** Owns an effect attached only to the TTS session. Never uses global session 0. */
public final class SpeechBoost implements AutoCloseable {
    public interface Effect {
        void enable(int gainMillibels);
        void release();
    }
    public interface Factory { Effect create(int sessionId); }

    private final int sessionId;
    private final Factory factory;
    private Effect effect;
    private int lastLevel = -1;
    private boolean available = true;
    private boolean closed;

    public SpeechBoost(int sessionId, Factory factory) {
        this.sessionId = sessionId;
        this.factory = factory;
    }

    public static int normalize(int level) { return Math.max(0, Math.min(2, level)); }

    /** False means fall back to ordinary speech; retry after changing the selection. */
    public synchronized boolean apply(int level) {
        if (closed) return false;
        level = normalize(level);
        if (level == lastLevel) return available;
        lastLevel = level;
        if (level == 0) {
            releaseEffect();
            return available = true;
        }
        if (sessionId <= 0) return available = false;
        try {
            if (effect == null) effect = factory.create(sessionId);
            effect.enable(level * 600);
            return available = true;
        } catch (RuntimeException unavailable) {
            releaseEffect();
            return available = false;
        }
    }

    private void releaseEffect() {
        Effect old = effect;
        effect = null;
        if (old != null) {
            try { old.release(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        releaseEffect();
    }
}
