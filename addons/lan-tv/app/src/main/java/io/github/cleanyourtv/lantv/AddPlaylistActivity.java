package io.github.cleanyourtv.lantv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Discover a host on the LAN or type one, then save a new playlist. */
public class AddPlaylistActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Hdhr.Device> foundDevices = new ArrayList<>();
    private EditText host;
    private EditText xmltv;
    private TextView status;
    private TextView foundHeading;
    private ListView foundList;
    private ArrayAdapter<Hdhr.Device> foundAdapter;
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
        foundHeading = findViewById(R.id.found_heading);
        foundList = findViewById(R.id.found_list);
        scan = findViewById(R.id.scan);
        save = findViewById(R.id.save);
        back = findViewById(R.id.back);

        foundAdapter = new ArrayAdapter<Hdhr.Device>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, foundDevices) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Hdhr.Device d = getItem(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                if (d != null) {
                    String name = d.name == null || d.name.isEmpty() ? "HDHomeRun" : d.name;
                    if (d.model != null && !d.model.isEmpty()) name = name + " · " + d.model;
                    t1.setText(name);
                    t1.setTextColor(0xFFF2F6FA);
                    t2.setText(d.host);
                    t2.setTextColor(0xFF9BB0C0);
                }
                return v;
            }
        };
        foundList.setAdapter(foundAdapter);
        foundList.setOnItemClickListener((parent, view, position, id) -> selectFound(position));

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
        foundList.setEnabled(!busy);
    }

    private void clearFound() {
        foundDevices.clear();
        foundAdapter.notifyDataSetChanged();
        foundHeading.setVisibility(View.GONE);
        foundList.setVisibility(View.GONE);
    }

    private void showFound(List<Hdhr.Device> found, String hint) {
        foundDevices.clear();
        foundDevices.addAll(found);
        foundAdapter.notifyDataSetChanged();
        foundHeading.setVisibility(View.VISIBLE);
        foundList.setVisibility(View.VISIBLE);

        int pick = 0;
        String hintClean = Hdhr.clean(hint).toLowerCase(Locale.US);
        if (!hintClean.isEmpty()) {
            String hintIp = hintClean.split(":")[0];
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).host.toLowerCase(Locale.US).startsWith(hintIp)) {
                    pick = i;
                    break;
                }
            }
        }
        selectFound(pick);
        foundList.post(foundList::requestFocus);
    }

    private void selectFound(int position) {
        if (position < 0 || position >= foundDevices.size()) return;
        foundList.setItemChecked(position, true);
        host.setText(foundDevices.get(position).host);
        status.setText(getString(R.string.found_selected, foundDevices.size()));
    }

    private void doScan() {
        setBusy(true);
        status.setText(R.string.status_scanning);
        clearFound();
        final String hint = Ui.text(host).trim();
        io.execute(() -> {
            try {
                // 12s: real HDHomeRun is on :80; old scans spent the budget on :8409 first.
                List<Hdhr.Device> found = Hdhr.discover(12_000, hint);
                main.post(() -> {
                    setBusy(false);
                    if (found.isEmpty()) {
                        status.setText(R.string.nothing_found);
                        return;
                    }
                    showFound(found, hint);
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
        final String clean = Hdhr.clean(h);
        final String x = Ui.text(xmltv).trim();
        setBusy(true);
        status.setText(R.string.status_checking_host);
        io.execute(() -> {
            try {
                Hdhr.Device device = Hdhr.info(clean);
                List<Hdhr.Channel> lineup = Hdhr.lineup(device);
                List<Prefs.Source> next = new ArrayList<>(Prefs.sources(AddPlaylistActivity.this));
                boolean replaced = false;
                String friendly = device.name == null || device.name.isEmpty()
                        ? device.host : device.name;
                for (int i = 0; i < next.size(); i++) {
                    if (next.get(i).host.equalsIgnoreCase(device.host)) {
                        next.set(i, new Prefs.Source(device.host, x, friendly));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) next.add(new Prefs.Source(device.host, x, friendly));
                Prefs.saveSources(AddPlaylistActivity.this, next);
                EpgSyncJob.setUpPeriodicSync(AddPlaylistActivity.this);
                final int n = lineup.size();
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.status_saved_channels, device.host, n));
                    host.postDelayed(AddPlaylistActivity.this::finish, 800);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.status_error,
                            e.getMessage() == null ? "host check failed" : e.getMessage()));
                });
            }
        });
    }
}
