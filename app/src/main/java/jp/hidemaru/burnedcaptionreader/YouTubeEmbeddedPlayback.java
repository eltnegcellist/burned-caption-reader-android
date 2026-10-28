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
        // Ask YouTube to start with its normal audio and controls. WebView or
        // YouTube may block audible autoplay; never force muted autoplay.
        return "https://www.youtube.com/embed/" + videoId
                + "?playsinline=1&autoplay=1&controls=1";
    }

    /**
     * Allow only the player surface to be shown as WebView's main document.
     * A navigation to /watch (or another YouTube page) must never restore
     * the retired full mobile watch-page interface.
     */
    public static boolean isEmbeddedPlayerUrl(String targetUrl) {
        if (targetUrl == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(targetUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("www.youtube.com") && !host.equals("youtube.com")) return false;
            String path = uri.getPath();
            if (path == null || !path.startsWith("/embed/")) return false;
            String id = path.substring("/embed/".length());
            return VIDEO_ID.matcher(id).matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * The embedded player's suggestions sometimes navigate to /watch instead
     * of keeping the player embedded. Route YouTube video links back to embed.
     * Embedded-player URLs are deliberately excluded to avoid redirect loops.
     * Non-video YouTube links (terms, accounts, channels) are not rewritten.
     */
    public static String suggestedVideoId(String destinationUrl) {
        if (destinationUrl == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(destinationUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath();
            if (path == null) return null;
            boolean youtubeHost = host.equals("www.youtube.com")
                    || host.equals("m.youtube.com") || host.equals("youtube.com");
            boolean shortHost = host.equals("youtu.be") || host.equals("www.youtu.be");
            if (youtubeHost && !(path.equals("/watch") || path.startsWith("/shorts/")
                    || path.startsWith("/live/"))) return null;
            if (shortHost && (path.length() < 2 || !path.startsWith("/"))) return null;
            if (!youtubeHost && !shortHost) return null;
            return YouTubeShareUrl.videoId(destinationUrl);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
