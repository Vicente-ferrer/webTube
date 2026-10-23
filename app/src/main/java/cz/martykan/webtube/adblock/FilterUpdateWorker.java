package cz.martykan.webtube.adblock;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

// Daily download of the enabled filter lists, scheduled by FilterListManager.scheduleUpdates
public class FilterUpdateWorker extends Worker {
    public FilterUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        FilterListManager lists = FilterListManager.get(getApplicationContext());
        FilterListManager.UpdateResult result = lists.update(false);
        if (result.changed) {
            lists.notifyListsUpdated(false);
        }
        return result.errors.isEmpty() ? Result.success() : Result.retry();
    }
}
