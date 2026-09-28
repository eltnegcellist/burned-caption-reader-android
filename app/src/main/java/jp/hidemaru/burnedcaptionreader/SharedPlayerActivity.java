package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import jp.hidemaru.burnedcaptionreader.ocr.MlKitJapaneseOcrEngine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrEngine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import jp.hidemaru.burnedcaptionreader.subtitle.AutoSubtitleRegionTracker;
import jp.hidemaru.burnedcaptionreader.subtitle.RowSpeechLedger;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleEvent;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleNormalizer;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleSpeechOrderBuffer;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleStabilizer;
import jp.hidemaru.burnedcaptionreader.tts.AndroidTtsSpeaker;
import jp.hidemaru.burnedcaptionreader.tts.SpeechEngine;

/**
 * The shared URL opens as a watch page in our own WebView. Only pixels inside
 * the visible HTML video element are copied. No screen capture permission is
 * involved. If the player is absent or protected, fail closed rather than
 * reading the surrounding YouTube page as a subtitle.
 */
public final class SharedPlayerActivity extends Activity {
    public static final String EXTRA_VIDEO_ID = "video_id";
    private static final long SAMPLE_MS = 480L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
    private final RowSpeechLedger ledger = new RowSpeechLedger(60_000L);
    private final SubtitleSpeechOrderBuffer order = new SubtitleSpeechOrderBuffer();
    private final SubtitleStabilizer.Config stableConfig = new SubtitleStabilizer.Config();
    private final Map<Integer, SubtitleStabilizer> stabilizers = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!sampling) return;
            if (!busy && player != null) locateVideoAndCopy();
            main.postDelayed(this, SAMPLE_MS);
        }
    };
    private WebView player;
    private TextView status;
    private TextView lastRead;
    private OcrEngine ocr;
    private SpeechEngine speaker;
    private AppPreferences preferences;
    private String videoId;
    private boolean sampling;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }
        preferences = new AppPreferences(this);
        stableConfig.stableMs = preferences.getStableMs();
        ocr = new MlKitJapaneseOcrEngine();
        speaker = new AndroidTtsSpeaker(this);
        getWindow().getDecorView().setKeepScreenOn(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        player = new WebView(this);
        player.setBackgroundColor(Color.BLACK);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(true);
        player.setWebChromeClient(new WebChromeClient());
        player.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String host = uri.getHost();
                // Never launch another app and never navigate to an untrusted origin.
                return !"https".equalsIgnoreCase(uri.getScheme()) || host == null
                        || !(host.equals("youtube.com") || host.endsWith(".youtube.com"));
            }
            @Override public void onPageFinished(WebView view, String url) {
                resetTracks();
                status.setText("動画をタップして再生してください。字幕領域を検出中…");
            }
        });
        root.addView(player, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(18, 12, 18, 12);
        status.setText("アプリ内で動画を読み込み中…");
        root.addView(status);
        lastRead = new TextView(this);
        lastRead.setTextColor(Color.WHITE);
        lastRead.setPadding(18, 8, 18, 8);
        root.addView(lastRead);
        Button stop = new Button(this);
        stop.setText("読み上げ停止");
        stop.setOnClickListener(v -> finish());
        root.addView(stop);
        setContentView(root);
        player.loadUrl("https://m.youtube.com/watch?v=" + videoId);
    }

    @Override protected void onResume() {
        super.onResume();
        if (player != null) player.onResume();
        sampling = true;
        main.removeCallbacks(sampler);
        main.post(sampler);
    }

    @Override protected void onPause() {
        sampling = false;
        main.removeCallbacks(sampler);
        if (player != null) player.onPause();
        if (speaker != null) speaker.stop();
        super.onPause();
    }

    private void locateVideoAndCopy() {
        busy = true;
        // Returning an array (not a string) keeps WebView's JSON result simple.
        player.evaluateJavascript("(() => {const v=document.querySelector('video');" +
                "if(!v)return null;const r=v.getBoundingClientRect();" +
                "return [r.left,r.top,r.right,r.bottom,innerWidth,innerHeight];})()", json -> {
            if (!sampling || player == null) { busy = false; return; }
            try {
                if (json == null || "null".equals(json)) {
                    status.setText("動画部分の読み込みを待っています");
                    busy = false;
                    return;
                }
                JSONArray bounds = new JSONArray(json);
                int width = player.getWidth(), height = player.getHeight();
                double xScale = width / Math.max(1.0, bounds.getDouble(4));
                double yScale = height / Math.max(1.0, bounds.getDouble(5));
                int left = clamp((int) Math.round(bounds.getDouble(0) * xScale), 0, width);
                int top = clamp((int) Math.round(bounds.getDouble(1) * yScale), 0, height);
                int right = clamp((int) Math.round(bounds.getDouble(2) * xScale), 0, width);
                int bottom = clamp((int) Math.round(bounds.getDouble(3) * yScale), 0, height);
                if (right - left < 100 || bottom - top < 80) {
                    status.setText("動画が画面外です。動画まで戻してください");
                    busy = false;
                    return;
                }
                int[] origin = new int[2];
                player.getLocationInWindow(origin);
                Rect rect = new Rect(origin[0] + left, origin[1] + top,
                        origin[0] + right, origin[1] + bottom);
                Bitmap frame = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
                PixelCopy.request(getWindow(), rect, frame, result -> {
                    if (!sampling || isDestroyed()) { frame.recycle(); busy = false; return; }
                    if (result != PixelCopy.SUCCESS) {
                        frame.recycle();
                        status.setText("動画の画像を取得できません (PixelCopy " + result + ")");
                        busy = false;
                        return;
                    }
                    recognizeFrame(frame);
                }, main);
            } catch (JSONException | IllegalArgumentException error) {
                status.setText("動画位置の取得に失敗しました");
                busy = false;
            } catch (RuntimeException error) {
                status.setText("動画画像の取得に失敗しました");
                busy = false;
            }
        });
    }

    private void recognizeFrame(Bitmap frame) {
        Bitmap input = frame;
        if (frame.getWidth() < 850) {
            float scale = Math.min(2.0f, 850f / frame.getWidth());
            input = Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * scale),
                    Math.round(frame.getHeight() * scale), true);
        }
        final Bitmap ocrInput = input;
        long timestamp = SystemClock.elapsedRealtime();
        ocr.recognize(ocrInput, result -> {
            try {
                if (sampling && !isDestroyed()) processResult(timestamp, result);
            } finally {
                ocrInput.recycle();
                if (ocrInput != frame) frame.recycle();
                busy = false;
            }
        }, error -> {
            ocrInput.recycle();
            if (ocrInput != frame) frame.recycle();
            if (sampling && !isDestroyed()) status.setText("OCRエラー: " + error.getMessage());
            busy = false;
        });
    }

    private void processResult(long timestamp, OcrResult result) {
        List<AutoSubtitleRegionTracker.Selection> selections =
                tracker.selectAll(timestamp, result, false, false);
        Set<Integer> visible = new HashSet<>();
        List<SubtitleSpeechOrderBuffer.Entry> committed = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Band> waiting = new ArrayList<>();
        for (AutoSubtitleRegionTracker.Selection selection : selections) {
            int id = selection.getTrackId();
            visible.add(id);
            lastSeen.put(id, timestamp);
            SubtitleStabilizer stabilizer = stabilizers.computeIfAbsent(id,
                    ignored -> new SubtitleStabilizer(stableConfig));
            SubtitleEvent event = stabilizer.observe(timestamp,
                    selection.getText(), selection.getConfidence());
            if (event != null) {
                committed.add(new SubtitleSpeechOrderBuffer.Entry(event,
                        selection.getTop(), selection.getBottom()));
            } else if (stabilizer.getState() == SubtitleStabilizer.State.CANDIDATE
                    || stabilizer.getState() == SubtitleStabilizer.State.STABILIZING) {
                waiting.add(new SubtitleSpeechOrderBuffer.Band(
                        selection.getTop(), selection.getBottom()));
            }
        }
        for (Map.Entry<Integer, SubtitleStabilizer> entry : stabilizers.entrySet()) {
            if (!visible.contains(entry.getKey())) entry.getValue().observe(timestamp, "", 100);
        }
        lastSeen.entrySet().removeIf(entry -> {
            if (timestamp - entry.getValue() <= 8_000L) return false;
            stabilizers.remove(entry.getKey());
            return true;
        });
        List<SubtitleSpeechOrderBuffer.Entry> ready = order.offer(timestamp, committed, waiting);
        if (!selections.isEmpty()) {
            StringBuilder current = new StringBuilder();
            for (AutoSubtitleRegionTracker.Selection selection : selections) {
                if (current.length() > 0) current.append('\n');
                current.append(selection.getText());
            }
            status.setText("動画内で検出した文字:\n" + current);
        } else {
            status.setText("動画内の字幕を探索中");
        }
        if (ready.isEmpty()) return;
        StringBuilder combined = new StringBuilder();
        for (SubtitleSpeechOrderBuffer.Entry entry : ready) {
            if (combined.length() > 0) combined.append('\n');
            combined.append(entry.event.getText());
        }
        SubtitleEvent event = new SubtitleEvent("local-" + timestamp,
                combined.toString(), timestamp, timestamp, 1.0);
        SpeechEngine.Mode mode = AppPreferences.MODE_LATEST.equals(preferences.getSpeechMode())
                ? SpeechEngine.Mode.LATEST : SpeechEngine.Mode.BALANCED;
        RowSpeechLedger.Reservation reservation = ledger.reserve(event, true,
                mode == SpeechEngine.Mode.LATEST);
        if (reservation == null) return;
        String text = SubtitleNormalizer.toSpeechText(reservation.getText());
        if (text.isEmpty()) { ledger.release(reservation.getId()); return; }
        lastRead.setText("読み上げ: " + reservation.getText());
        speaker.speak(text, mode, preferences.getSpeechRate(), new SpeechEngine.Completion() {
            @Override public void onStart() { ledger.markInFlight(reservation.getId()); }
            @Override public void onDone() {
                ledger.complete(reservation.getId(), SystemClock.elapsedRealtime());
            }
            @Override public void onError() { ledger.release(reservation.getId()); }
        });
    }

    private void resetTracks() {
        tracker.reset();
        stabilizers.clear();
        lastSeen.clear();
        order.reset();
        // Keep the already spoken rows across page navigation to prevent replay.
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override protected void onDestroy() {
        sampling = false;
        main.removeCallbacks(sampler);
        if (player != null) {
            player.stopLoading();
            player.destroy();
            player = null;
        }
        if (speaker != null) speaker.close();
        if (ocr != null) ocr.close();
        super.onDestroy();
    }
}
