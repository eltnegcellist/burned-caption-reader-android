package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.webkit.WebView;
import android.widget.Switch;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hidemaru.burnedcaptionreader.AppPreferences;
import jp.hidemaru.burnedcaptionreader.SettingsActivity;
import jp.hidemaru.burnedcaptionreader.SharedPlayerActivity;
import jp.hidemaru.burnedcaptionreader.ocr.*;

/** Four-way factorial native smoke: two independent UI switches and OCR backends. */
final class OcrSettingsNativeSmoke {
    static JSONObject run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences saved = context.getSharedPreferences(AppPreferences.PREFERENCES_FILE,0);
        String original = saved.getString(AppPreferences.OCR_MODE,null);
        AppPreferences preferences = new AppPreferences(context);
        Activity settings = null;SharedPlayerActivity player = null;
        Bitmap bitmap = sample();
        try {
            saved.edit().remove(AppPreferences.OCR_MODE).commit();
            check(preferences.getOcrMode() == CaptionOcrMode.BASELINE,"Default not baseline");
            settings = instrumentation.startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            Switch detector = settings.findViewById(android.R.id.content).findViewWithTag("ocr_detector_v6");
            Switch recognizer = settings.findViewById(android.R.id.content).findViewWithTag("ocr_recognizer_v6");
            check(detector!=null && recognizer!=null && !detector.isChecked() && !recognizer.isChecked(),"Missing independent switches");
            JSONArray modes=new JSONArray();
            for(CaptionOcrMode mode: CaptionOcrMode.values()) {
                instrumentation.runOnMainSync(() -> {
                    detector.setChecked(mode.v6Detection);
                    recognizer.setChecked(mode.v6Recognition);
                });
                check(preferences.getOcrMode()==mode,"UI selection failed: "+mode);
                check(new AppPreferences(context).getOcrMode()==mode,"Mode not saved: "+mode);
                try(OcrEngine engine = CaptionOcrEngines.create(context,mode)) {
                    OcrResult raw=read(engine,bitmap,false);
                    OcrResult refined=read(engine,bitmap,true);
                    check(raw!=null&&refined!=null,"OCR result absent: "+mode);
                    if(mode.v6Detection) {
                        check(raw.getBackend().startsWith("v6det_"),"C/D used ML Kit coarse OCR");
                        check(field(engine,"mlkit").get(engine)==null,"C/D instantiated ML Kit");
                        check(((V6ComparisonOcrEngine)engine).getMetrics().get("mlkit_calls")==0,"C/D called ML Kit");
                    }
                    else check("mlkit".equals(raw.getBackend()),"A/B lost ML Kit coarse detection");
                    modes.put(new JSONObject().put("mode",mode.storedValue)
                        .put("detector_v6",mode.v6Detection).put("recognizer_v6",mode.v6Recognition)
                        .put("raw_backend",raw.getBackend()).put("refined_backend",refined.getBackend())
                        .put("raw",raw.getText()).put("refined",refined.getText()));
                }
            }
            final Activity first=settings;instrumentation.runOnMainSync(first::finish);
            settings=instrumentation.startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            Switch detAgain=settings.findViewById(android.R.id.content).findViewWithTag("ocr_detector_v6");
            Switch recAgain=settings.findViewById(android.R.id.content).findViewWithTag("ocr_recognizer_v6");
            check(detAgain!=null && recAgain!=null && detAgain.isChecked() && recAgain.isChecked(),
                "Settings failed to persist both switches");
            final Activity second=settings;instrumentation.runOnMainSync(second::finish);settings=null;
            player=(SharedPlayerActivity)instrumentation.startActivitySync(
                new Intent(context,SharedPlayerActivity.class).putExtra(SharedPlayerActivity.EXTRA_VIDEO_ID,"X115n3Sn5pc")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SharedPlayerActivity current=player;
            AtomicReference<Throwable> failure=new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try {
                    ((WebView)field(current,"player").get(current)).stopLoading();
                    field(current,"sampling").setBoolean(current,false);
                    ((Handler)field(current,"main").get(current)).removeCallbacks((Runnable)field(current,"sampler").get(current));
                    Method apply=SharedPlayerActivity.class.getDeclaredMethod("applyOcrModeIfIdle");
                    apply.setAccessible(true);
                    check(field(current,"ocrMode").get(current)==CaptionOcrMode.BOTH_V6,"Startup mode mismatch");
                    OcrEngine previous=(OcrEngine)field(current,"ocr").get(current);
                    field(current,"busy").setBoolean(current,true);
                    preferences.setOcrMode(CaptionOcrMode.BASELINE);
                    apply.invoke(current);
                    check(field(current,"ocr").get(current)==previous,"Active model prematurely replaced");
                    field(current,"busy").setBoolean(current,false);
                    apply.invoke(current);
                    check(field(current,"ocrMode").get(current)==CaptionOcrMode.BASELINE,"Idle change not applied");
                    check(field(previous,"closed").getBoolean(previous),"Previous OCR not closed");
                    for(CaptionOcrMode mode:CaptionOcrMode.values()){
                        preferences.setOcrMode(mode);apply.invoke(current);
                        check(field(current,"ocrMode").get(current)==mode,"Player did not use mode "+mode);
                    }
                } catch(Throwable t){failure.set(t);}
            });
            if(failure.get()!=null)throw new IllegalStateException("Mode switch failed",failure.get());
            return new JSONObject().put("modes",modes).put("independent_switches",true)
                .put("settings_persistence",true).put("player_busy_deferral",true).put("player_live_modes",4)
                .put("scope","Synthetic image; emulator only, no measured real-device latency/accuracy");
        } finally {
            if(settings!=null){Activity a=settings;instrumentation.runOnMainSync(a::finish);}
            if(player!=null){Activity a=player;instrumentation.runOnMainSync(a::finish);}
            if(original==null)saved.edit().remove(AppPreferences.OCR_MODE).commit();
            else saved.edit().putString(AppPreferences.OCR_MODE,original).commit();
            bitmap.recycle();
        }
    }
    private static Field field(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static void check(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
    private static OcrResult read(OcrEngine engine,Bitmap bitmap,boolean refinement)throws Exception{
        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<OcrResult> result=new AtomicReference<>();
        AtomicReference<Exception> failure=new AtomicReference<>();
        java.util.function.Consumer<OcrResult> ok=v->{result.set(v);done.countDown();};
        java.util.function.Consumer<Exception> bad=e->{failure.set(e);done.countDown();};
        if(refinement)engine.refine(bitmap,ok,bad);else engine.recognize(bitmap,ok,bad);
        if(!done.await(60,TimeUnit.SECONDS))throw new IllegalStateException("OCR test timeout");
        if(failure.get()!=null)throw failure.get();return result.get();
    }
    private static Bitmap sample(){
        Bitmap b=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b);c.drawColor(Color.DKGRAY);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(52);
        String[] labels={"動画の字幕です","文字を読み取ります","最後の字幕です"};
        for(int i=0;i<labels.length;i++){
            p.setColor(Color.BLACK);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(8);
            c.drawText(labels[i],100,100+130*i,p);
            p.setColor(Color.WHITE);p.setStyle(Paint.Style.FILL);
            c.drawText(labels[i],100,100+130*i,p);
        }return b;
    }
}
