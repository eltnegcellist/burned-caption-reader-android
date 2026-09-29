package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    private static final Pattern JAPANESE = Pattern.compile("[\u3040-\u30ff\u3400-\u9fff]");
    // Browser-rendered CC overlays, not text burned into the video pixels.
    private static final String CC_STYLE = "video::cue{visibility:hidden!important}" +
            ".ytp-caption-window-container,.caption-window,.captions-text," +
            "ytm-caption-window,.ytp-caption-window-bottom{display:none!important}";
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
            if (!readingPaused && !busy && player != null) {
                applyCcPreference();
                if (fullView != null) copyFullscreenVideo();
                else locateVideoAndCopy();
            }
            main.postDelayed(this, SAMPLE_MS);
        }
    };
    private WebView player;
    private LinearLayout root;
    private LinearLayout controls;
    private TextView status;
    private TextView lastRead;
    private Button speechToggle;
    private SettingsPanel settingsPanel;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private int previousOrientation;
    private int videoPixelWidth;
    private int videoPixelHeight;
    private boolean hideCc = true;
    private OcrEngine ocr;
    private SpeechEngine speaker;
    private AppPreferences preferences;
    private String videoId;
    private boolean sampling;
    private boolean busy;
    private boolean readingPaused;
    private int captureGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }
        preferences = new AppPreferences(this);
        stableConfig.stableMs = preferences.getStableMs();
        hideCc = preferences.isYouTubeCcHidden();
        ocr = new MlKitJapaneseOcrEngine();
        speaker = new AndroidTtsSpeaker(this);
        getWindow().getDecorView().setKeepScreenOn(preferences.isKeepScreenOn());
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ReaderUi.SURFACE);
        player = new WebView(this);
        player.setBackgroundColor(Color.BLACK);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(true);
        player.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreen(view, callback);
            }
            @Override public void onHideCustomView() {
                hideFullscreen();
            }
        });
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
                captureGeneration++;
                resetTracks();
                applyCcPreference();
                if (!readingPaused) {
                    status.setText("動画をタップして再生してください。字幕領域を検出中…");
                }
            }
        });
        root.addView(player, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(ReaderUi.dp(this, 18), ReaderUi.dp(this, 12),
                ReaderUi.dp(this, 18), ReaderUi.dp(this, 18));
        root.addView(controls);
        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(ReaderUi.MUTED);
        status.setMaxLines(2);
        status.setText("アプリ内で動画を読み込み中…");
        controls.addView(status);
        lastRead = new TextView(this);
        lastRead.setTextColor(ReaderUi.INK);
        lastRead.setTextSize(15);
        lastRead.setMaxLines(2);
        controls.addView(lastRead, ReaderUi.block(this, 6));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(actions, ReaderUi.block(this, 14));
        speechToggle = ReaderUi.button(this, "読み上げを一時停止", true);
        LinearLayout.LayoutParams speechParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.7f);
        actions.addView(speechToggle, speechParams);
        speechToggle.setOnClickListener(v -> setReadingPaused(!readingPaused));
        Button settings = ReaderUi.button(this, "設定", false);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        settingsParams.leftMargin = ReaderUi.dp(this, 10);
        actions.addView(settings, settingsParams);
        settings.setOnClickListener(v -> showSettings());
        Button home = ReaderUi.button(this, "トップ画面に戻る", false);
        controls.addView(home, ReaderUi.block(this, 10));
        home.setOnClickListener(v -> finish());
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
        captureGeneration++;
        main.removeCallbacks(sampler);
        if (player != null) player.onPause();
        if (speaker != null) speaker.stop();
        super.onPause();
    }

    private void showFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullView = view;
        fullCallback = callback;
        previousOrientation = getRequestedOrientation();
        root.setVisibility(View.GONE);
        FrameLayout decor = (FrameLayout) getWindow().getDecorView();
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        view.setBackgroundColor(Color.BLACK);
        decor.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                bars.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                bars.hide(WindowInsets.Type.systemBars());
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        // Landscape for ordinary videos, portrait for native vertical videos.
        player.evaluateJavascript("(() => {const v=document.querySelector('video');" +
                "return v?[v.videoWidth,v.videoHeight]:null;})()", json -> {
            if (fullView != view || json == null || "null".equals(json)) return;
            try {
                JSONArray size = new JSONArray(json);
                videoPixelWidth = size.getInt(0);
                videoPixelHeight = size.getInt(1);
                if (videoPixelWidth > 0 && videoPixelHeight > 0) {
                    setRequestedOrientation(videoPixelHeight > videoPixelWidth
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
            } catch (JSONException ignored) {
                // The custom view still works without aspect-ratio information.
            }
        });
    }

    private void hideFullscreen() {
        if (fullView == null) return;
        View former = fullView;
        fullView = null;
        fullCallback = null;
        videoPixelWidth = 0;
        videoPixelHeight = 0;
        ((FrameLayout) getWindow().getDecorView()).removeView(former);
        root.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.show(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        setRequestedOrientation(previousOrientation);
    }

    @Override public void onBackPressed() {
        if (fullView != null) {
            WebChromeClient.CustomViewCallback callback = fullCallback;
            hideFullscreen();
            if (callback != null) callback.onCustomViewHidden();
        } else if (settingsPanel != null) {
            closeSettings();
        } else {
            super.onBackPressed();
        }
    }

    private void setReadingPaused(boolean paused) {
        if (readingPaused == paused) return;
        readingPaused = paused;
        captureGeneration++;
        speechToggle.setText(paused ? "読み上げを再開" : "読み上げを一時停止");
        if (paused) {
            speaker.stop();
            resetTracks();
            status.setText("読み上げを一時停止中 · 動画は再生中");
        } else {
            resetTracks();
            status.setText("動画内の字幕を探索中");
        }
    }

    private void showSettings() {
        if (settingsPanel != null) return;
        captureGeneration++;
        settingsPanel = new SettingsPanel(this, preferences, this::closeSettings,
                this::applyLiveSettings, true);
        controls.setVisibility(View.GONE);
        int height = Math.min(ReaderUi.dp(this, 410),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f));
        root.addView(settingsPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        applyLiveSettings();
    }

    private void closeSettings() {
        if (settingsPanel == null) return;
        captureGeneration++;
        root.removeView(settingsPanel);
        settingsPanel = null;
        controls.setVisibility(View.VISIBLE);
    }

    private void applyLiveSettings() {
        stableConfig.stableMs = preferences.getStableMs();
        hideCc = preferences.isYouTubeCcHidden();
        getWindow().getDecorView().setKeepScreenOn(preferences.isKeepScreenOn());
        applyCcPreference();
    }

    private void applyCcPreference() {
        if (player == null) return;
        String css = CC_STYLE;
        String script = "(() => {let s=document.getElementById('caption-reader-cc-hide');" +
                (hideCc
                        ? "if(!s){s=document.createElement('style');" +
                          "s.id='caption-reader-cc-hide';s.textContent='" + css + "';" +
                          "document.head.appendChild(s);}" +
                          "const v=document.querySelector('video');if(v&&v.textTracks)" +
                          "for(const t of v.textTracks){if(t.kind==='captions'||t.kind==='subtitles')" +
                          "t.mode='disabled';}"
                        : "if(s)s.remove();") + "})()";
        player.evaluateJavascript(script, null);
    }

    private void copyFullscreenVideo() {
        View view = fullView;
        if (view == null || view.getWidth() < 100 || view.getHeight() < 80) return;
        int width = view.getWidth();
        int height = view.getHeight();
        int fitWidth = width;
        int fitHeight = height;
        if (videoPixelWidth > 0 && videoPixelHeight > 0) {
            double aspect = videoPixelWidth / (double) videoPixelHeight;
            if (width / (double) height > aspect) fitWidth = (int) Math.round(height * aspect);
            else fitHeight = (int) Math.round(width / aspect);
        }
        int[] origin = new int[2];
        view.getLocationInWindow(origin);
        Rect area = new Rect(origin[0] + (width - fitWidth) / 2,
                origin[1] + (height - fitHeight) / 2,
                origin[0] + (width + fitWidth) / 2,
                origin[1] + (height + fitHeight) / 2);
        copyWindowFrame(area);
    }

    private void locateVideoAndCopy() {
        busy = true;
        final int generation = captureGeneration;
        // Returning an array (not a string) keeps WebView's JSON result simple.
        player.evaluateJavascript("(() => {const v=document.querySelector('video');" +
                "if(!v)return null;const r=v.getBoundingClientRect();" +
                "return [r.left,r.top,r.right,r.bottom,innerWidth,innerHeight];})()", json -> {
            if (!sampling || readingPaused || generation != captureGeneration
                    || player == null) { busy = false; return; }
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
                copyWindowFrame(rect);
            } catch (JSONException | IllegalArgumentException error) {
                status.setText("動画位置の取得に失敗しました");
                busy = false;
            } catch (RuntimeException error) {
                status.setText("動画画像の取得に失敗しました");
                busy = false;
            }
        });
    }

    private void copyWindowFrame(Rect rect) {
        busy = true;
        final int generation = captureGeneration;
        try {
            Bitmap frame = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(getWindow(), rect, frame, result -> {
                if (!sampling || readingPaused || generation != captureGeneration || isDestroyed()) {
                    frame.recycle(); busy = false; return;
                }
                if (result != PixelCopy.SUCCESS) {
                    frame.recycle();
                    status.setText("動画の画像を取得できません (PixelCopy " + result + ")");
                    busy = false;
                    return;
                }
                recognizeFrame(frame, generation);
            }, main);
        } catch (RuntimeException error) {
            busy = false;
            if (sampling) status.setText("動画画像の取得に失敗しました");
        }
    }

    private void recognizeFrame(Bitmap frame, int generation) {
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
                if (sampling && !readingPaused && generation == captureGeneration
                        && !isDestroyed()) processResult(timestamp, result);
            } finally {
                ocrInput.recycle();
                if (ocrInput != frame) frame.recycle();
                busy = false;
            }
        }, error -> {
            ocrInput.recycle();
            if (ocrInput != frame) frame.recycle();
            if (sampling && !readingPaused && generation == captureGeneration && !isDestroyed()) {
                status.setText("OCRエラー: " + error.getMessage());
            }
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
            // The app reads Japanese burned-in captions. English-only YouTube CC
            // must not become an utterance if the site's overlay changes.
            if (!JAPANESE.matcher(selection.getText()).find()) continue;
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
        if (fullView != null) hideFullscreen();
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
