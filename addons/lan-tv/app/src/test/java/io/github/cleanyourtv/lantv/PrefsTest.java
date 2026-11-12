package io.github.cleanyourtv.lantv;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PrefsTest {
    @Test
    public void sourceJsonRoundTrip() throws Exception {
        Prefs.Source s = new Prefs.Source("http://192.168.1.10:8409/x",
                "http://192.168.1.10:8409/iptv/xmltv.xml", "ErsatzTV");
        assertEquals("192.168.1.10:8409", s.host);
        JSONObject o = s.toJson();
        Prefs.Source back = Prefs.Source.fromJson(o);
        assertEquals(s.host, back.host);
        assertEquals(s.xmltv, back.xmltv);
        assertEquals(s.friendly, back.friendly);
        assertTrue(back.label().contains("ErsatzTV"));
        assertTrue(back.detail().contains("xmltv"));
    }
}
