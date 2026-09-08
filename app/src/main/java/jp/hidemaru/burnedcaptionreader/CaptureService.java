package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import jp.hidemaru.burnedcaptionreader.capture.SceneChangeDetector;
import jp.hidemaru.burnedcaptionreader.ocr.MlKitJapaneseOcrEngine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrEngine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;
import jp.hidemaru.burnedcaptionreader.subtitle.AutoSubtitleRegionTracker;
import jp.hidemaru.burnedcaptionreader.subtitle.OcrRefinementSelector;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleEvent;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleEventManager;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleNormalizer;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleStabilizer;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleSpeechOrderBuffer;
import jp.hidemaru.burnedcaptionreader.subtitle.TemporalOcrConsensus;
import jp.hidemaru.burnedcaptionreader.tts.AndroidTtsSpeaker;
import jp.hidemaru.burnedcaptionreader.tts.SpeechEngine;

import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticRecorder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CaptureService extends Service {
    public static final String ACTION_START = "jp.hidemaru.burnedcaptionreader.START";
    public static final String ACTION_STOP = "jp.hidemaru.burnedcaptionreader.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "caption_reader_capture";
    private static final int NOTIFICATION_ID = 7101;
    private static final long MANUAL_SAMPLE_INTERVAL_MS = 330L;
    private static final long AUTO_SAMPLE_INTERVAL_MS = 480L;
    private static final long AUTO_TRACK_EXPIRES_MS = 10_000L;
    private static final int AUTO_DETECTION_MAX_WIDTH = 1_100;
    private static final int AUTO_REFINEMENT_MAX_WIDTH = 2_000;
    private static final int AUTO_REFINEMENT_MIN_HEIGHT = 180;

    private static final class RefinedSelection {
        final AutoSubtitleRegionTracker.Selection selection;
        final String text;
        final double confidence;

        RefinedSelection(AutoSubtitleRegionTracker.Selection selection,
                         String text, double confidence) {
            this.selection = selection;
            this.text = text;
            this.confidence = confidence;
        }
    }

    private final SubtitleSpeechOrderBuffer speechOrder = new SubtitleSpeechOrderBuffer();
    private final Runnable flushSpeechOrder = this::flushOrderedSpeech;

    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final SubtitleStabilizer.Config stabilizerConfig = new SubtitleStabilizer.Config();
    private final SubtitleEventManager eventManager = new SubtitleEventManager(60_000L);
    private final AutoSubtitleRegionTracker regionTracker = new AutoSubtitleRegionTracker();
    private final SceneChangeDetector sceneChangeDetector = new SceneChangeDetector();
    private final Map<Integer, SubtitleStabilizer> autoStabilizers = new HashMap<>();
    private final Map<Integer, TemporalOcrConsensus> autoConsensus = new HashMap<>();
    private final Map<Integer, Long> autoTrackLastSeen = new HashMap<>();

    private DiagnosticRecorder diagnostics;
    private AppPreferences preferences;
    private OcrEngine ocrEngine;
    private SpeechEngine speechEngine;
    private BrowserMediaController browserMediaController;
    private SubtitleStabilizer stabilizer;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private long lastSampleAt;
    private int roiVersion = -1;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;
    private volatile boolean shuttingDown;
    private volatile boolean projectionEnded;
    private PowerManager.WakeLock screenWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new AppPreferences(this);
        diagnostics = DiagnosticRecorder.get(this);
        diagnostics.event("capture_service_start");
        ocrEngine = new MlKitJapaneseOcrEngine();
        speechEngine = new AndroidTtsSpeaker(this);
        browserMediaController = new BrowserMediaController(this);
        speechEngine.setListener(speaking -> {
            if (!speaking && browserMediaController != null) {
                browserMediaController.resumeIfPausedByUs();
            }
        });
        stabilizerConfig.stableMs = preferences.getStableMs();
        stabilizer = new SubtitleStabilizer(stabilizerConfig);
        captureThread = new HandlerThread("caption-frame-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || projection != null) {
            return START_NOT_STICKY;
        }

        startAsForeground("画面共有を開始しています");
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            AppState.setStatus("画面共有の許可を取得できませんでした");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(resultCode, resultData);
        } catch (RuntimeException error) {
            AppState.setStatus("画面共有を開始できません: " + safeMessage(error));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (shuttingDown) return;
                projectionEnded = true;
                AppState.setStatus("画面共有が終了しました（ロック後は再開始が必要です）");
                stopSelf();
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        && width > 0 && height > 0 && captureHandler != null) {
                    captureHandler.post(() -> resizeVirtualDisplay(width, height));
                }
            }
        }, captureHandler);

        updateCaptureSize();
        imageReader = newImageReader(captureWidth, captureHeight);
        virtualDisplay = projection.createVirtualDisplay(
                "BurnedCaptionReader",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
        updateScreenWakeLock();
        AppState.setRunning(true);
        AppState.setStatus(preferences.isAutoRegion()
                ? "字幕位置を自動検出しています"
                : preferences.hasRoi() ? "手動字幕領域を監視しています"
                : "通知から字幕領域を指定してください");
        updateNotification(AppState.getStatus());
    }

    private ImageReader newImageReader(int width, int height) {
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        return reader;
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            updateScreenWakeLock();
            boolean automatic = preferences.isAutoRegion();
            long sampleInterval = automatic ? AUTO_SAMPLE_INTERVAL_MS : MANUAL_SAMPLE_INTERVAL_MS;
            if (now - lastSampleAt < sampleInterval) return;
            lastSampleAt = now;

            Bitmap frame = imageToBitmap(image);
            if (frame == null) return;
            AppState.setLatestFrame(frame);

            if (!automatic && !preferences.hasRoi()) return;
            int currentRoiVersion = preferences.getRoiVersion();
            if (currentRoiVersion != roiVersion) {
                roiVersion = currentRoiVersion;
                resetSpeechOrder();
                stabilizer.reset();
                regionTracker.reset();
                sceneChangeDetector.reset();
                autoStabilizers.clear();
                autoConsensus.clear();
                autoTrackLastSeen.clear();
            }
            stabilizerConfig.stableMs = preferences.getStableMs();
            if (!ocrBusy.compareAndSet(false, true)) return;

            Bitmap source = automatic
                    ? cropRoi(frame, automaticVideoRoi(frame))
                    : cropRoi(frame, preferences.getRoi());
            boolean sceneChanged = automatic && sceneChangeDetector.observe(
                    now, sampleLuminance(source, 16, 12));
            Bitmap prepared = resizeForOcr(source,
                    automatic ? AUTO_DETECTION_MAX_WIDTH : 1_800);
            if (automatic) {
                recognize(prepared, source, now, true, sceneChanged);
            } else {
                if (prepared != source) source.recycle();
                recognize(prepared, null, now, false, false);
            }
        } catch (RuntimeException error) {
            AppState.setStatus("画面処理エラー: " + safeMessage(error));
            ocrBusy.set(false);
        } finally {
            if (image != null) image.close();
        }
    }

    private void recognize(Bitmap detectionBitmap, Bitmap highResSource,
                           long timestamp, boolean automatic, boolean sceneChanged) {
        diagnostics.image("ocr_input", detectionBitmap, "frame_id", timestamp,
                "automatic", automatic, "scene_changed", sceneChanged,
                "rate", preferences.getSpeechRate(), "stable_ms", preferences.getStableMs(),
                "roi_version", roiVersion, "capture_width", captureWidth, "capture_height", captureHeight);
        ocrEngine.recognize(detectionBitmap,
                result -> {
                    diagnostics.ocr("ocr_raw", timestamp, -1, result);
                    if (automatic) {
                        handleAutomaticOcrResult(result, timestamp, sceneChanged, highResSource,
                                () -> finishOcrPass(detectionBitmap, highResSource));
                        return;
                    }
                    try {
                        handleManualOcrResult(result, timestamp);
                    } finally {
                        finishOcrPass(detectionBitmap, null);
                    }
                },
                error -> {
                    diagnostics.event("ocr_error", "frame_id", timestamp, "error", safeMessage(error));
                    finishOcrPass(detectionBitmap, highResSource);
                    AppState.setStatus("OCRエラー: " + safeMessage(error));
                });
    }

    private void handleManualOcrResult(OcrResult result, long timestamp) {
        if (shuttingDown || projectionEnded) return;
        String observedText = result.getText();
        double confidence = result.getConfidence();
        AppState.setLastOcr(observedText);
        SubtitleEvent candidate = stabilizer.observe(timestamp, observedText, confidence);
        diagnostics.event("manual_stabilizer", "frame_id", timestamp, "state", stabilizer.getState().name(),
                "text", observedText, "event_id", candidate == null ? "" : candidate.getId());
        if (candidate == null) {
            AppState.setStatus("手動範囲（" + stateLabel(stabilizer.getState()) + "）");
            return;
        }
        SubtitleEvent accepted = eventManager.accept(candidate);
        diagnostics.event("dedup", "event_id", candidate.getId(), "text", candidate.getText(),
                "accepted", accepted != null, "output", accepted == null ? "" : accepted.getText());
        if (accepted == null) return;

        speakAcceptedText(accepted.getText());
    }

    private void handleAutomaticOcrResult(OcrResult result, long timestamp,
                                          boolean sceneChanged, Bitmap highResSource,
                                          Runnable completion) {
        if (shuttingDown || projectionEnded) {
            completion.run();
            return;
        }
        if (sceneChanged) {
            diagnostics.event("scene_reset", "frame_id", timestamp);
            resetSpeechOrder();
            autoStabilizers.clear();
            autoConsensus.clear();
            autoTrackLastSeen.clear();
        }

        // The video ROI already excludes most browser/page content. Do not discard
        // the upper 18% of that ROI: real burned-in captions can live there.
        List<AutoSubtitleRegionTracker.Selection> selections = regionTracker.selectAll(
                timestamp, result, false, sceneChanged);
        if (selections.isEmpty()) {
            processAutomaticSelections(result, timestamp, new ArrayList<>());
            completion.run();
            return;
        }

        refineSelections(highResSource, timestamp, selections, 0, new ArrayList<>(), refined -> {
            try {
                processAutomaticSelections(result, timestamp, refined);
            } finally {
                completion.run();
            }
        });
    }

    private void refineSelections(Bitmap highResSource, long timestamp,
                                  List<AutoSubtitleRegionTracker.Selection> selections,
                                  int index, List<RefinedSelection> output,
                                  Consumer<List<RefinedSelection>> completion) {
        if (index >= selections.size() || highResSource == null || highResSource.isRecycled()) {
            if (index < selections.size()) {
                for (int i = index; i < selections.size(); i++) {
                    AutoSubtitleRegionTracker.Selection selection = selections.get(i);
                    output.add(new RefinedSelection(selection, selection.getText(),
                            selection.getConfidence()));
                }
            }
            completion.accept(output);
            return;
        }

        AutoSubtitleRegionTracker.Selection selection = selections.get(index);
        Bitmap band = cropRefinementBand(highResSource, selection.getTop(), selection.getBottom());
        Bitmap prepared = resizeForRefinement(band);
        if (prepared != band) band.recycle();

        diagnostics.image("refinement_input", prepared, "frame_id", timestamp,
                "track_id", selection.getTrackId(), "selection_top", selection.getTop(),
                "selection_bottom", selection.getBottom(), "original", selection.getText());
        ocrEngine.recognize(prepared,
                refinedResult -> {
                    diagnostics.ocr("ocr_refined", timestamp, selection.getTrackId(), refinedResult);
                    try {
                        RefinedSelection chosen = selectBestRefinement(selection, refinedResult);
                        diagnostics.event("refinement_choice", "frame_id", timestamp,
                                "track_id", selection.getTrackId(), "text", chosen.text,
                                "confidence", chosen.confidence);
                        output.add(chosen);
                    } finally {
                        prepared.recycle();
                    }
                    refineSelections(highResSource, timestamp, selections, index + 1, output, completion);
                },
                error -> {
                    diagnostics.event("refinement_error", "frame_id", timestamp,
                            "track_id", selection.getTrackId(), "error", safeMessage(error));
                    prepared.recycle();
                    output.add(new RefinedSelection(selection, selection.getText(),
                            selection.getConfidence()));
                    refineSelections(highResSource, timestamp, selections, index + 1, output, completion);
                });
    }

    private RefinedSelection selectBestRefinement(AutoSubtitleRegionTracker.Selection selection,
                                                  OcrResult refinedResult) {
        OcrRefinementSelector.Result result = new OcrRefinementSelector().select(
                selection.getText(), selection.getConfidence(), refinedResult);
        return new RefinedSelection(selection, result.getText(), result.getConfidence());
    }

    private synchronized void processAutomaticSelections(OcrResult rawResult, long timestamp,
                                            List<RefinedSelection> selections) {
        if (selections.isEmpty()) {
            diagnostics.event("no_selection", "frame_id", timestamp);
            AppState.setLastOcr(rawResult.getText());
            for (SubtitleStabilizer track : autoStabilizers.values()) {
                track.observe(timestamp, "", 100.0);
            }
            speakOrderedEvents(speechOrder.offer(SystemClock.elapsedRealtime(),
                    new ArrayList<>(), new ArrayList<>()));
            scheduleSpeechOrderFlush();
            expireAutoTracks(timestamp);
            AppState.setStatus("自動字幕帯を学習中");
            return;
        }

        StringBuilder observed = new StringBuilder();
        Set<Integer> visibleTracks = new HashSet<>();
        List<SubtitleSpeechOrderBuffer.Entry> committed = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Band> waitingBands = new ArrayList<>();
        boolean allLocked = true;
        SubtitleStabilizer.State mostActiveState = SubtitleStabilizer.State.EMPTY;
        for (RefinedSelection refined : selections) {
            AutoSubtitleRegionTracker.Selection selection = refined.selection;
            int trackId = selection.getTrackId();
            visibleTracks.add(trackId);
            autoTrackLastSeen.put(trackId, timestamp);
            allLocked &= selection.isLocked();

            TemporalOcrConsensus.Result consensus = autoConsensus.computeIfAbsent(
                    trackId, ignored -> new TemporalOcrConsensus())
                    .observe(timestamp, refined.text, refined.confidence);
            if (observed.length() > 0) observed.append('\n');
            observed.append(consensus.getText());

            SubtitleStabilizer track = autoStabilizers.computeIfAbsent(
                    trackId, ignored -> new SubtitleStabilizer(stabilizerConfig));
            SubtitleEvent candidate = track.observe(timestamp, consensus.getText(),
                    consensus.getConfidence());
            mostActiveState = track.getState();
            diagnostics.event("stabilizer", "frame_id", timestamp, "track_id", trackId,
                    "selected", selection.getText(), "refined", refined.text,
                    "consensus", consensus.getText(), "confidence", consensus.getConfidence(),
                    "top", selection.getTop(), "bottom", selection.getBottom(),
                    "state", mostActiveState.name(), "event_id", candidate == null ? "" : candidate.getId(),
                    "committed_text", candidate == null ? "" : candidate.getText());
            if (candidate == null) {
                if (track.getState() == SubtitleStabilizer.State.CANDIDATE
                        || track.getState() == SubtitleStabilizer.State.STABILIZING) {
                    waitingBands.add(new SubtitleSpeechOrderBuffer.Band(
                            selection.getTop(), selection.getBottom()));
                }
                continue;
            }
            committed.add(new SubtitleSpeechOrderBuffer.Entry(candidate, selection.getTop(), selection.getBottom()));
        }

        for (Map.Entry<Integer, SubtitleStabilizer> entry : autoStabilizers.entrySet()) {
            if (!visibleTracks.contains(entry.getKey())) {
                entry.getValue().observe(timestamp, "", 100.0);
            }
        }
        expireAutoTracks(timestamp);
        AppState.setLastOcr(observed.toString());
        List<SubtitleSpeechOrderBuffer.Entry> ready = speechOrder.offer(
                SystemClock.elapsedRealtime(), committed, waitingBands);
        scheduleSpeechOrderFlush();
        if (ready.isEmpty()) {
            String label = selections.size() == 1 ? "1本" : selections.size() + "本";
            AppState.setStatus((allLocked ? "自動字幕帯を追跡中（" : "自動字幕候補（")
                    + label + "・" + stateLabel(mostActiveState) + "）");
            return;
        }

        speakOrderedEvents(ready);
    }

    private void speakOrderedEvents(List<SubtitleSpeechOrderBuffer.Entry> entries) {
        if (entries.isEmpty() || shuttingDown || projectionEnded) return;
        StringBuilder speech = new StringBuilder();
        for (SubtitleSpeechOrderBuffer.Entry entry : entries) {
            if (SubtitleNormalizer.toSpeechText(entry.event.getText()).isEmpty()) continue;
            SubtitleEvent accepted = eventManager.accept(entry.event);
            diagnostics.event("dedup", "event_id", entry.event.getId(), "text", entry.event.getText(),
                    "accepted", accepted != null, "output", accepted == null ? "" : accepted.getText());
            if (accepted == null) continue;
            if (speech.length() > 0) speech.append('\n');
            speech.append(accepted.getText());
        }
        if (speech.length() > 0) speakAcceptedText(speech.toString());
    }

    private synchronized void flushOrderedSpeech() {
        if (shuttingDown || projectionEnded) return;
        speakOrderedEvents(speechOrder.drain(SystemClock.elapsedRealtime()));
        scheduleSpeechOrderFlush();
    }

    private void scheduleSpeechOrderFlush() {
        if (captureHandler == null) return;
        captureHandler.removeCallbacks(flushSpeechOrder);
        long deadline = speechOrder.nextDeadline();
        diagnostics.event("speech_order", "deadline_ms", deadline == Long.MAX_VALUE ? -1 : deadline);
        if (deadline != Long.MAX_VALUE) captureHandler.postDelayed(flushSpeechOrder,
                Math.max(1, deadline - SystemClock.elapsedRealtime()));
    }

    private synchronized void resetSpeechOrder() {
        speechOrder.reset();
        if (captureHandler != null) captureHandler.removeCallbacks(flushSpeechOrder);
    }

    private void expireAutoTracks(long timestamp) {
        List<Integer> expired = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : autoTrackLastSeen.entrySet()) {
            if (timestamp - entry.getValue() > AUTO_TRACK_EXPIRES_MS) expired.add(entry.getKey());
        }
        for (Integer trackId : expired) {
            autoTrackLastSeen.remove(trackId);
            autoStabilizers.remove(trackId);
            autoConsensus.remove(trackId);
        }
    }

    private void speakAcceptedText(String originalText) {
        String speechText = SubtitleNormalizer.toSpeechText(originalText);
        diagnostics.event("tts_text", "original", originalText, "text", speechText,
                "rate", preferences.getSpeechRate(), "mode", preferences.getSpeechMode());
        if (speechText.isEmpty()) return;

        SpeechEngine.Mode mode = AppPreferences.MODE_LATEST.equals(preferences.getSpeechMode())
                ? SpeechEngine.Mode.LATEST
                : SpeechEngine.Mode.BALANCED;
        boolean pauseRequested = preferences.isAutoPauseBrowser();
        boolean paused = pauseRequested && browserMediaController.pauseBrowser();
        speechEngine.speak(speechText, mode, preferences.getSpeechRate());
        AppState.setLastSpoken(originalText);
        AppState.setStatus(pauseRequested && !paused
                ? "読み上げ中（ブラウザを自動停止できませんでした）"
                : "読み上げ中");
        updateNotification("読み上げ: " + oneLine(originalText));
    }

    private void finishOcrPass(Bitmap detectionBitmap, Bitmap highResSource) {
        if (detectionBitmap != null && !detectionBitmap.isRecycled()) detectionBitmap.recycle();
        if (highResSource != null && highResSource != detectionBitmap
                && !highResSource.isRecycled()) {
            highResSource.recycle();
        }
        ocrBusy.set(false);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + Math.max(0, rowPadding / pixelStride);
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private Bitmap cropRoi(Bitmap source, RectF normalized) {
        int left = clamp(Math.round(normalized.left * source.getWidth()), 0, source.getWidth() - 1);
        int top = clamp(Math.round(normalized.top * source.getHeight()), 0, source.getHeight() - 1);
        int right = clamp(Math.round(normalized.right * source.getWidth()), left + 1, source.getWidth());
        int bottom = clamp(Math.round(normalized.bottom * source.getHeight()), top + 1, source.getHeight());
        Bitmap output = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(source, -left, -top, null);
        return output;
    }

    private Bitmap cropRefinementBand(Bitmap source, float normalizedTop, float normalizedBottom) {
        float bandHeight = Math.max(0.02f, normalizedBottom - normalizedTop);
        float margin = Math.max(0.025f, bandHeight * 0.65f);
        RectF band = new RectF(
                0.01f,
                Math.max(0f, normalizedTop - margin),
                0.99f,
                Math.min(1f, normalizedBottom + margin)
        );
        return cropRoi(source, band);
    }

    /**
     * In portrait browsers the YouTube title and comments are below the 16:9 player.
     * Keep OCR around the possible player location instead of scanning the page body.
     * The extra 18% above/below the nominal player height accommodates Chrome's
     * address bar and the YouTube player controls without reaching the comments.
     */
    private RectF automaticVideoRoi(Bitmap frame) {
        float width = frame.getWidth();
        float height = frame.getHeight();
        if (width >= height) {
            return new RectF(0.015f, 0.025f, 0.985f, 0.975f);
        }
        float nominalPlayerHeight = (width * 9f / 16f) / Math.max(1f, height);
        float bottom = Math.min(0.68f, Math.max(0.38f, nominalPlayerHeight + 0.18f));
        return new RectF(0.01f, 0.025f, 0.99f, bottom);
    }

    private Bitmap resizeForOcr(Bitmap bitmap, int maxWidth) {
        int width = bitmap.getWidth();
        float scale = 1f;
        if (width > maxWidth) scale = maxWidth / (float) width;
        else if (width < 700) scale = Math.min(2f, 700f / width);
        if (Math.abs(scale - 1f) < 0.01f) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, Math.round(width * scale),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    private Bitmap resizeForRefinement(Bitmap bitmap) {
        int width = Math.max(1, bitmap.getWidth());
        int height = Math.max(1, bitmap.getHeight());
        float scale = 1f;
        if (height < AUTO_REFINEMENT_MIN_HEIGHT) {
            scale = Math.min(2.5f, AUTO_REFINEMENT_MIN_HEIGHT / (float) height);
        }
        if (width * scale > AUTO_REFINEMENT_MAX_WIDTH) {
            scale = AUTO_REFINEMENT_MAX_WIDTH / (float) width;
        }
        if (Math.abs(scale - 1f) < 0.01f) return bitmap;
        return Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    private int[] sampleLuminance(Bitmap bitmap, int columns, int rows) {
        int[] output = new int[columns * rows];
        int width = Math.max(1, bitmap.getWidth());
        int height = Math.max(1, bitmap.getHeight());
        for (int row = 0; row < rows; row++) {
            int y = Math.min(height - 1, Math.round((row + 0.5f) * height / rows));
            for (int column = 0; column < columns; column++) {
                int x = Math.min(width - 1,
                        Math.round((column + 0.5f) * width / columns));
                int color = bitmap.getPixel(x, y);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                output[row * columns + column] = (red * 77 + green * 150 + blue * 29) >> 8;
            }
        }
        return output;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (captureHandler != null) captureHandler.post(this::resizeVirtualDisplay);
    }

    private void resizeVirtualDisplay() {
        if (virtualDisplay == null || projection == null) return;
        int oldWidth = captureWidth;
        int oldHeight = captureHeight;
        updateCaptureSize();
        int targetWidth = captureWidth;
        int targetHeight = captureHeight;
        captureWidth = oldWidth;
        captureHeight = oldHeight;
        resizeVirtualDisplay(targetWidth, targetHeight);
    }

    private void resizeVirtualDisplay(int width, int height) {
        if (virtualDisplay == null || projection == null || width <= 0 || height <= 0) return;
        if (width == captureWidth && height == captureHeight && imageReader != null) return;
        captureWidth = width;
        captureHeight = height;
        ImageReader replacement = newImageReader(width, height);
        virtualDisplay.resize(width, height, captureDensity);
        virtualDisplay.setSurface(replacement.getSurface());
        ImageReader previous = imageReader;
        imageReader = replacement;
        if (previous != null) {
            previous.setOnImageAvailableListener(null, null);
            previous.close();
        }
        AppState.clearFrame();
        resetSpeechOrder();
        stabilizer.reset();
        regionTracker.reset();
        sceneChangeDetector.reset();
        autoStabilizers.clear();
        autoConsensus.clear();
        autoTrackLastSeen.clear();
    }

    private void updateCaptureSize() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            captureWidth = bounds.width();
            captureHeight = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            captureWidth = metrics.widthPixels;
            captureHeight = metrics.heightPixels;
        }
        captureDensity = getResources().getDisplayMetrics().densityDpi;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startAsForeground(String message) {
        Notification notification = buildNotification(message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(message));
    }

    private Notification buildNotification(String message) {
        PendingIntent openApp = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent selectRoi = PendingIntent.getActivity(this, 2,
                new Intent(this, RoiEditorActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "手動で範囲指定", selectRoi).build())
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
    }

    private String oneLine(String text) {
        String value = text.replace('\n', ' ');
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    private String stateLabel(SubtitleStabilizer.State state) {
        switch (state) {
            case CANDIDATE: return "候補検出";
            case STABILIZING: return "安定待ち";
            case COMMITTED: return "確定";
            case WAITING_CHANGE: return "次の字幕待ち";
            default: return "文字待ち";
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @SuppressWarnings("deprecation")
    private void updateScreenWakeLock() {
        boolean shouldHold = !shuttingDown && !projectionEnded
                && projection != null && preferences != null
                && preferences.isKeepScreenOn();
        if (!shouldHold) {
            releaseScreenWakeLock();
            return;
        }
        if (screenWakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            screenWakeLock = power.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    getPackageName() + ":caption-capture-screen");
            screenWakeLock.setReferenceCounted(false);
        }
        if (!screenWakeLock.isHeld()) screenWakeLock.acquire();
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        diagnostics.event("capture_service_stop");
        resetSpeechOrder();
        AppState.setRunning(false);
        if (!projectionEnded) AppState.setStatus("停止中");
        if (speechEngine != null) speechEngine.close();
        if (browserMediaController != null) {
            browserMediaController.resumeIfPausedByUs();
            browserMediaController.release();
        }
        if (ocrEngine != null) ocrEngine.close();
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        releaseScreenWakeLock();
        AppState.clearFrame();
        if (captureThread != null) captureThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

