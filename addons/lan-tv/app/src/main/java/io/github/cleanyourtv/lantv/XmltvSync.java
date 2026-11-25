package io.github.cleanyourtv.lantv;

import android.content.ContentValues;
import android.content.Context;
import android.media.tv.TvContract;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Streams XMLTV into TvContract.Programs for channels already in the provider. */
final class XmltvSync {
    private static final Pattern ONSCREEN_SE =
            Pattern.compile("(?i)s\\s*(\\d+)\\s*[ex]\\s*(\\d+)");

    static final class Programme {
        final String channelKey;
        final long startMs;
        final long endMs;
        final String title;
        final String desc;
        /** 1-based season for Live TV, or null. */
        final Integer season;
        /** 1-based episode for Live TV, or null. */
        final Integer episode;
        /** Episode name (XMLTV {@code sub-title}). */
        final String episodeTitle;

        Programme(String channelKey, long startMs, long endMs, String title, String desc,
                  Integer season, Integer episode, String episodeTitle) {
            this.channelKey = channelKey;
            this.startMs = startMs;
            this.endMs = endMs;
            this.title = title;
            this.desc = desc;
            this.season = season;
            this.episode = episode;
            this.episodeTitle = episodeTitle;
        }
    }

    private XmltvSync() {
    }

    static int apply(Context ctx, String xmltvUrl, Map<String, Long> channelIds,
                     List<Hdhr.Channel> lineup) throws IOException {
        return applyXml(ctx, Http.bytes(xmltvUrl, 32L * 1024 * 1024), channelIds, lineup);
    }

