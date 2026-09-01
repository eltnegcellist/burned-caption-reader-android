package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class RoiEditorActivity extends Activity {
    private Bitmap snapshot;
    private RoiSelectionView selectionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        snapshot = AppState.copyLatestFrame();
        if (snapshot == null) {
            Toast.makeText(this, "画面の取得を待ってから、もう一度お試しください", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(7, 21, 33));
        window.setNavigationBarColor(Color.rgb(7, 21, 33));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 21, 33));
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("読み上げる字幕を囲んでください");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText("字幕の左上から右下へドラッグします。動画タイトルや操作ボタンは含めないでください。");
        help.setTextColor(Color.rgb(196, 214, 224));
        help.setTextSize(14);
        help.setPadding(0, 0, 0, dp(12));
        root.addView(help, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        selectionView = new RoiSelectionView(this);
        selectionView.setBitmap(snapshot);
        AppPreferences preferences = new AppPreferences(this);
        if (preferences.hasRoi()) selectionView.setSelection(preferences.getRoi());
        root.addView(selectionView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);

        Button cancel = button("キャンセル");
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View spacer = new View(this);
        buttons.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));

        Button save = button("この領域を使う");
        save.setOnClickListener(v -> saveSelection());
        buttons.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding, padding + insets.getSystemWindowInsetTop(), padding,
                    padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
    }

    private void saveSelection() {
        if (!selectionView.hasUsableSelection()) {
            Toast.makeText(this, "もう少し大きな範囲を選択してください", Toast.LENGTH_SHORT).show();
            return;
        }
        new AppPreferences(this).setRoi(selectionView.getSelection());
        AppState.setStatus("字幕領域を監視しています");
        Toast.makeText(this, "字幕領域を保存しました。ブラウザに戻ってください", Toast.LENGTH_LONG).show();
        finish();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
        super.onDestroy();
    }
}
