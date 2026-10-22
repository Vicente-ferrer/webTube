package cz.martykan.webtube;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.preference.PreferenceManager;
import com.google.android.material.snackbar.Snackbar;
import androidx.core.content.ContextCompat;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class WebTubeWebViewClient extends WebViewClient {
    Context context;
    View.OnClickListener clickListener;
    View appWindow;
    View statusBarSpace;
    View bottomBar;
    PlayerBridge playerBridge;
    PlayerController playerController;

    public WebTubeWebViewClient(Context context, View appWindow, View.OnClickListener clickListener, View statusBarSpace, View bottomBar,
                                PlayerBridge playerBridge, PlayerController playerController) {
        this.context = context;
        this.clickListener = clickListener;
        this.appWindow = appWindow;
        this.statusBarSpace = statusBarSpace;
        this.bottomBar = bottomBar;
        this.playerBridge = playerBridge;
        this.playerController = playerController;
    }

    // Open links in a browser window (except for sign-in dialogs and YouTube URLs)
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url != null && url.startsWith("http") && !url.contains("accounts.google.") && !url.contains("youtube.")) {
            view.getContext().startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        }
        return false;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        playerBridge.onPageStarted(url);
    }

    // Called for full page loads and for YouTube's in-page (history.pushState) navigation
    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        playerController.onPageChanged(url);

        // To adapt the statusbar color
        statusBarSpace.setVisibility(View.VISIBLE);
        int colorId = PlayerController.isVideoUrl(url) ? R.color.colorWatch : R.color.colorPrimary;
        statusBarSpace.setBackgroundColor(ContextCompat.getColor(context, colorId));
        bottomBar.setBackgroundColor(ContextCompat.getColor(context, colorId));
    }

    // Deal with error messages
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        if (description.contains("NETWORK_CHANGED")) {
            view.loadUrl(PreferenceManager.getDefaultSharedPreferences(view.getContext()).getString("homepage", "https://m.youtube.com/"));
        } else if (description.contains("NAME_NOT_RESOLVED")) {
            Snackbar.make(appWindow, context.getString(R.string.errorNoInternet), Snackbar.LENGTH_INDEFINITE).setAction(context.getString(R.string.refresh), clickListener).show();
        } else if (description.contains("PROXY_CONNECTION_FAILED")) {
            Snackbar.make(appWindow, context.getString(R.string.errorTor), Snackbar.LENGTH_INDEFINITE).setAction(context.getString(R.string.refresh), clickListener).show();
        } else {
            Snackbar.make(appWindow, context.getString(R.string.error) + " " + description, Snackbar.LENGTH_INDEFINITE).
                    setAction(context.getString(R.string.refresh), clickListener).show();
        }
    }
}
