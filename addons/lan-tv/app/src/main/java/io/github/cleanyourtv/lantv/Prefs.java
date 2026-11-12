package io.github.cleanyourtv.lantv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Saved playlists: each has a host, optional XMLTV URL, and a friendly name. */
final class Prefs {
    private static final String NAME = "lan_tv";
    private static final String HOST = "host";
    private static final String HOSTS = "hosts";
    private static final String XMLTV = "xmltv";
    private static final String FRIENDLY = "friendly";
    private static final String SOURCES = "sources";

    static final class Source {
        final String host;
        final String xmltv;
        final String friendly;

        Source(String host, String xmltv, String friendly) {
            this.host = host == null ? "" : Hdhr.clean(host);
            this.xmltv = xmltv == null ? "" : xmltv.trim();
            this.friendly = friendly == null ? "" : friendly.trim();
        }

        String label() {
            if (!friendly.isEmpty()) return friendly + "  ·  " + host;
            return host;
        }

        String detail() {
            if (xmltv.isEmpty()) return "EPG: auto";
            return "EPG: " + xmltv;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("host", host);
            o.put("xmltv", xmltv);
            o.put("friendly", friendly);
            return o;
        }

        static Source fromJson(JSONObject o) {
            return new Source(
                    o.optString("host"),
                    o.optString("xmltv"),
                    o.optString("friendly"));
        }
    }

    private Prefs() {
    }

    static SharedPreferences p(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** All saved playlists, migrating older single-host / hosts-text prefs once. */
    static List<Source> sources(Context ctx) {
        SharedPreferences sp = p(ctx);
        String raw = sp.getString(SOURCES, "");
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                LinkedHashMap<String, Source> byHost = new LinkedHashMap<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Source s = Source.fromJson(o);
                    if (!s.host.isEmpty()) byHost.put(s.host.toLowerCase(Locale.US), s);
                }
                return new ArrayList<>(byHost.values());
            } catch (JSONException ignored) {
            }
        }
        // Legacy migration.
        List<Source> migrated = new ArrayList<>();
        String globalXmltv = sp.getString(XMLTV, "");
        String friendly = sp.getString(FRIENDLY, "");
        String hostsRaw = sp.getString(HOSTS, "");
        if (hostsRaw != null && !hostsRaw.trim().isEmpty()) {
            for (String line : hostsRaw.split("\n")) {
                String h = Hdhr.clean(line);
                if (!h.isEmpty()) {
                    migrated.add(new Source(h, globalXmltv, migrated.isEmpty() ? friendly : ""));
                }
            }
        } else {
            String legacy = sp.getString(HOST, "");
            if (legacy != null && !legacy.trim().isEmpty()) {
                migrated.add(new Source(legacy, globalXmltv, friendly));
            }
        }
        if (!migrated.isEmpty()) saveSources(ctx, migrated);
        return migrated;
    }

    static List<String> hosts(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Source s : sources(ctx)) out.add(s.host);
        return out;
    }

    /** First source's XMLTV (legacy callers / single-default). Prefer per-source xmltv. */
    static String xmltv(Context ctx) {
        List<Source> list = sources(ctx);
        if (!list.isEmpty() && !list.get(0).xmltv.isEmpty()) return list.get(0).xmltv;
        return p(ctx).getString(XMLTV, "");
    }

    static String friendly(Context ctx) {
        List<Source> list = sources(ctx);
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            Source s = list.get(i);
            sb.append(s.friendly.isEmpty() ? s.host : s.friendly);
        }
        return sb.toString();
    }

    static void saveSources(Context ctx, List<Source> sources) {
        LinkedHashMap<String, Source> byHost = new LinkedHashMap<>();
        if (sources != null) {
            for (Source s : sources) {
                if (s == null || s.host.isEmpty()) continue;
                byHost.put(s.host.toLowerCase(Locale.US), s);
            }
        }
        JSONArray arr = new JSONArray();
        StringBuilder hostsText = new StringBuilder();
        String firstHost = "";
        String firstXmltv = "";
        String friendlyJoin = "";
        int i = 0;
        for (Source s : byHost.values()) {
            try {
                arr.put(s.toJson());
            } catch (JSONException ignored) {
            }
            if (i > 0) {
                hostsText.append('\n');
                friendlyJoin += ", ";
            }
            hostsText.append(s.host);
            friendlyJoin += s.friendly.isEmpty() ? s.host : s.friendly;
            if (i == 0) {
                firstHost = s.host;
                firstXmltv = s.xmltv;
            }
            i++;
        }
        p(ctx).edit()
                .putString(SOURCES, arr.toString())
                .putString(HOSTS, hostsText.toString())
                .putString(HOST, firstHost)
                .putString(XMLTV, firstXmltv)
                .putString(FRIENDLY, friendlyJoin)
                .apply();
    }

    /** @deprecated use {@link #saveSources}; kept for ChannelSync transitional callers. */
    static void save(Context ctx, List<String> hosts, String xmltv, String friendly) {
        List<Source> existing = sources(ctx);
        LinkedHashMap<String, Source> byHost = new LinkedHashMap<>();
        for (Source s : existing) byHost.put(s.host.toLowerCase(Locale.US), s);
        List<Source> out = new ArrayList<>();
        if (hosts != null) {
            for (String h : hosts) {
                String clean = Hdhr.clean(h);
                if (clean.isEmpty()) continue;
                Source prev = byHost.get(clean.toLowerCase(Locale.US));
                String x = prev != null && !prev.xmltv.isEmpty() ? prev.xmltv : (xmltv == null ? "" : xmltv);
                String f = prev != null && !prev.friendly.isEmpty() ? prev.friendly : (friendly == null ? "" : friendly);
                out.add(new Source(clean, x, f));
            }
        }
        saveSources(ctx, out);
    }
}
