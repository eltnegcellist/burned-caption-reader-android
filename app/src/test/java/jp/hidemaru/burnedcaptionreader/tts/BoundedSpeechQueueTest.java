package jp.hidemaru.burnedcaptionreader.tts;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BoundedSpeechQueueTest {
    @Test
    public void balancedKeepsOnlyLatestWaitingCaption() {
        BoundedSpeechQueue<String> queue = new BoundedSpeechQueue<>(2);
        queue.offer("古い字幕", SpeechEngine.Mode.BALANCED);
        queue.offer("最新字幕", SpeechEngine.Mode.BALANCED);
        assertEquals(1, queue.size());
        assertEquals("最新字幕", queue.poll());
    }

    @Test
    public void continuousQueueNeverGrowsPastCapacity() {
        BoundedSpeechQueue<String> queue = new BoundedSpeechQueue<>(2);
        queue.offer("1", SpeechEngine.Mode.CONTINUOUS);
        queue.offer("2", SpeechEngine.Mode.CONTINUOUS);
        queue.offer("3", SpeechEngine.Mode.CONTINUOUS);
        assertEquals(2, queue.size());
        assertEquals("2", queue.poll());
        assertEquals("3", queue.poll());
    }
}
