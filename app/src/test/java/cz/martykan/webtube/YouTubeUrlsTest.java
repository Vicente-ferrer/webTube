package cz.martykan.webtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YouTubeUrlsTest {
    @Test
    public void videoPages() {
        assertTrue(YouTubeUrls.isVideoUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(YouTubeUrls.isVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1"));
        assertTrue(YouTubeUrls.isVideoUrl("https://m.youtube.com/shorts/abcdefghijk"));
        assertTrue(YouTubeUrls.isVideoUrl("https://m.youtube.com/shorts"));
        assertTrue(YouTubeUrls.isVideoUrl("https://m.youtube.com/live/abcdefghijk"));
        assertTrue(YouTubeUrls.isVideoUrl("https://youtu.be/dQw4w9WgXcQ?si=x"));
    }

    @Test
    public void otherPages() {
        assertFalse(YouTubeUrls.isVideoUrl("https://m.youtube.com/"));
        assertFalse(YouTubeUrls.isVideoUrl("https://m.youtube.com/watchlater"));
        assertFalse(YouTubeUrls.isVideoUrl("https://m.youtube.com/@channel/videos"));
        assertFalse(YouTubeUrls.isVideoUrl("https://m.youtube.com/results?search_query=watch"));
        assertFalse(YouTubeUrls.isVideoUrl("https://example.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(YouTubeUrls.isVideoUrl("about:blank"));
        assertFalse(YouTubeUrls.isVideoUrl(null));
    }

    @Test
    public void youTubeHosts() {
        assertTrue(YouTubeUrls.isYouTubeUrl("https://m.youtube.com/feed/subscriptions"));
        assertTrue(YouTubeUrls.isYouTubeUrl("https://youtube.com"));
        assertTrue(YouTubeUrls.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"));
        assertFalse(YouTubeUrls.isYouTubeUrl("https://accounts.google.com/ServiceLogin"));
        assertFalse(YouTubeUrls.isYouTubeUrl("https://notyoutube.com/"));
        assertFalse(YouTubeUrls.isYouTubeUrl("https://youtube.com.evil.example/"));
    }

    @Test
    public void videoIds() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrls.videoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&pp=abc"));
        assertEquals("dQw4w9WgXcQ", YouTubeUrls.videoId("https://m.youtube.com/watch?list=PL1&v=dQw4w9WgXcQ"));
        assertEquals("abcdefghijk", YouTubeUrls.videoId("https://m.youtube.com/shorts/abcdefghijk"));
        assertEquals("dQw4w9WgXcQ", YouTubeUrls.videoId("https://youtu.be/dQw4w9WgXcQ?si=x"));
        assertNull(YouTubeUrls.videoId("https://m.youtube.com/results?v=dQw4w9WgXcQ"));
        assertNull(YouTubeUrls.videoId(null));
    }

    @Test
    public void shortsDoNotMinimize() {
        assertTrue(YouTubeUrls.isMinimizable("https://m.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(YouTubeUrls.isMinimizable("https://m.youtube.com/shorts/abcdefghijk"));
        assertFalse(YouTubeUrls.isMinimizable("https://m.youtube.com/"));
    }
}
