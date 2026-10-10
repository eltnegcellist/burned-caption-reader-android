package jp.hidemaru.burnedcaptionreader.ocr;

/** Stored choices; existing installations continue using PP recognition refinement. */
public enum CaptionOcrMode {
    ML_KIT("mlkit", "ML Kitのみ",
            "文字の場所と読み取りにML Kitを使います。"),
    HYBRID("hybrid", "現行：PP-OCR文字認識併用",
            "文字の場所はML Kit、選んだ字幕の読み直しはPP-OCRを使います。"),
    PP_DETECTION("pp_detection", "改良版：PP-OCR検出併用（実験）",
            "PP-OCRで見落とした文字の検出と読み直しを追加します。字幕以外の文字を拾ったり、処理が遅くなる場合があります。");

    public final String storedValue;
    public final String label;
    public final String description;

    CaptionOcrMode(String storedValue, String label, String description) {
        this.storedValue = storedValue;
        this.label = label;
        this.description = description;
    }

    public static CaptionOcrMode fromStoredValue(String value) {
        for (CaptionOcrMode mode : values()) {
            if (mode.storedValue.equals(value)) return mode;
        }
        return HYBRID;
    }
}
