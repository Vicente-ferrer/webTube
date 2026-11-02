package cz.martykan.webtube;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.WebBackForwardList;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.List;

// Videos play in their own WebView, like in the YouTube app's player: over the browser WebView,
// which shows every other page, and shrunk into a mini player above the bottom bar when the
// user goes to another page, so the video keeps playing. Both WebViews share the cookies.
public class MiniPlayerController implements Navigator, PlayerBridge.LinkListener, PlayerController.Listener {
    private enum State {
        // Only the browser
        CLOSED,
        // The player covers the browser
        EXPANDED,
        // The browser, with the player shrunk at the bottom
        MINI
    }

    private static final String HOME = "https://m.youtube.com/";
    private static final String BLANK = "about:blank";
    private static final long PROGRESS_INTERVAL_MS = 500;

    private final MainActivity activity;
    private final WebTubeWebView player;
    private final WebTubeWebView browser;
    private final PlayerController playerController;
    private final SharedPreferences sp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View miniPlayer;
    private final View miniPlayerProgress;
    private final TextView miniPlayerTitle;
    private final TextView miniPlayerChannel;
    private final ImageButton miniPlayerPlayPause;
    private final View statusBarSpace;
    private final View bottomBar;
    private final int miniPlayerHeight;
    private final int videoWidth;
    private final int videoHeight;

    private State state = State.CLOSED;
    private boolean pictureInPicture;
    // False until the browser is asked to load something
    private boolean browserHasPage;
    private final Runnable progressUpdater = this::updateProgress;

    public MiniPlayerController(MainActivity activity, WebTubeWebView player, WebTubeWebView browser, PlayerController playerController) {
        this.activity = activity;
        this.player = player;
        this.browser = browser;
        this.playerController = playerController;
        sp = PreferenceManager.getDefaultSharedPreferences(activity);

        miniPlayer = activity.findViewById(R.id.miniPlayer);
        miniPlayerProgress = activity.findViewById(R.id.miniPlayerProgress);
        miniPlayerTitle = activity.findViewById(R.id.miniPlayerTitle);
        miniPlayerChannel = activity.findViewById(R.id.miniPlayerChannel);
        miniPlayerPlayPause = activity.findViewById(R.id.miniPlayerPlayPause);
        statusBarSpace = activity.findViewById(R.id.statusBarSpace);
        bottomBar = activity.findViewById(R.id.bottomBar);
        miniPlayerHeight = activity.getResources().getDimensionPixelSize(R.dimen.mini_player_height);
        videoWidth = activity.getResources().getDimensionPixelSize(R.dimen.mini_player_video_width);
        videoHeight = activity.getResources().getDimensionPixelSize(R.dimen.mini_player_video_height);

        miniPlayerProgress.setPivotX(0);
        miniPlayerPlayPause.setOnClickListener(v -> playerController.toggle());
        activity.findViewById(R.id.miniPlayerClose).setOnClickListener(v -> close());
        miniPlayer.setOnClickListener(v -> setState(State.EXPANDED));
        miniPlayer.setOnTouchListener(new SwipeToClose());
        playerController.addListener(this);
    }

    @Override
    public WebView getCurrentWebView() {
        return state == State.EXPANDED ? player : browser;
    }

    @Override
    public List<WebView> getWebViews() {
        return Arrays.asList(player, browser);
    }

    // Videos in the player, every other page in the browser
    @Override
    public void open(String url) {
        if (YouTubeUrls.isVideoUrl(url)) {
            openVideo(url);
        } else {
            openPage(url);
        }
    }

    // A link tapped on the page, which webtube.js sends here when it belongs in the other WebView
    @Override
    public void onOpenLink(String url) {
        open(url);
    }

    private void openVideo(String url) {
        String videoId = YouTubeUrls.videoId(url);
        boolean alreadyOpen = state != State.CLOSED && videoId != null && videoId.equals(YouTubeUrls.videoId(player.getUrl()));
        if (!alreadyOpen) {
            player.loadUrl(url);
        }
        setState(State.EXPANDED);
    }

    private void openPage(String url) {
        loadInBrowser(url);
        if (state == State.EXPANDED) {
            shrinkPlayer();
        }
    }

    private void loadInBrowser(String url) {
        browserHasPage = true;
        browser.loadUrl(url);
    }

