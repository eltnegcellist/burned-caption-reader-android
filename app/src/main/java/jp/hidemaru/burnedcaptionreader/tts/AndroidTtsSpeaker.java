package jp.hidemaru.burnedcaptionreader.tts;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.UUID;
import jp.hidemaru.burnedcaptionreader.AppPreferences;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticRecorder;

public final class AndroidTtsSpeaker implements SpeechEngine {
    private static final class PendingSpeech {
        final String id = UUID.randomUUID().toString();
        final String text;
        final Mode mode;
        final float rate;
        final long queuedAt;
        final Completion completion;

        PendingSpeech(String text, Mode mode, float rate, Completion completion) {
            this.text = text;
            this.mode = mode;
            this.rate = rate;
            this.queuedAt = SystemClock.elapsedRealtime();
            this.completion = completion;
        }
    }

    private final BoundedSpeechQueue<PendingSpeech> pending = new BoundedSpeechQueue<>(2);
    private final DiagnosticRecorder diagnostics;
    private final SharedPreferences preferences;
    private final SpeechBoost speechBoost;
    private final int speechSessionId;
    private final Context appContext;
    private boolean boostWarningShown;
    private TextToSpeech textToSpeech;
    private Listener listener;
    private String activeUtteranceId;
    private Completion activeCompletion;
    private boolean ready;
    private boolean initializationFailed;
    private boolean closed;

