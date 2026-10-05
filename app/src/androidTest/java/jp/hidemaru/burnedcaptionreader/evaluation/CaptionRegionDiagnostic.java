package jp.hidemaru.burnedcaptionreader.evaluation;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;
import org.json.*;
import jp.hidemaru.burnedcaptionreader.ocr.OcrResult;

/** Gold-region OCR diagnostic. Never selects captions automatically or emits speech. */
final class CaptionRegionDiagnostic {
    interface Recognize { OcrResult run(Bitmap input) throws Exception; }
    static JSONObject process(Bitmap frame, JSONObject row, String preparation, Recognize recognize) throws Exception {
        if (!"original".equals(preparation) && !"double".equals(preparation)
                && !"white-core".equals(preparation) && !"dark-core".equals(preparation))
            throw new IllegalArgumentException("Unknown preparation");
        if (!row.has("caption_regions")) throw new IllegalArgumentException("Manual regions missing");
        JSONArray boxes = row.getJSONArray("caption_regions"), readings = new JSONArray();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < boxes.length(); i++) {
            JSONArray box = boxes.getJSONArray(i);
            if (box.length() != 4) throw new IllegalArgumentException("Rectangle must have four coordinates");
            double l = box.getDouble(0), t = box.getDouble(1), r = box.getDouble(2), b = box.getDouble(3);
            if (!Double.isFinite(l) || !Double.isFinite(t) || !Double.isFinite(r) || !Double.isFinite(b)
                    || l < 0 || t < 0 || r > 1 || b > 1 || r <= l || b <= t)
                throw new IllegalArgumentException("Invalid manual region");
            int left = (int) Math.floor(l * frame.getWidth()), top = (int) Math.floor(t * frame.getHeight());
            int right = Math.min(frame.getWidth(), (int) Math.ceil(r * frame.getWidth()));
            int bottom = Math.min(frame.getHeight(), (int) Math.ceil(b * frame.getHeight()));
            Bitmap crop = Bitmap.createBitmap(frame, left, top, right-left, bottom-top), input = crop;
            try {
                if (!"original".equals(preparation)) {
                    double scale = Math.min(2, 2000.0 / Math.max(crop.getWidth(), crop.getHeight()));
                    input = Bitmap.createScaledBitmap(crop, Math.max(1, (int)Math.round(crop.getWidth()*scale)),
                            Math.max(1, (int)Math.round(crop.getHeight()*scale)), true);
                    if ("white-core".equals(preparation) || "dark-core".equals(preparation)) {
                        Bitmap mask = mask(input, "white-core".equals(preparation));
                        if (input != crop && input != frame) input.recycle();
                        input = mask;
                    }
                }
                OcrResult result = recognize.run(input); texts.add(result.getText());
                readings.put(new JSONObject().put("region", box).put("text", result.getText())
                        .put("confidence", result.getConfidence()));
            } finally {
                if (input != crop && input != frame) input.recycle();
                if (crop != frame) crop.recycle();
            }
        }
        return new JSONObject().put("ocr_text", String.join("\n", texts)).put("manual_region_readings", readings);
    }
    private static Bitmap mask(Bitmap image, boolean white) {
        int[] pixels = new int[image.getWidth()*image.getHeight()];
        image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
        for (int i = 0; i < pixels.length; i++) {
            int c=pixels[i], r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            int low=Math.min(r,Math.min(g,b)),high=Math.max(r,Math.max(g,b));
            boolean ink=high-low<=45 && (white ? low>=210 : high<=75);
            pixels[i]=ink ? 0xff000000 : 0xffffffff;
        }
        return Bitmap.createBitmap(pixels,image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
    }
}
