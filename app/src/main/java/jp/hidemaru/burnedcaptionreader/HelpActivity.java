package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** In-app guide for the two supported URL entry paths. */
public final class HelpActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ReaderUi.SURFACE);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(30));
        scroll.addView(root);

        root.addView(ReaderUi.text(this, "QUICK START", 12, ReaderUi.TEAL, true));
        root.addView(ReaderUi.text(this, "使い方", 29, ReaderUi.INK, true),
                ReaderUi.block(this, 8));
        root.addView(ReaderUi.text(this,
                "動画のURLを入力するか、ブラウザから共有して開けます。画面共有の設定は不要です。",
                16, ReaderUi.MUTED, false), ReaderUi.block(this, 12));

        LinearLayout paste = section(root, "01", "URLを入力して開く");
        instruction(paste, "ブラウザで見たいYouTube動画のURLをコピーします。");
        instruction(paste, "このアプリを開き、URL欄に貼り付けて「動画を開いて読み上げる」を押します。");
        illustrationLabel(paste, "アプリのトップ画面イメージ");
        LinearLayout homePreview = preview(paste, ReaderUi.SURFACE);
        homePreview.addView(ReaderUi.text(this, "動画内字幕リーダー", 17,
                ReaderUi.INK, true));
        homePreview.addView(ReaderUi.text(this, "動画を開く", 15,
                ReaderUi.INK, true), ReaderUi.block(this, 10));
        previewField(homePreview, "https://www.youtube.com/watch?v=…");
        previewPill(homePreview, "動画を開いて読み上げる  ›", true);
        previewPill(homePreview, "使い方を見る  ›", false);
        Button open = ReaderUi.button(this, "URL入力画面へ  ›", false);
        paste.addView(open, ReaderUi.block(this, 14));
        open.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });

        LinearLayout share = section(root, "02", "ブラウザから共有して開く");
        instruction(share, "ブラウザでYouTubeの動画ページを表示し、「共有」を押します。");
        instruction(share, "共有先から「動画内字幕リーダー」を選ぶと、その動画がアプリ内で開きます。");
        illustrationLabel(share, "Androidの共有メニューイメージ");
        LinearLayout sharePreview = preview(share, Color.WHITE);
        sharePreview.addView(ReaderUi.text(this, "リンクを共有", 15,
                ReaderUi.INK, true));
        previewField(sharePreview, "youtube.com/watch?v=…");
        previewPill(sharePreview, "動画内字幕リーダー  ↗", true);
        share.addView(ReaderUi.text(this,
                "共有先に表示されない場合は「リンクをコピー」を選び、上のURL入力をお使いください。共有メニューの見た目はブラウザや端末で異なります。",
                13, ReaderUi.MUTED, false), ReaderUi.block(this, 14));

        LinearLayout playback = section(root, "03", "再生中の操作");
        instruction(playback, "動画をタップして再生すると、映像内の日本語字幕を探して読み上げます。");
        instruction(playback, "「読み上げを一時停止」は音声だけ止めます。動画は再生を続けます。");
        instruction(playback, "「設定」は動画を見ながら開けます。「トップ画面に戻る」で再生画面を閉じます。");
        illustrationLabel(playback, "再生画面の操作イメージ");
        LinearLayout playerPreview = preview(playback, ReaderUi.SURFACE);
        TextView video = ReaderUi.text(this, "▶  動画をタップして再生", 15,
                Color.WHITE, true);
        video.setGravity(Gravity.CENTER);
        video.setMinHeight(dp(92));
        video.setBackground(ReaderUi.shape(this, ReaderUi.INK, 12, 0));
        playerPreview.addView(video);
        previewPill(playerPreview, "読み上げを一時停止    設定", true);
        previewPill(playerPreview, "トップ画面に戻る", false);
        playback.addView(ReaderUi.text(this,
                "全画面表示は動画プレーヤーの全画面ボタンから操作します。YouTubeのCC字幕の表示は読み上げ設定で切り替えられます。",
                13, ReaderUi.MUTED, false), ReaderUi.block(this, 14));

        LinearLayout note = ReaderUi.card(this);
        root.addView(note, ReaderUi.block(this, 20));
        note.addView(ReaderUi.text(this, "読み上げについて", 16, ReaderUi.INK, true));
        note.addView(ReaderUi.text(this,
                "動画に直接描かれた文字を端末内で読み取ります。字幕のデザインや動画によっては読み間違いや読み飛ばしがあります。一部の動画はアプリ内での再生が制限されます。",
                14, ReaderUi.MUTED, false), ReaderUi.block(this, 8));
        setContentView(scroll);
    }

    private LinearLayout section(LinearLayout root, String number, String title) {
        LinearLayout card = ReaderUi.card(this);
        root.addView(card, ReaderUi.block(this, 18));
        card.addView(ReaderUi.text(this, number + "  " + title, 20,
                ReaderUi.INK, true));
        return card;
    }

    private void instruction(LinearLayout parent, String sentence) {
        parent.addView(ReaderUi.text(this, "•  " + sentence, 15,
                ReaderUi.INK, false), ReaderUi.block(this, 12));
    }

    private void illustrationLabel(LinearLayout parent, String label) {
        parent.addView(ReaderUi.text(this, label + "（模式図）", 12,
                ReaderUi.MUTED, false), ReaderUi.block(this, 18));
    }

    private LinearLayout preview(LinearLayout parent, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(ReaderUi.shape(this, color, 16,
                Color.rgb(210, 225, 228)));
        parent.addView(box, ReaderUi.block(this, 6));
        return box;
    }

    private void previewField(LinearLayout parent, String text) {
        TextView field = ReaderUi.text(this, text, 13, ReaderUi.MUTED, false);
        field.setSingleLine(true);
        field.setEllipsize(android.text.TextUtils.TruncateAt.END);
        field.setPadding(dp(10), dp(10), dp(10), dp(10));
        field.setBackground(ReaderUi.shape(this, Color.WHITE, 9,
                Color.rgb(210, 225, 228)));
        parent.addView(field, ReaderUi.block(this, 8));
    }

    private void previewPill(LinearLayout parent, String text, boolean primary) {
        TextView pill = ReaderUi.text(this, text, 13,
                primary ? Color.WHITE : ReaderUi.TEAL, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(8), dp(11), dp(8), dp(11));
        pill.setBackground(ReaderUi.shape(this, primary ? ReaderUi.TEAL : Color.WHITE,
                12, primary ? 0 : Color.rgb(210, 225, 228)));
        parent.addView(pill, ReaderUi.block(this, 8));
    }

    private int dp(int value) {
        return ReaderUi.dp(this, value);
    }
}
