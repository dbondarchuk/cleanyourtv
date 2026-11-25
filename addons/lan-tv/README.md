# LAN TV

Feeds an [ErsatzTV](https://ersatztv.org/) server (or any HDHomeRun-compatible lineup on your LAN)
into the TV's own **Live TV** app: channels and a free XMLTV guide, with no Tvirl subscription.

A CleanYourTV add-on: install it, open the **LAN TV** card (or the app from the app list), add one or
more playlists, then **Sync now**. Watching stays in Sony Live TV / Live Channels.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | LAN discover/lineup/streams; optional SiliconDust `api.hdhomerun.com` XMLTV when EPG URL is empty |
| `WAKE_LOCK` | Keep the stream awake while Live TV is tuned (ExoPlayer network wake mode) |
| `RECEIVE_BOOT_COMPLETED` | Refresh channels after a reboot, and keep the 12-hour EPG job registered |
| `com.android.providers.tv.permission.READ_EPG_DATA` | Read programme rows already in the system TV provider |
| `com.android.providers.tv.permission.WRITE_EPG_DATA` | Publish channels and the XMLTV guide into Live TV |

No ads, no accounts, no telemetry. Only use streams and guides you are allowed to watch.

## Set up

1. Build and install (below).
2. On the TV, open **LAN TV**.
3. Press **Add playlist** to scan the LAN or type a host (and optional EPG URL), then save.
   OK on a saved row opens **Edit** (host/EPG, or **Remove** which also deletes that
   playlist’s channels and guide rows from Live TV).
4. Press **Sync now**. Channels are ordered by guide number; the guide gets season, episode and
   episode title from XMLTV when present. Sync also starts a **12-hour background refresh**
   (`EpgSyncJob.setUpPeriodicSync`).
5. Press **Channel settings** (Live Channels / the TV's inputs UI) and enable the LAN TV rows
   (apps cannot set browsable). Then open Live TV and pick a channel.

Auto-discovery tries SiliconDust HDHomeRun UDP on port 65001, then an HTTP scan of the LAN `/24`
on ports `8409`, `5004` and `80` for `/discover.json` (ErsatzTV often skips UDP). The app allows
cleartext `http://` on the LAN so local ErsatzTV/HDHomeRun servers work without HTTPS.

Discover uses SiliconDust UDP on 65001, then an HTTP sweep of the LAN `/24` (port **80** first for
real HDHomeRun, then `8409` / `5004` for ErsatzTV). Typing an IP before **Scan LAN** probes that
host first. Save checks `discover.json` + `lineup.json` so a bad host fails immediately.

When the host also serves `/iptv/channels.m3u` (ErsatzTV), sync replaces matching stream URLs with
those HLS segmenter links (same as Tvirl/VLC). Real HDHomeRun boxes keep lineup URLs such as
`http://tuner:5004/auto/v5.1`. DRM-flagged ATSC 3.0 rows are skipped. ExoPlayer picks HLS vs
progressive MPEG-TS from the URI. If one playlist fails while another succeeds, Sync still finishes
and lists the failure on screen.

**EPG:** leave the XMLTV field empty for a SiliconDust tuner — each Sync (and the 12-hour job)
re-reads `DeviceAuth` from `discover.json`, concatenates auth for every such playlist, and fetches
`https://api.hdhomerun.com/api/xmltv` (same approach as
[hdhomerun-xmltv](https://github.com/dbondarchuk/hdhomerun-xmltv)). DeviceAuth is never stored.
ErsatzTV still uses its local `/iptv/xmltv.xml` when present.

On some Sonys, third-party TV inputs drop out of the system list after a reboot until the package
is touched again. Boot / Setup / the input service call `TvInputManager.updateTvInputInfo` (as
Tvirl does) so Live TV can bind and ErsatzTV sees stream requests.

## Build

```bash
cd addons/lan-tv && ./gradlew assembleRelease
```

Install from the repo root:

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py --push addons/lan-tv/app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

## Notes

- This is a `TvInputService`, like Tvirl: it does not replace CleanYourTV's own player (see
  [TV Hub](../tv-hub/) if you want a guide inside an add-on UI instead).
- Held volume behaviour is unchanged; this add-on does not bind an accessibility key helper.
- If Live TV shows no channels after sync, open **Channel settings**, enable the LAN TV rows, then
  check `dumpsys tv_input` for `io.github.cleanyourtv.lantv`.
