package jp.hidemaru.burnedcaptionreader.tts;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.UUID;
import jp.hidemaru.burnedcaptionreader.AppPreferences;

public final class AndroidTtsSpeaker implements SpeechEngine {
    private static final class PendingSpeech {
        final String text;
        final Mode mode;
        final float rate;
        final long queuedAt;

        PendingSpeech(String text, Mode mode, float rate) {
            this.text = text;
            this.mode = mode;
            this.rate = rate;
            this.queuedAt = SystemClock.elapsedRealtime();
        }
    }

    private final BoundedSpeechQueue<PendingSpeech> pending = new BoundedSpeechQueue<>(2);
    private final SharedPreferences preferences;
    private TextToSpeech textToSpeech;
    private Listener listener;
    private String activeUtteranceId;
    private boolean ready;
    private boolean closed;

    public AndroidTtsSpeaker(Context context) {
        Context appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(
                AppPreferences.PREFERENCES_FILE, Context.MODE_PRIVATE);
        textToSpeech = new TextToSpeech(appContext, status -> {
            if (closed || status != TextToSpeech.SUCCESS) return;
            textToSpeech.setLanguage(Locale.JAPAN);
            textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    notifySpeaking(true);
                }

                @Override public void onDone(String utteranceId) {
                    finishUtterance(utteranceId);
                }

                @Override public void onError(String utteranceId) {
                    finishUtterance(utteranceId);
                }
            });
            ready = true;
            drainPending();
        });
    }

    @Override
    public synchronized void speak(String text, Mode mode, float rate) {
        if (closed || text == null || text.trim().isEmpty()) return;
        PendingSpeech speech = new PendingSpeech(text, mode, rate);
        if (!ready) {
            pending.offer(speech, mode);
            return;
        }
        if (mode == Mode.LATEST) {
            activeUtteranceId = null;
            pending.clear();
            textToSpeech.stop();
            speakNow(speech);
        } else if (activeUtteranceId == null) {
            speakNow(speech);
        } else {
            pending.offer(speech, mode);
        }
    }

    private void drainPending() {
        synchronized (this) {
            if (activeUtteranceId == null) startNext();
        }
    }

    private void speakNow(PendingSpeech speech) {
        if (closed || !ready || textToSpeech == null) return;
        long waitingMs = Math.max(0L, SystemClock.elapsedRealtime() - speech.queuedAt);
        // Once speech falls behind the video, catch up more aggressively. The user
        // selected base rate still dominates when there is no queue delay.
        float catchUp = Math.min(0.60f,
                (waitingMs / 3_000f) * 0.18f + pending.size() * 0.18f);
        textToSpeech.setSpeechRate(Math.min(2.0f, speech.rate * (1.0f + catchUp)));

        Bundle params = new Bundle();
        float volume = preferences.getFloat(AppPreferences.SPEECH_VOLUME, 1.0f);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                Math.max(0.0f, Math.min(1.0f, volume)));

        String utteranceId = UUID.randomUUID().toString();
        activeUtteranceId = utteranceId;
        int result = textToSpeech.speak(
                speech.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        if (result == TextToSpeech.ERROR) finishUtterance(utteranceId);
    }

    private synchronized void finishUtterance(String utteranceId) {
        if (closed || activeUtteranceId == null || !activeUtteranceId.equals(utteranceId)) return;
        activeUtteranceId = null;
        if (!startNext()) notifySpeaking(false);
    }

    private boolean startNext() {
        PendingSpeech next = pending.poll();
        if (next == null) return false;
        speakNow(next);
        return true;
    }

    private void notifySpeaking(boolean speaking) {
        Listener current;
        synchronized (this) { current = listener; }
        if (current != null) current.onSpeakingStateChanged(speaking);
    }

    @Override
    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized void stop() {
        pending.clear();
        activeUtteranceId = null;
        if (textToSpeech != null) textToSpeech.stop();
        notifySpeaking(false);
    }

    @Override
    public synchronized void close() {
        closed = true;
        ready = false;
        pending.clear();
        activeUtteranceId = null;
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        notifySpeaking(false);
        listener = null;
    }
}
