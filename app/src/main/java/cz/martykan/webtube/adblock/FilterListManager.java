package cz.martykan.webtube.adblock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import cz.martykan.webtube.TorHelper;

// The filter lists the user picked: downloaded from their official addresses, never bundled
public class FilterListManager {
    private static final String LOG_TAG = "webTubeAdblock";
    private static final String PREFS = "filterLists";
    private static final String UPDATE_WORK = "filterListsUpdate";
    private static final long MAX_AGE = TimeUnit.DAYS.toMillis(1);
    private static final int MAX_INCLUDE_DEPTH = 2;

    public static final class FilterList {
        public final String id;
        public final String name;
        public final String url;
        // May use trusted-* scriptlets; only uBlock Origin's own lists
        final boolean trusted;
        public final boolean custom;
        final boolean enabledByDefault;

        FilterList(String id, String name, String url, boolean trusted, boolean custom, boolean enabledByDefault) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.trusted = trusted;
            this.custom = custom;
            this.enabledByDefault = enabledByDefault;
        }
    }

    private static final List<FilterList> CATALOGUE = Arrays.asList(
            new FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", false, false, true),
            new FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", false, false, true),
            new FilterList("ublock-filters", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", true, false, true),
            new FilterList("ublock-quick-fixes", "uBlock filters – Quick fixes", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt", true, false, true),
            new FilterList("ublock-privacy", "uBlock filters – Privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", true, false, false));

    public interface Listener {
        // byUser: changed in the filter lists screen, as opposed to the daily update
        void onListsUpdated(boolean byUser);
    }

    public static final class UpdateResult {
        public boolean changed;
        public final List<String> errors = new ArrayList<>();
    }

    private static FilterListManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final File listsDir;
    private final File httpCacheDir;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized FilterListManager get(Context context) {
        if (instance == null) {
            instance = new FilterListManager(context.getApplicationContext());
        }
        return instance;
    }

    private FilterListManager(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        listsDir = new File(context.getFilesDir(), "filters");
        httpCacheDir = new File(listsDir, "http");
        httpCacheDir.mkdirs();
    }

    public List<FilterList> getLists() {
        List<FilterList> lists = new ArrayList<>(CATALOGUE);
        for (String url : customUrls()) {
            lists.add(new FilterList("custom-" + Integer.toHexString(url.hashCode()), hostOf(url), url, false, true, true));
        }
        return lists;
    }

    public boolean isEnabled(FilterList list) {
        return prefs.getBoolean("enabled." + list.id, list.enabledByDefault);
    }

    public void setEnabled(FilterList list, boolean enabled) {
        prefs.edit().putBoolean("enabled." + list.id, enabled).apply();
    }

    public void addCustom(String url) {
        List<String> urls = customUrls();
        if (!urls.contains(url)) {
            urls.add(url);
            prefs.edit().putString("custom", new JSONArray(urls).toString()).apply();
        }
    }

    public void removeCustom(FilterList list) {
        List<String> urls = customUrls();
        urls.remove(list.url);
        prefs.edit().putString("custom", new JSONArray(urls).toString())
                .remove("enabled." + list.id).remove("updated." + list.id).remove("rules." + list.id).apply();
        fileOf(list).delete();
    }

    private List<String> customUrls() {
        List<String> urls = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("custom", "[]"));
            for (int i = 0; i < array.length(); i++) {
                urls.add(array.getString(i));
            }
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Bad custom list setting", e);
        }
        return urls;
    }

    // 0 when never downloaded
    public long getUpdatedAt(FilterList list) {
        return fileOf(list).exists() ? prefs.getLong("updated." + list.id, 0) : 0;
    }

    // -1 before the list was first loaded
    public int getRuleCount(FilterList list) {
        return prefs.getInt("rules." + list.id, -1);
    }

    // Lists are not downloaded outside the WebView while it goes through Tor
    public boolean isTorEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(TorHelper.PREF_TOR_ENABLED, false);
    }

    private File fileOf(FilterList list) {
        return new File(listsDir, list.id + ".txt");
    }

    // Blocking: downloads the enabled lists that are missing or older than a day (all of them when forced)
    public UpdateResult update(boolean force) {
        UpdateResult result = new UpdateResult();
        if (isTorEnabled()) {
            return result;
        }
        for (FilterList list : getLists()) {
            if (!isEnabled(list)) {
                continue;
            }
            boolean stale = System.currentTimeMillis() - getUpdatedAt(list) > MAX_AGE;
            if (!force && !stale) {
                continue;
            }
            try {
                String text = download(list.url, list.url, 0);
                File file = fileOf(list);
                File temporary = new File(listsDir, list.id + ".tmp");
                try (OutputStream out = new FileOutputStream(temporary)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                if (!temporary.renameTo(file)) {
                    throw new IOException("Could not save " + file);
                }
                prefs.edit().putLong("updated." + list.id, System.currentTimeMillis()).apply();
                result.changed = true;
            } catch (IOException e) {
                Log.w(LOG_TAG, "Could not update " + list.name, e);
                result.errors.add(list.name + ": " + e.getMessage());
            }
        }
        return result;
    }

    // The list text with its "!#include file.txt" lines replaced by those files (same folder only)
    private String download(String url, String listUrl, int depth) throws IOException {
        String text = fetch(url);
        if (depth >= MAX_INCLUDE_DEPTH || !text.contains("!#include ")) {
            return text;
        }
        String folder = listUrl.substring(0, listUrl.lastIndexOf('/') + 1);
        StringBuilder result = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("!#include ")) {
                String included = new URL(new URL(url), trimmed.substring(10).trim()).toString();
                if (included.startsWith(folder) && included.indexOf('/', folder.length()) < 0) {
                    result.append(download(included, listUrl, depth + 1)).append('\n');
                    continue;
                }
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    // GET with If-None-Match / If-Modified-Since, so unchanged files are not downloaded again
    private String fetch(String url) throws IOException {
        String key = Integer.toHexString(url.hashCode());
        File cached = new File(httpCacheDir, key);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        if (cached.exists()) {
            String etag = prefs.getString("etag." + key, null);
            String lastModified = prefs.getString("modified." + key, null);
            if (etag != null) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            if (lastModified != null) {
                connection.setRequestProperty("If-Modified-Since", lastModified);
            }
        }
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && cached.exists()) {
                return readFile(cached);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            String text;
            try (InputStream in = connection.getInputStream()) {
                text = readStream(in);
            }
            try (OutputStream out = new FileOutputStream(cached)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            prefs.edit()
                    .putString("etag." + key, connection.getHeaderField("ETag"))
                    .putString("modified." + key, connection.getHeaderField("Last-Modified"))
                    .apply();
            return text;
        } finally {
            connection.disconnect();
        }
    }

    // Blocking: parses the enabled, downloaded lists
    public FilterEngine buildEngine() {
        List<FilterParser.Result> results = new ArrayList<>();
        SharedPreferences.Editor editor = prefs.edit();
        for (FilterList list : getLists()) {
            File file = fileOf(list);
            if (!isEnabled(list) || !file.exists()) {
                continue;
            }
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                FilterParser.Result result = FilterParser.parse(reader, list.trusted);
                editor.putInt("rules." + list.id, result.ruleCount());
                results.add(result);
            } catch (IOException e) {
                Log.w(LOG_TAG, "Could not read " + list.name, e);
            }
        }
        editor.apply();
        return new FilterEngine(results);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void notifyListsUpdated(boolean byUser) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onListsUpdated(byUser);
            }
        });
    }

    // Daily background update while the content blocker is on
    public void scheduleUpdates(boolean enabled) {
        WorkManager workManager = WorkManager.getInstance(context);
        if (enabled) {
            // Turning the blocker on downloads the lists itself, so the first periodic run waits a day
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(FilterUpdateWorker.class, 1, TimeUnit.DAYS)
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build();
            workManager.enqueueUniquePeriodicWork(UPDATE_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
        } else {
            workManager.cancelUniqueWork(UPDATE_WORK);
        }
    }

    private static String hostOf(String url) {
        String host = Domains.hostOf(url);
        return host.isEmpty() ? url : host;
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
