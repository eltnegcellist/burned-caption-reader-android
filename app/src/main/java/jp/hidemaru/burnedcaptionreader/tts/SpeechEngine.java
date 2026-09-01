package jp.hidemaru.burnedcaptionreader.tts;

public interface SpeechEngine extends AutoCloseable {
    enum Mode { BALANCED, CONTINUOUS, LATEST }

    interface Listener {
        void onSpeakingStateChanged(boolean speaking);
    }

    void speak(String text, Mode mode, float rate);
    void stop();
    default void setListener(Listener listener) {}
    @Override void close();
}
