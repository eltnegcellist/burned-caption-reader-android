package jp.hidemaru.burnedcaptionreader.tts;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechLevelTest {
    @Test public void normalVolumeAndBoostMeetAtOneControl() {
        assertEquals(0f, SpeechLevel.engineVolume(0), 0.001f);
        assertEquals(0.5f, SpeechLevel.engineVolume(50), 0.001f);
        assertEquals(1f, SpeechLevel.engineVolume(100), 0.001f);
        assertEquals(1f, SpeechLevel.engineVolume(400), 0.001f);
        assertEquals(0, SpeechLevel.gainMillibels(100));
        assertEquals(602, SpeechLevel.gainMillibels(200));
        assertEquals(1204, SpeechLevel.gainMillibels(400));
    }

    @Test public void migratesLegacyVolumeAndBoostTogether() {
        assertEquals(100, SpeechLevel.fromLegacy(1f, 0));
        assertEquals(200, SpeechLevel.fromLegacy(1f, 1));
        assertEquals(400, SpeechLevel.fromLegacy(1f, 2));
        assertEquals(100, SpeechLevel.fromLegacy(0.5f, 1));
        assertEquals(200, SpeechLevel.fromLegacy(0.5f, 2));
        assertEquals(0, SpeechLevel.fromLegacy(0f, 2));
    }

    @Test public void handlesInvalidSettings() {
        assertEquals(0, SpeechLevel.clamp(-1));
        assertEquals(400, SpeechLevel.clamp(999));
        assertEquals(100, SpeechLevel.fromLegacy(Float.NaN, 0));
    }
}
