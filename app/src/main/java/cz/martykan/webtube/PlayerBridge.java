package cz.martykan.webtube;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// Injects webtube.js into YouTube pages and forwards its messages to the PlayerController
public class PlayerBridge {
    private static final String LOG_TAG = "webTube";
    private static final String BRIDGE_NAME = "WebTubeBridge";
    private static final Set<String> ALLOWED_ORIGINS = new HashSet<>(Arrays.asList(
            "https://m.youtube.com", "https://www.youtube.com", "https://youtube.com"));

    private final WebView webView;
    private final PlayerController playerController;
    private final String script;
    private final boolean documentStartSupported;

    public PlayerBridge(Context context, WebView webView, PlayerController playerController) {
        this.webView = webView;
        this.playerController = playerController;
        this.script = readAsset(context, "webtube.js");
        this.documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    @SuppressLint({"RequiresFeature", "AddJavascriptInterface"})
    public void install() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, ALLOWED_ORIGINS,
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (isMainFrame) {
                            onMessage(message.getData());
                        }
                    });
        } else {
            // Same postMessage(String) API for webtube.js, but visible to every origin
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void postMessage(String message) {
                    webView.post(() -> onMessage(message));
                }
            }, BRIDGE_NAME);
        }

        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, ALLOWED_ORIGINS);
        }
    }

    // Fallback for WebViews without DOCUMENT_START_SCRIPT, called from onPageStarted
    public void onPageStarted(String url) {
        if (!documentStartSupported && url != null && url.contains("youtube.com")) {
            webView.evaluateJavascript(script, null);
        }
    }

    private void onMessage(String data) {
        try {
            JSONObject json = new JSONObject(data);
            if ("state".equals(json.optString("type"))) {
                playerController.onStateReported(new PlaybackState(json));
            }
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Bad message from page: " + data);
        }
    }

    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
    }
}
