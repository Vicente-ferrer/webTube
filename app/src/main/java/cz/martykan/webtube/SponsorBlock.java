package cz.martykan.webtube;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Skips the sponsored parts of videos, as marked by the SponsorBlock community (sponsor.ajay.app).
// webtube.js does the skipping; this class finds the segments of each video.
public class SponsorBlock implements PlayerController.Listener, PlayerController.SkipListener {
    public static final String PREF_ENABLED = "sponsorBlockEnabled";
    private static final String LOG_TAG = "webTube";
    // Takes a prefix of the SHA-256 of the video ID, so the server does not learn which video is watched
    private static final String API = "https://sponsor.ajay.app/api/skipSegments/";
    private static final String CATEGORIES = "[\"sponsor\",\"selfpromo\",\"interaction\"]";
    private static final String NO_SEGMENTS = "[]";
    private static final int CACHE_SIZE = 20;

    private final AppCompatActivity activity;
    private final PlayerController playerController;
    private final SharedPreferences sp;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> loading = new HashSet<>();
    // Video ID to its segments, as passed to webtube.js
    private final Map<String, String> cache = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public SponsorBlock(AppCompatActivity activity, PlayerController playerController) {
        this.activity = activity;
        this.playerController = playerController;
        sp = PreferenceManager.getDefaultSharedPreferences(activity);
        playerController.addListener(this);
        playerController.setSkipListener(this);
    }

    public boolean isEnabled() {
        return sp.getBoolean(PREF_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            onStateChanged(playerController.getState());
        } else {
            playerController.setSkipSegments("", NO_SEGMENTS);
        }
    }

    // Also called when the page reloads and loses the segments it had
    @Override
    public void onStateChanged(PlaybackState state) {
        final String videoId = state.videoId;
        if (!isEnabled() || !state.hasVideo() || videoId.equals(state.skipSegmentsVideoId)) {
            return;
        }
        String segments = cache.get(videoId);
        if (segments != null) {
            playerController.setSkipSegments(videoId, segments);
            return;
        }
        // Downloading outside the WebView would bypass the Tor proxy
        if (sp.getBoolean(TorHelper.PREF_TOR_ENABLED, false) || !loading.add(videoId)) {
            return;
        }
        executor.execute(() -> {
            final String loaded = load(videoId);
            mainHandler.post(() -> {
                loading.remove(videoId);
                cache.put(videoId, loaded);
                if (isEnabled() && videoId.equals(playerController.getState().videoId)) {
                    playerController.setSkipSegments(videoId, loaded);
                }
            });
        });
    }

    @Override
    public void onSegmentSkipped(String category) {
        // Not over the video in the picture-in-picture window, nor from the background
        boolean inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode();
        if (inPictureInPicture || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            return;
        }
        int message;
        switch (category) {
            case "sponsor":
                message = R.string.sponsorSkipped;
                break;
            case "selfpromo":
                message = R.string.selfPromotionSkipped;
                break;
            case "interaction":
                message = R.string.interactionReminderSkipped;
                break;
            default:
                message = R.string.segmentSkipped;
                break;
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    public void release() {
        executor.shutdownNow();
    }

    // Blocking; no segments when the video has none or they could not be loaded
    private static String load(String videoId) {
        try {
            URL url = new URL(API + hashPrefix(videoId)
                    + "?categories=" + URLEncoder.encode(CATEGORIES, "UTF-8")
                    + "&actionTypes=" + URLEncoder.encode("[\"skip\"]", "UTF-8"));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    return NO_SEGMENTS;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status);
                }
                try (InputStream in = connection.getInputStream()) {
                    return parse(videoId, readStream(in));
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException | JSONException e) {
            Log.w(LOG_TAG, "Could not load SponsorBlock segments for " + videoId, e);
            return NO_SEGMENTS;
        }
    }

    // The response lists every video whose hash has the same prefix
    static String parse(String videoId, String response) throws JSONException {
        JSONArray videos = new JSONArray(response);
        JSONArray result = new JSONArray();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.getJSONObject(i);
            if (!videoId.equals(video.optString("videoID"))) {
                continue;
            }
            JSONArray segments = video.optJSONArray("segments");
            for (int j = 0; segments != null && j < segments.length(); j++) {
                JSONObject segment = segments.getJSONObject(j);
                JSONArray times = segment.optJSONArray("segment");
                if (times == null || times.length() < 2 || !"skip".equals(segment.optString("actionType", "skip"))) {
                    continue;
                }
                double start = times.getDouble(0);
                double end = times.getDouble(1);
                if (end > start) {
                    result.put(new JSONArray().put(start).put(end).put(segment.optString("category")));
                }
            }
        }
        return result.toString();
    }

    static String hashPrefix(String videoId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(videoId.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "%02x%02x", hash[0], hash[1]);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
