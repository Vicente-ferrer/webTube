package cz.martykan.webtube;

import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

// Controls the <video> on the page through window.__webTube (webtube.js)
// and keeps the last state the page reported.
public class PlayerController {
    public interface Listener {
        void onStateChanged(PlaybackState state);
    }

    private static PlayerController instance;

    private final WebView webView;
    private final List<Listener> listeners = new ArrayList<>();
    private PlaybackState state = PlaybackState.NONE;

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

    void onStateReported(PlaybackState newState) {
        state = newState;
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onStateChanged(state);
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

    private void run(String call) {
        webView.post(() -> webView.evaluateJavascript("window.__webTube && window.__webTube." + call, null));
    }
}
