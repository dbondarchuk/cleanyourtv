package io.github.cleanyourtv.lantv;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes HDHomeRun lineup rows into TvContract for this app's TvInputService.
 * Upserts by {@link TvContract.Channels#COLUMN_INTERNAL_PROVIDER_ID} so a re-sync does not
 * wipe the browsable flags the user set in TV settings.
 */
final class ChannelSync {
    private static final String TAG = "LanTv.Sync";

    static final class Result {
        final int channels;
        final int programmes;
        final String inputId;
        final String friendly;
        final Map<String, Long> channelIdsByKey;
        /** Hosts that failed this pass (sync may still succeed via other playlists). */
        final List<String> hostErrors;

        Result(int channels, int programmes, String inputId, String friendly,
               Map<String, Long> channelIdsByKey, List<String> hostErrors) {
            this.channels = channels;
            this.programmes = programmes;
            this.inputId = inputId;
            this.friendly = friendly;
            this.channelIdsByKey = channelIdsByKey;
            this.hostErrors = hostErrors == null ? Collections.emptyList() : hostErrors;
        }
    }

    private static final class Row {
        final String host;
        final Hdhr.Channel channel;
        final String providerId;
        final String matchKey;

        Row(String host, Hdhr.Channel channel, String providerId, String matchKey) {
            this.host = host;
            this.channel = channel;
            this.providerId = providerId;
            this.matchKey = matchKey;
        }
    }

    private ChannelSync() {
    }

    static String inputId(Context ctx) {
        return TvContract.buildInputId(new ComponentName(ctx, LanTvInputService.class));
    }

    /** Sync every saved playlist; merge lineups sorted by channel number; refresh XMLTV per host. */
    static Result sync(Context ctx) throws IOException {
        return sync(ctx, Prefs.sources(ctx));
    }

    static Result sync(Context ctx, List<Prefs.Source> sources) throws IOException {
        if (sources == null || sources.isEmpty()) throw new IOException("Add at least one playlist");
        String inputId = inputId(ctx);
        Map<String, Long> existing = existingByProviderId(ctx, inputId);
        // Match by number+name even when a row already has a provider id — host/format changes
        // otherwise insert a second copy and Live TV shows 32 for a 16-channel lineup.
        Map<String, List<Long>> byLabel = allIdsByLabel(ctx, inputId);

        List<Row> rows = new ArrayList<>();
        Map<String, String> xmltvByHost = new HashMap<>();
        Map<String, List<Hdhr.Channel>> lineupByHost = new HashMap<>();
        // Hosts that need SiliconDust cloud guide (no user/local XMLTV); DeviceAuth never stored.
        List<String> siliconHosts = new ArrayList<>();
        List<String> siliconAuths = new ArrayList<>();
        List<Prefs.Source> saved = new ArrayList<>();
        Set<String> seenHosts = new HashSet<>();
        List<String> hostErrors = new ArrayList<>();
        IOException last = null;

        for (Prefs.Source src : sources) {
            if (src == null || src.host.isEmpty()) continue;
            String hostKey = Hdhr.clean(src.host).toLowerCase(Locale.US);
            if (!seenHosts.add(hostKey)) continue; // one lineup per host
            try {
                Hdhr.Device device = Hdhr.info(src.host);
                List<Hdhr.Channel> lineup = Hdhr.lineup(device);
                Hdhr.sortByNumber(lineup);
                lineupByHost.put(device.host, lineup);
                String friendly = device.name == null || device.name.isEmpty()
                        ? device.host : device.name;
                String xmltv = null;
                try {
                    xmltv = Hdhr.resolveXmltv(device.host, src.xmltv);
                } catch (IOException e) {
                    Log.w(TAG, "No local XMLTV for " + device.host + ": " + e.getMessage());
                }
                if (xmltv != null) {
                    xmltvByHost.put(device.host, xmltv);
                    // Persist auto-discovered local/ETV guide URL; never a DeviceAuth cloud URL.
                    saved.add(new Prefs.Source(device.host, xmltv, friendly));
                } else if (!device.deviceAuth.isEmpty()) {
                    siliconHosts.add(device.host);
                    siliconAuths.add(device.deviceAuth);
                    saved.add(new Prefs.Source(device.host, src.xmltv, friendly));
                } else {
                    saved.add(new Prefs.Source(device.host, src.xmltv, friendly));
                }
                for (Hdhr.Channel ch : lineup) {
                    rows.add(new Row(device.host, ch, providerId(device.host, ch), key(ch)));
                }
            } catch (IOException e) {
                last = e;
                String msg = src.host + ": " + (e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage());
                hostErrors.add(msg);
                Log.w(TAG, "Host failed " + msg);
                saved.add(src); // keep the playlist even if this pass failed
            }
        }
        if (rows.isEmpty()) {
            throw last != null ? last : new IOException("No channels from any host");
        }

        // One row per provider id (duplicate lineup entries would otherwise insert twice).
        Map<String, Row> unique = new LinkedHashMap<>();
        for (Row row : rows) unique.put(row.providerId, row);
        rows = new ArrayList<>(unique.values());
        rows.sort((a, b) -> Hdhr.compareGuideNumber(a.channel.number, b.channel.number));

        Map<String, Long> ids = new HashMap<>();
        Set<Long> keep = new HashSet<>();
        int n = 0;
        for (Row row : rows) {
            ContentValues v = new ContentValues();
            v.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
            v.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    row.channel.number == null ? "" : row.channel.number);
            v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, row.channel.name);
            v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER);
            v.put(TvContract.Channels.COLUMN_SERVICE_TYPE,
                    TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO);
            v.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                    Math.abs(row.providerId.hashCode()) % 65535);
            v.put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, 1);
            v.put(TvContract.Channels.COLUMN_SERVICE_ID, n + 1);
            v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, row.providerId);
            v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                    row.channel.url.getBytes(StandardCharsets.UTF_8));
            // Never set COLUMN_BROWSABLE / COLUMN_SEARCHABLE: third-party writes are refused,
            // and deleting rows clears the flags the user set in TV channel settings.

            Long existingId = existing.remove(row.providerId);
            String label = labelKey(row.channel);
            List<Long> labelIds = byLabel.get(label);
            if (existingId == null && labelIds != null && !labelIds.isEmpty()) {
                existingId = labelIds.remove(0);
            }
            if (labelIds != null) {
                // Any other row with the same number+name is a duplicate — do not keep.
                labelIds.clear();
            }
            long channelId = -1;
            if (existingId != null) {
                int updated = ctx.getContentResolver().update(
                        TvContract.buildChannelUri(existingId), v, null, null);
                if (updated > 0) channelId = existingId;
            }
            if (channelId < 0) {
                Uri uri = ctx.getContentResolver().insert(TvContract.Channels.CONTENT_URI, v);
                if (uri != null) channelId = ContentUris.parseId(uri);
            }
            if (channelId >= 0) {
                keep.add(channelId);
                existing.put(row.providerId, channelId); // so a later duplicate provider reuses it
                // Unique across hosts so XMLTV apply can target the right row.
                ids.put(row.host + "|" + row.matchKey, channelId);
                n++;
            }
        }

        // Drop every row for this input that we did not keep (programmes first — Sony can
        // refuse a channel delete while guide rows still point at it).
        List<Long> drop = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels._ID},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    if (!keep.contains(id)) drop.add(id);
                }
            }
        }
        int removed = deleteChannels(ctx, drop);
        if (removed > 0) Log.i(TAG, "Dropped " + removed + " duplicate/orphan channel(s)");

        int programmes = 0;
        for (Map.Entry<String, List<Hdhr.Channel>> e : lineupByHost.entrySet()) {
            String host = e.getKey();
            String xmltv = xmltvByHost.get(host);
            if (xmltv == null || xmltv.isEmpty()) continue;
            Map<String, Long> hostIds = new HashMap<>();
            for (Hdhr.Channel ch : e.getValue()) {
                Long id = ids.get(host + "|" + key(ch));
                if (id != null) hostIds.put(key(ch), id);
            }
            try {
                programmes += XmltvSync.apply(ctx, xmltv, hostIds, e.getValue());
            } catch (IOException ex) {
                Log.w(TAG, "XMLTV apply failed for " + host + ": " + ex.getMessage());
            }
        }

        // Real HDHomeRun: no local guide — fetch SiliconDust cloud XMLTV once per sync using
        // concatenated DeviceAuth (https://github.com/dbondarchuk/hdhomerun-xmltv).
        if (!siliconAuths.isEmpty()) {
            try {
                byte[] guide = Hdhr.fetchSiliconDustXmltv(siliconAuths);
                for (String host : siliconHosts) {
                    List<Hdhr.Channel> lineup = lineupByHost.get(host);
                    if (lineup == null) continue;
                    Map<String, Long> hostIds = new HashMap<>();
                    for (Hdhr.Channel ch : lineup) {
                        Long id = ids.get(host + "|" + key(ch));
                        if (id != null) hostIds.put(key(ch), id);
                    }
                    try {
                        programmes += XmltvSync.applyXml(ctx, guide, hostIds, lineup);
                    } catch (IOException ex) {
                        Log.w(TAG, "SiliconDust XMLTV apply failed for " + host + ": "
                                + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                hostErrors.add("SiliconDust guide: " + ex.getMessage());
                Log.w(TAG, "SiliconDust XMLTV fetch failed: " + ex.getMessage());
            }
        }

        Prefs.saveSources(ctx, saved);
        String friendly = Prefs.friendly(ctx);
        Log.i(TAG, "Synced " + n + " channels, " + programmes + " programmes from "
                + lineupByHost.size() + " host(s)"
                + (hostErrors.isEmpty() ? "" : "; failures: " + hostErrors));
        return new Result(n, programmes, inputId, friendly, ids, hostErrors);
    }

    /** Convenience for a single host (ADB). Merges into the saved playlist list. */
    static Result sync(Context ctx, String host, String xmltvUrl) throws IOException {
        List<Prefs.Source> all = new ArrayList<>();
        boolean replaced = false;
        String clean = Hdhr.clean(host);
        for (Prefs.Source s : Prefs.sources(ctx)) {
            if (s.host.equalsIgnoreCase(clean)) {
                all.add(new Prefs.Source(clean, xmltvUrl, s.friendly));
                replaced = true;
            } else {
                all.add(s);
            }
        }
        if (!replaced) all.add(new Prefs.Source(clean, xmltvUrl, ""));
        return sync(ctx, all);
    }

    static String streamUrl(Context ctx, long channelId) {
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelUri(channelId),
                new String[]{TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                byte[] data = c.getBlob(0);
                if (data != null) return new String(data, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Deletes every Live TV channel (and its programmes) that belongs to {@code host}.
     * Matches {@link #providerId} prefix and stream URLs that contain the host.
     *
     * @return number of channel rows removed
     */
    static int removeHost(Context ctx, String host) {
        String clean = Hdhr.clean(host);
        if (clean.isEmpty()) return 0;
        String prefix = clean.toLowerCase(Locale.US) + "|";
        String needle = clean.toLowerCase(Locale.US);
        String inputId = inputId(ctx);
        List<Long> drop = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelsUriForInput(inputId),
                new String[]{
                        TvContract.Channels._ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String provider = c.getString(1);
                    if (provider != null && provider.toLowerCase(Locale.US).startsWith(prefix)) {
                        drop.add(c.getLong(0));
                        continue;
                    }
                    byte[] data = c.getBlob(2);
                    if (data != null) {
                        String url = new String(data, StandardCharsets.UTF_8).toLowerCase(Locale.US);
                        if (url.contains(needle)) drop.add(c.getLong(0));
                    }
                }
            }
        }
        return deleteChannels(ctx, drop);
    }

    /** Deletes every channel published by this input (used when the last playlist is removed). */
    static int clearAll(Context ctx) {
        String inputId = inputId(ctx);
        List<Long> drop = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels._ID},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) drop.add(c.getLong(0));
            }
        }
        return deleteChannels(ctx, drop);
    }

    private static int deleteChannels(Context ctx, List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            if (id == null || id < 0) continue;
            try {
                ctx.getContentResolver().delete(TvContract.buildProgramsUriForChannel(id), null, null);
            } catch (Exception e) {
                Log.w(TAG, "Programme delete failed for " + id + ": " + e.getMessage());
            }
            try {
                int deleted = ctx.getContentResolver().delete(TvContract.buildChannelUri(id), null, null);
                if (deleted > 0) n++;
            } catch (Exception e) {
                Log.w(TAG, "Channel delete failed for " + id + ": " + e.getMessage());
            }
        }
        if (n > 0) Log.i(TAG, "Removed " + n + " channel(s) from Live TV");
        return n;
    }

    static String key(Hdhr.Channel ch) {
        String id = ch.name == null ? "" : ch.name.trim().toLowerCase(Locale.US);
        if (id.isEmpty() && ch.number != null) id = ch.number.trim().toLowerCase(Locale.US);
        return id;
    }

    /** Stable id so re-sync updates the same TvContract row (keeps browsable). */
    static String providerId(String host, Hdhr.Channel ch) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.US);
        String num = ch.number == null ? "" : ch.number.trim().toLowerCase(Locale.US);
        return h + "|" + num + "|" + key(ch);
    }

    static Map<String, Long> existingByProviderId(Context ctx, String inputId) {
        Map<String, Long> ids = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            if (c == null) return ids;
            while (c.moveToNext()) {
                String provider = c.getString(1);
                if (provider != null && !provider.isEmpty()) {
                    ids.put(provider, c.getLong(0));
                }
            }
        }
        return ids;
    }

    /** Number|name → all channel ids (including rows that already have a provider id). */
    static Map<String, List<Long>> allIdsByLabel(Context ctx, String inputId) {
        Map<String, List<Long>> ids = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels._ID,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                        TvContract.Channels.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c == null) return ids;
            while (c.moveToNext()) {
                String label = labelKey(new Hdhr.Channel(c.getString(1), c.getString(2), ""));
                List<Long> list = ids.get(label);
                if (list == null) {
                    list = new ArrayList<>();
                    ids.put(label, list);
                }
                list.add(c.getLong(0));
            }
        }
        return ids;
    }

    static String labelKey(Hdhr.Channel ch) {
        String num = ch.number == null ? "" : ch.number.trim().toLowerCase(Locale.US);
        return num + "|" + key(ch);
    }

    /** Existing channel keys for this input (for XMLTV-only refresh). */
    static Map<String, Long> existingIds(Context ctx) {
        String inputId = inputId(ctx);
        Map<String, Long> ids = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(
                TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER},
                null, null, null)) {
            if (c == null) return ids;
            while (c.moveToNext()) {
                String name = c.getString(1);
                String number = c.getString(2);
                Hdhr.Channel fake = new Hdhr.Channel(number, name, "");
                ids.put(key(fake), c.getLong(0));
            }
        }
        return ids;
    }

}