    // Videos keep playing in the mini player; Shorts close, like in the YouTube app
    private void shrinkPlayer() {
        if (YouTubeUrls.isMinimizable(player.getUrl())) {
            setState(State.MINI);
        } else {
            closePlayer();
        }
    }

    // Back from the player to the page under it
    private void leavePlayer() {
        shrinkPlayer();
        showBrowserPage();
    }

    // The mini player's close button, or swiped down
    public void close() {
        closePlayer();
        showBrowserPage();
    }

    private void closePlayer() {
        playerController.pause();
        PlaybackService.stop(activity);
        player.loadUrl(BLANK);
        // No video any more for the notification and picture-in-picture
        playerController.onPageChanged(BLANK);
        setState(State.CLOSED);
    }

    // The browser may be empty (the app opened on a video) or on a video page YouTube navigated
    // to by itself (which opened the video in the player): back to the last other page
    private void showBrowserPage() {
        if (browserHasPage && !YouTubeUrls.isVideoUrl(browser.getUrl())) {
            return;
        }
        int steps = stepsBackToPage();
        if (steps > 0) {
            browser.goBackOrForward(-steps);
        } else {
            loadInBrowser(homepage());
        }
    }

    // How far back the browser history has a page that is not a video, or 0
    private int stepsBackToPage() {
        WebBackForwardList history = browser.copyBackForwardList();
        int current = history.getCurrentIndex();
        for (int i = current - 1; i >= 0; i--) {
            String url = history.getItemAtIndex(i).getUrl();
            if (url != null && url.startsWith("http") && !YouTubeUrls.isVideoUrl(url)) {
                return current - i;
            }
        }
        return 0;
    }

    private String homepage() {
        String homepage = sp.getString("homepage", HOME);
        return YouTubeUrls.isVideoUrl(homepage) ? HOME : homepage;
    }

    // False when the activity should handle Back (leave the app)
    public boolean onBackPressed() {
        if (state == State.EXPANDED) {
            leavePlayer();
            return true;
        }
        int steps = stepsBackToPage();
        if (steps > 0) {
            browser.goBackOrForward(-steps);
            return true;
        }
        return false;
    }

    // shouldOverrideUrlLoading: a link that opens a whole new page, in the WebView it belongs in
    public boolean openElsewhere(WebView view, String url) {
        if (!YouTubeUrls.isYouTubeUrl(url)) {
            return false;
        }
        boolean video = YouTubeUrls.isVideoUrl(url);
        if (view == player && !video) {
            openPage(url);
            return true;
        } else if (view == browser && video) {
            openVideo(url);
            return true;
        }
        return false;
    }

    // doUpdateVisitedHistory, also for YouTube's in-page navigation, which webtube.js and
    // openElsewhere cannot always catch before it happens
    public void onPageChanged(WebView view, String url) {
        if (view == player) {
            if (BLANK.equals(url)) {
                player.clearHistory();
            } else if (state != State.CLOSED && url.startsWith("http") && !YouTubeUrls.isVideoUrl(url)) {
                // Left the video, like with YouTube's search: show that page in the browser
                loadInBrowser(url);
                closePlayer();
            }
        } else if (url.startsWith("http")) {
            if (YouTubeUrls.isVideoUrl(url)) {
                pauseBrowser();
                openVideo(url);
            } else {
                browserHasPage = true;
            }
        }
        updateBars();
    }

    // A new document does not have the classes of the previous one
    public void onPageFinished(WebView view) {
        if (view == player) {
            playerController.setMini(isMini());
            playerController.setPictureInPicture(pictureInPicture);
        } else if (browser.getVisibility() != View.VISIBLE) {
            pauseBrowser();
        }
    }

    // The picture-in-picture window shows the player full size, even from the mini player
    public void setPictureInPicture(boolean enabled) {
        pictureInPicture = enabled;
        applyLayout();
    }

    @Override
    public void onStateChanged(PlaybackState playback) {
        updateMiniPlayer();
    }

    public void saveState(Bundle outState) {
        Bundle playerState = new Bundle();
        Bundle browserState = new Bundle();
        player.saveState(playerState);
        browser.saveState(browserState);
        outState.putBundle("player", playerState);
        outState.putBundle("browser", browserState);
        outState.putString("miniPlayerState", state.name());
    }

