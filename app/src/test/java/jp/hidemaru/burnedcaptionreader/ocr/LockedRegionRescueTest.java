package jp.hidemaru.burnedcaptionreader.ocr;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LockedRegionRescueTest {
    private static OcrResult detected(OcrLine... rows) {
        return new OcrResult("candidate", 85, Arrays.asList(rows),
                "v6det_v6rec", -1);
    }

    @Test public void mapsUpperCaptionCropBackToFrameCoordinates() {
        OcrResult empty = detected();
        SubtitleCropPlan plan = SubtitleCropPlan.create(960, 540, .23f, .31f);
        float scale = plan.height / 540f;
        float cropTop = (.255f - plan.top / 540f) / scale;
        float cropBottom = (.30f - plan.top / 540f) / scale;
        OcrResult crop = detected(new OcrLine(0, "上側の字幕を認識します", 91,
                .12f, cropTop, .88f, cropBottom));
        OcrResult result = LockedRegionRescue.merge(empty, crop, plan, 540, .23f, .31f);
        assertEquals(1, result.getLines().size());
        assertEquals("上側の字幕を認識します", result.getText());
        assertEquals(.255f, result.getLines().get(0).getTop(), .002f);
        assertEquals(.30f, result.getLines().get(0).getBottom(), .002f);
    }

    @Test public void ignoresOffLaneSceneTextAndNonJapanese() {
        OcrResult empty = detected();
        SubtitleCropPlan plan = SubtitleCropPlan.create(960, 540, .70f, .80f);
        OcrResult crop = detected(new OcrLine(0, "画面上の固定文字", 95,
                .15f, .01f, .85f, .12f),
                new OcrLine(1, "DEVICE", 95, .18f, .5f, .82f, .62f));
        assertSame(empty, LockedRegionRescue.merge(empty, crop, plan, 540, .70f, .80f));
    }

    @Test public void cannotOverrideExistingRows() {
        OcrResult original = detected(new OcrLine(0, "既存字幕を消さない", 90,
                .15f, .75f, .85f, .81f));
        SubtitleCropPlan plan = SubtitleCropPlan.create(960, 540, .75f, .81f);
        OcrResult crop = detected(new OcrLine(1, "復旧字幕を上書きする", 99,
                .1f, .1f, .9f, .45f));
        assertSame(original, LockedRegionRescue.merge(original, crop, plan, 540, .75f, .81f));
    }

    @Test public void onlyAcceptsTwoLocalizedRows() {
        OcrResult empty = detected();
        SubtitleCropPlan plan = SubtitleCropPlan.create(960, 540, .5f, .65f);
        OcrResult crop = detected(
                new OcrLine(0, "最初の字幕を表示", 90, .1f, .2f, .9f, .3f),
                new OcrLine(1, "次の字幕を表示", 90, .1f, .42f, .9f, .52f),
                new OcrLine(2, "さらに字幕を表示", 90, .1f, .66f, .9f, .76f));
        assertTrue(LockedRegionRescue.merge(empty, crop, plan, 540, .5f, .65f).getLines().size() <= 2);
    }
}
