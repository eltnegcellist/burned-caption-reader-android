package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import org.json.JSONObject;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticRecorder;

/** Local full-evaluation probe; only active with the explicit debug recorder mode. */
final class EvaluationPlaybackProbe {
    private boolean inFlight;
    private boolean started;
    private double targetDuration;
    void sample(Context context, WebView player, DiagnosticRecorder recorder, Consumer<JSONObject> listener) {
        if (player == null || inFlight || !recorder.isFullEvaluation()) return;
        inFlight = true;
        File root = new File(context.getFilesDir(), "full-evaluation");
        File gate = new File(root, "start-from-zero");
        boolean rewindRequested = !started && gate.isFile();
        String js = "(() => {const v=document.querySelector('video');if(!v)return null;" +
                "const r=v.getBoundingClientRect();const ad=[...document.querySelectorAll(" +
                "'.ad-showing,.ytp-ad-text,.ytp-ad-preview-container,.ytp-ad-player-overlay,.ytp-ad-simple-ad-badge')].some(e=>{" +
                "const a=e.getBoundingClientRect();return a.width>0&&a.height>0&&a.top<r.bottom&&a.bottom>r.top;});" +
                "if(!v.__captionEvalListener){v.__captionEvalListener=true;v.addEventListener('ended',()=>{" +
                "if(Math.abs(v.duration*1000-window.__captionEvalTarget)<2000)window.__captionEvalEnded={media_ms:Math.round(v.currentTime*1000),duration_ms:Math.round(v.duration*1000)};});}" +
                "let adText=document.body.innerText.includes('Visit Advertiser');let rewound=false;if(" + rewindRequested + "&&!v.muted&&!v.paused&&!ad&&!adText&&v.duration>60){" +
                "window.__captionEvalTarget=v.duration*1000;window.__captionEvalEnded=null;v.currentTime=0;v.play();rewound=true;}" +
                "return {media_ms:Math.round(v.currentTime*1000),duration_ms:Number.isFinite(v.duration)?Math.round(v.duration*1000):0," +
                "ended:v.ended,paused:v.paused,muted:v.muted,ad_visible:ad||adText,ready_state:v.readyState," +
                "rewound:rewound,ended_event:window.__captionEvalEnded||null,url:location.href};})()";
        player.evaluateJavascript(js, raw -> {
            inFlight = false;
            try {
                if (raw == null || "null".equals(raw)) return;
                JSONObject state = new JSONObject(raw);
                if (state.optBoolean("rewound")) {
                    started = true;
                    targetDuration = state.getLong("duration_ms");
                    Files.deleteIfExists(gate.toPath());
                    recorder.event("evaluation_rewind", "media_ms", state.getLong("media_ms"),
                            "duration_ms", targetDuration);
                }
                state.put("mono_ms", SystemClock.elapsedRealtime()).put("evaluation_started", started)
                        .put("target_duration_ms", targetDuration);
                listener.accept(state);
                recorder.event("playback_state", "state", state);
                root.mkdirs();
                File temporary = new File(root,"state.tmp");
                Files.write(temporary.toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(),new File(root,"state.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception error) {
                recorder.event("playback_probe_error", "error", error.toString());
            }
        });
    }
}
