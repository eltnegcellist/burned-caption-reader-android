package jp.hidemaru.burnedcaptionreader;

import org.junit.Test;
import static org.junit.Assert.*;

/** Guard against regressions that would spoof activation or obscure controls. */
public final class YouTubeAudioStartAssistTest {
    @Test public void usesRealVideoGestureAndNeverClicksYouTubeControls() {
        String script = YouTubeAudioStartAssist.INSTALL_SCRIPT;
        assertTrue(script.contains("event.isTrusted"));
        assertTrue(script.contains("pointerup"));
        assertTrue(script.contains("getBoundingClientRect"));
        assertTrue(script.contains("v.muted = false"));
        assertFalse(script.contains(".click()"));
        assertFalse(script.contains("dispatchEvent"));
        assertFalse(script.contains("display:none"));
        assertFalse(script.contains("visibility:hidden"));
    }
    @Test public void protectsUserMutePreferenceAfterFirstSuccessfulUnmute() {
        String script = YouTubeAudioStartAssist.INSTALL_SCRIPT;
        assertTrue(script.contains("state.activated.has(v)"));
        assertTrue(script.contains("state.activated.add(v)"));
        assertTrue(script.contains("state.attempts >= 3"));
    }
}
