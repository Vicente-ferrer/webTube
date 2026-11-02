package cz.martykan.webtube;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

// Light or dark app, following the system by default. YouTube pages follow it too,
// since the WebView reports the app theme as prefers-color-scheme.
public class ThemeHelper {
    public static final String PREF_THEME = "theme";

    // In the order of R.array.themes
    private static final int[] NIGHT_MODES = {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES};

    // Before the activity is created, so it starts with the right theme
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(NIGHT_MODES[getTheme(context)]);
    }

    // Index in R.array.themes
    public static int getTheme(Context context) {
        int theme = PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_THEME, 0);
        return theme >= 0 && theme < NIGHT_MODES.length ? theme : 0;
    }

    // Recreates the running activities with the new theme
    public static void setTheme(Context context, int theme) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putInt(PREF_THEME, theme).apply();
        apply(context);
    }

    public static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    public static void setUpWebView(WebView webView) {
        Context context = webView.getContext();
        webView.setBackgroundColor(ContextCompat.getColor(context, R.color.pageBackground));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Darkens the pages without a dark theme of their own (not YouTube's)
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.getSettings(), true);
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // Older WebViews only report dark mode to the pages through force dark
            WebSettingsCompat.setForceDark(webView.getSettings(),
                    isDark(context) ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(webView.getSettings(),
                        WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
            }
        }
    }
}
