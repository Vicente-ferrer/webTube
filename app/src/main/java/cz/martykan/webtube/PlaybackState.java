package cz.martykan.webtube;

import android.os.SystemClock;

import org.json.JSONObject;

// Snapshot of the <video> element on the page, as reported by webtube.js
public class PlaybackState {
    public final boolean playing;
    public final boolean ended;
    public final long positionMs;
    public final long durationMs;
    public final float rate;
    public final int videoWidth;
    public final int videoHeight;
    public final boolean ad;
    public final String videoId;
    public final String url;
    public final String title;
    public final String artist;
    public final String artworkUrl;
    // When positionMs was measured, to extrapolate the current position
    public final long updateTime;

    public static final PlaybackState NONE = new PlaybackState(new JSONObject());

    public PlaybackState(JSONObject json) {
        playing = json.optBoolean("playing");
        ended = json.optBoolean("ended");
        positionMs = json.optLong("position");
        durationMs = json.optLong("duration");
        rate = (float) json.optDouble("rate", 1);
        videoWidth = json.optInt("width");
        videoHeight = json.optInt("height");
        ad = json.optBoolean("ad");
        videoId = json.optString("videoId");
        url = json.optString("url");
        title = json.optString("title");
        artist = json.optString("artist");
        artworkUrl = json.optString("artwork");
        updateTime = SystemClock.elapsedRealtime();
    }

    public boolean hasVideo() {
        return !videoId.isEmpty();
    }

    public String thumbnailUrl() {
        if (hasVideo()) {
            return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        }
        return artworkUrl;
    }
}
