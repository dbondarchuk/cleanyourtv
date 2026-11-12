package io.github.cleanyourtv.lantv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After reboot: re-register the 12-hour EPG job and sync once so Live TV keeps the input.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (Prefs.sources(context).isEmpty()) return;
        final Context app = context.getApplicationContext();
        Ui.registerInput(app);
        EpgSyncJob.setUpPeriodicSync(app);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                ChannelSync.sync(app);
            } catch (Exception ignored) {
            } finally {
                pending.finish();
            }
        }, "lan-tv-boot-sync").start();
    }
}
