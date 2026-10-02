package jp.hidemaru.burnedcaptionreader.diagnostics;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

/** Uses Android's document picker; no storage permission or automatic upload. */
public final class DiagnosticExport {
    private static final int REQUEST = 4901;
    private DiagnosticExport() {}

    public static void request(Activity activity) {
        DiagnosticRecorder recorder = DiagnosticRecorder.get(activity);
        if (recorder.isExporting()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "caption-diagnostics-" + System.currentTimeMillis() + ".zip");
        try { activity.startActivityForResult(intent, REQUEST); }
        catch (RuntimeException e) {
            Toast.makeText(activity, "保存先を開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    public static boolean handle(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST) return false;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
            DiagnosticRecorder.get(activity).export(data.getData(), error -> {
                if (!activity.isDestroyed()) Toast.makeText(activity,
                        error == null ? "診断ZIPを保存しました（記録は停止しました）" : error,
                        Toast.LENGTH_LONG).show();
            });
        }
        return true;
    }
}