    public AndroidTtsSpeaker(Context context) {
        appContext = context.getApplicationContext();
        diagnostics = DiagnosticRecorder.get(appContext);
        preferences = appContext.getSharedPreferences(
                AppPreferences.PREFERENCES_FILE, Context.MODE_PRIVATE);
        int session = AudioManager.ERROR;
        try {
            AudioManager audio = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) session = audio.generateAudioSessionId();
        } catch (RuntimeException error) {
            diagnostics.event("tts_boost_session_error", "error", error.toString());
        }
        speechSessionId = session;
        speechBoost = new SpeechBoost(session, id -> {
            LoudnessEnhancer enhancer = new LoudnessEnhancer(id);
            return new SpeechBoost.Effect() {
                @Override public void enable(int gainMillibels) {
                    enhancer.setTargetGain(gainMillibels);
                    if (enhancer.setEnabled(true) != AudioEffect.SUCCESS || !enhancer.getEnabled()) {
                        throw new IllegalStateException("Speech boost could not be enabled");
                    }
                }
                @Override public void release() { enhancer.release(); }
            };
        });
        textToSpeech = new TextToSpeech(appContext, status -> {
            diagnostics.event("tts_init", "status", status);
            if (closed || status != TextToSpeech.SUCCESS) {
                initializationFailed = status != TextToSpeech.SUCCESS;
                synchronized (AndroidTtsSpeaker.this) { failPending(); }
                return;
            }
            textToSpeech.setLanguage(Locale.JAPAN);
            textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    diagnostics.event("tts_start", "utterance_id", utteranceId);
                    synchronized (AndroidTtsSpeaker.this) {
                        if (activeUtteranceId != null && activeUtteranceId.equals(utteranceId)) {
                            notifySpeaking(true);
                            notifyStarted(utteranceId);
                        }
                    }
                }

                @Override public void onDone(String utteranceId) {
                    diagnostics.event("tts_done", "utterance_id", utteranceId);
                    finishUtterance(utteranceId, true);
                }

                @Override public void onStop(String utteranceId, boolean interrupted) {
                    diagnostics.event("tts_stop", "utterance_id", utteranceId, "interrupted", interrupted);
                    finishUtterance(utteranceId, false);
                }
                @Override public void onError(String utteranceId) {
                    diagnostics.event("tts_error", "utterance_id", utteranceId);
                    finishUtterance(utteranceId, false);
                }
            });
            ready = true;
            drainPending();
        });
    }

    @Override
    public synchronized void speak(String text, Mode mode, float rate) {
        speak(text, mode, rate, null);
    }

    @Override
    public synchronized void speak(String text, Mode mode, float rate, Completion completion) {
        if (closed || initializationFailed || text == null || text.trim().isEmpty()) {
            if (completion != null) completion.onError();
            return;
        }
        PendingSpeech speech = new PendingSpeech(text, mode, rate, completion);
        diagnostics.event("tts_request", "utterance_id", speech.id, "text", text,
                "mode", mode.name(), "rate", rate, "ready", ready);
        if (!ready) {
            diagnostics.event("tts_queued", "utterance_id", speech.id, "previous_queue_size", pending.size(), "mode", mode.name());
            if (mode == Mode.BALANCED || mode == Mode.LATEST) failPending();
            failEvicted(pending.offer(speech, mode));
            return;
        }
        if (mode == Mode.LATEST) {
            diagnostics.event("tts_interrupt", "active_id", activeUtteranceId, "replacement_id", speech.id);
            if (activeCompletion != null) activeCompletion.onError();
            activeUtteranceId = null;
            activeCompletion = null;
            failPending();
            textToSpeech.stop();
            speakNow(speech);
        } else if (activeUtteranceId == null) {
            speakNow(speech);
        } else {
            diagnostics.event("tts_queued", "utterance_id", speech.id, "previous_queue_size", pending.size(), "mode", mode.name());
            if (mode == Mode.BALANCED) failPending();
            failEvicted(pending.offer(speech, mode));
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
        // A private session lets the effect boost speech without changing browser audio.
        if (speechSessionId > 0) {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, speechSessionId);
        }
        int boostLevel = SpeechBoost.normalize(preferences.getInt(AppPreferences.SPEECH_BOOST, 0));
        boolean boostAvailable = speechBoost.apply(boostLevel);
        diagnostics.event("tts_boost", "level", boostLevel, "gain_mb", boostLevel * 600,
                "session_id", speechSessionId, "effect_configured", boostAvailable);
        if (!boostAvailable && !boostWarningShown) {
            boostWarningShown = true;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext,
                    "音量ブーストを利用できません。通常音量で読み上げます。",
                    Toast.LENGTH_LONG).show());
        } else if (boostLevel == 0) {
            boostWarningShown = false;
        }
        float volume = preferences.getFloat(AppPreferences.SPEECH_VOLUME, 1.0f);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                Math.max(0.0f, Math.min(1.0f, volume)));

        String utteranceId = speech.id;
        if (diagnostics.isRecording()) diagnostics.event("tts_submit", "utterance_id", utteranceId, "text", speech.text,
                "waiting_ms", waitingMs, "effective_rate", Math.min(2.0f, speech.rate * (1.0f + catchUp)),
                "volume", volume, "engine", textToSpeech.getDefaultEngine(),
                "voice", String.valueOf(textToSpeech.getVoice()));
        activeUtteranceId = utteranceId;
        activeCompletion = speech.completion;
        int result = textToSpeech.speak(
                speech.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        diagnostics.event("tts_submit_result", "utterance_id", utteranceId, "result", result);
        if (result == TextToSpeech.ERROR) finishUtterance(utteranceId, false);
    }

    private synchronized void finishUtterance(String utteranceId, boolean success) {
        if (closed || activeUtteranceId == null || !activeUtteranceId.equals(utteranceId)) return;
        Completion completion = activeCompletion;
        activeUtteranceId = null;
        activeCompletion = null;
        if (completion != null) {
            if (success) completion.onDone(); else completion.onError();
        }
        if (!startNext()) notifySpeaking(false);
    }

    private boolean startNext() {
        PendingSpeech next = pending.poll();
        if (next == null) return false;
        speakNow(next);
        return true;
    }

    private synchronized void notifyStarted(String utteranceId) {
        if (activeUtteranceId != null && activeUtteranceId.equals(utteranceId)
                && activeCompletion != null) activeCompletion.onStart();
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
        diagnostics.event("tts_stop_requested", "active_id", activeUtteranceId);
        failPending();
        if (activeCompletion != null) activeCompletion.onError();
        activeUtteranceId = null;
        activeCompletion = null;
        if (textToSpeech != null) textToSpeech.stop();
        notifySpeaking(false);
    }

    @Override
    public synchronized void close() {
        diagnostics.event("tts_close", "active_id", activeUtteranceId);
        closed = true;
        ready = false;
        failPending();
        if (activeCompletion != null) activeCompletion.onError();
        activeUtteranceId = null;
        activeCompletion = null;
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        speechBoost.close();
        notifySpeaking(false);
        listener = null;
    }

    private void failPending() {
        PendingSpeech value;
        while ((value = pending.poll()) != null) {
            if (value.completion != null) value.completion.onError();
        }
    }

    private void failEvicted(PendingSpeech value) {
        if (value != null && value.completion != null) value.completion.onError();
    }
}
