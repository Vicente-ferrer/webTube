package cz.martykan.webtube;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Rational;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

// Shrinks the video to a floating window when the user leaves the app while it plays
public class PictureInPictureHelper {
    public static final String PREF_PIP_ENABLED = "pictureInPictureEnabled";

    private static final String ACTION_PLAY_PAUSE = "cz.martykan.webtube.PIP_PLAY_PAUSE";
    private static final String ACTION_NEXT = "cz.martykan.webtube.PIP_NEXT";
    // Android only accepts aspect ratios between 1:2.39 and 2.39:1
    private static final float MAX_ASPECT_RATIO = 2.39f;

    Activity activity;
    PlayerController playerController;
    SharedPreferences sp;

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PLAY_PAUSE.equals(intent.getAction())) {
                playerController.toggle();
            } else if (ACTION_NEXT.equals(intent.getAction())) {
                playerController.next();
            }
        }
    };

    public PictureInPictureHelper(Activity activity, PlayerController playerController) {
        this.activity = activity;
        this.playerController = playerController;

        sp = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    public static boolean isSupported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    public void enablePictureInPicture() {
        sp.edit().putBoolean(PREF_PIP_ENABLED, true).apply();
        update();
    }

    public void disablePictureInPicture() {
        sp.edit().putBoolean(PREF_PIP_ENABLED, false).apply();
        update();
    }

    public boolean isPictureInPictureEnabled() {
        return sp.getBoolean(PREF_PIP_ENABLED, true);
    }

    public void register() {
        if (isSupported(activity)) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PLAY_PAUSE);
            filter.addAction(ACTION_NEXT);
            ContextCompat.registerReceiver(activity, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    public void unregister() {
        if (isSupported(activity)) {
            activity.unregisterReceiver(actionReceiver);
        }
    }

    // A video is playing, so leaving the app should keep it on screen
    private boolean shouldEnter() {
        PlaybackState state = playerController.getState();
        return isPictureInPictureEnabled() && state.hasVideo() && state.playing;
    }

    // Before Android 12, called from onUserLeaveHint
    public void enterIfPlaying() {
        if (isSupported(activity) && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && shouldEnter() && !activity.isInPictureInPictureMode()) {
            try {
                activity.enterPictureInPictureMode(buildParams());
            } catch (IllegalStateException e) {
                // Picture-in-picture disabled for this app in the system settings
            }
        }
    }

    // Keeps the play/pause button, aspect ratio and (Android 12+) auto-enter in sync with the video
    public void update() {
        if (isSupported(activity)) {
            try {
                activity.setPictureInPictureParams(buildParams());
            } catch (IllegalStateException e) {
                // Picture-in-picture disabled for this app in the system settings
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private PictureInPictureParams buildParams() {
        PlaybackState state = playerController.getState();
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio(state))
                .setActions(buildActions(state));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(shouldEnter())
                    .setSeamlessResizeEnabled(false);
        }
        return builder.build();
    }

    private static Rational aspectRatio(PlaybackState state) {
        if (state.videoWidth <= 0 || state.videoHeight <= 0) {
            return new Rational(16, 9);
        }
        float ratio = (float) state.videoWidth / state.videoHeight;
        if (ratio > MAX_ASPECT_RATIO) {
            return new Rational(239, 100);
        } else if (ratio < 1 / MAX_ASPECT_RATIO) {
            return new Rational(100, 239);
        }
        return new Rational(state.videoWidth, state.videoHeight);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private List<RemoteAction> buildActions(PlaybackState state) {
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(buildAction(state.playing ? R.drawable.pause : R.drawable.play,
                state.playing ? R.string.pause : R.string.play, ACTION_PLAY_PAUSE));
        actions.add(buildAction(R.drawable.skip_next, R.string.next, ACTION_NEXT));
        return actions;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private RemoteAction buildAction(int icon, int title, String action) {
        PendingIntent intent = PendingIntent.getBroadcast(activity, action.hashCode(),
                new Intent(action).setPackage(activity.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String label = activity.getString(title);
        return new RemoteAction(Icon.createWithResource(activity, icon), label, label, intent);
    }
}
