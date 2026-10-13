package jp.hidemaru.burnedcaptionreader.evaluation;

import android.graphics.Bitmap;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;
import jp.hidemaru.burnedcaptionreader.subtitle.*;

/** Timestamp-driven component replay. TTS completes immediately; no live timing claims. */
final class PlayerFrameReplay {
    interface Recognize {
        OcrResult run(Bitmap input) throws Exception;
        default OcrResult refine(Bitmap input) throws Exception { return run(input); }
    }
    private final int diagnosticCoarseWidth;
    private final boolean experimentalE;
    private final boolean subtitleRecheckEnabled;
    PlayerFrameReplay() { this(0); }
    PlayerFrameReplay(int diagnosticCoarseWidth) { this(diagnosticCoarseWidth,false); }
    PlayerFrameReplay(int diagnosticCoarseWidth,boolean experimentalE) { this(diagnosticCoarseWidth,experimentalE,true); }
    PlayerFrameReplay(int diagnosticCoarseWidth,boolean experimentalE,boolean recheck) { this.diagnosticCoarseWidth = diagnosticCoarseWidth; this.experimentalE=experimentalE; this.subtitleRecheckEnabled=recheck; }
    private final AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
    private final Method candidateSelector = recognitionSelector();
    private static Method recognitionSelector() {
        try { return AutoSubtitleRegionTracker.class.getMethod("selectForRecognition", long.class, OcrResult.class); }
        catch (NoSuchMethodException baseline) { return null; }
    }
    @SuppressWarnings("unchecked")
    private List<AutoSubtitleRegionTracker.Selection> select(long now, OcrResult raw) throws Exception {
        return candidateSelector == null ? tracker.selectAll(now, raw, false, false)
                : (List<AutoSubtitleRegionTracker.Selection>) candidateSelector.invoke(tracker, now, raw);
    }
    private final OcrRefinementSelector selector = new OcrRefinementSelector();
    private final Method adaptiveSelector = adaptiveSelector();
    private static Method adaptiveSelector() {
        try { return OcrRefinementSelector.class.getMethod("select", String.class, double.class, OcrResult.class, OcrResult.class); }
        catch (NoSuchMethodException baseline) { return null; }
    }
    private final Map<Integer, TemporalOcrConsensus> consensus = new HashMap<>();
    private final Map<Integer, SubtitleStabilizer> stabilizers = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final SubtitleSpeechOrderBuffer order = new SubtitleSpeechOrderBuffer();
    private final RowSpeechLedger ledger = new RowSpeechLedger(60000);
    final JSONArray speech = new JSONArray();
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");

