package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

/** Independent detector boxes and raw PP recognition on the SAME annotated crops.
 * No caption selection/acceptance gate: these metrics are separate from replay.
 * Initialization includes integrity checks. ML Kit lazily initializes at first call.
 */
final class BdComponents {
    static void run(Instrumentation instrumentation) throws Exception {
        File root = new File(instrumentation.getTargetContext().getFilesDir(), "image-evaluation");
        JSONObject dataset = new JSONObject(new String(Files.readAllBytes(new File(root,"dataset.json").toPath()),StandardCharsets.UTF_8));
        JSONArray frames = dataset.getJSONArray("frames"), results = new JSONArray();
        JSONObject init = new JSONObject();
        long started = SystemClock.elapsedRealtimeNanos();
        MlKitJapaneseOcrEngine ml = new MlKitJapaneseOcrEngine();
        init.put("mlkit_constructor_ms", elapsed(started));
        started = SystemClock.elapsedRealtimeNanos();
        try (PpOcrTextDetector det = new PpOcrTextDetector(instrumentation.getTargetContext(), true)) {
            init.put("v6_detector_constructor_ms", elapsed(started));
            started = SystemClock.elapsedRealtimeNanos();
            {
                // Recognition is fixed to v6 in this diagnostic.
                started = SystemClock.elapsedRealtimeNanos();
                try (PpOcrRecognizer v6 = new PpOcrRecognizer(instrumentation.getTargetContext(), true)) {
                    init.put("v6_recognizer_constructor_ms", elapsed(started));
                    long peakPss = Debug.getPss();
                    for (int i=0; i<frames.length(); i++) {
                        JSONObject frame = frames.getJSONObject(i);
                        Bitmap bitmap = BitmapFactory.decodeFile(new File(root,frame.getString("image")).toString());
                        if (bitmap==null) throw new IllegalArgumentException("Unreadable component image");
                        try {
                            JSONObject row = new JSONObject().put("id",frame.getString("id"))
                                    .put("mono_ms",frame.getLong("mono_ms"));
                            // The pure detection diagnostic uses the production coarse preparation.
                            // Every proposal is saved, including non-caption text and empty-recognition boxes.
                            if (frame.has("text_boxes")) {
                                Bitmap coarse = OcrBitmapInputs.coarse(bitmap);
                                try {
                                    JSONArray mlTimes = new JSONArray(), ppTimes = new JSONArray();
                                    JSONArray mlBoxes = new JSONArray(), ppBoxes = new JSONArray();
                                    for (int repeat=0; repeat<3; repeat++) {
                                        started=SystemClock.elapsedRealtimeNanos();
                                        OcrResult detected=read(ml,coarse);
                                        mlTimes.put(elapsed(started));
                                        started=SystemClock.elapsedRealtimeNanos();
                                        java.util.List<PpOcrDbPostProcessor.Box> boxes=det.detect(coarse);
                                        ppTimes.put(elapsed(started));
                                        if(repeat==0) {
                                            for(OcrLine line:detected.getLines()) mlBoxes.put(new JSONArray(new double[]{line.getLeft(),line.getTop(),line.getRight(),line.getBottom()}));
                                            for(PpOcrDbPostProcessor.Box b:boxes) ppBoxes.put(new JSONArray(new double[]{b.left,b.top,b.right,b.bottom}));
                                        }
                                    }
                                    row.put("mlkit_boxes",mlBoxes).put("v6_boxes",ppBoxes)
                                            .put("mlkit_detection_ms",mlTimes).put("v6_detection_ms",ppTimes);
                                } finally { if(coarse!=bitmap) coarse.recycle(); }
                            }
                            if(frame.has("caption_lines")) {
                                JSONArray crops=new JSONArray(), lines=frame.getJSONArray("caption_lines");
                                for(int j=0;j<lines.length();j++) {
                                    JSONObject line=lines.getJSONObject(j); JSONArray box=line.getJSONArray("box");
                                    int l=(int)Math.floor(box.getDouble(0)*bitmap.getWidth()), t=(int)Math.floor(box.getDouble(1)*bitmap.getHeight());
                                    int r=(int)Math.ceil(box.getDouble(2)*bitmap.getWidth()), b=(int)Math.ceil(box.getDouble(3)*bitmap.getHeight());
                                    Bitmap crop=Bitmap.createBitmap(bitmap,l,t,r-l,b-t);
                                    try {
                                        JSONObject readings=new JSONObject().put("expected_text",line.getString("text")).put("box",box);
                                        JSONArray times6=new JSONArray();
                                        for(int repeat=0;repeat<3;repeat++) {
                                            started=SystemClock.elapsedRealtimeNanos(); PpOcrCtcDecoder.Reading z=v6.recognizeLine(crop); times6.put(elapsed(started));
                                            if(repeat==0) readings.put("v6_text",z.text).put("v6_probability",z.probability);
                                        }
                                        crops.put(readings.put("v6_ms",times6));
                                    } finally {if(crop!=bitmap)crop.recycle();}
                                }
                                row.put("recognition_crops",crops);
                            }
                            peakPss=Math.max(peakPss,Debug.getPss());
                            results.put(row);
                        } finally {bitmap.recycle();}
                    }
                    JSONObject output=new JSONObject().put("dataset",dataset.getString("id"))
                            .put("initialization",init).put("sampled_peak_pss_kb",peakPss)
                            .put("process_status",new String(Files.readAllBytes(new File("/proc/self/status").toPath()),StandardCharsets.UTF_8))
                            .put("scope","coarse detector boxes; independent original manual line crops; all sessions coexist")
                            .put("frames",results);
                    Files.write(new File(root,"components.json").toPath(),output.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally {ml.close();}
    }
    private static double elapsed(long start){return (SystemClock.elapsedRealtimeNanos()-start)/1e6;}
    private static OcrResult read(OcrEngine engine,Bitmap bitmap)throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<OcrResult> result=new AtomicReference<>(); AtomicReference<Exception> failure=new AtomicReference<>();
        engine.recognize(bitmap,v->{result.set(v);done.countDown();},e->{failure.set(e);done.countDown();});
        if(!done.await(60,TimeUnit.SECONDS))throw new IllegalStateException("Component OCR timeout");
        if(failure.get()!=null)throw failure.get();return result.get();
    }
}
