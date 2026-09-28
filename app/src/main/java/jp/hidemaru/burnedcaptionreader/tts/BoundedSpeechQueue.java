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

    /** Returns the oldest value evicted by the capacity policy, if any. */
    public T offer(T value, SpeechEngine.Mode mode) {
        T evicted = null;
        if (mode == SpeechEngine.Mode.BALANCED || mode == SpeechEngine.Mode.LATEST) {
            values.clear();
        } else {
            while (values.size() >= capacity) evicted = values.removeFirst();
        }
        values.addLast(value);
        return evicted;
    }

    public T poll() { return values.pollFirst(); }
    public int size() { return values.size(); }
    public boolean isEmpty() { return values.isEmpty(); }
    public void clear() { values.clear(); }
}
