package jp.hidemaru.burnedcaptionreader;

import static org.junit.Assert.*;
import org.junit.Test;

public class YouTubeShareUrlTest {
    private static final String ID = "NylG8K2abcD";

    @Test public void handlesCommonMobileShareFormats() {
        assertEquals(ID, YouTubeShareUrl.videoId("動画はこちら https://m.youtube.com/watch?v=" + ID + "&t=3s"));
        assertEquals(ID, YouTubeShareUrl.videoId("https://youtu.be/" + ID + "?si=abc"));
        assertEquals(ID, YouTubeShareUrl.videoId("https://www.youtube.com/shorts/" + ID));
        assertEquals(ID, YouTubeShareUrl.videoId("https://www.youtube.com/live/" + ID));
    }
    @Test public void rejectsLookalikeHostsAndIds() {
        assertNull(YouTubeShareUrl.videoId("https://youtube.com.evil.example/watch?v=" + ID));
        assertNull(YouTubeShareUrl.videoId("http://www.youtube.com/watch?v=" + ID));
        assertNull(YouTubeShareUrl.videoId("https://youtube.com/watch?v=too-short"));
        assertNull(YouTubeShareUrl.videoId("no link"));
    }
    @Test public void findsValidLinkAfterInvalidLink() {
        assertEquals(ID, YouTubeShareUrl.videoId("https://example.com/abc then https://www.youtube.com/watch?v=" + ID));
    }
}