    /** Apply an already-fetched XMLTV document (e.g. SiliconDust cloud guide). */
    static int applyXml(Context ctx, byte[] raw, Map<String, Long> channelIds,
                        List<Hdhr.Channel> lineup) throws IOException {
        if (raw == null || raw.length == 0) throw new IOException("Empty XMLTV");
        Map<String, String> xmlIdToKey = new HashMap<>();
        for (Hdhr.Channel ch : lineup) {
            String key = ChannelSync.key(ch);
            if (ch.name != null && !ch.name.isEmpty()) {
                xmlIdToKey.put(norm(ch.name), key);
            }
            if (ch.number != null && !ch.number.isEmpty()) {
                xmlIdToKey.put(norm(ch.number), key);
            }
        }

        List<Programme> programmes = parse(new ByteArrayInputStream(raw), xmlIdToKey);

        for (Long id : channelIds.values()) {
            ctx.getContentResolver().delete(TvContract.buildProgramsUriForChannel(id), null, null);
        }

        int written = 0;
        long now = System.currentTimeMillis();
        long horizon = now + 7L * 24 * 60 * 60 * 1000;
        for (Programme p : programmes) {
            Long channelId = channelIds.get(p.channelKey);
            if (channelId == null) continue;
            if (p.endMs < now || p.startMs > horizon) continue;
            ContentValues v = new ContentValues();
            v.put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId);
            v.put(TvContract.Programs.COLUMN_TITLE,
                    p.title == null || p.title.isEmpty() ? "Programme" : p.title);
            if (p.desc != null && !p.desc.isEmpty()) {
                v.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, p.desc);
            }
            v.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, p.startMs);
            v.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, p.endMs);
            if (p.episodeTitle != null && !p.episodeTitle.isEmpty()) {
                v.put(TvContract.Programs.COLUMN_EPISODE_TITLE, p.episodeTitle);
            }
            if (p.season != null && p.season > 0) {
                v.put(TvContract.Programs.COLUMN_SEASON_NUMBER, p.season);
                v.put(TvContract.Programs.COLUMN_SEASON_DISPLAY_NUMBER, String.valueOf(p.season));
            }
            if (p.episode != null && p.episode > 0) {
                v.put(TvContract.Programs.COLUMN_EPISODE_NUMBER, p.episode);
                v.put(TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER, String.valueOf(p.episode));
            }
            if (ctx.getContentResolver().insert(TvContract.Programs.CONTENT_URI, v) != null) written++;
        }
        return written;
    }

    static List<Programme> parse(InputStream in, Map<String, String> xmlIdToKey) throws IOException {
        try {
            XmlPullParser xpp = newPullParser();
            xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xpp.setInput(in, StandardCharsets.UTF_8.name());

            Map<String, List<String>> channelNames = new HashMap<>();
            List<Programme> programmes = new ArrayList<>();

            String channelXmlId = null;
            String progChannel = null;
            long start = 0, end = 0;
            String title = null, desc = null, episodeTitle = null;
            Integer season = null, episode = null;
            Integer seasonNs = null, episodeNs = null;
            Integer seasonOn = null, episodeOn = null;
            boolean inChannel = false, inProgramme = false;
            String textTag = null;
            String episodeSystem = null;
            StringBuilder text = new StringBuilder();

            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = xpp.getName();
                    text.setLength(0);
                    textTag = null;
                    episodeSystem = null;
                    if ("channel".equals(name)) {
                        inChannel = true;
                        channelXmlId = xpp.getAttributeValue(null, "id");
                        if (channelXmlId != null) channelNames.put(channelXmlId, new ArrayList<>());
                    } else if (inChannel && "display-name".equals(name)) {
                        textTag = "display-name";
                    } else if ("programme".equals(name)) {
                        inProgramme = true;
                        progChannel = xpp.getAttributeValue(null, "channel");
                        start = parseXmltvTime(xpp.getAttributeValue(null, "start"));
                        end = parseXmltvTime(xpp.getAttributeValue(null, "stop"));
                        title = null;
                        desc = null;
                        episodeTitle = null;
                        season = null;
                        episode = null;
                        seasonNs = null;
                        episodeNs = null;
                        seasonOn = null;
                        episodeOn = null;
                    } else if (inProgramme && ("title".equals(name) || "desc".equals(name)
                            || "sub-title".equals(name))) {
                        textTag = name;
                    } else if (inProgramme && "episode-num".equals(name)) {
                        textTag = "episode-num";
                        episodeSystem = xpp.getAttributeValue(null, "system");
                    }
                } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                    if (textTag != null) {
                        String t = xpp.getText();
                        if (t != null) text.append(t);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String name = xpp.getName();
                    if (inChannel && "display-name".equals(name) && channelXmlId != null) {
                        List<String> names = channelNames.get(channelXmlId);
                        if (names != null) names.add(text.toString().trim());
                    } else if (inProgramme && "title".equals(name)) {
                        title = text.toString().trim();
                    } else if (inProgramme && "desc".equals(name)) {
                        desc = text.toString().trim();
                    } else if (inProgramme && "sub-title".equals(name)) {
                        episodeTitle = text.toString().trim();
                    } else if (inProgramme && "episode-num".equals(name)) {
                        int[] se = parseEpisodeNum(text.toString().trim(), episodeSystem);
                        if (se != null) {
                            if (episodeSystem != null
                                    && "xmltv_ns".equalsIgnoreCase(episodeSystem.trim())) {
                                seasonNs = se[0];
                                episodeNs = se[1];
                            } else {
                                seasonOn = se[0];
                                episodeOn = se[1];
                            }
                        }
                    } else if ("channel".equals(name)) {
                        inChannel = false;
                        channelXmlId = null;
                    } else if ("programme".equals(name)) {
                        String key = mapProgrammeChannel(progChannel, channelNames, xmlIdToKey);
                        if (key != null && start > 0 && end > start) {
                            season = seasonNs != null ? seasonNs : seasonOn;
                            episode = episodeNs != null ? episodeNs : episodeOn;
                            programmes.add(new Programme(key, start, end, title, desc,
                                    season, episode, episodeTitle));
                        }
                        inProgramme = false;
                    }
                    textTag = null;
                }
                event = xpp.next();
            }
            return programmes;
        } catch (Exception e) {
            throw new IOException("XMLTV parse failed: " + e.getMessage(), e);
        }
    }

    /** @return {@code [season, episode]} 1-based, or null. */
    static int[] parseEpisodeNum(String raw, String system) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.trim();
        if (system != null && "xmltv_ns".equalsIgnoreCase(system.trim())) {
            return parseXmltvNs(s);
        }
        int[] onscreen = parseOnscreen(s);
        if (onscreen != null) return onscreen;
        if (system == null || system.isEmpty()) {
            int[] ns = parseXmltvNs(s);
            if (ns != null) return ns;
        }
        return null;
    }

    /** XMLTV ns is 0-based: {@code season.episode.part[/parts]} → 1-based season/episode. */
    static int[] parseXmltvNs(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String core = raw.trim();
        int slash = core.indexOf('/');
        if (slash >= 0) core = core.substring(0, slash);
        String[] parts = core.split("\\.");
        if (parts.length < 2) return null;
        try {
            int season = Integer.parseInt(parts[0].trim()) + 1;
            int episode = Integer.parseInt(parts[1].trim()) + 1;
            if (season < 1 || episode < 1) return null;
            return new int[]{season, episode};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code S08E01}, {@code 8x01}, etc. */
    static int[] parseOnscreen(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        Matcher m = ONSCREEN_SE.matcher(raw.trim());
        if (!m.find()) return null;
        try {
            int season = Integer.parseInt(m.group(1));
            int episode = Integer.parseInt(m.group(2));
            if (season < 1 || episode < 1) return null;
            return new int[]{season, episode};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String mapProgrammeChannel(String xmlChannelId,
                                              Map<String, List<String>> channelNames,
                                              Map<String, String> xmlIdToKey) {
        if (xmlChannelId == null) return null;
        String direct = xmlIdToKey.get(norm(xmlChannelId));
        if (direct != null) return direct;
        List<String> names = channelNames.get(xmlChannelId);
        if (names != null) {
            for (String n : names) {
                String k = xmlIdToKey.get(norm(n));
                if (k != null) return k;
                String stripped = stripLeadingNumber(n);
                if (stripped != null) {
                    k = xmlIdToKey.get(norm(stripped));
                    if (k != null) return k;
                }
            }
        }
        String fromId = numberFromXmlId(xmlChannelId);
        if (fromId != null) {
            String k = xmlIdToKey.get(norm(fromId));
            if (k != null) return k;
        }
        return null;
    }

    static String stripLeadingNumber(String displayName) {
        if (displayName == null) return null;
        String s = displayName.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0 || i >= s.length()) return null;
        if (s.charAt(i) == '.') {
            i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        if (i >= s.length() || !Character.isWhitespace(s.charAt(i))) return null;
        String rest = s.substring(i).trim();
        return rest.isEmpty() ? null : rest;
    }

    static String numberFromXmlId(String xmlId) {
        if (xmlId == null) return null;
        String s = xmlId.trim();
        if (s.length() < 2) return null;
        if (s.charAt(0) == 'C' || s.charAt(0) == 'c') {
            int dot = s.indexOf('.');
            String num = dot > 1 ? s.substring(1, dot) : s.substring(1);
            if (!num.isEmpty() && Character.isDigit(num.charAt(0))) return num;
        }
        return null;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.US);
    }

    private static XmlPullParser newPullParser() throws Exception {
        try {
            return (XmlPullParser) Class.forName("org.kxml2.io.KXmlParser")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newPullParser();
        }
    }

    static long parseXmltvTime(String raw) {
        if (raw == null || raw.length() < 14) return 0;
        try {
            String core = raw.substring(0, 14);
            String rest = raw.length() > 14 ? raw.substring(14).trim() : "";
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            if (rest.startsWith("+") || rest.startsWith("-")) {
                String off = rest.replace(":", "");
                if (off.length() >= 5) {
                    fmt.setTimeZone(TimeZone.getTimeZone("GMT" + off.substring(0, 5)));
                } else {
                    fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                }
            } else {
                fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            }
            Date d = fmt.parse(core);
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }
}
