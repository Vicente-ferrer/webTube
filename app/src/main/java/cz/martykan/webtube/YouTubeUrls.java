package cz.martykan.webtube;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Which WebView a YouTube page belongs in (MiniPlayerController): the same test as isVideoPath in webtube.js
public class YouTubeUrls {
    private static final Pattern YOUTUBE_URL = Pattern.compile(
            "^https?://(?:[\\w-]+\\.)*(?:youtube\\.com|youtu\\.be)(?:[/?#:]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_URL = Pattern.compile(
            "^https?://(?:(?:[\\w-]+\\.)*youtube\\.com/(?:watch|shorts|live)(?:[/?#]|$)|youtu\\.be/[\\w-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID = Pattern.compile("(?:[?&]v=|/shorts/|/live/|youtu\\.be/)([\\w-]{11})");

    private YouTubeUrls() {
    }

    public static boolean isYouTubeUrl(String url) {
        return url != null && YOUTUBE_URL.matcher(url).find();
    }

    // A video or a Short, played in the player WebView
    public static boolean isVideoUrl(String url) {
        return url != null && VIDEO_URL.matcher(url).find();
    }

    // A video that can shrink to the mini player (Shorts close instead, like in the YouTube app)
    public static boolean isMinimizable(String url) {
        return isVideoUrl(url) && !url.contains("/shorts");
    }

    // The 11 character ID of the video at the URL, or null
    public static String videoId(String url) {
        if (!isVideoUrl(url)) {
            return null;
        }
        Matcher matcher = VIDEO_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }
}
