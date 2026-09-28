package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Entry point for locally reading a shared or pasted YouTube video URL. */
public final class MainActivity extends Activity {
    private EditText urlInput;
    private String pendingVideoId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        acceptSharedVideo(getIntent());
        showHome();
        if (state != null && pendingVideoId == null) {
            urlInput.setText(state.getString("draft_url", ""));
        }
        if (pendingVideoId != null) openPendingVideo();
    }

    private void showHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 248, 251));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        int padding = dp(22);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = label("焼き付け字幕リーダー", 27, Color.rgb(11, 37, 50));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView description = label(
                "YouTube動画のURLを入れて再生。画面に描かれた日本語字幕を読み上げます。",
                16, Color.rgb(68, 88, 100));
        description.setPadding(0, dp(10), 0, dp(30));
        root.addView(description);

        root.addView(label("動画のURL", 17, Color.rgb(11, 37, 50)));
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setTextSize(16);
        urlInput.setHint("https://www.youtube.com/watch?v=...");
        urlInput.setSelectAllOnFocus(true);
        root.addView(urlInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (pendingVideoId != null) {
            urlInput.setText("https://www.youtube.com/watch?v=" + pendingVideoId);
        }
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) return false;
            openEnteredUrl();
            return true;
        });

        Button play = button("動画を開いて読み上げる", true);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playParams.topMargin = dp(20);
        root.addView(play, playParams);
        play.setOnClickListener(v -> openEnteredUrl());

        TextView shareHint = label("ブラウザの「共有」からこのアプリを選ぶ方法も使えます。",
                14, Color.rgb(68, 88, 100));
        shareHint.setPadding(0, dp(14), 0, dp(24));
        root.addView(shareHint);

        Button settings = button("設定", false);
        root.addView(settings);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        setContentView(scroll);
    }

    private void openEnteredUrl() {
        String id = YouTubeShareUrl.videoId(urlInput.getText().toString().trim());
        if (id == null) {
            urlInput.setError("YouTube動画のURLを入力してください");
            return;
        }
        openVideo(id);
    }

    private void openVideo(String id) {
        urlInput.setText("https://www.youtube.com/watch?v=" + id);
        startActivity(new Intent(this, SharedPlayerActivity.class)
                .putExtra(SharedPlayerActivity.EXTRA_VIDEO_ID, id));
    }

    private void openPendingVideo() {
        String id = pendingVideoId;
        pendingVideoId = null;
        if (id != null) openVideo(id);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (acceptSharedVideo(intent)) openPendingVideo();
    }

    private boolean acceptSharedVideo(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())
                || !"text/plain".equals(intent.getType())) return false;
        String id = YouTubeShareUrl.videoId(intent.getStringExtra(Intent.EXTRA_TEXT));
        if (id == null) {
            Toast.makeText(this, "YouTube動画のURLを読み取れませんでした",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        pendingVideoId = id;
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (urlInput != null) outState.putString("draft_url", urlInput.getText().toString());
        super.onSaveInstanceState(outState);
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(54));
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackgroundColor(Color.rgb(8, 115, 157));
        }
        return button;
    }

    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }
}
