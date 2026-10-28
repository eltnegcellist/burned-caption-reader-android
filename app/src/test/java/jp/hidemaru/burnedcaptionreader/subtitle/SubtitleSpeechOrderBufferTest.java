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
        assertTrue(buffer.offer(180, List.of(entry("下段の字幕", .3f, .36f)), waiting).isEmpty());
        assertEquals(1, buffer.drain(300).size());
        assertTrue(buffer.drain(480).isEmpty());
    }

    @Test public void liveSettingsChangePreservesAlreadyPendingCaptions() {
        var buffer = new SubtitleSpeechOrderBuffer(700L);
        var upper = List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f));
        assertTrue(buffer.offer(1000L, List.of(entry("先の下段", .30f, .36f)),
                upper).isEmpty());
        assertEquals(1700L, buffer.nextDeadline());
        buffer.setMaxWaitMs(300L);
        assertEquals(300L, buffer.getMaxWaitMs());
        // The existing caption must not vanish or have its deadline changed.
        assertEquals(1700L, buffer.nextDeadline());
        assertTrue(buffer.offer(1100L, List.of(entry("別の下段", .60f, .66f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f),
                        new SubtitleSpeechOrderBuffer.Band(.50f, .56f))).isEmpty());
        // New rows observe the new limit; old rows keep their original limit.
        assertEquals(1400L, buffer.nextDeadline());
        var ready = buffer.drain(1400L);
        assertEquals(1, ready.size());
        assertEquals("別の下段", ready.get(0).event.getText());
        assertEquals(1700L, buffer.nextDeadline());
        assertEquals("先の下段", buffer.drain(1700L).get(0).event.getText());
        assertEquals(Long.MAX_VALUE, buffer.nextDeadline());

        buffer.setMaxWaitMs(0L);
        var released = buffer.offer(1800L, List.of(entry("即時", .30f, .36f)), upper);
        assertEquals(1, released.size());
    }

    @Test public void invalidWaitCannotCorruptLiveSettings() {
        var buffer = new SubtitleSpeechOrderBuffer(700L);
        try {
            buffer.setMaxWaitMs(220L);
            fail("Unsupported timing must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(700L, buffer.getMaxWaitMs());
        }
    }

    @Test public void productionUses300msByDefault() {
        assertEquals(300L, SubtitleSpeechOrderBuffer.MAX_WAIT_MS);
        assertEquals(300L, new SubtitleSpeechOrderBuffer().getMaxWaitMs());
    }

    @Test public void threeChoicesReleaseAtTheirOwnDeadlinesWithoutNewOcrFrames() {
        for (long delay : new long[]{700L, 300L, 0L}) {
            var buffer = new SubtitleSpeechOrderBuffer(delay);
            long start = 1000L;
            var immediate = buffer.offer(start, List.of(entry("下段", .30f, .36f)),
                    List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f)), 1200L);
            if (delay == 0L) {
                assertEquals(1, immediate.size());
                var records = buffer.takeReleases();
                assertEquals("deadline", records.get(0).reason);
                assertEquals(0L, records.get(0).heldMs);
                assertTrue(records.get(0).wasBlockedByUpper);
            } else {
                assertTrue(immediate.isEmpty());
                assertTrue(buffer.takeReleases().isEmpty());
                assertEquals(start + delay, buffer.nextDeadline());
                // On shorter timeouts, the logical deadline may already have passed
                // at OCR completion; a scheduled flush still must release it.
                assertTrue(buffer.drain(start + delay - 1).isEmpty());
                assertEquals(1, buffer.drain(start + delay).size());
                var releases = buffer.takeReleases();
                assertEquals(1, releases.size());
                assertEquals("deadline", releases.get(0).reason);
                assertTrue(releases.get(0).wasBlockedByUpper);
                assertEquals(Math.max(0L, start + delay - 1200L), releases.get(0).heldMs);
            }
            assertEquals(Long.MAX_VALUE, buffer.nextDeadline());
        }
    }

    @Test public void upperArrivalUnblocksAndSortsWithin300ms() {
        var buffer = new SubtitleSpeechOrderBuffer(300L);
        var waiting = List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f));
        assertTrue(buffer.offer(1000, List.of(entry("下段", .30f, .36f)),
                waiting, 1200).isEmpty());
        var ready = buffer.offer(1250, List.of(entry("上段", .20f, .26f)),
                List.of(), 1280);
        assertEquals(2, ready.size());
        assertEquals("上段", ready.get(0).event.getText());
        assertEquals("下段", ready.get(1).event.getText());
        var events = buffer.takeReleases();
        assertEquals(2, events.size());
        assertEquals("no_upper", events.get(0).reason);
        assertEquals("upper_cleared", events.get(1).reason);
        assertEquals(80L, events.get(1).heldMs);
    }

    @Test public void longerThreeRowVersionCarriesOriginalDeadlineAndHoldTimestamp() {
        var buffer = new SubtitleSpeechOrderBuffer(300L);
        buffer.offer(1000, List.of(entry("中段の字幕\n下段の字幕", .30f, .46f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.20f, .26f)), 1100);
        assertEquals(1300L, buffer.nextDeadline());
        var complete = buffer.offer(1280, List.of(entry("上段の字幕\n中段の字幕\n下段の字幕", .20f, .46f)),
                List.of(), 1280);
        assertEquals(1, complete.size());
        assertEquals("上段の字幕\n中段の字幕\n下段の字幕", complete.get(0).event.getText());
        assertEquals(180L, buffer.takeReleases().get(0).heldMs);
        assertEquals(Long.MAX_VALUE, buffer.nextDeadline());
    }

    @Test public void resetClearsPendingAndLastReleaseMetadata() {
        var buffer = new SubtitleSpeechOrderBuffer(0);
        buffer.offer(0, List.of(entry("下", .3f, .36f)),
                List.of(new SubtitleSpeechOrderBuffer.Band(.2f, .26f)));
        buffer.reset();
        assertTrue(buffer.takeReleases().isEmpty());
        assertTrue(buffer.drain(500).isEmpty());
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
        assertTrue(buffer.drain(309).isEmpty());
        assertEquals(1, buffer.drain(310).size());
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
        buffer.offer(180, List.of(), waiting);
        assertTrue(buffer.drain(299).isEmpty());
        assertEquals(1, buffer.drain(300).size());
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
