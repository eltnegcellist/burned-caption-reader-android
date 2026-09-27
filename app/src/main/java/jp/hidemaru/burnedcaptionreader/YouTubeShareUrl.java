package jp.hidemaru.burnedcaptionreader;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extract only a YouTube video ID; no URL from shared text is loaded into WebView. */
public final class YouTubeShareUrl {
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\\\"']+");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private YouTubeShareUrl() {}

    public static String videoId(String sharedText) {
        if (sharedText == null) return null;
        Matcher links = LINK.matcher(sharedText);
        while (links.find()) {
            try {
                URI uri = URI.create(links.group());
                String host = uri.getHost();
                if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) continue;
                host = host.toLowerCase(java.util.Locale.ROOT);
                String path = uri.getPath() == null ? "" : uri.getPath();
                String candidate = null;
                if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                    candidate = path.startsWith("/") ? path.substring(1).split("/", 2)[0] : null;
                } else if (host.equals("youtube.com") || host.equals("www.youtube.com")
                        || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
                    if (path.equals("/watch")) {
                        String raw = uri.getRawQuery();
                        if (raw != null) for (String field : raw.split("&")) {
                            int eq = field.indexOf('=');
                            if (eq > 0 && field.substring(0, eq).equals("v")) {
                                candidate = URLDecoder.decode(field.substring(eq + 1), StandardCharsets.UTF_8);
                                break;
                            }
                        }
                    } else if (path.startsWith("/shorts/") || path.startsWith("/live/")
                            || path.startsWith("/embed/")) {
                        String[] parts = path.split("/");
                        if (parts.length >= 3) candidate = parts[2];
                    }
                }
                if (candidate != null && ID.matcher(candidate).matches()) return candidate;
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed links and look for a valid one later in the message.
            }
        }
        return null;
    }
}
