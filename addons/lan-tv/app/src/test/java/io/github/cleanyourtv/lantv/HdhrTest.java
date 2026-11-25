package io.github.cleanyourtv.lantv;

import org.json.JSONArray;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HdhrTest {
    @Test
    public void discoverRequestHasValidCrc() {
        byte[] p = Hdhr.request();
        assertTrue(p.length > 8);
        CRC32 crc = new CRC32();
        crc.update(p, 0, p.length - 4);
        long v = crc.getValue();
        assertEquals((byte) v, p[p.length - 4]);
        assertEquals((byte) (v >> 8), p[p.length - 3]);
        assertEquals((byte) (v >> 16), p[p.length - 2]);
        assertEquals((byte) (v >> 24), p[p.length - 1]);
    }

    @Test
    public void lineupSkipsDrmAndKeepsHttp() throws Exception {
        JSONArray arr = new JSONArray("["
                + "{\"GuideNumber\":\"1\",\"GuideName\":\"One\",\"URL\":\"http://x/1\",\"DRM\":0},"
                + "{\"GuideNumber\":\"2\",\"GuideName\":\"Two\",\"URL\":\"http://x/2\",\"DRM\":1},"
                + "{\"GuideNumber\":\"3\",\"GuideName\":\"Three\",\"URL\":\"ftp://x/3\"}"
                + "]");
        List<Hdhr.Channel> ch = Hdhr.parseLineup(arr);
        assertEquals(1, ch.size());
        assertEquals("One", ch.get(0).name);
        assertEquals("1", ch.get(0).number);
    }

    @Test
    public void lineupSortsByGuideNumber() throws Exception {
        JSONArray arr = new JSONArray("["
                + "{\"GuideNumber\":\"10\",\"GuideName\":\"Ten\",\"URL\":\"http://x/10\"},"
                + "{\"GuideNumber\":\"2\",\"GuideName\":\"Two\",\"URL\":\"http://x/2\"},"
                + "{\"GuideNumber\":\"1\",\"GuideName\":\"One\",\"URL\":\"http://x/1\"}"
                + "]");
        List<Hdhr.Channel> ch = Hdhr.parseLineup(arr);
        assertEquals("1", ch.get(0).number);
        assertEquals("2", ch.get(1).number);
        assertEquals("10", ch.get(2).number);
    }

    @Test
    public void cleanStripsSchemeAndPath() {
        assertEquals("192.168.1.10:8409", Hdhr.clean("http://192.168.1.10:8409/lineup.json"));
        assertEquals("ersatztv.local", Hdhr.clean("ersatztv.local"));
        assertFalse(Hdhr.clean("").contains("/"));
    }

    @Test
    public void isHlsDetectsPlaylistUrlsOnly() {
        assertTrue(Hdhr.isHls("http://x/iptv/channel/2.m3u8?mode=segmenter"));
        assertFalse(Hdhr.isHls("http://x/iptv/hdhr/channel/2.ts"));
        assertFalse(Hdhr.isHls("http://x/auto/v3"));
    }

    @Test
    public void parseIptvM3uReadsTvgChno() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:0 tvg-chno=\"1\" channel-number=\"1\" tvg-name=\"QTv\", QTv\n"
                + "http://x/iptv/channel/1.m3u8?mode=segmenter\n"
                + "#EXTINF:0 tvg-chno=\"2\" channel-number=\"2\" tvg-name=\"AS\", AS\n"
                + "http://x/iptv/channel/2.m3u8?mode=segmenter\n";
        Map<String, String> by = Hdhr.parseIptvM3uUrlsByNumber(m3u);
        assertEquals(2, by.size());
        assertEquals("http://x/iptv/channel/1.m3u8?mode=segmenter", by.get("1"));
        assertEquals("http://x/iptv/channel/2.m3u8?mode=segmenter", by.get("2"));
    }

    @Test
    public void lineupKeepsDecimalGuideNumbers() throws Exception {
        JSONArray arr = new JSONArray("["
                + "{\"GuideNumber\":\"5.1\",\"GuideName\":\"WRAL HD\","
                + "\"URL\":\"http://192.168.0.139:5004/auto/v5.1\"},"
                + "{\"GuideNumber\":\"117.1\",\"GuideName\":\"DRM\","
                + "\"URL\":\"http://192.168.0.139:5004/auto/v117.1\",\"DRM\":1}"
                + "]");
        List<Hdhr.Channel> ch = Hdhr.parseLineup(arr);
        assertEquals(1, ch.size());
        assertEquals("5.1", ch.get(0).number);
        assertEquals("http://192.168.0.139:5004/auto/v5.1", ch.get(0).url);
    }

    @Test
    public void joinDeviceAuthsConcatenatesInOrder() {
        List<String> auths = new ArrayList<>();
        auths.add("AAA");
        auths.add(" ");
        auths.add("BBB");
        assertEquals("AAABBB", Hdhr.joinDeviceAuths(auths));
    }

    @Test
    public void siliconDustXmltvUrlEmbedsJoinedAuth() throws Exception {
        List<String> auths = new ArrayList<>();
        auths.add("auth1");
        auths.add("auth2");
        String url = Hdhr.siliconDustXmltvUrl(auths);
        assertTrue(url.startsWith(Hdhr.SILICONDUST_XMLTV + "?DeviceAuth="));
        assertTrue(url.contains("auth1auth2"));
    }

    @Test
    public void applyM3uUrlsSwapsMatchingGuideNumbers() {
        List<Hdhr.Channel> ch = new ArrayList<>();
        ch.add(new Hdhr.Channel("1", "QTv", "http://x/iptv/hdhr/channel/1.ts"));
        ch.add(new Hdhr.Channel("2", "AS", "http://x/iptv/hdhr/channel/2.ts"));
        ch.add(new Hdhr.Channel("99", "Other", "http://x/iptv/hdhr/channel/99.ts"));
        Map<String, String> m3u = Hdhr.parseIptvM3uUrlsByNumber(
                "#EXTM3U\n"
                        + "#EXTINF:0 tvg-chno=\"1\", QTv\n"
                        + "http://x/iptv/channel/1.m3u8?mode=segmenter\n"
                        + "#EXTINF:0 tvg-chno=\"2\", AS\n"
                        + "http://x/iptv/channel/2.m3u8?mode=segmenter\n");
        assertEquals(2, Hdhr.applyM3uUrls(ch, m3u));
        assertTrue(Hdhr.isHls(ch.get(0).url));
        assertTrue(Hdhr.isHls(ch.get(1).url));
        assertFalse(Hdhr.isHls(ch.get(2).url));
    }
}
