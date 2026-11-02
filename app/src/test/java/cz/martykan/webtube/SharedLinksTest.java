package cz.martykan.webtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SharedLinksTest {
    @Test
    public void findsTheYouTubeLinkInSharedText() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc",
                SharedLinks.urlFor("Watch this! https://youtu.be/dQw4w9WgXcQ?si=abc"));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                SharedLinks.urlFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ\nShared from the app"));
        assertEquals("https://m.youtube.com/shorts/abcdefghijk",
                SharedLinks.urlFor("https://m.youtube.com/shorts/abcdefghijk"));
    }

    @Test
    public void ignoresOtherSites() {
        assertEquals("https://m.youtube.com/results?search_query=see+https%3A%2F%2Fnotyoutube.com%2Fx",
                SharedLinks.urlFor("see https://notyoutube.com/x"));
    }

    @Test
    public void searchesForPlainText() {
        assertEquals("https://m.youtube.com/results?search_query=lo-fi+beats+%26+chill",
                SharedLinks.urlFor("  lo-fi beats & chill "));
    }

    @Test
    public void ignoresEmptyText() {
        assertNull(SharedLinks.urlFor(null));
        assertNull(SharedLinks.urlFor("   "));
    }
}
