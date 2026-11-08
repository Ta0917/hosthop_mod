package dev.hosthop.lease;

import com.google.gson.JsonParser;
import dev.hosthop.reconnect.ReconnectController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Opt-in isolated HTTPS/workerd/D1 integration test; never connects to public services. */
public final class LeaseServiceIntegrationProbe {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("hosthop-integration-test-");
        var store = directory.resolve("test.p12");
        Process bridge = null;
        try {
            var keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
            var generate = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", "test-password", "-keypass", "test-password", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost", "-validity", "1").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!generate.waitFor(20, TimeUnit.SECONDS) || generate.exitValue() != 0) throw new AssertionError("test certificate failed");
            bridge = new ProcessBuilder("node", System.getProperty("hosthop.bridge"), System.getProperty("hosthop.miniflare"), store.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            var reader = bridge.inputReader();
            var line = CompletableFuture.supplyAsync(() -> {
                try { return reader.readLine(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).get(20, TimeUnit.SECONDS);
            if (line == null || !line.matches("PORT=[0-9]+")) throw new AssertionError("local bridge did not start");
            var base = URI.create("https://localhost:" + line.substring(5));
            var keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
            var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, tm.getTrustManagers(), null);
            var http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build();
            var client = new LeaseAuthorityClient(base, "a".repeat(43), LeaseAuthorityClient.httpExchange(http, Duration.ofSeconds(5)), System::nanoTime, System::currentTimeMillis);
            var gate = new LocalHostGate();
            var coordinator = new LeaseCoordinator(gate, client, System::nanoTime, System::currentTimeMillis);
            var acquisition = coordinator.begin("world", "lease", "owner", 30, 2);
            try { coordinator.acquire(acquisition); throw new AssertionError("lost reply was accepted"); }
            catch (java.io.IOException expected) { }
            check(gate.phase() == LocalHostGate.Phase.ACQUIRING);
            check(coordinator.recoverAcquisition(acquisition));
            var record = client.lookupAcquisition("world", "lease", 30, "owner", 2);
            check(record.epoch() == 1 && record.generation() == 0);
            check(gate.snapshotVerified(acquisition.attempt(), 0, System.nanoTime(), System.currentTimeMillis()));
            check(gate.beginHosting(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis()));
            check(coordinator.renew(acquisition));
            var revision = post(http, base, "/endpoint-revision", "{\"world\":\"world\",\"lease\":\"lease\",\"epoch\":1}");
            long number = JsonParser.parseString(revision).getAsJsonObject().get("endpoint_revision").getAsLong();
            post(http, base, "/endpoint-update", "{\"world\":\"world\",\"lease\":\"lease\",\"epoch\":1,\"revision\":" + number + ",\"endpoint\":\"test.e4mc.link\"}");
            var endpoint = client.endpoint("world", 2);
            var reconnect = new ReconnectController();
            var session = reconnect.start("world", 1, System.nanoTime());
            check(reconnect.connect(session, endpoint, System.nanoTime(), System.currentTimeMillis()) != null);
            post(http, base, "/test-expire", "{}");
            try { coordinator.renew(acquisition); throw new AssertionError("expired lease renewed"); }
            catch (java.io.IOException expected) { }
            check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED);
            var otherGate = new LocalHostGate();
            var other = new LeaseCoordinator(otherGate, client, System::nanoTime, System::currentTimeMillis);
            var replacement = other.begin("world", "new-lease", "owner", 30, 2);
            try { other.acquire(replacement); throw new AssertionError("expiry allowed takeover"); }
            catch (java.io.IOException expected) { }
            check(otherGate.phase() == LocalHostGate.Phase.ACQUIRING);
            try { client.endpoint("world", 2); throw new AssertionError("expired endpoint returned"); }
            catch (java.io.IOException expected) { }
            System.out.println("PASS: Java HTTPS client to local workerd and D1");
        } finally {
            if (bridge != null) {
                bridge.getOutputStream().close();
                if (!bridge.waitFor(10, TimeUnit.SECONDS)) bridge.destroyForcibly();
            }
            Files.deleteIfExists(store);
            Files.delete(directory);
        }
    }

    private static String post(HttpClient http, URI base, String route, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(route)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + "a".repeat(43)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200);
        return response.body();
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("integration assertion failed"); }
}
