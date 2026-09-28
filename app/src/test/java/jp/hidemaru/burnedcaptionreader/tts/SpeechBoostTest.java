package jp.hidemaru.burnedcaptionreader.tts;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechBoostTest {
    private static class FakeEffect implements SpeechBoost.Effect {
        int gain, calls, releases;
        boolean fail;
        public void enable(int value) {
            calls++;
            if (fail) throw new IllegalStateException("unsupported");
            gain = value;
        }
        public void release() { releases++; }
    }

    @Test public void onlyDedicatedSessionReceivesEffect() {
        FakeEffect effect = new FakeEffect();
        SpeechBoost boost = new SpeechBoost(42, id -> { assertEquals(42, id); return effect; });
        assertTrue(boost.apply(0));
        assertEquals(0, effect.calls);
        assertTrue(boost.apply(1));
        assertEquals(600, effect.gain);
        assertTrue(boost.apply(2));
        assertEquals(1200, effect.gain);
        assertTrue(boost.apply(0));
        assertEquals(1, effect.releases);
    }

    @Test public void neverCreatesGlobalOrInvalidSessionEffect() {
        for (int id : new int[]{0, -1}) {
            SpeechBoost boost = new SpeechBoost(id, session -> { fail("global effect"); return null; });
            assertFalse(boost.apply(1));
            assertTrue(boost.apply(0));
        }
    }

    @Test public void failedEnableReleasesAndDoesNotRetryEverySubtitle() {
        FakeEffect effect = new FakeEffect();
        effect.fail = true;
        SpeechBoost boost = new SpeechBoost(42, id -> effect);
        assertFalse(boost.apply(1));
        assertFalse(boost.apply(1));
        assertEquals(1, effect.calls);
        assertEquals(1, effect.releases);
        assertTrue(boost.apply(0));
        effect.fail = false;
        assertTrue(boost.apply(1));
        assertEquals(2, effect.calls);
    }

    @Test public void unsupportedCreationFallsBackWithoutThrowing() {
        SpeechBoost boost = new SpeechBoost(42, id -> { throw new UnsupportedOperationException(); });
        assertFalse(boost.apply(2));
        boost.close();
    }

    @Test public void closingIsIdempotentAndCannotReactivate() {
        FakeEffect effect = new FakeEffect();
        SpeechBoost boost = new SpeechBoost(42, id -> effect);
        boost.apply(1);
        boost.close();
        boost.close();
        assertEquals(1, effect.releases);
        assertFalse(boost.apply(2));
        assertEquals(1, effect.calls);
    }

    @Test public void invalidStoredLevelsAreBoundedAndRepeatedSettingIsStable() {
        FakeEffect effect = new FakeEffect();
        SpeechBoost boost = new SpeechBoost(42, id -> effect);
        assertTrue(boost.apply(-5));
        assertTrue(boost.apply(999));
        assertTrue(boost.apply(2));
        assertEquals(1200, effect.gain);
        assertEquals(1, effect.calls);
    }

    @Test public void releaseFailureDoesNotStopOrdinarySpeech() {
        FakeEffect effect = new FakeEffect() {
            @Override public void release() { throw new IllegalStateException(); }
        };
        SpeechBoost boost = new SpeechBoost(42, id -> effect);
        assertTrue(boost.apply(1));
        assertTrue(boost.apply(0));
        boost.close();
    }
}
