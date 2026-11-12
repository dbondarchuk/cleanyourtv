package io.github.cleanyourtv.lantv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small HTTP GET helper for lineup and XMLTV. */
final class Http {
    private Http() {
    }

    static String text(String url, long maxBytes) throws IOException {
        return text(url, maxBytes, 8000, 30000);
    }

    static String text(String url, long maxBytes, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return new String(bytes(url, maxBytes, connectTimeoutMs, readTimeoutMs), StandardCharsets.UTF_8);
    }

    static byte[] bytes(String url, long maxBytes) throws IOException {
        return bytes(url, maxBytes, 8000, 30000);
    }

    static byte[] bytes(String url, long maxBytes, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return read(url, maxBytes, connectTimeoutMs, readTimeoutMs, false);
    }

    /**
     * Read up to {@code maxBytes} and return what arrived. Does not fail when the body is larger —
     * used to sniff XMLTV / discover endpoints without downloading multi‑MB guides twice.
     */
    static String peekText(String url, int maxBytes, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        return new String(read(url, maxBytes, connectTimeoutMs, readTimeoutMs, true), StandardCharsets.UTF_8);
    }

    private static byte[] read(String url, long maxBytes, int connectTimeoutMs, int readTimeoutMs,
                               boolean truncate) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "LAN-TV/0.2");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    if (truncate) {
                        int keep = (int) (n - (total - maxBytes));
                        if (keep > 0) out.write(buf, 0, keep);
                        break;
                    }
                    throw new IOException("Response too large");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
