package cz.martykan.webtube;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Text shared to the app from others (ACTION_SEND), like "Watch this! https://youtu.be/..."
public class SharedLinks {
    private static final Pattern YOUTUBE_URL = Pattern.compile(
            "https?://(?:[\\w-]+\\.)*(?:youtube\\.com|youtu\\.be)(?:/\\S*)?", Pattern.CASE_INSENSITIVE);

    private SharedLinks() {
    }

    // The YouTube link in the text, or else a YouTube search for it; null for empty text
    public static String urlFor(String sharedText) {
        if (sharedText == null || sharedText.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = YOUTUBE_URL.matcher(sharedText);
        if (matcher.find()) {
            return matcher.group();
        }
        try {
            return "https://m.youtube.com/results?search_query=" + URLEncoder.encode(sharedText.trim(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
