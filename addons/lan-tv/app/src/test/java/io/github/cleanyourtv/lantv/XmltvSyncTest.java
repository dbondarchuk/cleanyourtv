package io.github.cleanyourtv.lantv;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class XmltvSyncTest {
    @Test
    public void parseMatchesDisplayName() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tv>"
                + "<channel id=\"ch1\"><display-name>News</display-name></channel>"
                + "<programme start=\"20261112120000 +0000\" stop=\"20261112130000 +0000\" channel=\"ch1\">"
                + "<title>Noon</title><desc>Day news</desc></programme>"
                + "</tv>";
        Map<String, String> keys = new HashMap<>();
        keys.put("news", "news");
        List<XmltvSync.Programme> out = XmltvSync.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), keys);
        assertEquals(1, out.size());
        assertEquals("news", out.get(0).channelKey);
        assertEquals("Noon", out.get(0).title);
        assertTrue(out.get(0).endMs > out.get(0).startMs);
    }

    @Test
    public void parseXmltvTimeUtc() {
        long t = XmltvSync.parseXmltvTime("20260101120000 +0000");
        assertTrue(t > 0);
    }

    @Test
    public void stripLeadingNumber() {
        assertEquals("QTv", XmltvSync.stripLeadingNumber("1 QTv"));
        assertEquals("Movies: Action", XmltvSync.stripLeadingNumber("10 Movies: Action"));
        assertEquals(null, XmltvSync.stripLeadingNumber("News"));
    }

    @Test
    public void numberFromXmlId() {
        assertEquals("1", XmltvSync.numberFromXmlId("C1.145.ersatztv.org"));
        assertEquals("16", XmltvSync.numberFromXmlId("C16.199.ersatztv.org"));
    }

    @Test
    public void parseXmltvNsIsOneBased() {
        int[] se = XmltvSync.parseXmltvNs("7.0.0/1");
        assertNotNull(se);
        assertEquals(8, se[0]);
        assertEquals(1, se[1]);
    }

    @Test
    public void parseOnscreen() {
        int[] se = XmltvSync.parseOnscreen("S08E01");
        assertNotNull(se);
        assertEquals(8, se[0]);
        assertEquals(1, se[1]);
    }

    @Test
    public void parseEpisodeMetadata() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tv>"
                + "<channel id=\"C1.145.ersatztv.org\">"
                + "<display-name>1 QTv</display-name>"
                + "<display-name>1</display-name>"
                + "<display-name>QTv</display-name>"
                + "</channel>"
                + "<programme start=\"20261112120000 +0000\" stop=\"20261112130000 +0000\" "
                + "channel=\"C1.145.ersatztv.org\">"
                + "<title>Futurama</title>"
                + "<sub-title>The Impossible Stream</sub-title>"
                + "<episode-num system=\"onscreen\">S08E01</episode-num>"
                + "<episode-num system=\"xmltv_ns\">7.0.0/1</episode-num>"
                + "</programme>"
                + "</tv>";
        Map<String, String> keys = new HashMap<>();
        keys.put("qtv", "qtv");
        keys.put("1", "qtv");
        List<XmltvSync.Programme> out = XmltvSync.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), keys);
        assertEquals(1, out.size());
        assertEquals("Futurama", out.get(0).title);
        assertEquals("The Impossible Stream", out.get(0).episodeTitle);
        assertEquals(Integer.valueOf(8), out.get(0).season);
        assertEquals(Integer.valueOf(1), out.get(0).episode);
    }
}
