package io.github.cleanyourtv.lantv;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * HDHomeRun-compatible LAN tuners (including ErsatzTV's HDHR emulation): UDP discover on 65001,
 * plus an HTTP probe of common ports (ErsatzTV often skips UDP), then discover.json / lineup.json.
 */
final class Hdhr {
    private static final String TAG = "LanTv.Hdhr";
    /** Ports ErsatzTV / HDHomeRun commonly use for discover.json. */
    private static final int[] HTTP_PORTS = {8409, 5004, 80};

    static final class Device {
        final String host;
        final String name;
        final String model;
        final String lineupUrl;

        Device(String host, String name, String model, String lineupUrl) {
            this.host = host;
            this.name = name;
            this.model = model;
            this.lineupUrl = lineupUrl;
        }
    }

    static final class Channel {
        final String number;
        final String name;
        final String url;

        Channel(String number, String name, String url) {
            this.number = number;
            this.name = name;
            this.url = url;
        }
    }

    private Hdhr() {
    }

    /** UDP broadcast, then HTTP probe of the LAN /24 for ErsatzTV-style discover.json. */
    static List<Device> discover(int timeoutMs) {
        LinkedHashMap<String, Device> byHost = new LinkedHashMap<>();

        LinkedHashSet<String> udpHosts = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] req = request();
            long deadline = System.currentTimeMillis() + Math.min(timeoutMs, 2500);
            long nextSend = 0L;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    try {
                        s.send(new DatagramPacket(req, req.length, InetAddress.getByName("255.255.255.255"), 65001));
                    } catch (IOException ignored) {
                    }
                    nextSend = System.currentTimeMillis() + 800;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    if (p.getLength() >= 4 && buf[0] == 0 && buf[1] == 3) {
                        String addr = p.getAddress().getHostAddress();
                        if (addr != null) udpHosts.add(addr);
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        for (String h : udpHosts) {
            try {
                Device d = info(h);
                byHost.put(d.host, d);
            } catch (IOException ignored) {
            }
        }

        // ErsatzTV (and some soft HDHR servers) do not answer UDP 65001; probe HTTP on the /24.
        for (Device d : httpScan(timeoutMs)) {
            byHost.putIfAbsent(d.host, d);
        }
        return new ArrayList<>(byHost.values());
    }

    /** Parallel HTTP GET of http://x.x.x.n:port/discover.json on each LAN /24 address. */
    static List<Device> httpScan(int timeoutMs) {
        List<String> prefixes = ipv4Prefixes();
        if (prefixes.isEmpty()) return Collections.emptyList();

        Map<String, Device> found = Collections.synchronizedMap(new LinkedHashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(32);
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 4000);
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                for (int port : HTTP_PORTS) {
                    pool.execute(() -> {
                        if (System.currentTimeMillis() > deadline) return;
                        String hostPort = port == 80 ? ip : ip + ":" + port;
                        try {
                            Device d = infoQuick(hostPort, 400);
                            found.putIfAbsent(d.host, d);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        }
        pool.shutdown();
        try {
            long left = deadline - System.currentTimeMillis();
            pool.awaitTermination(Math.max(left, 500), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        return new ArrayList<>(found.values());
    }

    static Device info(String hostOrUrl) throws IOException {
        return infoQuick(hostOrUrl, 8000);
    }

    static Device infoQuick(String hostOrUrl, int connectTimeoutMs) throws IOException {
        String host = clean(hostOrUrl);
        if (host.isEmpty()) throw new IOException("Enter a host or IP");
        try {
            JSONObject o = new JSONObject(Http.text(base(host) + "/discover.json", 64 * 1024, connectTimeoutMs, 4000));
            // Prefer the host:port the user/scanner used; LineupURL may point at a hostname the TV cannot resolve.
            String lineup = base(host) + "/lineup.json";
            String remote = o.optString("LineupURL");
            if (remote.startsWith("http")) {
                // Keep remote only when it already targets this IP (or no port clash).
                if (remote.contains(host.split(":")[0])) lineup = remote;
            }
            return new Device(
                    host,
                    o.optString("FriendlyName", "HDHomeRun"),
                    o.optString("ModelNumber"),
                    lineup);
        } catch (JSONException e) {
            throw new IOException("discover.json was not valid JSON", e);
        }
    }

    static List<Channel> lineup(Device device) throws IOException {
        try {
            JSONArray arr = new JSONArray(Http.text(device.lineupUrl, 8L * 1024 * 1024));
            List<Channel> channels = parseLineup(arr);
            // ErsatzTV's HDHR lineup points at continuous .ts; Tvirl/VLC users paste
            // /iptv/channels.m3u which already has HLS segmenter URLs. Prefer those so the TV
            // pulls short segments instead of pinning ffmpeg on a progressive pipe.
            int swapped = applyIptvM3uUrls(device.host, channels);
            if (swapped > 0) {
                Log.i(TAG, "Using IPTV M3U HLS for " + swapped + "/" + channels.size()
                        + " channels on " + device.host);
            }
            return channels;
        } catch (JSONException e) {
            throw new IOException("lineup.json was not valid JSON", e);
        }
    }

    static List<Channel> parseLineup(JSONArray arr) {
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("URL");
            if (!url.startsWith("http") || o.optInt("DRM") == 1) continue;
            String number = o.optString("GuideNumber");
            String name = o.optString("GuideName");
            if (name.isEmpty()) name = number;
            if (name.isEmpty()) name = "Channel " + (i + 1);
            out.add(new Channel(number, name, url));
        }
        sortByNumber(out);
        return out;
    }

    /**
     * If {@code http://host/iptv/channels.m3u} answers, replace matching channel URLs with the
     * M3U entries (matched by {@code tvg-chno} / {@code channel-number}).
     *
     * @return how many channel URLs were replaced
     */
    static int applyIptvM3uUrls(String host, List<Channel> channels) {
        if (channels == null || channels.isEmpty() || host == null || host.isEmpty()) return 0;
        String m3u;
        try {
            m3u = Http.text(base(host) + "/iptv/channels.m3u", 2L * 1024 * 1024, 4000, 8000);
        } catch (IOException e) {
            return 0; // real HDHomeRun / servers without IPTV M3U — keep lineup.json URLs
        }
        return applyM3uUrls(channels, parseIptvM3uUrlsByNumber(m3u));
    }

    /** Replace channel URLs whose guide number appears in {@code byNumber}. */
    static int applyM3uUrls(List<Channel> channels, Map<String, String> byNumber) {
        if (channels == null || channels.isEmpty() || byNumber == null || byNumber.isEmpty()) {
            return 0;
        }
        int swapped = 0;
        for (int i = 0; i < channels.size(); i++) {
            Channel ch = channels.get(i);
            String num = ch.number == null ? "" : ch.number.trim();
            String hls = byNumber.get(num);
            if (hls != null && !hls.isEmpty()) {
                channels.set(i, new Channel(ch.number, ch.name, hls));
                swapped++;
            }
        }
        return swapped;
    }

    /** EXTINF attrs → next URL; keys are channel numbers from {@code tvg-chno} / {@code channel-number}. */
    static Map<String, String> parseIptvM3uUrlsByNumber(String m3u) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m3u == null || m3u.isEmpty()) return out;
        String pending = null;
        for (String raw : m3u.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                pending = m3uChannelNumber(line);
                continue;
            }
            if (line.startsWith("#")) continue;
            if (pending != null && line.startsWith("http")) {
                out.put(pending, line);
                pending = null;
            }
        }
        return out;
    }

    static String m3uChannelNumber(String extinf) {
        // tvg-chno="2" or channel-number="2"
        for (String key : new String[]{"tvg-chno", "channel-number", "tvg-num"}) {
            String needle = key + "=\"";
            int i = extinf.indexOf(needle);
            if (i < 0) {
                needle = key + "=";
                i = extinf.indexOf(needle);
                if (i < 0) continue;
                int start = i + needle.length();
                int end = start;
                while (end < extinf.length() && !Character.isWhitespace(extinf.charAt(end))
                        && extinf.charAt(end) != ',') {
                    end++;
                }
                String v = extinf.substring(start, end).replace("\"", "").trim();
                if (!v.isEmpty()) return v;
                continue;
            }
            int start = i + needle.length();
            int end = extinf.indexOf('"', start);
            if (end > start) return extinf.substring(start, end).trim();
        }
        return null;
    }

    /** Same idea as Tvirl's StreamTypeUtils: path ends with {@code .m3u8} (or contains it) → HLS. */
    static boolean isHls(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains(".m3u8") || u.contains("format=m3u8") || u.contains("/hls");
    }

    /** Sort in place by GuideNumber (1, 2, 10 — not string order). */
    static void sortByNumber(List<Channel> channels) {
        if (channels == null) return;
        channels.sort((a, b) -> compareGuideNumber(
                a == null ? null : a.number,
                b == null ? null : b.number));
    }

    static int compareGuideNumber(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        Double da = parseGuideNumber(left);
        Double db = parseGuideNumber(right);
        if (da != null && db != null) {
            int c = Double.compare(da, db);
            if (c != 0) return c;
        } else if (da != null) {
            return -1;
        } else if (db != null) {
            return 1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static Double parseGuideNumber(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Prefer an explicit XMLTV URL; otherwise try common ErsatzTV paths on the host. */
    static String resolveXmltv(String host, String configured) throws IOException {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String h = clean(host);
        String[] candidates = {
                base(h) + "/iptv/xmltv.xml",
                base(h) + "/xmltv.xml",
                base(h) + "/xmltv",
        };
        IOException last = null;
        for (String u : candidates) {
            try {
                // Peek only — ErsatzTV guides are often >256 KB; a full read here would throw
                // "Response too large" and skip a valid /iptv/xmltv.xml.
                String head = Http.peekText(u, 64 * 1024, 4000, 8000);
                if (head.contains("<tv") || head.contains("<?xml")) return u;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IOException("No XMLTV guide found; set an XMLTV URL");
    }

    static String clean(String h) {
        if (h == null) return "";
        String s = h.trim();
        if (s.startsWith("http://")) s = s.substring(7);
        else if (s.startsWith("https://")) s = s.substring(8);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    static String base(String host) {
        return "http://" + clean(host);
    }

    /** IPv4 /24 prefixes for interfaces that look like a LAN (not loopback). */
    static List<String> ipv4Prefixes() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    int last = ip.lastIndexOf('.');
                    if (last > 0) out.add(ip.substring(0, last + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Discover request packet (type 0x0002) with CRC32, same shape SiliconDust uses. */
    static byte[] request() {
        byte[] payload = new byte[]{1, 4, -1, -1, -1, -1, 2, 4, -1, -1, -1, -1};
        byte[] head = new byte[]{0, 2, 0, (byte) payload.length};
        byte[] body = new byte[head.length + payload.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(payload, 0, body, head.length, payload.length);
        CRC32 crc = new CRC32();
        crc.update(body);
        long v = crc.getValue();
        byte[] out = new byte[body.length + 4];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) v;
        out[body.length + 1] = (byte) (v >> 8);
        out[body.length + 2] = (byte) (v >> 16);
        out[body.length + 3] = (byte) (v >> 24);
        return out;
    }
}
