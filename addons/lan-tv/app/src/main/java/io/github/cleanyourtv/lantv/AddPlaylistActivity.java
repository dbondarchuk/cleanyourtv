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

/** Discover a host on the LAN or type one, then save a new playlist. */
public class AddPlaylistActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText host;
    private EditText xmltv;
    private TextView status;
    private Button scan;
    private Button save;
    private Button back;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_playlist);
        host = findViewById(R.id.host);
        xmltv = findViewById(R.id.xmltv);
        status = findViewById(R.id.status);
        scan = findViewById(R.id.scan);
        save = findViewById(R.id.save);
        back = findViewById(R.id.back);

        if (getIntent() != null) {
            String intentHost = getIntent().getStringExtra(SetupActivity.EXTRA_HOST);
            if (intentHost != null && !intentHost.trim().isEmpty()) {
                host.setText(Hdhr.clean(intentHost));
            }
            String intentXmltv = getIntent().getStringExtra(SetupActivity.EXTRA_XMLTV);
            if (intentXmltv != null) xmltv.setText(intentXmltv.trim());
        }

        scan.setOnClickListener(v -> doScan());
        save.setOnClickListener(v -> doSave());
        back.setOnClickListener(v -> finish());
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void setBusy(boolean busy) {
        scan.setEnabled(!busy);
        save.setEnabled(!busy);
        back.setEnabled(!busy);
        host.setEnabled(!busy);
        xmltv.setEnabled(!busy);
    }

    private void doScan() {
        setBusy(true);
        status.setText(R.string.status_scanning);
        io.execute(() -> {
            try {
                List<Hdhr.Device> found = Hdhr.discover(6000);
                main.post(() -> {
                    setBusy(false);
                    if (found.isEmpty()) {
                        status.setText(R.string.nothing_found);
                        return;
                    }
                    Hdhr.Device first = found.get(0);
                    host.setText(first.host);
                    StringBuilder label = new StringBuilder();
                    for (int i = 0; i < found.size(); i++) {
                        Hdhr.Device d = found.get(i);
                        if (i > 0) label.append('\n');
                        label.append(d.name).append(" @ ").append(d.host);
                    }
                    status.setText(getString(R.string.found_many, found.size()) + "\n" + label);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.status_error,
                            e.getMessage() == null ? "scan failed" : e.getMessage()));
                });
            }
        });
    }

    private void doSave() {
        String h = Ui.text(host).trim();
        if (h.isEmpty()) {
            status.setText(R.string.need_host);
            return;
        }
        String clean = Hdhr.clean(h);
        String x = Ui.text(xmltv).trim();
        List<Prefs.Source> next = new ArrayList<>(Prefs.sources(this));
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).host.equalsIgnoreCase(clean)) {
                next.set(i, new Prefs.Source(clean, x, next.get(i).friendly));
                replaced = true;
                break;
            }
        }
        if (!replaced) next.add(new Prefs.Source(clean, x, ""));
        Prefs.saveSources(this, next);
        EpgSyncJob.setUpPeriodicSync(this);
        status.setText(getString(R.string.status_saved, clean));
        host.postDelayed(this::finish, 600);
    }
}
