package jp.hidemaru.burnedcaptionreader;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Use YouTube's own embedded player, rather than its mobile watch page which
 * can overlay an "open in YouTube app" promotion over burned-in captions.
 * Embedded playback remains controlled by the user and by YouTube.
 */
public final class YouTubeEmbeddedPlayback {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PACKAGE_ID =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+");

    private YouTubeEmbeddedPlayback() {}

    public static String embedUrl(String videoId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("Invalid YouTube video ID");
        }
        // Keep YouTube's native controls; do not start in muted autoplay mode.
        return "https://www.youtube.com/embed/" + videoId
                + "?playsinline=1&autoplay=0&controls=1";
    }

    public static String watchUrl(String videoId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("Invalid YouTube video ID");
        }
        return "https://m.youtube.com/watch?v=" + videoId;
    }

    /**
     * Official YouTube embed requirements use Referer to identify WebView apps.
     * Google documents application IDs represented as https origins.
     */
    public static Map<String, String> identityHeaders(String applicationId) {
        if (applicationId == null || !PACKAGE_ID.matcher(applicationId).matches()) {
            throw new IllegalArgumentException("Invalid Android application ID");
        }
        return Collections.singletonMap("Referer", "https://" + applicationId + "/");
    }
}
