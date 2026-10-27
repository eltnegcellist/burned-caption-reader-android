package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
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
import android.app.Activity;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticExport;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticRecorder;

/** Shared settings view; embedding it keeps video playback and OCR active. */
final class SettingsPanel extends ScrollView {
    private final AppPreferences preferences;
    private final Runnable onChanged;
    private LinearLayout section;
    private Switch diagnosticSwitch;
    private TextView diagnosticStatus;

    SettingsPanel(Context context, AppPreferences preferences, Runnable onClose,
            Runnable onChanged, boolean inPlayer) {
        super(context);
        this.preferences = preferences;
        this.onChanged = onChanged;
        setFillViewport(!inPlayer);
        setBackgroundColor(ReaderUi.SURFACE);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(inPlayer ? 16 : 28), dp(20), dp(24));
        addView(page);
        if (!inPlayer) setOnApplyWindowInsetsListener((view, insets) -> {
            setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        page.addView(ReaderUi.text(context, "読み上げ設定", inPlayer ? 22 : 28,
                ReaderUi.INK, true));
        section = ReaderUi.card(context);
        page.addView(section, ReaderUi.block(context, inPlayer ? 12 : 22));
        note(inPlayer ? "動画は再生を続けます。声の変更は次の読み上げから反映されます。"
                : "次に開く動画から反映されます。");

        note("字幕の検出にはML Kit、文字認識にはPP-OCRv6 Smallを使用します。");

        heading("字幕が続いたとき");
        Spinner mode = spinner(new String[]{
                "追従バランス（今の文は最後まで読む）",
                "最新字幕優先（古い読み上げを中断）"});
        mode.setSelection(AppPreferences.MODE_LATEST.equals(preferences.getSpeechMode()) ? 1 : 0);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                preferences.setSpeechMode(index == 1
                        ? AppPreferences.MODE_LATEST : AppPreferences.MODE_BALANCED);
                onChanged.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        heading("読み上げ速度");
        note("設定した速度で読み上げます。読み上げ待ちが長くなった場合は、一時的に速くなることがあります。");
        TextView rateValue = value(String.format(Locale.JAPAN, "%.2f倍", preferences.getSpeechRate()));
        SeekBar rate = new SeekBar(context);
        rate.setMax(150);
        rate.setProgress(Math.round((preferences.getSpeechRate() - 0.5f) * 100));
        rate.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float selected = 0.5f + progress / 100f;
                preferences.setSpeechRate(selected);
                rateValue.setText(String.format(Locale.JAPAN, "%.2f倍", selected));
                onChanged.run();
            }
        });
        section.addView(rate);

        heading("読み上げ音量");
        TextView volumeValue = value(volumeLabel(preferences.getSpeechLevel()));
        SeekBar volume = new SeekBar(context);
        volume.setMax(SpeechLevel.MAX_PERCENT);
        volume.setProgress(preferences.getSpeechLevel());
        volume.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                preferences.setSpeechLevel(progress);
                volumeValue.setText(volumeLabel(progress));
                onChanged.run();
            }
        });
        section.addView(volume);
        note("100%を超えると声だけを増幅。動画の音量は変わりません。声が割れる場合は下げてください。");


        Switch recheck = toggle("字幕を再確認してから読み上げる",
                preferences.isSubtitleRecheckEnabled());
        recheck.setTag("subtitle_recheck_enabled");
        recheck.setContentDescription("字幕を再確認してから読み上げる");
        note("OFFでは最初に読み取った字幕をすぐ読み上げます。ONではもう一度確認するため、開始が遅くなりますが、認識の揺れを抑えられる場合があります。");
        LinearLayout stableGroup = new LinearLayout(context);
        stableGroup.setOrientation(LinearLayout.VERTICAL);
        TextView stableHeading = ReaderUi.text(context, "再確認時の安定待ち時間", 17,
                ReaderUi.INK, true);
        stableHeading.setPadding(0, dp(22), 0, dp(8));
        stableGroup.addView(stableHeading);
        TextView stableValue = ReaderUi.text(context, preferences.getStableMs() + " ms",
                15, ReaderUi.TEAL, false);
        stableGroup.addView(stableValue);
        SeekBar stable = new SeekBar(context);
        stable.setMax(15);
        stable.setProgress((int) ((preferences.getStableMs() - 250L) / 50L));
        stable.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long selected = 250L + progress * 50L;
                preferences.setStableMs(selected);
                stableValue.setText(selected + " ms");
                onChanged.run();
            }
        });
        stableGroup.addView(stable);
        stableGroup.setVisibility(preferences.isSubtitleRecheckEnabled()
                ? View.VISIBLE : View.GONE);
        section.addView(stableGroup);
        recheck.setOnCheckedChangeListener((button, checked) -> {
            if (checked == preferences.isSubtitleRecheckEnabled()) return;
            preferences.setSubtitleRecheckEnabled(checked);
            stableGroup.setVisibility(checked ? View.VISIBLE : View.GONE);
            onChanged.run();
        });

        section = ReaderUi.card(context);
        page.addView(section, ReaderUi.block(context, 16));
        section.addView(ReaderUi.text(context, "再生画面", 20, ReaderUi.INK, true));
        Switch hideCc = toggle("YouTubeの表示字幕（CC）を隠す",
                preferences.isYouTubeCcHidden());
        hideCc.setOnCheckedChangeListener((button, checked) -> {
            preferences.setYouTubeCcHidden(checked);
            onChanged.run();
        });
        note("動画に焼き付いた字幕は隠れません。YouTube側の表示方法によってはCCが残る場合があります。");
        Switch keepAwake = toggle("再生中の自動消灯を防ぐ", preferences.isKeepScreenOn());
        keepAwake.setOnCheckedChangeListener((button, checked) -> {
            preferences.setKeepScreenOn(checked);
            onChanged.run();
        });

        // The production app must never expose or apply experimental timing controls.
        if ((context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            section = ReaderUi.card(context);
            page.addView(section, ReaderUi.block(context, 16));
            section.addView(ReaderUi.text(context, "速度実験（試験版のみ）",
                    20, ReaderUi.INK, true));
            note("YouTubeの画面に表示された動画だけを読み取ります。"
                    + "動画ファイルや未来のフレームは取得しません。");
            heading("字幕画像の取得方式");
            Spinner samplingChoice = spinner(new String[]{
                    "480ms周期（従来）", "240ms周期", "フレーム更新に連動"
            });
            samplingChoice.setTag("experiment_sampling_mode");
            samplingChoice.setContentDescription("字幕画像の取得方式");
            SubtitleSamplingExperiment currentMode = preferences.getExperimentSamplingMode();
            samplingChoice.setSelection(currentMode == SubtitleSamplingExperiment.VIDEO_FRAME
                    ? 2 : currentMode == SubtitleSamplingExperiment.POLL_240 ? 1 : 0);
            samplingChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                    SubtitleSamplingExperiment selected = index == 2
                            ? SubtitleSamplingExperiment.VIDEO_FRAME
                            : index == 1 ? SubtitleSamplingExperiment.POLL_240
                            : SubtitleSamplingExperiment.POLL_480;
                    if (preferences.getExperimentSamplingMode() == selected) return;
                    preferences.setExperimentSamplingMode(selected);
                    onChanged.run();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            note("フレーム連動に対応していない場合は480ms周期に戻ります。"
                    + "画面更新の通知を使う方式で、字幕の先読みではありません。");

            heading("上段字幕の最大待機時間");
            Spinner orderChoice = spinner(new String[]{
                    "700ms（従来）", "300ms（短縮）", "0ms（待たない）"
            });
            orderChoice.setTag("experiment_order_wait_ms");
            orderChoice.setContentDescription("上段字幕の最大待機時間");
            long selectedWait = preferences.getExperimentOrderWaitMs();
            orderChoice.setSelection(selectedWait == 0 ? 2 : selectedWait == 300 ? 1 : 0);
            orderChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                    int selected = index == 2 ? 0 : index == 1 ? 300 : 700;
                    if (preferences.getExperimentOrderWaitMs() == selected) return;
                    preferences.setExperimentOrderWaitMs(selected);
                    onChanged.run();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            note("上の行が認識途中の場合だけ待ちます。0msでは3行字幕の順番が"
                    + "崩れる可能性があります。変更後、新しい字幕から反映します。");
        }

        section = ReaderUi.card(context);
        page.addView(section, ReaderUi.block(context, 16));
        section.addView(ReaderUi.text(context, "診断データ", 20, ReaderUi.INK, true));
        DiagnosticRecorder recorder = DiagnosticRecorder.get(context);
        diagnosticSwitch = toggle("動画画像と読み上げログを記録", recorder.isRecording());
        diagnosticStatus = value(recorder.status());
        diagnosticSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) recorder.start(); else recorder.stop();
            diagnosticStatus.setText(recorder.status());
        });
        note("オンにした後の動画部分の画像とOCR・発話ログを端末内に保存します。"
                + "直近約2分・最大64MB。外部へ自動送信しません。新しくオンにすると前の記録を消去します。");
        android.widget.Button export = ReaderUi.button(context, "診断ZIPを書き出す", false);
        export.setOnClickListener(v -> {
            if (context instanceof Activity) DiagnosticExport.request((Activity) context);
        });
        section.addView(export, ReaderUi.block(context, 12));
        note("保存先を選びます。画像や字幕が含まれるので、共有前に内容を確認してください。"
                + "書き出しは記録開始ボタンではありません。");

        android.widget.Button back = ReaderUi.button(context,
                inPlayer ? "動画へ戻る" : "URL入力へ戻る", false);
        back.setOnClickListener(v -> onClose.run());
        page.addView(back, ReaderUi.block(context, 22));
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && diagnosticSwitch != null) {
            DiagnosticRecorder recorder = DiagnosticRecorder.get(getContext());
            diagnosticSwitch.setChecked(recorder.isRecording());
            diagnosticStatus.setText(recorder.status());
        }
    }

    private Switch toggle(String title, boolean checked) {
        Switch value = new Switch(getContext());
        value.setText(title);
        value.setTextSize(16);
        value.setTextColor(ReaderUi.INK);
        value.setChecked(checked);
        value.setPadding(0, dp(22), 0, dp(6));
        section.addView(value);
        return value;
    }

    private Spinner spinner(String[] choices) {
        Spinner value = new Spinner(getContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        value.setAdapter(adapter);
        section.addView(value);
        return value;
    }

    private void heading(String text) {
        TextView view = ReaderUi.text(getContext(), text, 17, ReaderUi.INK, true);
        view.setPadding(0, dp(22), 0, dp(8));
        section.addView(view);
    }

    private TextView value(String text) {
        TextView view = ReaderUi.text(getContext(), text, 15, ReaderUi.TEAL, false);
        section.addView(view);
        return view;
    }

    private void note(String text) {
        TextView view = ReaderUi.text(getContext(), text, 14, ReaderUi.MUTED, false);
        view.setPadding(0, dp(8), 0, dp(8));
        section.addView(view);
    }

    private static String volumeLabel(int percent) {
        if (percent <= 100) return percent + "%" + (percent == 100 ? "  ·  標準" : "");
        return String.format(Locale.JAPAN, "%d%%  ·  声だけ +%.1f dB",
                percent, SpeechLevel.gainMillibels(percent) / 100f);
    }

    private int dp(int px) { return ReaderUi.dp(getContext(), px); }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
