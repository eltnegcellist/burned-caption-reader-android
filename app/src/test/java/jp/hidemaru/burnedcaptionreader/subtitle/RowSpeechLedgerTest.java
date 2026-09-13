package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class RowSpeechLedgerTest {
    private static SubtitleEvent event(String text, long time) {
        return new SubtitleEvent("e" + time, text, time - 100, time, 80);
    }

    @Test public void mergedEventEmitsOnlyUnspokenRows() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation first = ledger.reserve(event("曖昧な一行", 1000));
        assertNotNull(first);
        assertTrue(ledger.markInFlight(first.getId()));
        assertTrue(ledger.markSpoken(first.getId(), 1000));
        RowSpeechLedger.Reservation second = ledger.reserve(event("既読ではない二行\n新しい三行", 1500));
        assertNotNull(second);
        assertEquals("既読ではない二行\n新しい三行", second.getText());
    }

    @Test public void exactSecondRowSurvivesAmbiguousFirstRow() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation first = ledger.reserve(event("地獄が待ているため", 1000));
        assertNotNull(first);
        assertTrue(ledger.markInFlight(first.getId()));
        assertTrue(ledger.markSpoken(first.getId(), 1000));
        RowSpeechLedger.Reservation merged = ledger.reserve(event(
                "をれらを何ももえず丸香みしていると\n地獄が待わているため", 2000));
        assertNotNull(merged);
        assertEquals("をれらを何ももえず丸香みしていると", merged.getText());
    }

    @Test public void inFlightIsNotCompletedUntilDone() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation r = ledger.reserve(event("保留中の字幕です", 1000));
        assertTrue(ledger.markInFlight(r.getId()));
        assertEquals(0, ledger.spokenRowCount());
        assertNull(ledger.reserve(event("保留中の字幕です", 1100)));
        assertTrue(ledger.markSpoken(r.getId(), 1200));
        assertNull(ledger.reserve(event("保留中の字幕です", 1300)));
    }

    @Test public void cancellationReleasesReservation() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation reservation = ledger.reserve(event("再試行できます", 1000));
        assertNotNull(reservation);
        assertTrue(ledger.release(reservation.getId()));
        assertEquals(0, ledger.pendingReservationCount());
        assertNotNull(ledger.reserve(event("再試行できます", 1100)));
    }

    @Test public void balancedReplacementCarriesWaitingRowsIntoFullCaption() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation partial = ledger.reserve(event("一行目", 1000));
        assertNotNull(partial);

        RowSpeechLedger.Reservation full = ledger.reserve(
                event("一行目\n二行目", 1100), true, false);
        assertNotNull(full);
        assertEquals("一行目\n二行目", full.getText());
        assertFalse(ledger.markInFlight(partial.getId()));
        assertEquals(1, ledger.pendingReservationCount());
    }

    @Test public void latestReplacementCarriesInterruptedRowIntoFullCaption() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation partial = ledger.reserve(event("一行目", 1000));
        assertTrue(ledger.markInFlight(partial.getId()));

        RowSpeechLedger.Reservation full = ledger.reserve(
                event("一行目\n二行目", 1100), true, true);
        assertNotNull(full);
        assertEquals("一行目\n二行目", full.getText());
        assertFalse(ledger.markSpoken(partial.getId(), 1200));
        assertEquals(0, ledger.inFlightReservationCount());
    }

    @Test public void continuousModeKeepsWaitingRowAndEmitsOnlyNewAddition() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        assertNotNull(ledger.reserve(event("一行目", 1000)));
        RowSpeechLedger.Reservation addition = ledger.reserve(
                event("一行目\n二行目", 1100), false, false);
        assertNotNull(addition);
        assertEquals("二行目", addition.getText());
    }

    @Test public void changedNumbersAndNegationRemainNew() {
        RowSpeechLedger ledger = new RowSpeechLedger(60000);
        RowSpeechLedger.Reservation price = ledger.reserve(event("価格は100円です", 1000));
        assertTrue(ledger.markInFlight(price.getId()));
        assertTrue(ledger.markSpoken(price.getId(), 1000));
        assertNotNull(ledger.reserve(event("価格は200円です", 1100)));
        RowSpeechLedger.Reservation allowed = ledger.reserve(event("利用できます", 2000));
        assertTrue(ledger.markInFlight(allowed.getId()));
        assertTrue(ledger.markSpoken(allowed.getId(), 2000));
        assertNotNull(ledger.reserve(event("利用できません", 2100)));
    }
}
