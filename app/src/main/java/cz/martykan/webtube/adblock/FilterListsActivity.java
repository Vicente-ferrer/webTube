package cz.martykan.webtube.adblock;

import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.snackbar.Snackbar;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cz.martykan.webtube.R;

// Pick the filter lists the content blocker uses, add one by URL, or update them now
public class FilterListsActivity extends AppCompatActivity {
    private FilterListManager lists;
    private ArrayAdapter<FilterListManager.FilterList> adapter;
    private TextView status;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean changed;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_lists);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle(R.string.filterLists);

        lists = FilterListManager.get(this);
        status = findViewById(R.id.status);

        adapter = new ArrayAdapter<FilterListManager.FilterList>(this, R.layout.filter_list_item) {
            @Override
            public View getView(int position, View view, ViewGroup parent) {
                if (view == null) {
                    view = LayoutInflater.from(getContext()).inflate(R.layout.filter_list_item, parent, false);
                }
                FilterListManager.FilterList list = getItem(position);
                ((CheckBox) view.findViewById(R.id.enabled)).setChecked(lists.isEnabled(list));
                ((TextView) view.findViewById(R.id.name)).setText(list.name);
                ((TextView) view.findViewById(R.id.details)).setText(details(list));
                return view;
            }
        };
        ListView listView = findViewById(R.id.lists);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            FilterListManager.FilterList list = adapter.getItem(position);
            lists.setEnabled(list, !lists.isEnabled(list));
            changed = true;
            refresh();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            FilterListManager.FilterList list = adapter.getItem(position);
            if (!list.custom) {
                return false;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.removeFilterList)
                    .setMessage(list.url)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        lists.removeCustom(list);
                        changed = true;
                        refresh();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        findViewById(R.id.add_list).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.update_lists).setOnClickListener(v -> update());
        refresh();
    }

    private String details(FilterListManager.FilterList list) {
        long updatedAt = lists.getUpdatedAt(list);
        if (updatedAt == 0) {
            return getString(R.string.filterListNotDownloaded);
        }
        String date = DateFormat.getDateFormat(this).format(new Date(updatedAt)) + " "
                + DateFormat.getTimeFormat(this).format(new Date(updatedAt));
        int rules = lists.getRuleCount(list);
        return rules < 0 ? getString(R.string.filterListUpdated, date) : getString(R.string.filterListRules, rules, date);
    }

    private void refresh() {
        List<FilterListManager.FilterList> all = lists.getLists();
        adapter.clear();
        adapter.addAll(all);
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(ContentBlocker.PREF_ENABLED, false);
        if (updating) {
            status.setText(R.string.updatingFilterLists);
        } else if (lists.isTorEnabled()) {
            status.setText(R.string.filterListsTor);
        } else {
            status.setText(enabled ? R.string.contentBlockerOn : R.string.contentBlockerOff);
        }
        findViewById(R.id.update_lists).setEnabled(!updating && !lists.isTorEnabled());
    }

    private void showAddDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://");
        FrameLayout container = new FrameLayout(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.addFilterList)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.startsWith("https://") || Domains.hostOf(url).isEmpty()) {
                        Snackbar.make(status, R.string.filterListBadUrl, Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    lists.addCustom(url);
                    changed = true;
                    refresh();
                    update();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void update() {
        updating = true;
        refresh();
        executor.execute(() -> {
            FilterListManager.UpdateResult result = lists.update(true);
            // Rule counts come from parsing the new files
            lists.buildEngine();
            runOnUiThread(() -> {
                updating = false;
                changed |= result.changed;
                refresh();
                String message = result.errors.isEmpty() ? getString(R.string.filterListsUpdated)
                        : getString(R.string.filterListsUpdateFailed, result.errors.get(0));
                Snackbar.make(status, message, Snackbar.LENGTH_LONG).show();
            });
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (changed) {
            lists.notifyListsUpdated(true);
        }
    }
}
