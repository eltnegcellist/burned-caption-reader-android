package jp.hidemaru.burnedcaptionreader.evaluation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.graphics.*;
import android.os.Debug;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;
import jp.hidemaru.burnedcaptionreader.subtitle.*;

final class PpOcrNativeSmoke {
    static JSONObject run(Context context) throws Exception {
        JSONObject output = new JSONObject(); JSONArray checks = new JSONArray();
        long before = Debug.getPss(), started = SystemClock.elapsedRealtime();
        try (PpOcrRecognizer pp = new PpOcrRecognizer(context)) {
            output.put("model_sha256",PpOcrRecognizer.MODEL_SHA256).put("initialization_ms",SystemClock.elapsedRealtime()-started);
            for (String text : new String[]{"字幕テスト","動画の字幕です","ABC123"}) {
                Bitmap line = render(new String[]{text}); started = SystemClock.elapsedRealtime();
                PpOcrCtcDecoder.Reading reading;
                try { reading = pp.recognizeLine(line); } finally { line.recycle(); }
                int e = Similarity.levenshteinDistance(SubtitleNormalizer.comparisonKey(text),SubtitleNormalizer.comparisonKey(reading.text));
                if (text.equals("字幕テスト") && e!=0) throw new AssertionError("Japanese decoder/model failure: "+reading.text);
                checks.put(new JSONObject().put("expected",text).put("text",reading.text).put("edits",e).put("probability",reading.probability).put("processing_ms",SystemClock.elapsedRealtime()-started));
            }
        }
        Bitmap band=render(new String[]{"動画の字幕です","文字を読み取ります","最後の行です"});
        try (HybridCaptionOcrEngine engine = new HybridCaptionOcrEngine(context)) {
            OcrResult result=refine(engine,band);
            if (!"ppocrv5".equals(result.getBackend()) || result.getLines().size()!=3)
                throw new AssertionError("Production PP refinement not used: "+result.getBackend()+" "+result.getText());
            OcrRefinementSelector.Result chosen=new OcrRefinementSelector().select("動画の字幕です\n文字を読み取ります\n最後の行です",55,result);
            output.put("production_refinement",new JSONObject().put("backend",result.getBackend()).put("lines",result.getLines().size()).put("text",chosen.getText()).put("probability",result.getModelProbability()));
        }
        Context missingAssets=new ContextWrapper(context) {
            @Override public Context getApplicationContext() {return this;}
            @Override public AssetManager getAssets() {throw new IllegalStateException("test model assets unavailable");}
        };
        try (HybridCaptionOcrEngine engine=new HybridCaptionOcrEngine(missingAssets)) {
            OcrResult fallback=refine(engine,band);
            if (!"mlkit".equals(fallback.getBackend()) || fallback.getText().isEmpty())throw new AssertionError("Missing-model fallback failed");
            OcrResult repeat=refine(engine,band); // permanently disabled backend must keep working
            if (!"mlkit".equals(repeat.getBackend()))throw new AssertionError("Fallback changed");
            output.put("missing_model_fallback",true);
        } finally {band.recycle();}
        output.put("checks",checks).put("pss_before_kb",before).put("pss_after_kb",Debug.getPss())
                .put("scope","Android native synthetic integration only; no actual video or audio accuracy claim");
        return output;
    }
    private static OcrResult refine(OcrEngine engine,Bitmap b)throws Exception {
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<OcrResult> result=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();
        engine.refine(b,r->{result.set(r);latch.countDown();},e->{error.set(e);latch.countDown();});
        if (!latch.await(120,TimeUnit.SECONDS))throw new Exception("Refinement timed out");
        if(error.get()!=null)throw error.get();return result.get();
    }
    private static Bitmap render(String[] rows) {
        Bitmap b=Bitmap.createBitmap(1000,100*rows.length+20,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(52);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        for(int i=0;i<rows.length;i++)canvas.drawText(rows[i],20,72+i*100,paint);return b;
    }
}
