package jp.hidemaru.burnedcaptionreader;

import org.junit.Test;
import static org.junit.Assert.*;

public final class YouTubeEmbeddedPlaybackTest {
    private static final String ID = "NylG8K2abcD";

    @Test public void officialEmbedRequestsAudibleAutoplayAndKeepsStandardControls() {
        assertEquals("https://www.youtube.com/embed/" + ID
                + "?playsinline=1&autoplay=1&controls=1",
                YouTubeEmbeddedPlayback.embedUrl(ID));
    }

    @Test public void embeddedPlaybackAlwaysUsesUserVisibleControls() {
        String url = YouTubeEmbeddedPlayback.embedUrl(ID);
        assertTrue(url.contains("autoplay=1"));
        assertTrue(url.contains("controls=1"));
        assertFalse(url.contains("mute=1"));
        assertFalse(url.contains("autoplay=1&mute=1"));
    }

    @Test public void providesDocumentedRefererIdentityForAndroidWebView() {
        assertEquals("https://jp.hidemaru.burnedcaptionreader.audioresearch/",
                YouTubeEmbeddedPlayback.identityHeaders(
                        "jp.hidemaru.burnedcaptionreader.audioresearch").get("Referer"));
    }

    @Test public void rejectsRetiredWatchAndOtherPagesAsMainWebViewDocument() {
        assertTrue(YouTubeEmbeddedPlayback.isEmbeddedPlayerUrl(
                YouTubeEmbeddedPlayback.embedUrl(ID)));
        assertTrue(YouTubeEmbeddedPlayback.isEmbeddedPlayerUrl(
                "https://youtube.com/embed/" + ID + "?autoplay=1"));
        for (String forbidden : new String[] {
                "https://www.youtube.com/watch?v=" + ID,
                "https://m.youtube.com/watch?v=" + ID,
                "https://www.youtube.com/shorts/" + ID,
                "https://www.youtube.com/channel/" + ID,
                "https://www.youtube.com/embed/BAD",
                "http://www.youtube.com/embed/" + ID,
                "https://youtube.com.evil.example/embed/" + ID,
                "about:blank"
        }) {
            assertFalse(forbidden, YouTubeEmbeddedPlayback.isEmbeddedPlayerUrl(forbidden));
        }
    }

    @Test public void suggestedVideoLinksAreRoutedBackToEmbed() {
        assertEquals(ID, YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://www.youtube.com/watch?v=" + ID + "&t=10"));
        assertEquals(ID, YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://m.youtube.com/watch?v=" + ID));
        assertEquals(ID, YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://youtu.be/" + ID + "?feature=share"));
        assertEquals(ID, YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://www.youtube.com/shorts/" + ID));
        assertEquals(ID, YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://www.youtube.com/live/" + ID));
    }

    @Test public void cannotRewritePlayerItselfOrUnrelatedYouTubeNavigation() {
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(
                YouTubeEmbeddedPlayback.embedUrl(ID)));
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://www.youtube.com/channel/" + ID));
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://www.youtube.com/account"));
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://youtube.com.evil.example/watch?v=" + ID));
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(
                "http://www.youtube.com/watch?v=" + ID));
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(
                "https://www.youtube.com/watch?v=BAD"));
        assertNull(YouTubeEmbeddedPlayback.suggestedVideoId(null));
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
