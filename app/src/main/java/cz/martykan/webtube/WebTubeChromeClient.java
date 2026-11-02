package cz.martykan.webtube;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

public class WebTubeChromeClient extends WebChromeClient {

    View mCustomView;
    CustomViewCallback customViewCallback;
    int orientationBeforeFullscreen;
    Activity activity;
    ProgressBar progress;
    WebView webView;
    FrameLayout customViewContainer;
    DrawerLayout drawerLayout;
    PlayerController playerController;

    public WebTubeChromeClient(Activity activity, WebView webView, ProgressBar progress, FrameLayout customViewContainer,
                               DrawerLayout drawerLayout, PlayerController playerController) {
        this.activity = activity;
        this.webView = webView;
        this.progress = progress;
        this.customViewContainer = customViewContainer;
        this.drawerLayout = drawerLayout;
        this.playerController = playerController;
    }

    // Fullscreen playback
    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (mCustomView != null) {
            callback.onCustomViewHidden();
            return;
        }
        mCustomView = view;
        customViewCallback = callback;
        webView.loadUrl("javascript:(function() { document.body.style.overflowX = 'hidden'; })();");
        webView.loadUrl("javascript:(function() { window.scrollTo(0, 0); })();");
        drawerLayout.setVisibility(View.GONE);
        customViewContainer.setVisibility(View.VISIBLE);
        customViewContainer.addView(view);

        // Hide the status and navigation bars until swiped in
        WindowInsetsControllerCompat insetsController = insetsController();
        insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        insetsController.hide(WindowInsetsCompat.Type.systemBars());

        // Turn the screen sideways for landscape videos, like the YouTube app
        orientationBeforeFullscreen = activity.getRequestedOrientation();
        PlaybackState state = playerController.getState();
        if (state.videoHeight <= state.videoWidth) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    @Override
    public void onHideCustomView() {
        super.onHideCustomView();
        if (mCustomView == null)
            return;

        webView.loadUrl("javascript:(function() { window.scrollTo(0, 0); })();");
        webView.loadUrl("javascript:(function() { document.body.style.overflowX = 'scroll'; })();");
        drawerLayout.setVisibility(View.VISIBLE);
        customViewContainer.setVisibility(View.GONE);

        mCustomView.setVisibility(View.GONE);
        customViewContainer.removeView(mCustomView);
        mCustomView = null;
        customViewCallback = null;

        // Show the status bar.
        insetsController().show(WindowInsetsCompat.Type.systemBars());
        activity.setRequestedOrientation(orientationBeforeFullscreen);
    }

    // Leaves fullscreen as if its button was tapped; false when not in fullscreen
    public boolean exitFullscreen() {
        if (mCustomView == null) {
            return false;
        }
        customViewCallback.onCustomViewHidden();
        return true;
    }

    private WindowInsetsControllerCompat insetsController() {
        return WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    }

    // Progressbar
    public void onProgressChanged(WebView view, int percentage) {
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(percentage);

        // For more advanced loading status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            progress.setIndeterminate(percentage == 100);
            view.evaluateJavascript("(function() { return document.readyState == \"complete\"; })();",
                    value -> {
                        if (value.equals("true")) {
                            progress.setVisibility(View.INVISIBLE);
                        } else {
                            onProgressChanged(webView, 100);
                        }
                    });
        } else {
            if (percentage == 100) {
                progress.setVisibility(View.GONE);
            }
        }
    }
}
