package cz.martykan.webtube;

import android.webkit.WebView;

import java.util.List;

// The WebViews of MainActivity: a player for videos and a browser for every other page (MiniPlayerController)
public interface Navigator {
    // The WebView the user is looking at
    WebView getCurrentWebView();

    // Every WebView, for settings like cookies
    List<WebView> getWebViews();

    // Opens the page in the WebView it belongs in
    void open(String url);
}
