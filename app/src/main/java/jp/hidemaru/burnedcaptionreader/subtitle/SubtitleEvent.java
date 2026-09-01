package jp.hidemaru.burnedcaptionreader.subtitle;

public final class SubtitleEvent {
    private final String id;
    private final String text;
    private final long detectedAt;
    private final long committedAt;
    private final double confidence;

    public SubtitleEvent(String id, String text, long detectedAt, long committedAt, double confidence) {
        this.id = id;
        this.text = text;
        this.detectedAt = detectedAt;
        this.committedAt = committedAt;
        this.confidence = confidence;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public long getDetectedAt() { return detectedAt; }
    public long getCommittedAt() { return committedAt; }
    public double getConfidence() { return confidence; }
}
