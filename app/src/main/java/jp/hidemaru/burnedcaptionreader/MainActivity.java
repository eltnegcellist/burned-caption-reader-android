package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
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
        scroll.setBackgroundColor(ReaderUi.SURFACE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        int padding = dp(24);
        root.setPadding(padding, dp(42), padding, dp(26));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView eyebrow = ReaderUi.text(this, "VIDEO CAPTIONS  ·  AUDIO", 12,
                ReaderUi.TEAL, true);
        root.addView(eyebrow);
        TextView title = ReaderUi.text(this, getString(R.string.app_name), 29,
                ReaderUi.INK, true);
        root.addView(title, ReaderUi.block(this, 10));
        TextView description = ReaderUi.text(this,
                "動画に映る字幕を、耳で楽しむ。\nYouTubeのURLからそのまま再生できます。",
                16, ReaderUi.MUTED, false);
        root.addView(description, ReaderUi.block(this, 12));

        LinearLayout videoCard = ReaderUi.card(this);
        root.addView(videoCard, ReaderUi.block(this, 34));
        videoCard.addView(ReaderUi.text(this, "動画を開く", 21, ReaderUi.INK, true));
        videoCard.addView(ReaderUi.text(this, "YouTube動画のURL", 14,
                ReaderUi.MUTED, false), ReaderUi.block(this, 16));
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setTextSize(16);
        urlInput.setTextColor(ReaderUi.INK);
        urlInput.setHintTextColor(ReaderUi.MUTED);
        urlInput.setHint("https://www.youtube.com/watch?v=...");
        urlInput.setSelectAllOnFocus(true);
        urlInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        urlInput.setBackground(ReaderUi.shape(this, ReaderUi.SURFACE, 12,
                android.graphics.Color.rgb(210, 225, 228)));
        videoCard.addView(urlInput, ReaderUi.block(this, 8));
        if (pendingVideoId != null) {
            urlInput.setText("https://www.youtube.com/watch?v=" + pendingVideoId);
        }
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) return false;
            openEnteredUrl();
            return true;
        });

        android.widget.Button play = ReaderUi.button(this, "動画を開いて読み上げる  ›", true);
        videoCard.addView(play, ReaderUi.block(this, 18));
        play.setOnClickListener(v -> openEnteredUrl());

        TextView shareHint = ReaderUi.text(this,
                "ブラウザの「共有」から直接開くこともできます。", 14,
                ReaderUi.MUTED, false);
        videoCard.addView(shareHint, ReaderUi.block(this, 16));

        android.widget.Button settings = ReaderUi.button(this, "読み上げ設定  ›", false);
        root.addView(settings, ReaderUi.block(this, 18));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        android.widget.Button guide = ReaderUi.button(this, "使い方を見る  ›", false);
        root.addView(guide, ReaderUi.block(this, 10));
        guide.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
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

    private int dp(int px) {
        return ReaderUi.dp(this, px);
    }
}
