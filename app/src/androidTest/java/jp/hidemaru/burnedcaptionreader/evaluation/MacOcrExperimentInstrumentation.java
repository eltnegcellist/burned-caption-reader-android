package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;
import jp.hidemaru.burnedcaptionreader.subtitle.*;

/** Explicit emulator-only raw inference and oracle ROI feasibility. Never starts TTS.
 * Does not alter Activity, production engine, preferences, or P3 eligibility.
 */
public final class MacOcrExperimentInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) {args=arguments;start();}
    @Override public void onStart() {
        Bundle report=new Bundle();
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.MODEL.contains("sdk"))
                throw new IllegalArgumentException("Explicit emulator instrumentation only");
            File root=new File(getTargetContext().getFilesDir(),"mac-experiment");
            JSONObject dataset=new JSONObject(new String(Files.readAllBytes(new File(root,"dataset.json").toPath()),StandardCharsets.UTF_8));
            if(!dataset.optBoolean("reference_verified_from_pixels"))throw new IllegalArgumentException("Frozen visual review required");
            String mode=args.getString("ocr_mode","hybrid");
            boolean limited=args.getBoolean("limited",false) || "true".equals(args.getString("limited"));
            OcrEngine engine="hybrid".equals(mode)?new HybridCaptionOcrEngine(getTargetContext(),false):new V6ComparisonOcrEngine(getTargetContext(),true,true);
            JSONArray results=new JSONArray();long peakPss=Debug.getPss();
            try {
                JSONArray frames=dataset.getJSONArray("frames");
                for(int i=0;i<frames.length();i++) {
                    JSONObject frame=frames.getJSONObject(i);
                    Bitmap original=BitmapFactory.decodeFile(new File(root,frame.getString("image")).toString());
                    if(original==null)throw new IllegalArgumentException("Unreadable image");
                    Bitmap input=original;SubtitleCropPlan plan=null;
                    try {
                        if(limited) {
                            JSONArray box=frame.getJSONArray("caption_regions").getJSONArray(0);
                            plan=SubtitleCropPlan.create(original.getWidth(),original.getHeight(),(float)box.getDouble(1),(float)box.getDouble(3));
                            input=OcrBitmapInputs.refined(original,plan);
                        } else input=OcrBitmapInputs.coarse(original);
                        long start=SystemClock.elapsedRealtimeNanos();
                        OcrResult value=read(engine,input,limited);
                        double elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
                        JSONArray rows=rows(value.getLines());
                        JSONArray composed=rows(OcrVisualLineComposer.compose(value.getLines()));
                        JSONObject r=new JSONObject().put("id",frame.getString("id")).put("mono_ms",frame.getLong("mono_ms"))
                            .put("expected_text",frame.getString("expected_text")).put("ocr_text",value.getText())
                            .put("rows",rows).put("composed_rows",composed).put("ocr_ms",elapsed).put("sha256",frame.getString("sha256"));
                        if(engine instanceof V6ComparisonOcrEngine)r.put("cumulative_engine_metrics",new JSONObject(((V6ComparisonOcrEngine)engine).getMetrics()));
                        if(limited) {
                            JSONArray box=frame.getJSONArray("caption_regions").getJSONArray(0);
                            OcrResult mapped=LockedRegionRescue.merge(new OcrResult("",80),value,plan,original.getHeight(),(float)box.getDouble(1),(float)box.getDouble(3));
                            r.put("mapped_text",mapped.getText()).put("mapped_rows",rows(mapped.getLines()));
                        }
                        peakPss=Math.max(peakPss,Debug.getPss());results.put(r);
                    } finally {if(input!=original)input.recycle();original.recycle();}
                }
            } finally {engine.close();}
            JSONObject output=new JSONObject().put("scope",limited?"oracle ROI limited detector feasibility; no production trigger or TTS":"production coarse raw engine only; no automatic selection/refinement/TTS")
                .put("ocr_mode",mode).put("limited",limited).put("device_model",android.os.Build.MODEL).put("device_fingerprint",android.os.Build.FINGERPRINT)
                .put("sampled_peak_pss_kb",peakPss).put("frames",results);
            Files.write(new File(root,"results.json").toPath(),output.toString(2).getBytes(StandardCharsets.UTF_8));
            report.putString("stream","Mac emulator OCR experiment PASS\n");finish(-1,report);
        } catch(Throwable failure) {report.putString("stream","FAILED: "+failure+"\n");finish(1,report);}
    }
    private static JSONArray rows(java.util.List<OcrLine> lines)throws JSONException {
        JSONArray out=new JSONArray();
        for(OcrLine l:lines)out.put(new JSONObject().put("block",l.getBlockIndex()).put("text",l.getText()).put("confidence",l.getConfidence())
            .put("left",l.getLeft()).put("top",l.getTop()).put("right",l.getRight()).put("bottom",l.getBottom()).put("glyph_height",l.getGlyphHeight()));
        return out;
    }
    private static OcrResult read(OcrEngine engine,Bitmap bitmap,boolean limited)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<OcrResult> result=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();
        java.util.function.Consumer<OcrResult> success=v->{result.set(v);done.countDown();};
        java.util.function.Consumer<Exception> failure=e->{error.set(e);done.countDown();};
        if(limited)((V6ComparisonOcrEngine)engine).recognizeLimited(bitmap,success,failure);else engine.recognize(bitmap,success,failure);
        if(!done.await(90,TimeUnit.SECONDS))throw new IllegalStateException("OCR timeout");
        if(error.get()!=null)throw error.get();return result.get();
    }
}
