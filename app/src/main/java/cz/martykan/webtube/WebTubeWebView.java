package cz.martykan.webtube;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

public class WebTubeWebView extends WebView {
    public interface BackgroundPolicy {
        boolean keepPlayingInBackground();
    }

    private BackgroundPolicy backgroundPolicy;

    public WebTubeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setBackgroundPolicy(BackgroundPolicy backgroundPolicy) {
        this.backgroundPolicy = backgroundPolicy;
    }

    // Chromium suspends <video> elements when the window is hidden (app in background
    // or screen off), so keep it "visible" while the PlaybackService can play the video
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != View.VISIBLE && backgroundPolicy != null && backgroundPolicy.keepPlayingInBackground()) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        } else {
            super.onWindowVisibilityChanged(visibility);
        }
    }
}
