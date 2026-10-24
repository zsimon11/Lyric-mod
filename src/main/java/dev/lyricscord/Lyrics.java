package dev.lyricscord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches time-synced lyrics from LRCLIB (https://lrclib.net), a free open lyrics database. */
public final class Lyrics {
    private Lyrics() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    public static List<PlaybackState.LrcLine> fetch(PlaybackState.Track t) {
        try {
            String get = "https://lrclib.net/api/get?artist_name=" + enc(t.artist())
                    + "&track_name=" + enc(t.title())
                    + "&album_name=" + enc(t.album())
                    + "&duration=" + (t.durationMs() / 1000);
            HttpResponse<String> r = send(get);
            if (r.statusCode() == 200) {
                String synced = syncedOf(JsonParser.parseString(r.body()).getAsJsonObject());
                if (synced != null) return parse(synced);
            }
            String search = "https://lrclib.net/api/search?track_name=" + enc(t.title()) + "&artist_name=" + enc(t.artist());
            r = send(search);
            if (r.statusCode() == 200) {
                JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
                for (JsonElement e : arr) {
                    String synced = syncedOf(e.getAsJsonObject());
                    if (synced != null) return parse(synced);
                }
            }
        } catch (Exception e) {
            System.err.println("[Lyricscord] Lyrics lookup failed: " + e);
        }
        return List.of();
    }

    private static String syncedOf(JsonObject o) {
        JsonElement s = o.get("syncedLyrics");
        return (s == null || s.isJsonNull() || s.getAsString().isBlank()) ? null : s.getAsString();
    }

    private static HttpResponse<String> send(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Lyricscord/0.1 (Minecraft Fabric mod)")
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Parses LRC text like "[01:23.45] some words" into sorted lines. */
    static List<PlaybackState.LrcLine> parse(String lrc) {
        List<PlaybackState.LrcLine> out = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String frac = m.group(3);
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                times.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long time : times) out.add(new PlaybackState.LrcLine(time, text));
        }
        out.sort(Comparator.comparingLong(PlaybackState.LrcLine::timeMs));
        return out;
    }
}
