package cz.martykan.webtube;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import com.google.android.material.navigation.NavigationView;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Lifecycle;

import cz.martykan.webtube.adblock.ContentBlocker;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.ActionMenuView;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "webTube";

    private WebTubeWebView webView;
    String time;
    private NavigationView navigationView;
    private SharedPreferences sp;
    // For the snackbar with error message
    View.OnClickListener clickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            webView.loadUrl(sp.getString("homepage", "https://m.youtube.com/"));
        }
    };
    private Context mApplicationContext;

    TorHelper torHelper;
    BackgroundPlayHelper backgroundPlayHelper;
    BookmarkManager bookmarkManager;
    MenuHelper menuHelper;
    PlayerController playerController;
    PictureInPictureHelper pictureInPictureHelper;
    ContentBlocker contentBlocker;
    SponsorBlock sponsorBlock;
    WebTubeChromeClient chromeClient;

    // Light or dark, as the views and the page were created
    private int nightMode;
    private boolean recreating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mApplicationContext = getApplicationContext();
        // Set HW acceleration flags
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // The status bar is drawn over statusBarSpace and the navigation bar over the bottom bar,
        // both dark or red, so their icons are always light
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        View appWindow = findViewById(R.id.appWindow);
        ProgressBar progress = findViewById(R.id.progress);
        FrameLayout customViewContainer = findViewById(R.id.customViewContainer);

        sp = PreferenceManager.getDefaultSharedPreferences(this);
        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.bookmarks_panel);
        setUpWindowInsets(appWindow);

        // Bridge between the page's <video> and the app
        playerController = new PlayerController(webView);
        PlayerBridge playerBridge = new PlayerBridge(this, webView, playerController);
        playerBridge.install();

        // Set up WebChromeClient
        chromeClient = new WebTubeChromeClient(this, webView, progress, customViewContainer, drawerLayout, playerController);
        webView.setWebChromeClient(chromeClient);

        // Filter lists: blocked requests, hidden elements and scriptlets
        contentBlocker = new ContentBlocker(this, webView);

        // Sponsored segments skipped
        sponsorBlock = new SponsorBlock(this, playerController);

        // Background playback, notification and lock screen controls
        playerController.addListener(state -> {
            if (state.playing && backgroundPlayHelper.isBackgroundPlayEnabled()) {
                PlaybackService.start(this);
            }
            pictureInPictureHelper.update();
            recreateIfThemeChanged();
        });
        webView.setBackgroundPolicy(() ->
                backgroundPlayHelper.isBackgroundPlayEnabled() && playerController.getState().hasVideo());

        // Set up WebViewClient
        webView.setWebViewClient(new WebTubeWebViewClient(this, appWindow, clickListener, findViewById(R.id.statusBarSpace), findViewById(R.id.bottomBar),
                playerBridge, playerController, contentBlocker));

        // Set up WebView
        setUpWebview();

        // Initialize bookmarks panel
        bookmarkManager = new BookmarkManager(this, webView);
        bookmarkManager.initializeBookmarks(navigationView);
        drawerLayout.setDrawerListener(new DrawerLayout.DrawerListener() {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                bookmarkManager.initializeBookmarks(navigationView);
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                bookmarkManager.initializeBookmarks(navigationView);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                /* Nothing */
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                /* Nothing */
            }
        });

        navigationView.setNavigationItemSelectedListener(new BookmarkSelectedListener(this, webView, bookmarkManager, drawerLayout));

        // Tor
        torHelper = new TorHelper(mApplicationContext, webView);
        torHelper.setUpTor();

        backgroundPlayHelper = new BackgroundPlayHelper(mApplicationContext);

        pictureInPictureHelper = new PictureInPictureHelper(this, playerController);
        pictureInPictureHelper.register();

        // Menu helper
        ActionMenuView actionMenu = findViewById(R.id.menu_main);
        menuHelper = new MenuHelper(this, webView, torHelper, backgroundPlayHelper, pictureInPictureHelper, contentBlocker,
                sponsorBlock, playerController, appWindow);
        getMenuInflater().inflate(R.menu.menu_main, actionMenu.getMenu());
        menuHelper.setUpMenu(actionMenu, drawerLayout, findViewById(R.id.bookmarks_panel));
        actionMenu.setOverflowIcon(getResources().getDrawable(R.drawable.ic_dots_vertical_white_24dp));


        // Load the page, once the content blocker rules are in place
        contentBlocker.start(() -> {
            // Recreated for a new theme: back to the same pages
            if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
                return;
            }
            if (!loadUrlFromIntent(getIntent())) {
                webView.loadUrl(sp.getString("homepage", "https://m.youtube.com/"));
            }
        });
    }

    // The app draws behind the status and navigation bars, so make room for them
    // (and for the keyboard), coloring them like statusBarSpace and the bottom bar
    private void setUpWindowInsets(View appWindow) {
        View statusBarSpace = findViewById(R.id.statusBarSpace);
        View content = findViewById(R.id.content);
        View bottomBar = findViewById(R.id.bottomBar);
        View bookmarksHeader = navigationView.getHeaderView(0);
        int bottomBarHeight = getResources().getDimensionPixelSize(R.dimen.bottom_bar_height);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drawer_layout), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            // The keyboard covers the navigation bar and pushes the bottom bar up
            int navigationBar = keyboard > 0 ? 0 : bars.bottom;
            appWindow.setPadding(bars.left, 0, bars.right, 0);
            content.setPadding(0, 0, 0, keyboard);
            statusBarSpace.getLayoutParams().height = bars.top;
            statusBarSpace.requestLayout();
            bottomBar.getLayoutParams().height = bottomBarHeight + navigationBar;
            bottomBar.setPadding(0, 0, 0, navigationBar);
            bookmarksHeader.setPadding(0, bars.top, 0, 0);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        recreateIfThemeChanged();
    }

    @Override
    public void onPause() {
        super.onPause();
        // webtube.js keeps YouTube from pausing by itself, so stop here unless the PlaybackService
        // or the picture-in-picture window takes over
        boolean inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode();
        if (!backgroundPlayHelper.isBackgroundPlayEnabled() && !inPictureInPicture) {
            playerController.pause();
        }
    }

    // The manifest keeps the system from recreating the activity when dark mode turns on or off
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recreateIfThemeChanged();
    }

    // Recreating reloads the page, so the new theme waits until no video is open
    // (dark mode can turn on at bedtime, while a video plays in the background)
    private void recreateIfThemeChanged() {
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode != nightMode && !playerController.getState().hasVideo()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            recreateWithState();
        }
    }

    // Like recreate(), keeping the open pages (onSaveInstanceState)
    public void recreateWithState() {
        if (!recreating) {
            recreating = true;
            recreate();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        pictureInPictureHelper.enterIfPlaying();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        // Only the video is shown in the small window
        int appUiVisibility = isInPictureInPictureMode ? View.GONE : View.VISIBLE;
        findViewById(R.id.bottomBar).setVisibility(appUiVisibility);
        findViewById(R.id.statusBarSpace).setVisibility(appUiVisibility);
        playerController.setPictureInPicture(isInPictureInPictureMode);

        // The window was closed instead of being expanded back to the app
        if (!isInPictureInPictureMode && getLifecycle().getCurrentState() == Lifecycle.State.CREATED) {
            playerController.pause();
            PlaybackService.stop(this);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        pictureInPictureHelper.unregister();
        contentBlocker.release();
        sponsorBlock.release();
        // The WebView playing the video dies with this activity
        PlaybackService.stop(this);
        playerController.release();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        loadUrlFromIntent(intent);
    }

    // A YouTube link opened (ACTION_VIEW) or shared (ACTION_SEND) with the app
    private boolean loadUrlFromIntent(final Intent intent) {
        String url = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            url = SharedLinks.urlFor(intent.getStringExtra(Intent.EXTRA_TEXT));
        }
        if (url == null) {
            return false;
        }

        if (!url.equals(webView.getUrl())) {
            webView.loadUrl(url);
        }
        return true;
    }

    public void setUpWebview() {
        // Lets chrome://inspect attach to debug builds
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // To save login info
        CookieHelper.acceptCookies(webView, true);

        // Some settings
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setAllowFileAccess(false);

        webSettings.setDatabaseEnabled(true);

        webSettings.setAllowFileAccess(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setHorizontalScrollBarEnabled(false);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Background color and dark mode of the pages
        ThemeHelper.setUpWebView(webView);
        webView.setScrollbarFadingEnabled(true);
        webView.setNetworkAvailable(true);
    }

    @Override
    public void onBackPressed() {
        if (chromeClient.exitFullscreen()) {
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }
}