    JSONObject process(Bitmap frame, long now, Recognize recognize) throws Exception {
        long selectionNs = 0, cropNs = 0, ePolicyNs = 0;
        int ePolicyCalls = 0, eRemovedRows = 0, eChangedChoices = 0;
        int selectionCalls = 0, cropCalls = 0;
        long clock = android.os.SystemClock.elapsedRealtimeNanos();
        flushBefore(now);
        selectionNs += android.os.SystemClock.elapsedRealtimeNanos() - clock;
        clock = android.os.SystemClock.elapsedRealtimeNanos();
        Bitmap coarse;
        if (diagnosticCoarseWidth == 0) coarse = OcrBitmapInputs.coarse(frame);
        else {
            float scale = Math.min(2f, diagnosticCoarseWidth / (float) frame.getWidth());
            scale = Math.min(scale, 2000f / frame.getHeight());
            coarse = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth()*scale)),
                    Math.max(1, Math.round(frame.getHeight()*scale)), true);
        }
        cropNs += android.os.SystemClock.elapsedRealtimeNanos() - clock; cropCalls++;
        OcrResult raw;
        try { raw = recognize.run(coarse); }
        finally { if (coarse != frame) coarse.recycle(); }
        OcrResult detection = raw;
        OcrResult strip = null;
        int ocrJobs = 1;
        try {
            Method method = OcrBitmapInputs.class.getMethod("stripRecoveryPlan", Bitmap.class, OcrResult.class);
            SubtitleCropPlan plan = (SubtitleCropPlan) method.invoke(null, frame, raw);
            if (plan != null) {
                Bitmap crop = OcrBitmapInputs.refined(frame, plan);
                try { strip = recognize.run(crop); ocrJobs++; }
                finally { if (crop != frame) crop.recycle(); }
                detection = (OcrResult) Class.forName("jp.hidemaru.burnedcaptionreader.ocr.CaptionStripRecovery")
                        .getMethod("merge", OcrResult.class, OcrResult.class, SubtitleCropPlan.class, int.class)
                        .invoke(null, raw, strip, plan, frame.getHeight());
            }
        } catch (NoSuchMethodException legacy) { /* Same harness preserves old APK's detection path. */ }
        if(experimentalE) {
            clock=android.os.SystemClock.elapsedRealtimeNanos();
            ECaptionOcrPolicy.Filtered filtered=ECaptionOcrPolicy.filter(detection);
            detection=filtered.result;eRemovedRows=filtered.removed;ePolicyCalls++;
            long elapsed=android.os.SystemClock.elapsedRealtimeNanos()-clock;
            ePolicyNs+=elapsed;selectionNs+=elapsed;selectionCalls++;
        }
        clock = android.os.SystemClock.elapsedRealtimeNanos();
        List<AutoSubtitleRegionTracker.Selection> selected = select(now, detection);
        selectionNs += android.os.SystemClock.elapsedRealtimeNanos() - clock; selectionCalls++;
        JSONArray bands = new JSONArray();
        List<String> texts = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Entry> committed = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Band> waiting = new ArrayList<>();
        Set<Integer> visible = new HashSet<>();
        for (AutoSubtitleRegionTracker.Selection band : selected) {
            clock = android.os.SystemClock.elapsedRealtimeNanos();
            SubtitleCropPlan plan = SubtitleCropPlan.create(frame.getWidth(), frame.getHeight(), band.getTop(), band.getBottom());
            Bitmap refined = OcrBitmapInputs.refined(frame, plan);
            cropNs += android.os.SystemClock.elapsedRealtimeNanos() - clock; cropCalls++;
            OcrResult rerun;
            OcrResult masked = null;
            OcrRefinementSelector.Result chosen;
            try {
                rerun = recognize.refine(refined); ocrJobs++;
                clock = android.os.SystemClock.elapsedRealtimeNanos();
                chosen = selector.select(band.getText(), band.getConfidence(), rerun);
                selectionNs += android.os.SystemClock.elapsedRealtimeNanos() - clock; selectionCalls++;
                if (!ppOcrUsed(rerun) && adaptiveSelector != null && (boolean) OcrRefinementSelector.class
                        .getMethod("shouldTryWhiteCore", OcrRefinementSelector.Result.class).invoke(selector, chosen)) {
                    Bitmap mask = (Bitmap) OcrBitmapInputs.class.getMethod("whiteCore", Bitmap.class).invoke(null, refined);
                    try { masked = recognize.refine(mask); ocrJobs++; }
                    finally { if (mask != frame && mask != refined) mask.recycle(); }
                    chosen = (OcrRefinementSelector.Result) adaptiveSelector.invoke(selector, band.getText(), band.getConfidence(), rerun, masked);
                }
            } finally { if (refined != frame) refined.recycle(); }
            String chosenText=chosen.getText(); double chosenConfidence=chosen.getConfidence();
            boolean eChoiceChanged=false;
            if(experimentalE) {
                clock=android.os.SystemClock.elapsedRealtimeNanos();
                ECaptionOcrPolicy.Choice e=ECaptionOcrPolicy.choose(band.getText(),rerun,chosenText,chosenConfidence);
                chosenText=e.text;chosenConfidence=e.confidence;eChoiceChanged=e.changed;
                if(e.changed)eChangedChoices++;ePolicyCalls++;
                long elapsed=android.os.SystemClock.elapsedRealtimeNanos()-clock;
                ePolicyNs+=elapsed;selectionNs+=elapsed;selectionCalls++;
            }
            JSONObject stages = new JSONObject().put("track_id", band.getTrackId()).put("top", band.getTop())
                    .put("bottom", band.getBottom()).put("coarse", band.getText())
                    .put("refined", rerun.getText()).put("selected", chosenText)
                    .put("e_choice_changed",eChoiceChanged)
                    .put("refinement_backend", backend(rerun));
            if (masked != null) stages.put("white_core", masked.getText());
            bands.put(stages);
            texts.add(chosenText);
            if (!JAPANESE.matcher(chosenText).find()) continue;
            int id = band.getTrackId(); visible.add(id); lastSeen.put(id, now);
            TemporalOcrConsensus.Result fused = subtitleRecheckEnabled
                    ? consensus.computeIfAbsent(id, ignored -> new TemporalOcrConsensus())
                            .observe(now, chosenText, chosenConfidence, band.getText())
                    : TemporalOcrConsensus.singleFrame(chosenText, chosenConfidence, band.getText());
            SubtitleStabilizer stabilizer = stabilizers.computeIfAbsent(id, ignored -> {
                SubtitleStabilizer.Config config = new SubtitleStabilizer.Config(); config.stableMs = 300;
                config.commitMode = subtitleRecheckEnabled ? SubtitleStabilizer.CommitMode.REOBSERVE : SubtitleStabilizer.CommitMode.FIRST_OBSERVATION;
                return new SubtitleStabilizer(config);
            });
            SubtitleEvent event = stabilizer.observe(now, fused.getText(), fused.getConfidence());
            if (event != null) committed.add(new SubtitleSpeechOrderBuffer.Entry(event, band.getTop(), band.getBottom()));
            else if (stabilizer.getState() == SubtitleStabilizer.State.CANDIDATE
                    || stabilizer.getState() == SubtitleStabilizer.State.STABILIZING)
                waiting.add(new SubtitleSpeechOrderBuffer.Band(band.getTop(), band.getBottom()));
        }
        for (Map.Entry<Integer, SubtitleStabilizer> entry : stabilizers.entrySet())
            if (!visible.contains(entry.getKey())) entry.getValue().observe(now, "", 100);
        lastSeen.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= 8000) return false;
            stabilizers.remove(entry.getKey()); consensus.remove(entry.getKey()); return true;
        });
        emit(now, order.offer(now, committed, waiting));
        return new JSONObject().put("e_policy_ns",ePolicyNs).put("e_policy_calls",ePolicyCalls)
                .put("e_removed_rows",eRemovedRows).put("e_changed_choices",eChangedChoices)
                .put("subtitle_recheck_enabled",subtitleRecheckEnabled).put("selection_ns", selectionNs).put("selection_calls", selectionCalls)
                .put("crop_ns", cropNs).put("crop_calls", cropCalls).put("ocr_text", String.join("\n", texts)).put("bands", bands)
                .put("raw_rows", rawRows(raw))
                .put("raw_text", raw.getText()).put("recognition_rows", rawRows(detection))
                .put("strip_recovery", strip == null ? JSONObject.NULL : strip.getText()).put("selection_api", candidateSelector == null ? "selectAll" : "selectForRecognition")
                .put("ocr_jobs", ocrJobs);
    }
    private static String backend(OcrResult result) {
        try { return (String) result.getClass().getMethod("getBackend").invoke(result); }
        catch (ReflectiveOperationException legacy) { return "mlkit"; }
    }
    private static boolean ppOcrUsed(OcrResult result) {
        return backend(result).startsWith("ppocr") || backend(result).startsWith("v6det_");
    }
    private JSONArray rawRows(OcrResult raw) throws Exception {
        JSONArray rows = new JSONArray();
        for (OcrLine row : raw.getLines()) {
            JSONObject value = rowGeometry(row);
            // One harness must still run against APKs predating geometry metadata.
            try {
                Object parts = row.getClass().getMethod("getSeparatedParts").invoke(row);
                JSONArray values = new JSONArray();
                for (Object part : (List<?>) parts) values.put(rowGeometry((OcrLine) part));
                if (values.length() > 0) value.put("separated_parts", values);
            } catch (NoSuchMethodException legacy) { /* No character-gap evidence in old APKs. */ }
            rows.put(value);
        }
        return rows;
    }
    private JSONObject rowGeometry(OcrLine row) throws JSONException {
        JSONObject value = new JSONObject().put("text", row.getText()).put("confidence", row.getConfidence())
                .put("block", row.getBlockIndex()).put("left", row.getLeft()).put("top", row.getTop())
                .put("right", row.getRight()).put("bottom", row.getBottom());
        try {
            value.put("glyph_height", row.getClass().getMethod("getGlyphHeight").invoke(row));
        } catch (ReflectiveOperationException legacy) { /* Legacy APKs retain union-box height. */ }
        return value;
    }
    private void flushBefore(long now) throws JSONException {
        long deadline = order.nextDeadline();
        if (deadline <= now) emit(deadline, order.drain(deadline));
    }
    void finish(long now) throws JSONException { flushBefore(now + SubtitleSpeechOrderBuffer.MAX_WAIT_MS); }
    private void emit(long now, List<SubtitleSpeechOrderBuffer.Entry> ready) throws JSONException {
        if (ready.isEmpty()) return;
        List<String> texts = new ArrayList<>();
        for (SubtitleSpeechOrderBuffer.Entry entry : ready) texts.add(entry.event.getText());
        RowSpeechLedger.Reservation r = ledger.reserve(new SubtitleEvent("replay-" + now,
                String.join("\n", texts), now, now, 1), true, false);
        if (r != null) {
            speech.put(new JSONObject().put("mono_ms", now).put("text", r.getText()));
            ledger.complete(r.getId(), now);
        }
    }
}
