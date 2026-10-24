package dev.lyricscord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Spotify login using the official "Authorization Code with PKCE" flow.
 * No client secret is needed. The browser is redirected to a tiny local server
 * on 127.0.0.1 so the mod can receive the login code.
 */
public final class SpotifyAuth {
    private SpotifyAuth() {}

    static final int PORT = 8898;
    static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPE = "user-read-currently-playing user-read-playback-state";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Tokens {
        String accessToken;
        String refreshToken;
        long expiresAtMs;
    }

    private static Tokens tokens;
    private static volatile boolean loginRunning;

    private static Path tokenPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("lyricscord_tokens.json");
    }

    private static synchronized Tokens tokens() {
        if (tokens == null) {
            try {
                Path p = tokenPath();
                if (Files.exists(p)) tokens = GSON.fromJson(Files.readString(p), Tokens.class);
            } catch (Exception e) {
                System.err.println("[Lyricscord] Could not read tokens: " + e);
            }
        }
        return tokens;
    }

    private static synchronized void saveTokens() {
        try {
            if (tokens == null) Files.deleteIfExists(tokenPath());
            else Files.writeString(tokenPath(), GSON.toJson(tokens));
        } catch (Exception e) {
            System.err.println("[Lyricscord] Could not save tokens: " + e);
        }
    }

    public static boolean loggedIn() {
        Tokens t = tokens();
        return t != null && t.refreshToken != null;
    }

    public static synchronized void logout() {
        tokens = null;
        saveTokens();
    }

    /** Returns a valid access token (refreshing if needed), or null if not logged in. */
    public static synchronized String accessToken() throws Exception {
        Tokens t = tokens();
        if (t == null || t.refreshToken == null) return null;
        if (t.accessToken == null || System.currentTimeMillis() > t.expiresAtMs - 60_000) {
            refresh(t);
        }
        return t.accessToken;
    }

    public static synchronized void invalidateAccessToken() {
        Tokens t = tokens();
        if (t != null) t.accessToken = null;
    }

    private static void refresh(Tokens t) throws Exception {
        String cid = Config.get().clientId;
        try {
            JsonObject o = tokenRequest(form("grant_type", "refresh_token",
                    "refresh_token", t.refreshToken, "client_id", cid));
            apply(t, o);
        } catch (InvalidGrant e) {
            tokens = null;
            saveTokens();
            throw new IllegalStateException("Spotify login expired. Run /lyricscord login again.");
        }
    }

    private static void apply(Tokens t, JsonObject o) {
        t.accessToken = o.get("access_token").getAsString();
        long expiresIn = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600;
        t.expiresAtMs = System.currentTimeMillis() + expiresIn * 1000;
        if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) {
            t.refreshToken = o.get("refresh_token").getAsString();
        }
        saveTokens();
    }

    private static final class InvalidGrant extends Exception {}

    private static JsonObject tokenRequest(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) return JsonParser.parseString(r.body()).getAsJsonObject();
        if (r.statusCode() == 400 && r.body().contains("invalid_grant")) throw new InvalidGrant();
        throw new IllegalStateException("Spotify token error " + r.statusCode() + ": " + r.body());
    }

    // ------------------------------------------------------------------ login

    public static void login() {
        String cid = Config.get().clientId;
        if (cid == null || cid.isBlank()) {
            Chat.say("First set your Spotify client ID: /lyricscord setup <clientId>");
            return;
        }
        if (loginRunning) {
            Chat.say("A login is already in progress. Check your browser.");
            return;
        }
        loginRunning = true;
        SpotifyService.EXEC.execute(() -> {
            try {
                doLogin(cid);
            } catch (Exception e) {
                Chat.say("Login failed: " + e.getMessage());
            } finally {
                loginRunning = false;
            }
        });
    }

    private static void doLogin(String cid) throws Exception {
        String verifier = randomUrlSafe(64);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlSafe(16);

        String url = "https://accounts.spotify.com/authorize?" + query(
                "client_id", cid,
                "response_type", "code",
                "redirect_uri", REDIRECT,
                "scope", SCOPE,
                "state", state,
                "code_challenge_method", "S256",
                "code_challenge", challenge);

        String code = null;
        try (ServerSocket server = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(180_000);
            openBrowser(url);
            Chat.say("Opened the Spotify login page in your browser. Approve it to finish (3 minutes).");
            while (code == null) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = in.readLine();
                    if (requestLine == null) continue;
                    String[] parts = requestLine.split(" ");
                    String target = parts.length > 1 ? parts[1] : "/";
                    if (!target.startsWith("/callback")) {
                        respond(s, 404, "Not found");
                        continue;
                    }
                    Map<String, String> q = parseQuery(target);
                    if (q.containsKey("error")) {
                        respond(s, 400, "Spotify login was cancelled. You can close this tab.");
                        throw new IllegalStateException("Spotify said: " + q.get("error"));
                    }
                    if (!state.equals(q.get("state")) || q.get("code") == null) {
                        respond(s, 400, "Invalid login response. You can close this tab.");
                        throw new IllegalStateException("Invalid response from browser.");
                    }
                    code = q.get("code");
                    respond(s, 200, "Lyricscord is connected to Spotify. You can close this tab and go back to Minecraft.");
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException("Timed out waiting for the browser login.");
        }

        JsonObject o = tokenRequest(form(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT,
                "client_id", cid,
                "code_verifier", verifier));
        synchronized (SpotifyAuth.class) {
            Tokens t = new Tokens();
            apply(t, o);
            tokens = t;
            saveTokens();
        }
        Chat.say("Connected to Spotify! Play a song and it will appear at the top of your screen.");
    }

    private static void respond(Socket s, int status, String message) throws Exception {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Lyricscord</title></head>"
                + "<body style=\"font-family:sans-serif;background:#111;color:#eee;display:flex;align-items:center;"
                + "justify-content:center;height:100vh;margin:0\"><h2>" + message + "</h2></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " X\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            else if (os.contains("mac")) pb = new ProcessBuilder("open", url);
            else pb = new ProcessBuilder("xdg-open", url);
            pb.start();
        } catch (Exception e) {
            Chat.say("Could not open your browser automatically. The login link is in the game log (logs/latest.log).");
            System.out.println("[Lyricscord] Open this URL to log in: " + url);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return s.length() > bytes ? s.substring(0, bytes) : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String form(String... kv) { return query(kv); }

    private static String query(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String target) {
        Map<String, String> map = new HashMap<>();
        int qi = target.indexOf('?');
        if (qi < 0) return map;
        for (String pair : target.substring(qi + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return map;
    }
}
