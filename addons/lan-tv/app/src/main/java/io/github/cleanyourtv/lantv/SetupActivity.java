package io.github.cleanyourtv.lantv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hub: saved playlists (OK opens edit), Sync now, then Live Channels / channel settings
 * like Tvirl's setup gateway.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_HOST = "host";
    static final String EXTRA_XMLTV = "xmltv";
    static final String EXTRA_SYNC = "sync";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Prefs.Source> sources = new ArrayList<>();

    private ListView sourceList;
    private TextView sourcesEmpty;
    private TextView status;
    private Button addPlaylist;
    private Button sync;
    private Button channelSettings;
    private ArrayAdapter<Prefs.Source> adapter;
    private int selected = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        Ui.registerInput(this);
        sourceList = findViewById(R.id.source_list);
        sourcesEmpty = findViewById(R.id.sources_empty);
        status = findViewById(R.id.status);
        addPlaylist = findViewById(R.id.add_playlist);
        sync = findViewById(R.id.sync);
        channelSettings = findViewById(R.id.channel_settings);

        adapter = new ArrayAdapter<Prefs.Source>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, sources) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Prefs.Source s = getItem(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                if (s != null) {
                    t1.setText(s.label());
                    t1.setTextColor(0xFFF2F6FA);
                    t2.setText(s.detail());
                    t2.setTextColor(0xFF9BB0C0);
                }
                return v;
            }
        };
        sourceList.setAdapter(adapter);
        sourceList.setOnItemClickListener((parent, view, position, id) -> {
            selected = position;
            sourceList.setItemChecked(position, true);
            openEdit();
        });

        addPlaylist.setOnClickListener(v ->
                startActivity(new Intent(this, AddPlaylistActivity.class)));
        sync.setOnClickListener(v -> doSync());
        channelSettings.setOnClickListener(v -> Ui.openChannelSettings(this, status));

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SYNC, false)) {
            sourceList.post(this::doSync);
        } else if (getIntent() != null) {
            String intentHost = getIntent().getStringExtra(EXTRA_HOST);
            if (intentHost != null && !intentHost.trim().isEmpty()) {
                Intent add = new Intent(this, AddPlaylistActivity.class);
                add.putExtra(EXTRA_HOST, intentHost.trim());
                String intentXmltv = getIntent().getStringExtra(EXTRA_XMLTV);
                if (intentXmltv != null) add.putExtra(EXTRA_XMLTV, intentXmltv.trim());
                startActivity(add);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadSources();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void reloadSources() {
        String keepHost = selected >= 0 && selected < sources.size() ? sources.get(selected).host : null;
        sources.clear();
        sources.addAll(Prefs.sources(this));
        adapter.notifyDataSetChanged();
        boolean empty = sources.isEmpty();
        sourcesEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        sourceList.setVisibility(empty ? View.GONE : View.VISIBLE);
        selected = -1;
        if (keepHost != null) {
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).host.equalsIgnoreCase(keepHost)) {
                    selected = i;
                    break;
                }
            }
        }
        if (selected < 0 && !sources.isEmpty()) selected = 0;
        CharSequence cur = status.getText();
        boolean keepStatus = cur != null && (
                cur.toString().startsWith("Synced ")
                        || cur.toString().startsWith("Syncing")
                        || cur.toString().startsWith("Opening "));
        if (selected >= 0) {
            sourceList.setItemChecked(selected, true);
            if (!keepStatus) {
                Prefs.Source s = sources.get(selected);
                status.setText(s.label() + "\n" + s.detail());
            }
        } else if (empty && !keepStatus) {
            status.setText(R.string.status_idle);
        }
    }

    private void openEdit() {
        if (selected < 0 || selected >= sources.size()) {
            status.setText(R.string.need_selection);
            return;
        }
        Intent i = new Intent(this, EditPlaylistActivity.class);
        i.putExtra(EXTRA_HOST, sources.get(selected).host);
        startActivity(i);
    }

    private void setBusy(boolean busy) {
        addPlaylist.setEnabled(!busy);
        sync.setEnabled(!busy);
        channelSettings.setEnabled(!busy);
        sourceList.setEnabled(!busy);
    }

    private void doSync() {
        if (Prefs.sources(this).isEmpty()) {
            status.setText(R.string.need_sources);
            return;
        }
        setBusy(true);
        status.setText(R.string.status_syncing);
        io.execute(() -> {
            try {
                ChannelSync.Result r = ChannelSync.sync(SetupActivity.this);
                EpgSyncJob.setUpPeriodicSync(SetupActivity.this);
                main.post(() -> {
                    setBusy(false);
                    setResult(RESULT_OK);
                    reloadSources();
                    status.setText(getString(R.string.status_done, r.channels, r.programmes));
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.status_error,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                });
            }
        });
    }
}
