package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class SubtitleSpeechOrderBufferTest {
    @Test public void completeCaptionReplacesPendingTwoRowFragment() {
        var buffer = new SubtitleSpeechOrderBuffer();
        buffer.offer(0, List.of(entry("中段の字幕\n下段の字幕", .3f, .46f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.2f, .26f)));
        var ready = buffer.offer(480, List.of(entry("上段の字幕\n中段の字幕\n下段の字幕", .2f, .46f)), List.of());
        assertEquals(1, ready.size());
        assertEquals("上段の字幕\n中段の字幕\n下段の字幕", ready.get(0).event.getText());
    }

    @Test public void duplicateCommitDuringWaitIsEmittedOnce() {
        var buffer = new SubtitleSpeechOrderBuffer();
        var waiting = List.of(new SubtitleSpeechOrderBuffer.Band(.2f, .26f));
        buffer.offer(0, List.of(entry("下段の字幕", .3f, .36f)), waiting);
        buffer.offer(480, List.of(entry("下段の字幕", .3f, .36f)), waiting);
        assertEquals(1, buffer.drain(700).size());
    }

    private SubtitleSpeechOrderBuffer.Entry entry(String text, float top, float bottom) {
        return new SubtitleSpeechOrderBuffer.Entry(new SubtitleEvent(text, text, 0, 0, 90), top, bottom);
    }
    @Test public void waitsForUpperFragmentThenSpeaksTopToBottom() {
        SubtitleSpeechOrderBuffer buffer = new SubtitleSpeechOrderBuffer();
        assertTrue(buffer.offer(0, List.of(entry("下段", .30f, .36f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f))).isEmpty());
        var ready = buffer.offer(480, List.of(entry("上段", .20f, .26f)), List.of());
        assertEquals(2, ready.size());
        assertEquals("上段", ready.get(0).event.getText());
        assertEquals("下段", ready.get(1).event.getText());
        assertTrue(buffer.drain(1000).isEmpty());
    }
    @Test public void sortsThreeSimultaneousFragments() {
        var buffer = new SubtitleSpeechOrderBuffer();
        var ready = buffer.offer(0, List.of(entry("下", .36f, .42f),
                entry("上", .20f, .26f), entry("中", .28f, .34f)), List.of());
        assertEquals("上", ready.get(0).event.getText());
        assertEquals("中", ready.get(1).event.getText());
        assertEquals("下", ready.get(2).event.getText());
    }
    @Test public void deadlineReleasesEvenWithoutMoreOcrFrames() {
        var buffer = new SubtitleSpeechOrderBuffer();
        buffer.offer(10, List.of(entry("下", .30f, .36f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f)));
        assertTrue(buffer.drain(709).isEmpty());
        assertEquals(1, buffer.drain(710).size());
    }
    @Test public void ordinaryCaptionHasNoAddedDelay() {
        var buffer = new SubtitleSpeechOrderBuffer();
        assertEquals(1, buffer.offer(0, List.of(entry("本文", .3f, .5f)), List.of()).size());
    }
    @Test public void distantConversationDoesNotBlock() {
        var buffer = new SubtitleSpeechOrderBuffer();
        assertEquals(1, buffer.offer(0, List.of(entry("下", .8f, .86f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.2f, .26f))).size());
    }
    @Test public void repeatedPendingObservationsDoNotExtendDeadline() {
        var buffer = new SubtitleSpeechOrderBuffer();
        var waiting = List.of(new SubtitleSpeechOrderBuffer.Band(.2f, .26f));
        buffer.offer(0, List.of(entry("下", .3f, .36f)), waiting);
        buffer.offer(480, List.of(), waiting);
        assertEquals(1, buffer.drain(700).size());
    }
    @Test public void stopOrSceneResetDiscardsPendingSpeech() {
        var buffer = new SubtitleSpeechOrderBuffer();
        buffer.offer(0, List.of(entry("下", .3f, .36f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.2f, .26f)));
        buffer.reset();
        assertTrue(buffer.drain(1000).isEmpty());
        assertEquals(Long.MAX_VALUE, buffer.nextDeadline());
    }
}
