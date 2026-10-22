package cz.martykan.webtube;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

// The "Play in background" preference; the playback itself is done by PlaybackService
public class BackgroundPlayHelper {
    public static final String PREF_BACKGROUND_PLAY_ENABLED = "backgroundPlayEnabled";

    Context context;
    SharedPreferences sp;

    public BackgroundPlayHelper(Context context) {
        this.context = context;

        sp = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void enableBackgroundPlay() {
        SharedPreferences.Editor spedit = sp.edit();
        spedit.putBoolean(PREF_BACKGROUND_PLAY_ENABLED, true);
        spedit.apply();
    }

    public void disableBackgroundPlay() {
        SharedPreferences.Editor spedit = sp.edit();
        spedit.putBoolean(PREF_BACKGROUND_PLAY_ENABLED, false);
        spedit.apply();
        PlaybackService.stop(context);
    }

    public boolean isBackgroundPlayEnabled() {
        return sp.getBoolean(PREF_BACKGROUND_PLAY_ENABLED, true);
    }
}
