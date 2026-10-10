package jp.hidemaru.burnedcaptionreader.ocr;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;

/** One worker owns this session. Model/dictionary are bundled; no model download at runtime. */
public final class PpOcrRecognizer implements AutoCloseable {
    public static final String MODEL_SHA256 = "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092";
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final List<String> characters = new ArrayList<>();
    private ByteBuffer model;
    private boolean closed;

    public PpOcrRecognizer(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("ppocrv5/characters.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) bytes.write(buffer, 0, n);
            JSONArray json = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < json.length(); i++) characters.add(json.getString(i));
        }
        if (characters.size() != 18385 || !characters.get(0).isEmpty()) throw new IOException("Invalid PP-OCR dictionary");
        try (AssetFileDescriptor asset = context.getAssets().openFd("ppocrv5/recognition.onnx");
             FileInputStream input = asset.createInputStream()) {
            if (asset.getLength() != 16534782L) throw new IOException("Invalid PP-OCR model size");
            model = input.getChannel().map(FileChannel.MapMode.READ_ONLY, asset.getStartOffset(), asset.getLength());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(model.duplicate());
        StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!MODEL_SHA256.contentEquals(hex)) throw new IOException("PP-OCR model integrity failure");
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            session = environment.createSession(model, options);
        }
    }
    public synchronized PpOcrCtcDecoder.Reading recognizeLine(Bitmap bitmap) throws Exception {
        if (closed) throw new IllegalStateException("PP-OCR closed");
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[Math.multiplyExact(width, height)]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        PpOcrInput input = PpOcrInput.fromArgb(pixels, width, height);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), new long[]{1, 3, 48, input.width});
             OrtSession.Result result = session.run(Collections.singletonMap("x", tensor))) {
            if (!(result.get(0) instanceof OnnxTensor)) throw new IOException("Invalid PP-OCR output");
            OnnxTensor output = (OnnxTensor) result.get(0); long[] shape = output.getInfo().getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] != characters.size() || shape[1] > 1600)
                throw new IOException("Unexpected PP-OCR output dimensions");
            return PpOcrCtcDecoder.decode(output.getFloatBuffer(), Math.toIntExact(shape[1]), characters);
        }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        try { session.close(); } catch (OrtException ignored) { }
        model = null; // Global OrtEnvironment is shared; session alone is owned here.
    }
}
