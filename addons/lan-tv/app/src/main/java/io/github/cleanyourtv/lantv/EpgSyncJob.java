package io.github.cleanyourtv.lantv;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * Background channel + XMLTV refresh, same role as the TIF Companion Library's
 * {@code EpgSyncJobService#setUpPeriodicSync}: every 12 hours when a host is saved.
 */
public class EpgSyncJob extends JobService {
    private static final String TAG = "LanTv.EpgJob";
    private static final int PERIODIC_JOB_ID = 1001;
    private static final int IMMEDIATE_JOB_ID = 1002;
    /** Same default period as TIF Companion Library EpgSyncJobService. */
    static final long PERIOD_MS = 12L * 60 * 60 * 1000;

    /** Schedule (or refresh) the persisted 12-hour sync. No-op when no playlists are saved. */
    public static void setUpPeriodicSync(Context context) {
        Context app = context.getApplicationContext();
        if (Prefs.sources(app).isEmpty()) {
            cancel(app);
            return;
        }
        JobScheduler js = app.getSystemService(JobScheduler.class);
        if (js == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("input_id", ChannelSync.inputId(app));
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB_ID, new ComponentName(app, EpgSyncJob.class))
                .setExtras(extras)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        int result = js.schedule(job);
        Log.i(TAG, "Periodic sync scheduled every " + PERIOD_MS + "ms, result=" + result);
    }

    /** One-shot sync as soon as the scheduler allows (boot / after Save and sync). */
    public static void requestImmediateSync(Context context) {
        Context app = context.getApplicationContext();
        if (Prefs.sources(app).isEmpty()) return;
        JobScheduler js = app.getSystemService(JobScheduler.class);
        if (js == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("input_id", ChannelSync.inputId(app));
        JobInfo job = new JobInfo.Builder(IMMEDIATE_JOB_ID, new ComponentName(app, EpgSyncJob.class))
                .setExtras(extras)
                .setMinimumLatency(0)
                .setOverrideDeadline(5_000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        js.schedule(job);
    }

    public static void cancel(Context context) {
        JobScheduler js = context.getApplicationContext().getSystemService(JobScheduler.class);
        if (js == null) return;
        js.cancel(PERIODIC_JOB_ID);
        js.cancel(IMMEDIATE_JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean wantsReschedule = false;
            try {
                if (!Prefs.sources(app).isEmpty()) {
                    ChannelSync.Result r = ChannelSync.sync(app);
                    Log.i(TAG, "Job synced " + r.channels + " channels, " + r.programmes + " programmes");
                }
            } catch (Exception e) {
                Log.w(TAG, "Job sync failed: " + e.getMessage());
                wantsReschedule = true;
            } finally {
                jobFinished(params, wantsReschedule);
            }
        }, "lan-tv-epg-sync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