    // False when there was nothing to restore
    public boolean restoreState(Bundle savedState) {
        Bundle playerState = savedState.getBundle("player");
        Bundle browserState = savedState.getBundle("browser");
        if (browserState != null && browser.restoreState(browserState) != null) {
            browserHasPage = true;
        }
        State saved = State.valueOf(savedState.getString("miniPlayerState", State.CLOSED.name()));
        if (saved != State.CLOSED && playerState != null && player.restoreState(playerState) != null) {
            setState(saved);
            if (saved == State.MINI) {
                showBrowserPage();
            }
        }
        return browserHasPage || state != State.CLOSED;
    }

    private boolean isMini() {
        return state == State.MINI && !pictureInPicture;
    }

    private void setState(State newState) {
        state = newState;
        applyLayout();
        updateBars();
        updateMiniPlayer();
    }

    private void applyLayout() {
        boolean playerVisible = state != State.CLOSED;
        boolean playerFull = state == State.EXPANDED || (playerVisible && pictureInPicture);
        boolean mini = isMini();

        player.setVisibility(playerVisible ? View.VISIBLE : View.GONE);
        player.setTranslationY(0);
        setSize(player, mini ? videoWidth : ViewGroup.LayoutParams.MATCH_PARENT,
                mini ? videoHeight : ViewGroup.LayoutParams.MATCH_PARENT, 0);
        miniPlayer.setVisibility(mini ? View.VISIBLE : View.GONE);
        miniPlayer.setTranslationY(0);
        miniPlayer.setAlpha(1);

        // Under the full player the browser is hidden, and its feed previews paused
        browser.setVisibility(playerFull ? View.INVISIBLE : View.VISIBLE);
        if (playerFull) {
            pauseBrowser();
        }
        setSize(browser, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, mini ? miniPlayerHeight : 0);
        playerController.setMini(mini);
    }

    private static void setSize(View view, int width, int height, int bottomMargin) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params.width != width || params.height != height || params.bottomMargin != bottomMargin) {
            params.width = width;
            params.height = height;
            params.bottomMargin = bottomMargin;
            params.gravity = Gravity.BOTTOM | Gravity.START;
            view.setLayoutParams(params);
        }
    }

    private void pauseBrowser() {
        browser.evaluateJavascript("window.__webTube && window.__webTube.pauseAll()", null);
    }

    // The status bar and the bottom bar take the color of the page the user is looking at
    private void updateBars() {
        int colorId = YouTubeUrls.isVideoUrl(getCurrentWebView().getUrl()) ? R.color.colorWatch : R.color.colorPrimary;
        int color = ContextCompat.getColor(activity, colorId);
        statusBarSpace.setBackgroundColor(color);
        bottomBar.setBackgroundColor(color);
    }

    private void updateMiniPlayer() {
        if (state != State.MINI) {
            handler.removeCallbacks(progressUpdater);
            return;
        }
        PlaybackState playback = playerController.getState();
        miniPlayerTitle.setText(playback.title);
        miniPlayerChannel.setText(playback.ad ? activity.getString(R.string.advertisement) : playback.artist);
        miniPlayerPlayPause.setImageResource(playback.playing ? R.drawable.pause : R.drawable.play);
        miniPlayerPlayPause.setContentDescription(activity.getString(playback.playing ? R.string.pause : R.string.play));
        updateProgress();
    }

    private void updateProgress() {
        handler.removeCallbacks(progressUpdater);
        PlaybackState playback = playerController.getState();
        long position = playback.positionMs;
        if (playback.playing) {
            position += (long) ((SystemClock.elapsedRealtime() - playback.updateTime) * playback.rate);
        }
        miniPlayerProgress.setScaleX(playback.durationMs > 0 ? Math.min(1f, (float) position / playback.durationMs) : 0f);
        if (state == State.MINI && playback.playing) {
            handler.postDelayed(progressUpdater, PROGRESS_INTERVAL_MS);
        }
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
    }

    // Tapping the mini player expands it, and dragging it down closes it
    private class SwipeToClose implements View.OnTouchListener {
        private final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        private float downY;
        private boolean dragging;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            float offset = Math.max(0, event.getRawY() - downY);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = event.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && offset > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        miniPlayer.setTranslationY(offset);
                        player.setTranslationY(offset);
                        miniPlayer.setAlpha(1 - offset / miniPlayerHeight);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        view.performClick();
                    } else if (offset > miniPlayerHeight / 2f) {
                        close();
                    } else {
                        applyLayout();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    applyLayout();
                    return true;
                default:
                    return false;
            }
        }
    }
}
