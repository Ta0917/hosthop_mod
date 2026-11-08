package dev.hosthop.lease;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Local test server and ephemeral certificate; no external network or real credentials. */
public final class LeaseAuthorityHttpsTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("hosthop-https-test-");
        var store = directory.resolve("test.p12");
        HttpsServer server = null;
        try {
            var keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
            var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "test-password",
                    "-keypass", "test-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor() != 0) throw new AssertionError("test certificate generation failed");
            var keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "test-password".toCharArray());
            var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys);
            var tls = SSLContext.getInstance("TLS");
            tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            var mode = new AtomicReference<>("valid");
            server.createContext("/acquire", exchange -> {
                try (exchange) {
                    if (!"Bearer ".concat("a".repeat(43)).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    if (mode.get().equals("slow")) {
                        try { Thread.sleep(1_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    byte[] body = (mode.get().equals("large") ? "x".repeat(20_000) :
                            "{\"epoch\":1,\"generation\":0,\"server_now\":100,\"expires\":130}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) { /* Expected if the test client cancels. */ }
            });
            server.start();
            var http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build();
            var client = new LeaseAuthorityClient(URI.create("https://localhost:" + server.getAddress().getPort()), "a".repeat(43),
                    LeaseAuthorityClient.httpExchange(http, Duration.ofSeconds(5)), System::nanoTime, System::currentTimeMillis);
            check(client.acquire("world", "lease", 30, 2).epoch() == 1);
            var untrusted = new LeaseAuthorityClient(URI.create("https://localhost:" + server.getAddress().getPort()), "a".repeat(43),
                    LeaseAuthorityClient.httpExchange(HttpClient.newHttpClient(), Duration.ofSeconds(5)), System::nanoTime, System::currentTimeMillis);
            expectFailure(() -> untrusted.acquire("world", "lease", 30, 2));
            var wrongHost = new LeaseAuthorityClient(URI.create("https://127.0.0.1:" + server.getAddress().getPort()), "a".repeat(43),
                    LeaseAuthorityClient.httpExchange(http, Duration.ofSeconds(5)), System::nanoTime, System::currentTimeMillis);
            expectFailure(() -> wrongHost.acquire("world", "lease", 30, 2));
            mode.set("large");
            expectFailure(() -> client.acquire("world", "lease", 30, 2));
            mode.set("slow");
            var impatient = new LeaseAuthorityClient(URI.create("https://localhost:" + server.getAddress().getPort()), "a".repeat(43),
                    LeaseAuthorityClient.httpExchange(http, Duration.ofMillis(300)), System::nanoTime, System::currentTimeMillis);
            expectFailure(() -> impatient.acquire("world", "lease", 30, 2));
            System.out.println("Local HTTPS lease transport checks passed");
        } finally {
            if (server != null) server.stop(0);
            Files.deleteIfExists(store);
            Files.delete(directory);
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("request should fail"); }
        catch (IOException expected) { }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
