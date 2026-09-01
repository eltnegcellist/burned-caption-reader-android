package jp.hidemaru.burnedcaptionreader.tts;

import java.util.ArrayDeque;
import java.util.Deque;

/** Queue policy kept independent from Android TTS so backlog behavior is unit-testable. */
public final class BoundedSpeechQueue<T> {
    private final int capacity;
    private final Deque<T> values = new ArrayDeque<>();

    public BoundedSpeechQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void offer(T value, SpeechEngine.Mode mode) {
        if (mode == SpeechEngine.Mode.BALANCED || mode == SpeechEngine.Mode.LATEST) {
            values.clear();
        } else {
            while (values.size() >= capacity) values.removeFirst();
        }
        values.addLast(value);
    }

    public T poll() { return values.pollFirst(); }
    public int size() { return values.size(); }
    public boolean isEmpty() { return values.isEmpty(); }
    public void clear() { values.clear(); }
}
