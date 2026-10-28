package jp.hidemaru.burnedcaptionreader;

import org.junit.Test;
import static org.junit.Assert.*;

public final class YouTubeEmbeddedPlaybackTest {
    private static final String ID = "NylG8K2abcD";

    @Test public void officialEmbedStartsPausedAndKeepsStandardControls() {
        assertEquals("https://www.youtube.com/embed/" + ID
                + "?playsinline=1&autoplay=0&controls=1",
                YouTubeEmbeddedPlayback.embedUrl(ID));
        assertEquals("https://m.youtube.com/watch?v=" + ID,
                YouTubeEmbeddedPlayback.watchUrl(ID));
    }

    @Test public void providesDocumentedRefererIdentityForAndroidWebView() {
        assertEquals("https://jp.hidemaru.burnedcaptionreader.audioresearch/",
                YouTubeEmbeddedPlayback.identityHeaders(
                        "jp.hidemaru.burnedcaptionreader.audioresearch").get("Referer"));
    }

    @Test public void validatesUntrustedIdentifiersBeforeBuildingUrlsOrHeaders() {
        for (String invalid : new String[]{"", "../../passwd", "NylG8K2abcD?x=1", "bad"}) {
            try {
                YouTubeEmbeddedPlayback.embedUrl(invalid);
                fail("Invalid video id accepted: " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
        for (String invalid : new String[]{"", "https://evil.test/", "a. b", "app"}) {
            try {
                YouTubeEmbeddedPlayback.identityHeaders(invalid);
                fail("Invalid package ID accepted: " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
    }
}
