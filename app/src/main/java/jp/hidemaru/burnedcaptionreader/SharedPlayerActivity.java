package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.content.Intent;
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
import jp.hidemaru.burnedcaptionreader.ocr.SubtitleCropPlan;
import jp.hidemaru.burnedcaptionreader.ocr.OcrBitmapInputs;
import jp.hidemaru.burnedcaptionreader.ocr.CaptionStripRecovery;
import jp.hidemaru.burnedcaptionreader.subtitle.OcrRefinementSelector;
import jp.hidemaru.burnedcaptionreader.subtitle.TemporalOcrConsensus;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticRecorder;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticExport;
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
    private final Map<Integer, TemporalOcrConsensus> consensus = new HashMap<>();
    private final OcrRefinementSelector refinement = new OcrRefinementSelector();
    private DiagnosticRecorder diagnostics;
    private long frameSequence;
    private Rect evaluationRect;
    private boolean referenceBusy;
    private long referenceSequence;
    private long lastReferenceCapture;
    private long lastPlaybackProbe;
    private long evaluationMediaMs = -1;
    private boolean evaluationStarted;
    private long evaluationMediaObservedAt;
    private long evaluationDurationMs;
    private boolean evaluationAd;
    private final EvaluationPlaybackProbe evaluationProbe = new EvaluationPlaybackProbe();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!sampling) return;
            if (diagnostics != null && diagnostics.isFullEvaluation()) {
                if (SystemClock.elapsedRealtime() - lastPlaybackProbe >= 1000) {
                    lastPlaybackProbe = SystemClock.elapsedRealtime();
                    evaluationProbe.sample(SharedPlayerActivity.this, player, diagnostics, state -> {
                        if (state.optBoolean("rewound")) {
                            captureGeneration++;
                            if (speaker != null) speaker.stop();
                            ledger.reset(); resetTracks();
                        }
                        evaluationMediaMs = state.optLong("media_ms", -1);
                        evaluationStarted = state.optBoolean("evaluation_started");
                        evaluationMediaObservedAt = state.optLong("mono_ms");
                        evaluationDurationMs = state.optLong("duration_ms");
                        evaluationAd = state.optBoolean("ad_visible");
                    });
                }
                captureEvaluationReference();
            }
            if (!readingPaused && !busy && player != null) {
                applyCcPreference();
                // Also inspect the watch URL in fullscreen: YouTube can switch
                // videos without a full page navigation (autoplay / SPA).
                locateVideoAndCopy();
            }
            main.postDelayed(this, SAMPLE_MS);
        }
    };
    private final Runnable orderFlush = this::flushOrderedSpeech;
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
        diagnostics = DiagnosticRecorder.get(this);
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
            @Override public void onPageStarted(WebView view, String url,
                    Bitmap favicon) {
                captureGeneration++;
                if (speaker != null) speaker.stop();
                resetTracks();
                updateVideoContext(url);
            }
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
                updateVideoContext(url);
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
        // TTS cancellation releases its ledger reservation. Its stabilizer
        // must also forget the commit, or the visible unfinished caption can
        // never be retried after returning. Completed ledger rows are retained.
        resetTracks();
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
                "return [r.left,r.top,r.right,r.bottom,innerWidth,innerHeight," +
                "location.href,v.videoWidth,v.videoHeight];})()", json -> {
            if (!sampling || readingPaused || generation != captureGeneration
                    || player == null) { busy = false; return; }
            try {
                if (json == null || "null".equals(json)) {
                    status.setText("動画部分の読み込みを待っています");
                    busy = false;
                    return;
                }
                JSONArray bounds = new JSONArray(json);
                if (updateVideoContext(bounds.getString(6))) {
                    busy = false;
                    return; // Next sample belongs to the new video generation.
                }
                if (fullView != null) {
                    videoPixelWidth = bounds.getInt(7);
                    videoPixelHeight = bounds.getInt(8);
                    copyFullscreenVideo();
                    // The custom view may not have been laid out yet.
                    if (fullView.getWidth() < 100 || fullView.getHeight() < 80) busy = false;
                    return;
                }
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
        evaluationRect = new Rect(rect);
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

    private void captureEvaluationReference() {
        if (!evaluationStarted || referenceBusy || evaluationRect == null || isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (now-lastReferenceCapture < 900) return;
        lastReferenceCapture = now;
        referenceBusy = true;
        final long referenceId = ++referenceSequence;
        final long media = evaluationMediaMs;
        try {
            Bitmap frame = Bitmap.createBitmap(evaluationRect.width(), evaluationRect.height(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(getWindow(), evaluationRect, frame, result -> {
                try {
                    if (result == PixelCopy.SUCCESS && !isDestroyed())
                        diagnostics.image("reference_frame", frame, "reference_id", referenceId,
                                "media_ms", media, "video_id", videoId,
                                "media_observed_mono_ms", evaluationMediaObservedAt,
                                "duration_ms", evaluationDurationMs, "ad_visible", evaluationAd,
                                "generation", captureGeneration);
                    else diagnostics.event("reference_error", "reference_id", referenceId, "pixelcopy", result);
                } finally { frame.recycle(); referenceBusy = false; }
            }, main);
        } catch (RuntimeException e) {
            referenceBusy = false;
            diagnostics.event("reference_error", "reference_id", referenceId, "error", e.toString());
        }
    }

    private static final class BandText {
        final AutoSubtitleRegionTracker.Selection selection;
        final String text;
        final double confidence;
        BandText(AutoSubtitleRegionTracker.Selection selection, String text, double confidence) {
            this.selection = selection; this.text = text; this.confidence = confidence;
        }
    }

    private boolean acceptsFrame(int generation) {
        return sampling && !readingPaused && generation == captureGeneration && !isDestroyed();
    }

    private void finishFrame(Bitmap frame) {
        if (!frame.isRecycled()) frame.recycle();
        busy = false;
    }

    private void recognizeFrame(Bitmap frame, int generation) {
        long timestamp = SystemClock.elapsedRealtime();
        long frameId = ++frameSequence;
        diagnostics.image("video_frame", frame, "frame_id", frameId,
                "generation", generation, "video_id", videoId);
        // Broad detection is bounded; refinement always uses this original frame.
        final Bitmap input;
        try {
            input = OcrBitmapInputs.coarse(frame);
        } catch (RuntimeException error) {
            diagnostics.event("ocr_error", "frame_id", frameId, "stage", "coarse_prepare", "error", error.toString());
            finishFrame(frame); return;
        }
        diagnostics.image("ocr_input", input, "frame_id", frameId, "stage", "coarse");
        ocr.recognize(input, result -> {
            if (input != frame) input.recycle();
            if (!acceptsFrame(generation)) { finishFrame(frame); return; }
            diagnostics.ocr("ocr_raw", frameId, -1, result);
            recoverStrip(frame, generation, timestamp, frameId, result);
        }, error -> {
            if (input != frame) input.recycle();
            diagnostics.event("ocr_error", "frame_id", frameId, "stage", "coarse", "error", error.toString());
            if (acceptsFrame(generation)) status.setText("OCRエラー: " + error.getMessage());
            finishFrame(frame);
        });
    }

    private void recoverStrip(Bitmap frame, int generation, long timestamp, long frameId, OcrResult raw) {
        Bitmap input = null;
        SubtitleCropPlan plan = null;
        try {
            plan = OcrBitmapInputs.stripRecoveryPlan(frame, raw);
            if (plan != null) input = OcrBitmapInputs.refined(frame, plan);
        } catch (RuntimeException error) {
            diagnostics.event("strip_recovery_fallback", "frame_id", frameId, "reason", "prepare_error");
        }
        if (input == null) { selectBands(frame, generation, timestamp, frameId, raw); return; }
        final Bitmap crop = input;
        final SubtitleCropPlan region = plan;
        diagnostics.image("ocr_input", crop, "frame_id", frameId, "stage", "strip_recovery");
        ocr.recognize(crop, result -> {
            if (crop != frame) crop.recycle();
            if (!acceptsFrame(generation)) { finishFrame(frame); return; }
            diagnostics.ocr("ocr_strip_recovery", frameId, -1, result);
            selectBands(frame, generation, timestamp, frameId,
                    CaptionStripRecovery.merge(raw, result, region, frame.getHeight()));
        }, error -> {
            if (crop != frame) crop.recycle();
            diagnostics.event("strip_recovery_fallback", "frame_id", frameId, "reason", "ocr_error");
            if (!acceptsFrame(generation)) { finishFrame(frame); return; }
            selectBands(frame, generation, timestamp, frameId, raw);
        });
    }

    private void selectBands(Bitmap frame, int generation, long timestamp, long frameId, OcrResult raw) {
        List<AutoSubtitleRegionTracker.Selection> selections = tracker.selectForRecognition(timestamp, raw);
        diagnostics.event("selection_summary", "frame_id", frameId, "raw_rows", raw.getLines().size(),
                "selected_bands", selections.size(), "policy", "subtitle_tracker");
        refineBands(frame, generation, timestamp, frameId, selections, 0, new ArrayList<>());
    }

    private void refineBands(Bitmap frame, int generation, long timestamp, long frameId,
            List<AutoSubtitleRegionTracker.Selection> selections, int index, List<BandText> bands) {
        if (!acceptsFrame(generation)) { finishFrame(frame); return; }
        if (index == selections.size()) {
            try { processResult(timestamp, frameId, bands); }
            finally {
                diagnostics.event("frame_done", "frame_id", frameId,
                        "processing_ms", SystemClock.elapsedRealtime() - timestamp);
                finishFrame(frame);
            }
            return;
        }
        AutoSubtitleRegionTracker.Selection selection = selections.get(index);
        diagnostics.event("selection", "frame_id", frameId, "track_id", selection.getTrackId(),
                "text", selection.getText(), "top", selection.getTop(), "bottom", selection.getBottom(),
                "confidence", selection.getConfidence(), "locked", selection.isLocked());
        final Bitmap input;
        try {
            SubtitleCropPlan plan = SubtitleCropPlan.create(frame.getWidth(), frame.getHeight(),
                    selection.getTop(), selection.getBottom());
            input = OcrBitmapInputs.refined(frame, plan);
        } catch (RuntimeException error) {
            diagnostics.event("refinement_fallback", "frame_id", frameId, "reason", "crop_error");
            bands.add(new BandText(selection, selection.getText(), selection.getConfidence()));
            refineBands(frame, generation, timestamp, frameId, selections, index + 1, bands);
            return;
        }
        diagnostics.image("ocr_input", input, "frame_id", frameId, "track_id", selection.getTrackId(), "stage", "refined");
        ocr.recognize(input, result -> {
            if (!acceptsFrame(generation)) {
                if (input != frame) input.recycle();
                finishFrame(frame); return;
            }
            diagnostics.ocr("ocr_refined", frameId, selection.getTrackId(), result);
            OcrRefinementSelector.Result regular = refinement.select(selection.getText(), selection.getConfidence(), result);
            Bitmap white = null;
            try {
                if (refinement.shouldTryWhiteCore(regular)) white = OcrBitmapInputs.whiteCore(input);
            } catch (RuntimeException error) {
                diagnostics.event("refinement_fallback", "frame_id", frameId, "track_id", selection.getTrackId(),
                        "reason", "white_core_prepare_error", "error", error.toString());
            } finally { if (input != frame) input.recycle(); }
            if (white == null) {
                acceptRefinement(frame, generation, timestamp, frameId, selections, index, bands, selection, regular);
                return;
            }
            final Bitmap mask = white;
            diagnostics.image("ocr_input", mask, "frame_id", frameId, "track_id", selection.getTrackId(), "stage", "white_core");
            ocr.recognize(mask, alternate -> {
                mask.recycle();
                if (!acceptsFrame(generation)) { finishFrame(frame); return; }
                diagnostics.ocr("ocr_white_core", frameId, selection.getTrackId(), alternate);
                OcrRefinementSelector.Result chosen = refinement.select(selection.getText(), selection.getConfidence(), result, alternate);
                acceptRefinement(frame, generation, timestamp, frameId, selections, index, bands, selection, chosen);
            }, error -> {
                mask.recycle();
                diagnostics.event("refinement_fallback", "frame_id", frameId, "track_id", selection.getTrackId(),
                        "reason", "white_core_ocr_error", "error", error.toString());
                if (!acceptsFrame(generation)) { finishFrame(frame); return; }
                acceptRefinement(frame, generation, timestamp, frameId, selections, index, bands, selection, regular);
            });
        }, error -> {
            if (input != frame) input.recycle();
            diagnostics.event("refinement_fallback", "frame_id", frameId, "track_id", selection.getTrackId(),
                    "reason", "ocr_error", "error", error.toString());
            bands.add(new BandText(selection, selection.getText(), selection.getConfidence()));
            refineBands(frame, generation, timestamp, frameId, selections, index + 1, bands);
        });
    }

    private void acceptRefinement(Bitmap frame, int generation, long timestamp, long frameId,
            List<AutoSubtitleRegionTracker.Selection> selections, int index, List<BandText> bands,
            AutoSubtitleRegionTracker.Selection selection, OcrRefinementSelector.Result chosen) {
        bands.add(new BandText(selection, chosen.getText(), chosen.getConfidence()));
        diagnostics.event("refinement_choice", "frame_id", frameId, "track_id", selection.getTrackId(),
                "text", chosen.getText(), "confidence", chosen.getConfidence());
        refineBands(frame, generation, timestamp, frameId, selections, index + 1, bands);
    }

    private void processResult(long timestamp, long frameId, List<BandText> bands) {
        Set<Integer> visible = new HashSet<>();
        List<SubtitleSpeechOrderBuffer.Entry> committed = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Band> waiting = new ArrayList<>();
        for (BandText band : bands) {
            AutoSubtitleRegionTracker.Selection selection = band.selection;
            // The app reads Japanese burned-in captions. English-only YouTube CC
            // must not become an utterance if the site's overlay changes.
            if (!JAPANESE.matcher(band.text).find()) {
                diagnostics.event("selection_excluded", "frame_id", frameId, "track_id", selection.getTrackId(), "reason", "no_japanese");
                continue;
            }
            int id = selection.getTrackId();
            visible.add(id);
            lastSeen.put(id, timestamp);
            SubtitleStabilizer stabilizer = stabilizers.computeIfAbsent(id,
                    ignored -> new SubtitleStabilizer(stableConfig));
            TemporalOcrConsensus.Result fused = consensus.computeIfAbsent(id,
                    ignored -> new TemporalOcrConsensus()).observe(timestamp, band.text,
                            band.confidence, selection.getText());
            diagnostics.event("ocr_consensus", "frame_id", frameId, "track_id", id,
                    "text", fused.getText(), "confidence", fused.getConfidence());
            SubtitleEvent event = stabilizer.observe(timestamp, fused.getText(), fused.getConfidence());
            diagnostics.event("stabilizer", "frame_id", frameId, "track_id", id,
                    "state", stabilizer.getState().name(), "committed", event != null);
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
            consensus.remove(entry.getKey());
            return true;
        });
        List<SubtitleSpeechOrderBuffer.Entry> ready = order.offer(timestamp, committed, waiting);
        if (!bands.isEmpty()) {
            StringBuilder current = new StringBuilder();
            for (BandText band : bands) {
                if (current.length() > 0) current.append('\n');
                current.append(band.text);
            }
            status.setText("動画内で検出した文字:\n" + current);
        } else {
            status.setText("動画内の字幕を探索中");
        }
        speakReady(timestamp, frameId, ready);
        scheduleOrderFlush();
    }

    private void flushOrderedSpeech() {
        if (!sampling || readingPaused || isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        speakReady(now, -1, order.drain(now));
        scheduleOrderFlush();
    }

    private void scheduleOrderFlush() {
        main.removeCallbacks(orderFlush);
        long deadline = order.nextDeadline();
        if (sampling && !readingPaused && deadline != Long.MAX_VALUE) {
            main.postDelayed(orderFlush, Math.max(0, deadline - SystemClock.elapsedRealtime()));
        }
    }

    private void speakReady(long timestamp, long frameId, List<SubtitleSpeechOrderBuffer.Entry> ready) {
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
        diagnostics.event("speech_decision", "frame_id", frameId, "event_id", event.getId(),
                "text", event.getText(), "spoken_rows", ledger.spokenRowCount(),
                "pending_reservations", ledger.pendingReservationCount(),
                "decision", reservation == null ? "covered_by_spoken_or_reserved_rows" : "reserved");
        if (reservation == null) return;
        String text = SubtitleNormalizer.toSpeechText(reservation.getText());
        if (text.isEmpty()) { ledger.release(reservation.getId()); return; }
        lastRead.setText("読み上げ: " + reservation.getText());
        speaker.speak(text, mode, preferences.getSpeechRate(), new SpeechEngine.Completion() {
            @Override public void onStart() {
                ledger.markInFlight(reservation.getId());
                diagnostics.event("speech_ledger", "reservation_id", reservation.getId(), "state", "in_flight");
            }
            @Override public void onDone() {
                ledger.complete(reservation.getId(), SystemClock.elapsedRealtime());
                diagnostics.event("speech_ledger", "reservation_id", reservation.getId(), "state", "completed");
            }
            @Override public void onError() {
                ledger.release(reservation.getId());
                diagnostics.event("speech_ledger", "reservation_id", reservation.getId(), "state", "released");
            }
        });
    }

    private boolean updateVideoContext(String url) {
        String nextVideoId = YouTubeShareUrl.videoId(url);
        if (nextVideoId == null || nextVideoId.equals(videoId)) return false;
        if (speaker != null) speaker.stop();
        // Identical dialogue in two different videos is new content.
        ledger.reset();
        videoId = nextVideoId;
        captureGeneration++;
        resetTracks();
        if (lastRead != null) lastRead.setText("");
        return true;
    }

    private void resetTracks() {
        main.removeCallbacks(orderFlush);
        tracker.reset();
        stabilizers.clear();
        consensus.clear();
        lastSeen.clear();
        order.reset();
        // Retain completed rows for the same video across interruptions and
        // layout changes. updateVideoContext clears them for a different video.
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        DiagnosticExport.handle(this, request, result, data);
    }

    @Override protected void onDestroy() {
        sampling = false;
        main.removeCallbacks(sampler);
        main.removeCallbacks(orderFlush);
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
