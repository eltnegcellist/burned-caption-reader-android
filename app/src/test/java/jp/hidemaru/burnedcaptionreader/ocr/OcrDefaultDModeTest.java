package jp.hidemaru.burnedcaptionreader.ocr;

import static org.junit.Assert.*;
import org.junit.Test;

/** Preserve the already merged PR #43 default, including upgraded installs. */
public final class OcrDefaultDModeTest {
    @Test public void unsetPreferenceUsesD() {
        assertEquals(CaptionOcrMode.BOTH_V6, CaptionOcrMode.fromStoredValue(null));
        assertTrue(CaptionOcrMode.fromStoredValue(null).v6Detection);
        assertTrue(CaptionOcrMode.fromStoredValue(null).v6Recognition);
    }

    @Test public void persistedFourSelectionsSurviveUpgrade() {
        for (CaptionOcrMode candidate : CaptionOcrMode.values()) {
            assertEquals(candidate, CaptionOcrMode.fromStoredValue(candidate.storedValue));
        }
    }

    @Test public void unexpectedLegacyValueFallsBackToA() {
        assertEquals(CaptionOcrMode.BASELINE,
                CaptionOcrMode.fromStoredValue("unrecognized_legacy_mode"));
    }
}
