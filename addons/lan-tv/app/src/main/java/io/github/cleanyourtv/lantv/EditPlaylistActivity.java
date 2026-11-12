package io.github.cleanyourtv.lantv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Edit or remove one saved playlist (host + EPG URL); remove also clears its Live TV channels. */
public class EditPlaylistActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText host;
    private EditText xmltv;
    private TextView heading;
    private TextView status;
    private Button save;
    private Button remove;
    private Button back;
    private String originalHost = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_playlist);
        host = findViewById(R.id.host);
        xmltv = findViewById(R.id.xmltv);
        heading = findViewById(R.id.heading);
        status = findViewById(R.id.status);
        save = findViewById(R.id.save);
        remove = findViewById(R.id.remove);
        back = findViewById(R.id.back);

        String want = getIntent() != null ? getIntent().getStringExtra(SetupActivity.EXTRA_HOST) : null;
        Prefs.Source found = null;
        if (want != null) {
            for (Prefs.Source s : Prefs.sources(this)) {
                if (s.host.equalsIgnoreCase(Hdhr.clean(want))) {
                    found = s;
                    break;
                }
            }
        }
        if (found == null) {
            status.setText(R.string.need_selection);
            host.postDelayed(this::finish, 800);
            return;
        }
        originalHost = found.host;
        host.setText(found.host);
        xmltv.setText(found.xmltv);
        heading.setText(found.label());
        status.setText(found.detail());

        save.setOnClickListener(v -> doSave());
        remove.setOnClickListener(v -> doRemove());
        back.setOnClickListener(v -> finish());
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void setBusy(boolean busy) {
        save.setEnabled(!busy);
        remove.setEnabled(!busy);
        back.setEnabled(!busy);
        host.setEnabled(!busy);
        xmltv.setEnabled(!busy);
    }

    private void doSave() {
        String h = Ui.text(host).trim();
        if (h.isEmpty()) {
            status.setText(R.string.need_host);
            return;
        }
        String clean = Hdhr.clean(h);
        String x = Ui.text(xmltv).trim();
        final String oldHost = originalHost;
        final boolean hostChanged = !oldHost.equalsIgnoreCase(clean);

        List<Prefs.Source> next = new ArrayList<>();
        String friendly = "";
        for (Prefs.Source s : Prefs.sources(this)) {
            if (s.host.equalsIgnoreCase(oldHost)) {
                friendly = s.friendly;
                continue; // replace below
            }
            if (s.host.equalsIgnoreCase(clean)) continue; // drop duplicate host
            next.add(s);
        }
        Prefs.Source updated = new Prefs.Source(clean, x, friendly);
        next.add(updated);
        Prefs.saveSources(this, next);
        EpgSyncJob.setUpPeriodicSync(this);
        originalHost = clean;
        heading.setText(updated.label());

        if (!hostChanged) {
            status.setText(getString(R.string.status_saved, updated.label()));
            return;
        }

        // Host renamed: drop Live TV rows that still point at the old server.
        setBusy(true);
        status.setText(R.string.status_removing_channels);
        io.execute(() -> {
            int n = ChannelSync.removeHost(EditPlaylistActivity.this, oldHost);
            main.post(() -> {
                setBusy(false);
                status.setText(getString(R.string.status_saved_host_moved, updated.label(), n));
            });
        });
    }

    private void doRemove() {
        final String hostToDrop = originalHost;
        List<Prefs.Source> next = new ArrayList<>();
        for (Prefs.Source s : Prefs.sources(this)) {
            if (!s.host.equalsIgnoreCase(hostToDrop)) next.add(s);
        }
        Prefs.saveSources(this, next);
        final boolean last = next.isEmpty();
        if (last) EpgSyncJob.cancel(this);
        else EpgSyncJob.setUpPeriodicSync(this);

        setBusy(true);
        status.setText(R.string.status_removing_channels);
        io.execute(() -> {
            int n = last
                    ? ChannelSync.clearAll(EditPlaylistActivity.this)
                    : ChannelSync.removeHost(EditPlaylistActivity.this, hostToDrop);
            main.post(() -> {
                setBusy(false);
                status.setText(getString(R.string.status_removed_channels, hostToDrop, n));
                host.postDelayed(this::finish, 700);
            });
        });
    }
}
