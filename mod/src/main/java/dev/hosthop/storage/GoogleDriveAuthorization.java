package dev.hosthop.storage;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Google desktop OAuth with PKCE and a user-selected Drive folder. */
public final class GoogleDriveAuthorization {
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private final String clientId, clientSecret;
    private final Exchange exchange;
    private final SecureRandom random = new SecureRandom();

    @FunctionalInterface interface Exchange { byte[] send(HttpRequest request) throws IOException, InterruptedException; }
    public static final class Tokens {
        private final String accessToken, refreshToken, folderId;
        private final long expiresInSeconds;
        private Tokens(String accessToken, String refreshToken, String folderId, long expiresInSeconds) {
            this.accessToken = accessToken; this.refreshToken = refreshToken; this.folderId = folderId; this.expiresInSeconds = expiresInSeconds;
        }
        public String accessToken() { return accessToken; }
        public String refreshToken() { return refreshToken; }
        public String folderId() { return folderId; }
        public long expiresInSeconds() { return expiresInSeconds; }
        @Override public String toString() { return "Google Drive authorization [redacted]"; }
    }

    public GoogleDriveAuthorization(String clientId, String clientSecret) { this(clientId, clientSecret, httpExchange()); }
    GoogleDriveAuthorization(String clientId, String clientSecret, Exchange exchange) {
        if (!valid(clientId, 512) || !valid(clientSecret, 512) || exchange == null) throw new IllegalArgumentException("Invalid OAuth client configuration");
        this.clientId = clientId; this.clientSecret = clientSecret; this.exchange = exchange;
    }

    /** Starts only a loopback listener; the caller explicitly opens authorizationUri in the user's browser. */
    public Session begin(String expectedFolder) throws IOException {
        if (expectedFolder != null && !expectedFolder.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalArgumentException("Invalid folder ID");
        String verifier = randomString(), state = randomString();
        String challenge;
        try { challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        var callback = new CompletableFuture<Map<String, String>>();
        server.createContext("/callback", request -> handleCallback(request, state, callback));
        server.start();
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        String query = "client_id=" + encode(clientId) + "&redirect_uri=" + encode(redirect) + "&response_type=code&scope=" + encode(SCOPE)
                + "&access_type=offline&prompt=consent&trigger_onepick=true&allow_folder_selection=true&state=" + encode(state)
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256"
                + (expectedFolder == null ? "" : "&file_ids=" + encode(expectedFolder));
        return new Session(server, callback, URI.create("https://accounts.google.com/o/oauth2/v2/auth?" + query), redirect, verifier, expectedFolder);
    }

    public final class Session implements AutoCloseable {
        private final HttpServer server;
        private final CompletableFuture<Map<String, String>> callback;
        private final URI uri;
        private final String redirect, verifier, expectedFolder;
        private Session(HttpServer server, CompletableFuture<Map<String, String>> callback, URI uri, String redirect, String verifier, String expectedFolder) {
            this.server = server; this.callback = callback; this.uri = uri; this.redirect = redirect; this.verifier = verifier; this.expectedFolder = expectedFolder;
        }
        public URI authorizationUri() { return uri; }
        /** Blocks until consent, cancellation or timeout; invoke off the game thread. */
        public Tokens await(Duration timeout) throws IOException, InterruptedException {
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
                throw new IllegalArgumentException("Invalid authorization timeout");
            try {
                var result = callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                String code = result.get("code"), folder = result.get("picked_file_ids");
                if (!valid(code, 2048) || folder == null || !folder.matches("[A-Za-z0-9_-]{1,200}")
                        || (expectedFolder != null && !expectedFolder.equals(folder))) throw new IOException("Drive authorization was not completed");
                String form = "code=" + encode(code) + "&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                        + "&redirect_uri=" + encode(redirect) + "&grant_type=authorization_code&code_verifier=" + encode(verifier);
                return tokens(send(form), folder, null);
            } catch (TimeoutException | ExecutionException | java.util.concurrent.CancellationException failure) { throw new IOException("Drive authorization was not completed", failure); }
            finally { close(); }
        }
        @Override public void close() { server.stop(0); callback.cancel(false); }
    }

    public Tokens refresh(String refreshToken, String folderId) throws IOException, InterruptedException {
        if (!valid(refreshToken, 4096) || folderId == null || !folderId.matches("[A-Za-z0-9_-]{1,200}"))
            throw new IllegalArgumentException("Invalid refresh credentials");
        String form = "refresh_token=" + encode(refreshToken) + "&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret) + "&grant_type=refresh_token";
        return tokens(send(form), folderId, refreshToken);
    }

    private byte[] send(String form) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(TOKEN_ENDPOINT).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        return exchange.send(request);
    }

    private static Tokens tokens(byte[] data, String folder, String previousRefresh) throws IOException {
        if (data == null || data.length > 65_536) throw new IOException("Invalid authorization response");
        try {
            var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            String access = json.get("access_token").getAsString();
            String refresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : previousRefresh;
            long expires = json.get("expires_in").getAsLong();
            if (!valid(access, 4096) || (refresh != null && !valid(refresh, 4096)) || expires < 1 || expires > 86_400
                    || !"Bearer".equalsIgnoreCase(json.get("token_type").getAsString())) throw new IOException("Invalid authorization response");
            return new Tokens(access, refresh, folder, expires);
        } catch (RuntimeException invalid) { throw new IOException("Invalid authorization response", invalid); }
    }

    private static void handleCallback(HttpExchange request, String expectedState, CompletableFuture<Map<String, String>> callback) throws IOException {
        int status = 400;
        try {
            String query = request.getRequestURI().getRawQuery();
            if (!"GET".equals(request.getRequestMethod()) || !"/callback".equals(request.getRequestURI().getPath()) || query == null || query.length() > 8192)
                throw new IllegalArgumentException();
            var values = new HashMap<String, String>();
            for (String item : query.split("&")) {
                String[] parts = item.split("=", 2);
                if (parts.length != 2 || values.putIfAbsent(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
                    throw new IllegalArgumentException();
            }
            String state = values.get("state");
            if (state != null && MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.US_ASCII), state.getBytes(StandardCharsets.UTF_8))) {
                callback.complete(values);
                status = 200;
            } else status = 403;
        } catch (IllegalArgumentException ignored) { }
        byte[] message = "Return to Minecraft.".getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.sendResponseHeaders(status, message.length);
        try (var output = request.getResponseBody()) { output.write(message); }
    }

    private String randomString() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean valid(String value, int limit) { return value != null && !value.isBlank() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl); }
    private static Exchange httpExchange() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var pending = http.sendAsync(request, ignored -> new GoogleDriveSnapshotStore.BoundedBody());
            try {
                var response = pending.get(15, TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw new IOException("Drive authorization failed");
                return response.body();
            } catch (TimeoutException | ExecutionException failure) { pending.cancel(true); throw new IOException("Drive authorization failed", failure); }
            catch (InterruptedException interrupted) { pending.cancel(true); throw interrupted; }
        };
    }
}
