package cz.martykan.webtube;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.preference.PreferenceManager;
import com.google.android.material.snackbar.Snackbar;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import cz.martykan.webtube.adblock.ContentBlocker;

public class WebTubeWebViewClient extends WebViewClient {
    Context context;
    View.OnClickListener clickListener;
    View appWindow;
    PlayerBridge playerBridge;
    PlayerController playerController;
    ContentBlocker.Page contentBlockerPage;
    MiniPlayerController miniPlayer;

    // playerController: null for the browser WebView
    public WebTubeWebViewClient(Context context, View appWindow, View.OnClickListener clickListener, PlayerBridge playerBridge,
                                PlayerController playerController, ContentBlocker.Page contentBlockerPage, MiniPlayerController miniPlayer) {
        this.context = context;
        this.clickListener = clickListener;
        this.appWindow = appWindow;
        this.playerBridge = playerBridge;
        this.playerController = playerController;
        this.contentBlockerPage = contentBlockerPage;
        this.miniPlayer = miniPlayer;
    }

    // Open links in a browser window (except for sign-in dialogs and YouTube URLs),
    // and videos in the player, other YouTube pages in the browser WebView
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url != null && url.startsWith("http") && !url.contains("accounts.google.") && !url.contains("youtube.")) {
            view.getContext().startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        }
        return miniPlayer.openElsewhere(view, url);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        contentBlockerPage.onPageChanged(url);
        contentBlockerPage.onPageStarted(url);
        playerBridge.onPageStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        miniPlayer.onPageFinished(view);
    }

    // Runs on a network thread
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        WebResourceResponse blocked = contentBlockerPage.intercept(request);
        return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
    }

    // Called for full page loads and for YouTube's in-page (history.pushState) navigation
    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        if (playerController != null) {
            playerController.onPageChanged(url);
        }
        contentBlockerPage.onPageChanged(url);
        miniPlayer.onPageChanged(view, url);
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
