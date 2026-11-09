package dev.hosthop.storage;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GoogleDriveAuthorizationTest {
    public static void main(String[] args) throws Exception {
        check(GoogleDriveAuthorization.invalidGrant("{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8)));
        check(!GoogleDriveAuthorization.invalidGrant("{\"error\":\"temporarily_unavailable\"}".getBytes(StandardCharsets.UTF_8)));
        check(!GoogleDriveAuthorization.invalidGrant("not json".getBytes(StandardCharsets.UTF_8)));
        var challenge = new AtomicReference<String>();
        var auth = new GoogleDriveAuthorization("client-id", "client-secret", request -> {
            check(request.uri().toString().equals("https://oauth2.googleapis.com/token"));
            var form = parameters(readBody(request));
            check(form.get("client_id").equals("client-id") && form.get("client_secret").equals("client-secret"));
            if (form.get("grant_type").equals("authorization_code")) {
                String verifier = form.get("code_verifier");
                try {
                    String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                    check(actual.equals(challenge.get()) && form.get("code").equals("test-code"));
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                return "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8);
            }
            check(form.get("refresh_token").equals("refresh"));
            return "{\"access_token\":\"new-access\",\"expires_in\":3600,\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8);
        });
        try (var session = auth.begin("folder")) {
            var query = parameters(session.authorizationUri().getRawQuery());
            challenge.set(query.get("code_challenge"));
            check(query.get("scope").endsWith("/drive.file") && query.get("file_ids").equals("folder") && query.get("code_challenge_method").equals("S256"));
            var http = HttpClient.newHttpClient();
            String callback = query.get("redirect_uri");
            var invalid = HttpRequest.newBuilder(URI.create(callback + "?state=incorrect&code=test-code&picked_file_ids=folder")).GET().build();
            check(http.send(invalid, HttpResponse.BodyHandlers.discarding()).statusCode() == 403);
            String queryString = "?state=" + URLEncoder.encode(query.get("state"), StandardCharsets.UTF_8) + "&code=test-code&picked_file_ids=folder";
            check(http.send(HttpRequest.newBuilder(URI.create(callback + queryString)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
            var tokens = session.await(Duration.ofSeconds(5));
            check(tokens.accessToken().equals("access") && tokens.refreshToken().equals("refresh") && tokens.folderId().equals("folder"));
            check(!tokens.toString().contains("access") && auth.refresh(tokens.refreshToken(), "folder").accessToken().equals("new-access"));
        }
        System.out.println("Google Drive authorization checks passed");
    }

    private static String readBody(HttpRequest request) throws java.io.IOException, InterruptedException {
        var result = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();
            @Override public void onSubscribe(Flow.Subscription value) { value.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer value) { byte[] bytes = new byte[value.remaining()]; value.get(bytes); out.writeBytes(bytes); }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { result.complete(out.toByteArray()); }
        });
        try { return new String(result.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new java.io.IOException(failure); }
    }
    private static HashMap<String, String> parameters(String input) {
        var result = new HashMap<String, String>();
        for (String item : input.split("&")) {
            String[] pair = item.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
