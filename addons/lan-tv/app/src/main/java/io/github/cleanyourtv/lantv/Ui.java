package io.github.cleanyourtv.lantv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Shared setup UI helpers. Channel-settings launch mirrors Tvirl's SetupGateway:
 * prefer Live Channels when present, else the system SETUP_INPUTS handler (Sony Tvx, etc.).
 */
final class Ui {
    private static final String TAG = "LanTv.Ui";

    /** Same package order Tvirl uses when forcing Live Channels. */
    private static final String[] LIVE_CHANNELS_PKGS = {
            "com.google.android.tv",
            "com.android.tv",
    };

    private Ui() {
    }

    /**
     * Re-publish our TvInputInfo (same idea as Tvirl's {@code updateTvInputInfo}).
     * On this Sony, third-party inputs can vanish from {@code dumpsys tv_input} after a reboot
     * until something triggers a rebuild — without that, Live TV never binds us and ErsatzTV
     * sees no stream requests.
     */
    static void registerInput(Context ctx) {
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return;
            ComponentName cn = new ComponentName(ctx, LanTvInputService.class);
            TvInputInfo info = new TvInputInfo.Builder(ctx, cn)
                    .setTunerCount(1)
                    .build();
            tim.updateTvInputInfo(info);
            Log.i(TAG, "updateTvInputInfo " + info.getId());
        } catch (Exception e) {
            Log.w(TAG, "updateTvInputInfo failed", e);
        }
    }

    static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    /**
     * Opens the system channel-sources / inputs UI so the user can enable LAN TV channels.
     * Third-party apps cannot set COLUMN_BROWSABLE themselves.
     */
    static void openChannelSettings(Activity activity, TextView status) {
        PackageManager pm = activity.getPackageManager();

        // Tvirl path: Live Channels MainActivity + SETUP_INPUTS when that app is installed.
        for (String pkg : LIVE_CHANNELS_PKGS) {
            try {
                pm.getPackageInfo(pkg, 0);
                Intent i = new Intent(TvInputManager.ACTION_SETUP_INPUTS);
                i.setClassName(pkg, "com.android.tv.MainActivity");
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }

        // Default: whatever the OEM registered for SETUP_INPUTS (Sony → Tvx).
        try {
            activity.startActivity(new Intent(TvInputManager.ACTION_SETUP_INPUTS));
            return;
        } catch (Exception ignored) {
        }

        try {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            if (status != null) status.setText(R.string.settings_missing);
        }
    }
}
