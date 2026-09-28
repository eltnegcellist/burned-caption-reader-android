package jp.hidemaru.burnedcaptionreader.tts;

public interface SpeechEngine extends AutoCloseable {
    enum Mode { BALANCED, CONTINUOUS, LATEST }

    interface Listener {
        void onSpeakingStateChanged(boolean speaking);
    }
    interface Completion {
        default void onStart() {}
        default void onDone() {}
        default void onError() {}
    }

    void speak(String text, Mode mode, float rate);
    default void speak(String text, Mode mode, float rate, Completion completion) {
        speak(text, mode, rate);
    }
    void stop();
    default void setListener(Listener listener) {}
    @Override void close();
}
