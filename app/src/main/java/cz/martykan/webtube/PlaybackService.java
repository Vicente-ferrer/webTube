package cz.martykan.webtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Keeps the video playing with the app in the background or the screen off, and
// exposes it to the notification, lock screen and headset buttons through a MediaSession.
public class PlaybackService extends Service implements PlayerController.Listener {
    private static final String LOG_TAG = "webTube";
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_PLAY_PAUSE = "cz.martykan.webtube.PLAY_PAUSE";
    private static final String ACTION_NEXT = "cz.martykan.webtube.NEXT";
    private static final String ACTION_PREVIOUS = "cz.martykan.webtube.PREVIOUS";
    private static final String ACTION_CLOSE = "cz.martykan.webtube.CLOSE";

    private static boolean running;

    private PlayerController playerController;
    private MediaSessionCompat mediaSession;
    private PlaybackState state = PlaybackState.NONE;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audioManager;
    private AudioFocusRequestCompat audioFocusRequest;
    private boolean hasAudioFocus;
    private boolean resumeOnFocusGain;
    private boolean noisyReceiverRegistered;

    private final ExecutorService artworkLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String artworkVideoId;
    private Bitmap artwork;

    // Headphones unplugged or Bluetooth disconnected
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            playerController.pause();
        }
    };

    public static void start(Context context) {
        if (!running) {
            ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        playerController = PlayerController.get();
        if (playerController != null) {
            state = playerController.getState();
            playerController.addListener(this);
        }

        createNotificationChannel();

        mediaSession = new MediaSessionCompat(this, LOG_TAG);
        mediaSession.setSessionActivity(openAppIntent());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                playerController.play();
            }

            @Override
            public void onPause() {
                playerController.pause();
            }

            @Override
            public void onSkipToNext() {
                playerController.next();
            }

            @Override
            public void onSkipToPrevious() {
                playerController.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                playerController.seekTo(pos);
            }

            @Override
            public void onStop() {
                close();
            }
        });
        mediaSession.setActive(true);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webTube:playback");
        wakeLock.setReferenceCounted(false);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        //noinspection deprecation: still the right lock to keep streaming with the screen off
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "webTube:playback");
        wifiLock.setReferenceCounted(false);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributesCompat.Builder()
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MOVIE)
                        .build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start must promote the service within a few seconds
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);

        if (playerController == null) {
            // MainActivity (and its WebView) is gone
            stopSelf();
            return START_NOT_STICKY;
        }
        onStateChanged(playerController.getState());

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY_PAUSE.equals(action)) {
            playerController.toggle();
        } else if (ACTION_NEXT.equals(action)) {
            playerController.next();
        } else if (ACTION_PREVIOUS.equals(action)) {
            playerController.previous();
        } else if (ACTION_CLOSE.equals(action)) {
            close();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onStateChanged(PlaybackState newState) {
        state = newState;
        if (!state.hasVideo()) {
            // Left the video page
            stopSelf();
            return;
        }

        updateMediaSession();
        if (state.playing) {
            wakeLock.acquire();
            wifiLock.acquire();
            requestAudioFocus();
            registerNoisyReceiver();
        } else {
            wakeLock.release();
            wifiLock.release();
            unregisterNoisyReceiver();
            if (!resumeOnFocusGain) {
                abandonAudioFocus();
            }
        }
        loadArtwork();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void close() {
        if (playerController != null) {
            playerController.pause();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (playerController != null) {
            playerController.removeListener(this);
        }
        wakeLock.release();
        wifiLock.release();
        unregisterNoisyReceiver();
        abandonAudioFocus();
        mediaSession.setActive(false);
        mediaSession.release();
        artworkLoader.shutdownNow();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateMediaSession() {
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP;
        if (!state.ad) {
            actions |= PlaybackStateCompat.ACTION_SEEK_TO;
        }
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state.playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        state.positionMs, state.playing ? state.rate : 0, state.updateTime)
                .build());

        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.ad ? -1 : state.durationMs)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .build());
    }

    private String subtitle() {
        return state.ad ? getString(R.string.advertisement) : state.artist;
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_headphones_white_24dp)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setContentTitle(state.title.isEmpty() ? getString(R.string.app_name) : state.title)
                .setContentText(subtitle())
                .setLargeIcon(artwork)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(actionIntent(ACTION_CLOSE))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(state.playing)
                .addAction(R.drawable.skip_previous, getString(R.string.previous), actionIntent(ACTION_PREVIOUS))
                .addAction(state.playing ? R.drawable.pause : R.drawable.play,
                        getString(state.playing ? R.string.pause : R.string.play), actionIntent(ACTION_PLAY_PAUSE))
                .addAction(R.drawable.skip_next, getString(R.string.next), actionIntent(ACTION_NEXT))
                .addAction(R.drawable.close, getString(R.string.close), actionIntent(ACTION_CLOSE))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private PendingIntent openAppIntent() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent actionIntent(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.playbackChannel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void loadArtwork() {
        final String videoId = state.videoId;
        final String url = state.thumbnailUrl();
        if (videoId.equals(artworkVideoId) || url.isEmpty()) {
            return;
        }
        artworkVideoId = videoId;
        artwork = null;

        // Downloading outside the WebView would bypass the Tor proxy
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (sp.getBoolean(TorHelper.PREF_TOR_ENABLED, false)) {
            return;
        }

        artworkLoader.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                try (InputStream in = connection.getInputStream()) {
                    final Bitmap bitmap = BitmapFactory.decodeStream(in);
                    mainHandler.post(() -> {
                        if (running && videoId.equals(artworkVideoId)) {
                            artwork = bitmap;
                            onStateChanged(state);
                        }
                    });
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not load artwork for " + videoId, e);
            }
        });
    }

    private void requestAudioFocus() {
        if (!hasAudioFocus) {
            hasAudioFocus = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        resumeOnFocusGain = false;
    }

    private void abandonAudioFocus() {
        if (hasAudioFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest);
            hasAudioFocus = false;
        }
        resumeOnFocusGain = false;
    }

    // Ducking (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) is done by the system
    private void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    playerController.play();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (state.playing) {
                    resumeOnFocusGain = true;
                    playerController.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                hasAudioFocus = false;
                resumeOnFocusGain = false;
                playerController.pause();
                break;
        }
    }

    private void registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(this, noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            noisyReceiverRegistered = true;
        }
    }

    private void unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(noisyReceiver);
            noisyReceiverRegistered = false;
        }
    }
}
