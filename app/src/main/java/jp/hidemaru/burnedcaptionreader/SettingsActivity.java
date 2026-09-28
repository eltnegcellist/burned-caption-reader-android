package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import jp.hidemaru.burnedcaptionreader.tts.SpeechLevel;

/** Settings used by the in-app video reader. */
public final class SettingsActivity extends Activity {
    private AppPreferences preferences;
    private LinearLayout root;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new AppPreferences(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ReaderUi.SURFACE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        page.setPadding(padding, dp(28), padding, dp(32));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = label("読み上げ設定", 28, ReaderUi.INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title);
        page.addView(label("字幕と声を自分に合う設定に。", 15, ReaderUi.MUTED),
                ReaderUi.block(this, 6));
        root = ReaderUi.card(this);
        page.addView(root, ReaderUi.block(this, 22));
        note("変更は次に開く動画から反映されます。");

        heading("字幕が続いたとき");
        Spinner mode = spinner(new String[]{
                "追従バランス（今の文は最後まで読む）",
                "最新字幕優先（古い読み上げを中断）"});
        mode.setSelection(AppPreferences.MODE_LATEST.equals(preferences.getSpeechMode()) ? 1 : 0);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                preferences.setSpeechMode(index == 1
                        ? AppPreferences.MODE_LATEST : AppPreferences.MODE_BALANCED);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        heading("読み上げ速度");
        TextView rateValue = label("", 15, ReaderUi.TEAL);
        root.addView(rateValue);
        SeekBar rate = new SeekBar(this);
        rate.setMax(150);
        rate.setProgress(Math.round((preferences.getSpeechRate() - 0.5f) * 100));
        rateValue.setText(String.format(Locale.JAPAN, "%.2f倍", preferences.getSpeechRate()));
        rate.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float value = 0.5f + progress / 100f;
                preferences.setSpeechRate(value);
                rateValue.setText(String.format(Locale.JAPAN, "%.2f倍", value));
            }
        });
        root.addView(rate);

        heading("読み上げ音量");
        TextView volumeValue = label("", 15, ReaderUi.TEAL);
        root.addView(volumeValue);
        SeekBar volume = new SeekBar(this);
        volume.setMax(SpeechLevel.MAX_PERCENT);
        volume.setProgress(preferences.getSpeechLevel());
        volumeValue.setText(volumeLabel(preferences.getSpeechLevel()));
        volume.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                preferences.setSpeechLevel(progress);
                volumeValue.setText(volumeLabel(progress));
            }
        });
        root.addView(volume);
        note("100%が標準。超えると声だけを増幅します（最大400%相当）。動画の音量は変わりません。声が割れる場合は下げてください。");

        heading("字幕の安定待ち時間");
        TextView stableValue = label(preferences.getStableMs() + " ms",
                15, ReaderUi.TEAL);
        root.addView(stableValue);
        SeekBar stable = new SeekBar(this);
        stable.setMax(15);
        stable.setProgress((int) ((preferences.getStableMs() - 250L) / 50L));
        stable.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long value = 250L + progress * 50L;
                preferences.setStableMs(value);
                stableValue.setText(value + " ms");
            }
        });
        root.addView(stable);

        root = ReaderUi.card(this);
        page.addView(root, ReaderUi.block(this, 16));
        root.addView(label("再生画面", 20, ReaderUi.INK));
        Switch hideCc = toggle("YouTubeの表示字幕（CC）を隠す",
                preferences.isYouTubeCcHidden());
        hideCc.setOnCheckedChangeListener((button, checked) ->
                preferences.setYouTubeCcHidden(checked));
        note("動画に焼き付いた字幕は隠れません。YouTube側の表示方法によってはCCが残る場合があります。");

        Switch keepAwake = toggle("再生中の自動消灯を防ぐ", preferences.isKeepScreenOn());
        keepAwake.setOnCheckedChangeListener((button, checked) ->
                preferences.setKeepScreenOn(checked));

        android.widget.Button back = ReaderUi.button(this, "動画のURL入力へ戻る", false);
        back.setOnClickListener(v -> finish());
        page.addView(back, ReaderUi.block(this, 22));
        setContentView(scroll);
    }

    private Switch toggle(String title, boolean checked) {
        Switch value = new Switch(this);
        value.setText(title);
        value.setTextSize(16);
        value.setTextColor(ReaderUi.INK);
        value.setChecked(checked);
        value.setPadding(0, dp(22), 0, dp(6));
        root.addView(value);
        return value;
    }

    private Spinner spinner(String[] choices) {
        Spinner value = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        value.setAdapter(adapter);
        root.addView(value);
        return value;
    }

    private void heading(String text) {
        TextView heading = label(text, 17, ReaderUi.INK);
        heading.setPadding(0, dp(22), 0, dp(8));
        root.addView(heading);
    }

    private void note(String text) {
        TextView value = label(text, 14, ReaderUi.MUTED);
        value.setPadding(0, dp(8), 0, dp(8));
        root.addView(value);
    }

    private TextView label(String text, int size, int color) {
        return ReaderUi.text(this, text, size, color, false);
    }

    private String volumeLabel(int percent) {
        if (percent <= 100) return percent + "%" + (percent == 100 ? "  ·  標準" : "");
        return String.format(Locale.JAPAN, "%d%%  ·  声だけ +%.1f dB",
                percent, SpeechLevel.gainMillibels(percent) / 100f);
    }

    private int dp(int px) {
        return ReaderUi.dp(this, px);
    }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
