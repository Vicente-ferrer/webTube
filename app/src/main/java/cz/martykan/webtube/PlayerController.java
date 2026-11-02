package cz.martykan.webtube;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Controls the <video> on the page through window.__webTube (webtube.js)
// and keeps the last state the page reported.
public class PlayerController {
    public interface Listener {
        void onStateChanged(PlaybackState state);
    }

    public interface SkipListener {
        void onSegmentSkipped(String category);
    }

    // After a video ends, the next one autoplays within a few seconds
    private static final long AUTOPLAY_DELAY_MS = 30000;

    private static PlayerController instance;

    private final WebView webView;
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlaybackState state = PlaybackState.NONE;
    private SkipListener skipListener;

    // Sleep timer: when to pause, in elapsedRealtime (0 when off), or at the end of the video
    private long sleepAt;
    private boolean sleepAtEndOfVideo;
    private final Runnable sleepTimeout = this::onSleepTimeout;
    private final Runnable cancelSleepTimer = this::cancelSleepTimer;

    public PlayerController(WebView webView) {
        this.webView = webView;
        instance = this;
    }

    // Null when MainActivity is not running
    public static PlayerController get() {
        return instance;
    }

    public void release() {
        listeners.clear();
        skipListener = null;
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
    }

    public PlaybackState getState() {
        return state;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setSkipListener(SkipListener skipListener) {
        this.skipListener = skipListener;
    }

    void onStateReported(PlaybackState newState) {
        state = newState;
        checkSleepTimer();
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onStateChanged(state);
        }
    }

    void onSegmentSkipped(String category) {
        if (skipListener != null) {
            skipListener.onSegmentSkipped(category);
        }
    }

    // A page without a video was loaded
    void onPageChanged(String url) {
        if (state.hasVideo() && !url.equals(state.url) && !isVideoUrl(url)) {
            onStateReported(PlaybackState.NONE);
        }
    }

    public static boolean isVideoUrl(String url) {
        return url != null && (url.contains("/watch") || url.contains("/shorts/"));
    }

    public void play() {
        run("play()");
    }

    public void pause() {
        run("pause()");
    }

    public void toggle() {
        run("toggle()");
    }

    public void seekTo(long positionMs) {
        run("seekTo(" + positionMs + ")");
    }

    public void next() {
        run("next()");
    }

    public void previous() {
        run("previous()");
    }

    public void requestReport() {
        run("report()");
    }

    // Shows only the video, filling the page
    public void setPictureInPicture(boolean enabled) {
        run("setPictureInPicture(" + enabled + ")");
    }

    // segments: JSON array of [start, end, category], in seconds
    public void setSkipSegments(String videoId, String segments) {
        run("setSkipSegments(" + JSONObject.quote(videoId) + ", " + segments + ")");
    }

    public void startSleepTimer(long durationMs) {
        cancelSleepTimer();
        sleepAt = SystemClock.elapsedRealtime() + durationMs;
        handler.postDelayed(sleepTimeout, durationMs);
    }

    public void sleepAtEndOfVideo() {
        cancelSleepTimer();
        sleepAtEndOfVideo = true;
    }

    public void cancelSleepTimer() {
        sleepAt = 0;
        sleepAtEndOfVideo = false;
        handler.removeCallbacks(sleepTimeout);
        handler.removeCallbacks(cancelSleepTimer);
    }

    public boolean isSleepingAtEndOfVideo() {
        return sleepAtEndOfVideo;
    }

    // 0 when the sleep timer is off or waits for the end of the video
    public long getSleepTimerRemainingMs() {
        return sleepAt > 0 ? Math.max(0, sleepAt - SystemClock.elapsedRealtime()) : 0;
    }

    private void onSleepTimeout() {
        long remaining = sleepAt - SystemClock.elapsedRealtime();
        if (remaining > 0) {
            // The handler clock stops while the device sleeps
            handler.postDelayed(sleepTimeout, remaining);
        } else {
            cancelSleepTimer();
            pause();
        }
    }

    private void checkSleepTimer() {
        if (sleepAtEndOfVideo && state.ended && !state.ad) {
            // Pause the video that autoplays next, if any
            sleepAtEndOfVideo = false;
            sleepAt = SystemClock.elapsedRealtime();
            handler.postDelayed(cancelSleepTimer, AUTOPLAY_DELAY_MS);
        } else if (sleepAt > 0 && state.playing && SystemClock.elapsedRealtime() >= sleepAt) {
            // Played again while the device was asleep, before the handler ran
            cancelSleepTimer();
            pause();
        }
    }

    private void run(String call) {
        webView.post(() -> webView.evaluateJavascript("window.__webTube && window.__webTube." + call, null));
    }
}
