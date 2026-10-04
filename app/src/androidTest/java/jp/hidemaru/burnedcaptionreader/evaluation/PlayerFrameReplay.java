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
    interface Recognize { OcrResult run(Bitmap input) throws Exception; }
    private final int diagnosticCoarseWidth;
    PlayerFrameReplay() { this(0); }
    PlayerFrameReplay(int diagnosticCoarseWidth) { this.diagnosticCoarseWidth = diagnosticCoarseWidth; }
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
    private final Map<Integer, TemporalOcrConsensus> consensus = new HashMap<>();
    private final Map<Integer, SubtitleStabilizer> stabilizers = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final SubtitleSpeechOrderBuffer order = new SubtitleSpeechOrderBuffer();
    private final RowSpeechLedger ledger = new RowSpeechLedger(60000);
    final JSONArray speech = new JSONArray();
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");

    JSONObject process(Bitmap frame, long now, Recognize recognize) throws Exception {
        flushBefore(now);
        Bitmap coarse;
        if (diagnosticCoarseWidth == 0) coarse = OcrBitmapInputs.coarse(frame);
        else {
            float scale = Math.min(2f, diagnosticCoarseWidth / (float) frame.getWidth());
            scale = Math.min(scale, 2000f / frame.getHeight());
            coarse = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth()*scale)),
                    Math.max(1, Math.round(frame.getHeight()*scale)), true);
        }
        OcrResult raw;
        try { raw = recognize.run(coarse); }
        finally { if (coarse != frame) coarse.recycle(); }
        List<AutoSubtitleRegionTracker.Selection> selected = select(now, raw);
        JSONArray bands = new JSONArray();
        List<String> texts = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Entry> committed = new ArrayList<>();
        List<SubtitleSpeechOrderBuffer.Band> waiting = new ArrayList<>();
        Set<Integer> visible = new HashSet<>();
        for (AutoSubtitleRegionTracker.Selection band : selected) {
            SubtitleCropPlan plan = SubtitleCropPlan.create(frame.getWidth(), frame.getHeight(), band.getTop(), band.getBottom());
            Bitmap refined = OcrBitmapInputs.refined(frame, plan);
            OcrResult rerun;
            try { rerun = recognize.run(refined); }
            finally { if (refined != frame) refined.recycle(); }
            OcrRefinementSelector.Result chosen = selector.select(band.getText(), band.getConfidence(), rerun);
            bands.put(new JSONObject().put("track_id", band.getTrackId()).put("top", band.getTop())
                    .put("bottom", band.getBottom()).put("coarse", band.getText())
                    .put("refined", rerun.getText()).put("selected", chosen.getText()));
            texts.add(chosen.getText());
            if (!JAPANESE.matcher(chosen.getText()).find()) continue;
            int id = band.getTrackId(); visible.add(id); lastSeen.put(id, now);
            TemporalOcrConsensus.Result fused = consensus.computeIfAbsent(id, ignored -> new TemporalOcrConsensus())
                    .observe(now, chosen.getText(), chosen.getConfidence(), band.getText());
            SubtitleStabilizer stabilizer = stabilizers.computeIfAbsent(id, ignored -> {
                SubtitleStabilizer.Config config = new SubtitleStabilizer.Config(); config.stableMs = 300;
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
        return new JSONObject().put("ocr_text", String.join("\n", texts)).put("bands", bands)
                .put("raw_rows", rawRows(raw))
                .put("raw_text", raw.getText()).put("selection_api", candidateSelector == null ? "selectAll" : "selectForRecognition")
                .put("ocr_jobs", 1 + selected.size());
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
