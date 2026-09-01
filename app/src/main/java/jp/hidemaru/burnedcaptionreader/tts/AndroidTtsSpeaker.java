package jp.hidemaru.burnedcaptionreader.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

public final class AndroidTtsSpeaker implements SpeechEngine {
    private static final class PendingSpeech {
        final String text;
        final Mode mode;
        final float rate;

        PendingSpeech(String text, Mode mode, float rate) {
            this.text = text;
            this.mode = mode;
            this.rate = rate;
        }
    }

    private final Queue<PendingSpeech> pending = new ArrayDeque<>();
    private TextToSpeech textToSpeech;
    private boolean ready;
    private boolean closed;

    public AndroidTtsSpeaker(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
            if (closed || status != TextToSpeech.SUCCESS) return;
            textToSpeech.setLanguage(Locale.JAPAN);
            textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            ready = true;
            drainPending();
        });
    }

    @Override
    public synchronized void speak(String text, Mode mode, float rate) {
        if (closed || text == null || text.trim().isEmpty()) return;
        PendingSpeech speech = new PendingSpeech(text, mode, rate);
        if (!ready) {
            if (mode == Mode.LATEST) pending.clear();
            pending.add(speech);
            return;
        }
        speakNow(speech);
    }

    private void drainPending() {
        synchronized (this) {
            while (!pending.isEmpty()) {
                speakNow(pending.remove());
            }
        }
    }

    private void speakNow(PendingSpeech speech) {
        textToSpeech.setSpeechRate(speech.rate);
        if (speech.mode == Mode.LATEST) {
            textToSpeech.stop();
        }
        int queueMode = speech.mode == Mode.LATEST ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        textToSpeech.speak(speech.text, queueMode, null, UUID.randomUUID().toString());
    }

    @Override
    public synchronized void stop() {
        pending.clear();
        if (textToSpeech != null) textToSpeech.stop();
    }

    @Override
    public synchronized void close() {
        closed = true;
        ready = false;
        pending.clear();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }
}
