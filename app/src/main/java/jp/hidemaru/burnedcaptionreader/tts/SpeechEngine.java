package jp.hidemaru.burnedcaptionreader.tts;

public interface SpeechEngine extends AutoCloseable {
    enum Mode { CONTINUOUS, LATEST }
    void speak(String text, Mode mode, float rate);
    void stop();
    @Override void close();
}
