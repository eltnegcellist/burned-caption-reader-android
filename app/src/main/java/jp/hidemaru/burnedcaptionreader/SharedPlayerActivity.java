package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import jp.hidemaru.burnedcaptionreader.ocr.MlKitJapaneseOcrEngine;
import jp.hidemaru.burnedcaptionreader.ocr.OcrEngine;

/** Native host for the official embedded player. PixelCopy is a diagnostic probe. */
public final class SharedPlayerActivity extends Activity {
    public static final String EXTRA_VIDEO_ID = "video_id";
    private WebView player;
    private OcrEngine probeOcr;
    private TextView probeStatus;
    private ImageView preview;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }
        getWindow().getDecorView().setKeepScreenOn(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        player = new WebView(this);
        player.setBackgroundColor(Color.BLACK);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(true);
        player.setWebViewClient(new WebViewClient());
        root.addView(player, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().widthPixels * 9f / 16f)));

        TextView instructions = new TextView(this);
        instructions.setText("動画をタップして再生してください。画面共有を許可した場合は、既存の字幕読み上げが動作します。");
        instructions.setTextColor(Color.WHITE);
        instructions.setPadding(18, 18, 18, 18);
        root.addView(instructions);
        Button probe = new Button(this);
        probe.setText("映像取得を確認（実験）");
        root.addView(probe);
        probeStatus = new TextView(this);
        probeStatus.setTextColor(Color.WHITE);
        probeStatus.setPadding(18, 14, 18, 14);
        probeStatus.setText("再生中に押すと、画面共有を使わずにプレーヤー部分を1枚取得してOCRを試します。");
        root.addView(probeStatus);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        probe.setOnClickListener(v -> probeFrame());

        // Only validated video IDs are inserted into this fixed, local HTML page.
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:#000;height:100vh'><iframe width='100%' height='100%' "
                + "src='https://www.youtube.com/embed/" + videoId + "?enablejsapi=1&playsinline=1' "
                + "title='YouTube video player' allow='autoplay; encrypted-media; fullscreen; picture-in-picture' "
                + "allowfullscreen frameborder='0'></iframe></body></html>";
        player.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null);
    }

    private void probeFrame() {
        if (player == null || player.getWidth() <= 0 || player.getHeight() <= 0) return;
        int[] origin = new int[2];
        player.getLocationInWindow(origin);
        Rect area = new Rect(origin[0], origin[1],
                origin[0] + player.getWidth(), origin[1] + player.getHeight());
        Bitmap frame = Bitmap.createBitmap(player.getWidth(), player.getHeight(), Bitmap.Config.ARGB_8888);
        probeStatus.setText("プレーヤー画像を確認中…");
        try {
            PixelCopy.request(getWindow(), area, frame, result -> {
                if (isDestroyed()) { frame.recycle(); return; }
                if (result != PixelCopy.SUCCESS) {
                    probeStatus.setText("映像を取得できませんでした（PixelCopy: " + result + "）。画面共有方式を使ってください。");
                    frame.recycle();
                    return;
                }
                preview.setImageBitmap(frame);
                if (probeOcr == null) probeOcr = new MlKitJapaneseOcrEngine();
                probeOcr.recognize(frame, text -> {
                    if (!isDestroyed()) probeStatus.setText("取得画像のOCR: "
                            + (text.getText().trim().isEmpty() ? "文字なし。画像に動画が写っているか確認してください。" : text.getText()));
                }, error -> {
                    if (!isDestroyed()) probeStatus.setText("OCRエラー: " + error.getMessage());
                });
            }, main);
        } catch (RuntimeException error) {
            frame.recycle();
            probeStatus.setText("画像取得エラー: " + error.getMessage());
        }
    }

    @Override protected void onDestroy() {
        if (player != null) {
            player.stopLoading();
            player.destroy();
            player = null;
        }
        if (probeOcr != null) probeOcr.close();
        super.onDestroy();
    }
}
