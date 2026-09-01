package jp.hidemaru.burnedcaptionreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshStatus();
            uiHandler.postDelayed(this, 500L);
        }
    };

    private AppPreferences preferences;
    private TextView statusValue;
    private TextView ocrValue;
    private TextView spokenValue;
    private TextView rateValue;
    private TextView stableValue;
    private Button startButton;
    private Button stopButton;
    private boolean continueStartAfterNotificationRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new AppPreferences(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 248, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("焼き付け字幕リーダー", 28, Color.rgb(7, 21, 33));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("ブラウザのYouTube画面に直接描かれた日本語字幕を、端末内で認識して読み上げます。", 16,
                Color.rgb(56, 74, 85));
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        root.addView(section("使い方"));
        root.addView(text("1. 「画面共有を開始」を押す\n2. ブラウザでYouTube動画を再生する\n3. 通知の「字幕領域を選択」から字幕を囲む\n4. ブラウザへ戻る", 16,
                Color.rgb(31, 52, 64)));

        startButton = primaryButton("画面共有を開始");
        startButton.setOnClickListener(v -> beginStartFlow());
        addWithTopMargin(root, startButton, 16);

        Button browserButton = normalButton("ブラウザへ移動");
        browserButton.setOnClickListener(v -> openBrowser());
        addWithTopMargin(root, browserButton, 8);

        Button roiButton = normalButton("現在の画面から字幕領域を選択");
        roiButton.setOnClickListener(v -> openRoiEditor());
        addWithTopMargin(root, roiButton, 8);

        stopButton = normalButton("読み上げを停止");
        stopButton.setOnClickListener(v -> stopCapture());
        addWithTopMargin(root, stopButton, 8);

        root.addView(section("動作状況"));
        statusValue = valueText();
        root.addView(labelValue("状態", statusValue));
        ocrValue = valueText();
        root.addView(labelValue("最新のOCR", ocrValue));
        spokenValue = valueText();
        root.addView(labelValue("最後に読み上げた字幕", spokenValue));

        root.addView(section("読み上げ設定"));
        TextView modeLabel = text("字幕が続いたとき", 14, Color.rgb(56, 74, 85));
        root.addView(modeLabel);
        Spinner modeSpinner = new Spinner(this);
        String[] modes = {"連続読み上げ（順番に読む）", "最新字幕優先（途中で切り替える）"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        modeSpinner.setSelection(AppPreferences.MODE_LATEST.equals(preferences.getSpeechMode()) ? 1 : 0);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.setSpeechMode(position == 1 ? AppPreferences.MODE_LATEST : AppPreferences.MODE_CONTINUOUS);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(modeSpinner);

        rateValue = valueText();
        root.addView(labelValue("読み上げ速度", rateValue));
        SeekBar rate = new SeekBar(this);
        rate.setMax(150);
        rate.setProgress(Math.round((preferences.getSpeechRate() - 0.5f) * 100));
        rate.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.5f + progress / 100f;
                preferences.setSpeechRate(value);
                rateValue.setText(String.format(Locale.JAPAN, "%.2f倍", value));
            }
        });
        root.addView(rate);

        stableValue = valueText();
        root.addView(labelValue("字幕の安定待ち時間", stableValue));
        SeekBar stable = new SeekBar(this);
        stable.setMax(15);
        stable.setProgress((int) ((preferences.getStableMs() - 250L) / 50L));
        stable.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                long value = 250L + progress * 50L;
                preferences.setStableMs(value);
                stableValue.setText(value + " ms");
            }
        });
        root.addView(stable);

        root.addView(section("プライバシー"));
        TextView privacy = text("画面画像と認識結果は端末内だけで処理します。映像をサーバーへ送信しません。DRMなどで保護された画面は取得できない場合があります。", 14,
                Color.rgb(56, 74, 85));
        privacy.setPadding(0, 0, 0, dp(28));
        root.addView(privacy);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding, padding + insets.getSystemWindowInsetTop(), padding,
                    padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        refreshStatus();
    }

    private void beginStartFlow() {
        if (AppState.isRunning()) {
            Toast.makeText(this, "すでに画面共有中です", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            continueStartAfterNotificationRequest = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "画面共有は開始されませんでした", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        AppState.setStatus("画面共有を準備しています");
        Toast.makeText(this, "ブラウザへ移動し、通知から字幕領域を選択してください", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && continueStartAfterNotificationRequest) {
            continueStartAfterNotificationRequest = false;
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "ブラウザ上から字幕領域を選択するため、通知の許可が必要です", Toast.LENGTH_LONG).show();
                return;
            }
            requestScreenCapture();
        }
    }

    private void openBrowser() {
        try {
            Intent browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
            startActivity(browser);
        } catch (RuntimeException error) {
            Toast.makeText(this, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void openRoiEditor() {
        if (!AppState.isRunning()) {
            Toast.makeText(this, "先に画面共有を開始してください", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, RoiEditorActivity.class));
    }

    private void stopCapture() {
        stopService(new Intent(this, CaptureService.class));
        AppState.setRunning(false);
        AppState.setStatus("停止中");
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusValue == null) return;
        statusValue.setText(AppState.getStatus());
        ocrValue.setText(AppState.getLastOcr());
        spokenValue.setText(AppState.getLastSpoken());
        rateValue.setText(String.format(Locale.JAPAN, "%.2f倍", preferences.getSpeechRate()));
        stableValue.setText(preferences.getStableMs() + " ms");
        startButton.setEnabled(!AppState.isRunning());
        stopButton.setEnabled(AppState.isRunning());
    }

    private TextView section(String title) {
        TextView view = text(title, 19, Color.rgb(7, 92, 126));
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(24), 0, dp(10));
        return view;
    }

    private LinearLayout labelValue(String label, TextView value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundColor(Color.WHITE);
        TextView labelView = text(label, 13, Color.rgb(82, 99, 108));
        box.addView(labelView);
        box.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        box.setLayoutParams(params);
        return box;
    }

    private TextView valueText() {
        TextView value = text("", 16, Color.rgb(12, 31, 42));
        value.setPadding(0, dp(4), 0, 0);
        value.setTextIsSelectable(true);
        return value;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.15f);
        return text;
    }

    private Button primaryButton(String label) {
        Button button = normalButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(7, 126, 164));
        return button;
    }

    private Button normalButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(52));
        return button;
    }

    private void addWithTopMargin(LinearLayout root, View view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        root.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStart() {
        super.onStart();
        uiHandler.post(refreshTask);
    }

    @Override protected void onStop() {
        uiHandler.removeCallbacks(refreshTask);
        super.onStop();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
