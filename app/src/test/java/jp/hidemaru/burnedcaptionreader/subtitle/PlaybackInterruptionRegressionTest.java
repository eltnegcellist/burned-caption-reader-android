package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure pipeline regressions; Activity/WebView callbacks need device validation. */
public final class PlaybackInterruptionRegressionTest {
    private SubtitleStabilizer stabilizer() {
        SubtitleStabilizer.Config config = new SubtitleStabilizer.Config();
        config.stableMs = 300;
        return new SubtitleStabilizer(config);
    }

    private SubtitleEvent commit(SubtitleStabilizer stabilizer, long time) {
        stabilizer.observe(time, "同じ字幕が表示されています", 90);
        return stabilizer.observe(time + 480, "同じ字幕が表示されています", 90);
    }

    @Test public void interruptedSpeechCanRetryAfterDetectionStateIsReset() {
        RowSpeechLedger ledger = new RowSpeechLedger(60_000);
        SubtitleStabilizer detector = stabilizer();
        RowSpeechLedger.Reservation interrupted = ledger.reserve(commit(detector, 1000));
        assertNotNull(interrupted);
        assertTrue(ledger.markInFlight(interrupted.getId()));
        assertTrue(ledger.release(interrupted.getId()));
        assertNull(commit(detector, 2000)); // The former Activity path lost this caption.
        SubtitleEvent retry = commit(stabilizer(), 3000);
        assertNotNull(retry);
        assertNotNull(ledger.reserve(retry));
    }

    @Test public void completedSpeechDoesNotReplayWhenDetectionStateIsReset() {
        RowSpeechLedger ledger = new RowSpeechLedger(60_000);
        RowSpeechLedger.Reservation completed = ledger.reserve(commit(stabilizer(), 1000));
        assertTrue(ledger.complete(completed.getId(), 1600));
        assertNull(ledger.reserve(commit(stabilizer(), 2000)));
    }

    @Test public void newVideoMayReadSameDialogueAndOldCompletionIsIgnored() {
        RowSpeechLedger ledger = new RowSpeechLedger(60_000);
        RowSpeechLedger.Reservation old = ledger.reserve(commit(stabilizer(), 1000));
        assertTrue(ledger.markInFlight(old.getId()));
        ledger.reset();
        assertFalse(ledger.complete(old.getId(), 2000));
        assertNotNull(ledger.reserve(commit(stabilizer(), 2100)));
    }
}
