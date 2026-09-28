package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;

/** Settings used by the in-app video reader. */
public final class SettingsActivity extends Activity {
    private AppPreferences preferences;
    private LinearLayout root;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new AppPreferences(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 248, 251));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = label("読み上げ設定", 26, Color.rgb(11, 37, 50));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
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
        TextView rateValue = label("", 15, Color.rgb(68, 88, 100));
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
        TextView volumeValue = label("", 15, Color.rgb(68, 88, 100));
        root.addView(volumeValue);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(preferences.getSpeechVolume() * 100));
        volumeValue.setText(Math.round(preferences.getSpeechVolume() * 100) + "%");
        volume.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                preferences.setSpeechVolume(progress / 100f);
                volumeValue.setText(progress + "%");
            }
        });
        root.addView(volume);

        heading("声だけを増幅");
        Spinner boost = spinner(new String[]{"オフ", "弱（+6 dB）", "強（+12 dB）"});
        boost.setSelection(preferences.getSpeechBoost());
        boost.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                preferences.setSpeechBoost(index);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        note("動画の音量は変えません。声が割れる場合は弱めてください。");

        heading("字幕の安定待ち時間");
        TextView stableValue = label(preferences.getStableMs() + " ms",
                15, Color.rgb(68, 88, 100));
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

        Switch hideCc = toggle("YouTubeの表示字幕（CC）を隠す",
                preferences.isYouTubeCcHidden());
        hideCc.setOnCheckedChangeListener((button, checked) ->
                preferences.setYouTubeCcHidden(checked));
        note("動画に焼き付いた字幕は隠れません。YouTube側の表示方法によってはCCが残る場合があります。");

        Switch keepAwake = toggle("再生中の自動消灯を防ぐ", preferences.isKeepScreenOn());
        keepAwake.setOnCheckedChangeListener((button, checked) ->
                preferences.setKeepScreenOn(checked));

        Button back = new Button(this);
        back.setText("戻る");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private Switch toggle(String title, boolean checked) {
        Switch value = new Switch(this);
        value.setText(title);
        value.setTextSize(16);
        value.setTextColor(Color.rgb(11, 37, 50));
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
        TextView heading = label(text, 17, Color.rgb(11, 37, 50));
        heading.setPadding(0, dp(22), 0, dp(8));
        root.addView(heading);
    }

    private void note(String text) {
        TextView value = label(text, 14, Color.rgb(68, 88, 100));
        value.setPadding(0, dp(8), 0, dp(8));
        root.addView(value);
    }

    private TextView label(String text, int size, int color) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextSize(size);
        value.setTextColor(color);
        return value;
    }

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
