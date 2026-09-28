package jp.hidemaru.burnedcaptionreader.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import jp.hidemaru.burnedcaptionreader.ocr.*;

public class RealDiagnosticRegressionTest {
    @Test public void capturedFrame0KeepsAllDialogueGroups() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult result = new OcrResult("", 60, Arrays.asList(
            new OcrLine(0, "るm.youtube.com/watch?v=AalK-1Ts ロ", 41.777974367141724, 0.03591682389378548f, 0.1092783510684967f, 0.822306215763092f, 0.15670102834701538f),
            new OcrLine(1, "ト YouTube", 35.43526828289032, 0.05576559528708458f, 0.2618556618690491f, 0.28638941049575806f, 0.32061856985092163f),
            new OcrLine(2, "をれらを何も考えず丸客みしていると", 49.37959611415863, 0.022684309631586075f, 0.38969072699546814f, 0.9697542786598206f, 0.47422680258750916f),
            new OcrLine(3, "地獄が待わているため", 67.6562488079071, 0.20415878295898438f, 0.47628864645957947f, 0.782608687877655f, 0.5567010045051575f),
            new OcrLine(4, "ハイハイ", 62.6953125, 0.13232514262199402f, 0.692783534526825f, 0.3185255229473114f, 0.7515463829040527f),
            new OcrLine(4, "言ってたら", 73.98437261581421, 0.0907372385263443f, 0.7711340188980103f, 0.32136106491088867f, 0.8268041014671326f),
            new OcrLine(5, "お先", 89.6484375, 0.6616256833076477f, 0.8020618557929993f, 0.760869562625885f, 0.8577319383621216f),
            new OcrLine(6, "真っ暗…", 53.22265625, 0.6597353219985962f, 0.8721649646759033f, 0.8648393154144287f, 0.9350515604019165f)));
        tracker.selectAll(0, result);
        String all = tracker.selectAll(500, result).stream().map(s -> s.getText()).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(all.contains("ハイハイ"));
        assertTrue(all.contains("言ってたら"));
        assertTrue(all.contains("お先"));
        assertTrue(all.contains("真っ暗"));
    }
    @Test public void capturedFrame1KeepsAllDialogueGroups() {
        AutoSubtitleRegionTracker tracker = new AutoSubtitleRegionTracker();
        OcrResult result = new OcrResult("", 60, Arrays.asList(
            new OcrLine(0, "るm.youtube.com/watch?v=AalK-1Ts ロ", 41.777974367141724, 0.03591682389378548f, 0.1092783510684967f, 0.822306215763092f, 0.15670102834701538f),
            new OcrLine(1, "ト YouTube", 35.32366156578064, 0.05576559528708458f, 0.2618556618690491f, 0.28638941049575806f, 0.32061856985092163f),
            new OcrLine(2, "-程度に応じて。", 41.6015625, 0.31758034229278564f, 0.39175257086753845f, 0.6805292963981628f, 0.47422680258750916f),
            new OcrLine(3, "『る「楽件をつける』", 47.539061307907104, 0.20037807524204254f, 0.47422680258750916f, 0.7920604944229126f, 0.5587629079818726f),
            new OcrLine(4, "『別の何かで低減する」など", 52.6442289352417, 0.14933837950229645f, 0.561855673789978f, 0.8591682314872742f, 0.6432989835739136f),
            new OcrLine(5, "断る一辺倒だと", 80.13392686843872, 0.026465028524398804f, 0.7092783451080322f, 0.3875236213207245f, 0.7649484276771545f),
            new OcrLine(5, "それはそれで……", 73.095703125, 0.04725898057222366f, 0.7855669856071472f, 0.385633260011673f, 0.8412371277809143f),
            new OcrLine(6, "上手くやるのも", 78.62723469734192, 0.6370510458946228f, 0.8247422575950623f, 0.974480152130127f, 0.8814433217048645f),
            new OcrLine(7, "大事ではある", 84.5703125, 0.6351606845855713f, 0.8989690542221069f, 0.9470699429512024f, 0.9567010402679443f)));
        tracker.selectAll(0, result);
        String all = tracker.selectAll(500, result).stream().map(s -> s.getText()).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(all.contains("程度に応じて"));
        assertTrue(all.contains("断る一辺倒だと"));
        assertTrue(all.contains("上手くやるのも"));
    }
    @Test public void separatelySpokenRowsMustNotRepeatWhenMergedNineteenSecondsLater() {
        SubtitleEventManager m = new SubtitleEventManager(60000);
        m.accept(event("をれらを何ももえず丸呑みしていると", 9330));
        m.accept(event("地獄が待ているため", 12080));
        assertNull(m.accept(event("をれらを何も考えすず丸香みしていると\n地獄が待わているため", 28630)));
    }
    @Test public void recoveredLineIsNotLostInMergedCaption() {
        SubtitleEventManager m = new SubtitleEventManager(60000);
        m.accept(event("最初の字幕を読みます", 1000));
        m.accept(event("これは別の話題です", 1500));
        assertEquals("新しい説明が増えました", m.accept(event("最初の字幕を読みます\nこれは別の話題です\n新しい説明が増えました", 20000)).getText());
    }
    @Test public void changedNumbersAndNegationAreNotDuplicates() {
        SubtitleEventManager m = new SubtitleEventManager(60000);
        m.accept(event("価格は100円になります", 1000));
        assertNotNull(m.accept(event("価格は200円になります", 1500)));
        m.accept(event("この条件で利用できます", 2000));
        assertNotNull(m.accept(event("この条件で利用できません", 2500)));
    }
    private static SubtitleEvent event(String text, long time) {
        return new SubtitleEvent(""+time, text, time-300, time, 60);
    }
}
