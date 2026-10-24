package dev.lyricscord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls Spotify's "currently playing" endpoint and keeps PlaybackState up to date. */
public final class SpotifyService {
    private SpotifyService() {}

    static final ScheduledExecutorService EXEC = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "lyricscord-worker");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static volatile long backoffUntilMs = 0;
    private static volatile String lastError = "";

    public static void start() {
        EXEC.scheduleWithFixedDelay(SpotifyService::pollSafely, 1000, 1500, TimeUnit.MILLISECONDS);
    }

    private static void pollSafely() {
        try {
            poll();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (!msg.equals(lastError)) {
                lastError = msg;
                System.err.println("[Lyricscord] " + msg);
            }
            PlaybackState.set(PlaybackState.Snapshot.idle("Spotify: " + shorten(msg)));
        }
    }

    private static void poll() throws Exception {
        if (System.currentTimeMillis() < backoffUntilMs) return;

        if (!SpotifyAuth.loggedIn()) {
            PlaybackState.set(PlaybackState.Snapshot.idle(Config.get().clientId.isBlank()
                    ? "Lyricscord: run /lyricscord setup <clientId>"
                    : "Lyricscord: run /lyricscord login"));
            return;
        }

        String token = SpotifyAuth.accessToken();
        if (token == null) return;

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        int code = r.statusCode();

        if (code == 204) {
            PlaybackState.set(PlaybackState.Snapshot.idle("Nothing playing on Spotify"));
            return;
        }
        if (code == 401) {
            SpotifyAuth.invalidateAccessToken();
            return;
        }
        if (code == 429) {
            long wait = r.headers().firstValueAsLong("Retry-After").orElse(5);
            backoffUntilMs = System.currentTimeMillis() + wait * 1000;
            return;
        }
        if (code == 403) {
            PlaybackState.set(PlaybackState.Snapshot.idle("Spotify refused access (check your developer app)"));
            return;
        }
        if (code != 200) {
            PlaybackState.set(PlaybackState.Snapshot.idle("Spotify error " + code));
            return;
        }

        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        JsonElement itemEl = o.get("item");
        if (itemEl == null || itemEl.isJsonNull()) {
            PlaybackState.set(PlaybackState.Snapshot.idle("Nothing playing (ad or podcast)"));
            return;
        }
        JsonObject item = itemEl.getAsJsonObject();

        String id = str(item, "id");
        if (id.isEmpty()) id = str(item, "uri");
        String title = str(item, "name");
        String artist = "";
        JsonArray artists = item.has("artists") ? item.getAsJsonArray("artists") : new JsonArray();
        if (!artists.isEmpty()) artist = str(artists.get(0).getAsJsonObject(), "name");
        String album = item.has("album") && item.get("album").isJsonObject() ? str(item.getAsJsonObject("album"), "name") : "";
        long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
        long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
        boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();

        PlaybackState.Track track = new PlaybackState.Track(id, title, artist, album, duration);
        PlaybackState.Snapshot prev = PlaybackState.get();
        boolean sameTrack = prev.track() != null && prev.track().id().equals(id);

        List<PlaybackState.LrcLine> lyrics = sameTrack ? prev.lyrics() : List.of();
        PlaybackState.set(new PlaybackState.Snapshot(track, playing, progress, System.nanoTime(), lyrics, ""));

        if (!sameTrack) {
            final String trackId = id;
            EXEC.execute(() -> PlaybackState.attachLyrics(trackId, Lyrics.fetch(track)));
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
