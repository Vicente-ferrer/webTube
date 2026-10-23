package cz.martykan.webtube.adblock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// Blocks requests, hides elements and runs scriptlets in the WebView, following the filter lists
public class ContentBlocker implements FilterListManager.Listener {
    public static final String PREF_ENABLED = "contentBlockerEnabled";
    public static final String PREF_WARNING_ACCEPTED = "contentBlockerWarningAccepted";
    private static final String LOG_TAG = "webTubeAdblock";
    // The sites this app opens; scriptlets and element hiding are injected per site
    private static final String[] HOSTS = {"m.youtube.com", "www.youtube.com", "youtube.com"};

    private final WebView webView;
    private final FilterListManager lists;
    private final SharedPreferences sp;
    private final String library;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ScriptHandler> scripts = new ArrayList<>();
    private final Map<String, String> fallbackScripts = new HashMap<>();
    private final AtomicInteger blockedOnPage = new AtomicInteger();

    private volatile FilterEngine engine;
    private volatile String pageHost = "";
    private volatile boolean pageAllowlisted;
    private String lastError;

    public ContentBlocker(Context context, WebView webView) {
        this.webView = webView;
        lists = FilterListManager.get(context);
        sp = PreferenceManager.getDefaultSharedPreferences(context);
        library = readAsset(context, "scriptlets.js");
        lists.addListener(this);
    }

    public boolean isEnabled() {
        return sp.getBoolean(PREF_ENABLED, false);
    }

    public boolean isWarningAccepted() {
        return sp.getBoolean(PREF_WARNING_ACCEPTED, false);
    }

    public void acceptWarning() {
        sp.edit().putBoolean(PREF_WARNING_ACCEPTED, true).apply();
    }

    // Error of the last download, or null
    public String getLastError() {
        return lastError;
    }

    // Loads the rules of the downloaded lists before the first page; onReady runs on the main thread
    public void start(Runnable onReady) {
        if (isEnabled()) {
            load(false, onReady);
        } else {
            onReady.run();
        }
    }

    // Turning it on downloads the lists the first time; onReady runs once the rules are in place
    public void setEnabled(boolean enabled, Runnable onReady) {
        sp.edit().putBoolean(PREF_ENABLED, enabled).apply();
        lists.scheduleUpdates(enabled);
        if (enabled) {
            load(true, onReady);
        } else {
            engine = null;
            removeScripts();
            onReady.run();
        }
    }

    @Override
    public void onListsUpdated(boolean byUser) {
        if (isEnabled()) {
            // A daily update applies from the next page, without interrupting a video
            load(byUser, byUser ? webView::reload : null);
        }
    }

    // download: first fetch the enabled lists that are missing or more than a day old
    private void load(boolean download, Runnable onReady) {
        executor.execute(() -> {
            String error = null;
            if (download) {
                FilterListManager.UpdateResult result = lists.update(false);
                if (!result.errors.isEmpty()) {
                    error = result.errors.get(0);
                }
            }
            long start = System.currentTimeMillis();
            FilterEngine built = lists.buildEngine();
            Log.d(LOG_TAG, "Loaded " + built.networkRuleCount() + " network and " + built.cosmeticRuleCount()
                    + " cosmetic rules in " + (System.currentTimeMillis() - start) + " ms");
            String finalError = error;
            mainHandler.post(() -> {
                lastError = finalError;
                if (isEnabled()) {
                    engine = built;
                    installScripts();
                }
                if (onReady != null) {
                    onReady.run();
                }
            });
        });
    }

    @SuppressLint("RequiresFeature")
    private void installScripts() {
        removeScripts();
        FilterEngine current = engine;
        if (current == null) {
            return;
        }
        boolean documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        for (String host : HOSTS) {
            String script = ScriptBuilder.build(library, current, host);
            if (script == null) {
                continue;
            }
            if (documentStart) {
                scripts.add(WebViewCompat.addDocumentStartJavaScript(webView, script,
                        Collections.singleton("https://" + host)));
            } else {
                fallbackScripts.put(host, script);
            }
        }
    }

    private void removeScripts() {
        for (ScriptHandler script : scripts) {
            script.remove();
        }
        scripts.clear();
        fallbackScripts.clear();
    }

    // WebViews older than version 87 cannot inject at document start: inject as early as
    // possible instead, after the page's first scripts may already have run
    public void onPageStarted(String url) {
        String script = fallbackScripts.get(Domains.hostOf(url));
        if (script != null) {
            webView.evaluateJavascript(script, null);
        }
    }

    // The main frame changed (also for YouTube's in-page navigation)
    public void onPageChanged(String url) {
        int blocked = blockedOnPage.getAndSet(0);
        if (blocked > 0) {
            Log.d(LOG_TAG, "Blocked " + blocked + " requests on the previous page");
        }
        pageHost = Domains.hostOf(url);
        FilterEngine current = engine;
        pageAllowlisted = current != null && current.isPageAllowlisted(url);
    }

    // Called on a WebView network thread; null lets the request through
    public WebResourceResponse intercept(WebResourceRequest request) {
        FilterEngine current = engine;
        if (current == null || pageAllowlisted || request.isForMainFrame()) {
            return null;
        }
        String url = request.getUrl().toString();
        if (!url.startsWith("http")) {
            return null;
        }
        if (!current.shouldBlock(url, pageHost, requestType(request))) {
            return null;
        }
        blockedOnPage.incrementAndGet();
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    // WebView does not tell the resource type, so guess it from the path and the Accept header
    static int requestType(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String extension = path.substring(path.lastIndexOf('.') + 1);
        switch (extension) {
            case "js":
            case "mjs":
                return NetworkRule.TYPE_SCRIPT;
            case "css":
                return NetworkRule.TYPE_STYLESHEET;
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "avif":
            case "svg":
            case "ico":
                return NetworkRule.TYPE_IMAGE;
            case "woff":
            case "woff2":
            case "ttf":
            case "otf":
                return NetworkRule.TYPE_FONT;
            case "mp4":
            case "webm":
            case "m3u8":
            case "mp3":
            case "m4a":
                return NetworkRule.TYPE_MEDIA;
            default:
                break;
        }
        String accept = "";
        for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
            if ("accept".equalsIgnoreCase(header.getKey())) {
                accept = header.getValue().toLowerCase(Locale.ROOT);
            }
        }
        if (accept.startsWith("text/css")) {
            return NetworkRule.TYPE_STYLESHEET;
        } else if (accept.startsWith("image/")) {
            return NetworkRule.TYPE_IMAGE;
        } else if (accept.startsWith("text/html")) {
            return NetworkRule.TYPE_SUBDOCUMENT;
        } else if (accept.startsWith("video/") || accept.startsWith("audio/")) {
            return NetworkRule.TYPE_MEDIA;
        }
        // fetch() and XMLHttpRequest, by far the most common; sendBeacon() looks the same from here,
        // but treating these as "ping" too would let "*$ping,third-party" block the video streams
        return NetworkRule.TYPE_XHR;
    }

    public void release() {
        lists.removeListener(this);
        executor.shutdownNow();
        removeScripts();
    }

    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
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
