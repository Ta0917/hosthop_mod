package dev.hosthop.lease;

import com.google.gson.JsonParser;
import dev.hosthop.reconnect.ReconnectController;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.SnapshotStore;
import dev.hosthop.world.SharedWorldTransfer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
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
        var initial = directory.resolve("initial");
        var preparedA = directory.resolve("host-a");
        var preparedB = directory.resolve("host-b");
        var archiveA = directory.resolve("initial.zip");
        var finalArchive = directory.resolve("final.zip");
        var archiveB = directory.resolve("download.zip");
        var remote = directory.resolve("remote-1.zip");
        var remote2 = directory.resolve("remote-2.zip");
        var journal = directory.resolve("pending");
        var finalArchive2 = directory.resolve("final-2.zip");
        var journal2 = directory.resolve("pending-2");
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
            SnapshotStore storage = new SnapshotStore() {
                private int uploaded;
                private final java.util.Map<String, Path> objects = new java.util.HashMap<>();
                @Override public GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folder, String driveToken) throws java.io.IOException, InterruptedException {
                    String fileId = "file-" + ++uploaded;
                    Path target = directory.resolve("remote-" + uploaded + ".zip");
                    Files.copy(archive, target);
                    objects.put(fileId, target);
                    var digest = java.util.HexFormat.of().formatHex(MessageDigestHolder.sha256(Files.readAllBytes(target)));
                    long size = Files.size(target);
                    try { post(http, base, "/test-object", "{\"fileId\":\"" + fileId + "\",\"digest\":\"" + digest + "\",\"size\":" + size + "}"); }
                    catch (InterruptedException interrupted) { throw interrupted; }
                    catch (Exception failure) { throw new java.io.IOException(failure); }
                    return new GoogleDriveSnapshotStore.ObjectRef(fileId, digest, size);
                }
                @Override public SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String driveToken) throws java.io.IOException {
                    Files.copy(objects.get(object.fileId()), destination);
                    return SnapshotVerifier.verify(destination);
                }
            };
            Files.createDirectory(initial);
            Files.writeString(initial.resolve("level.dat"), "initial");
            var gate = new LocalHostGate();
            var coordinator = new LeaseCoordinator(gate, client, System::nanoTime, System::currentTimeMillis);
            var transfer = new SharedWorldTransfer(gate, client, storage, System::nanoTime, System::currentTimeMillis);
            var acquisition = coordinator.begin("world", "lease", "owner", 30, 2);
            try { coordinator.acquire(acquisition); throw new AssertionError("lost reply was accepted"); }
            catch (java.io.IOException expected) { }
            check(gate.phase() == LocalHostGate.Phase.ACQUIRING);
            check(coordinator.recoverAcquisition(acquisition));
            var record = client.lookupAcquisition("world", "lease", 30, "owner", 2);
            check(record.epoch() == 1 && record.generation() == 0);
            transfer.prepare(acquisition, initial, archiveA, preparedA, "drive-test-token");
            check(gate.beginHosting(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis()));
            check(coordinator.renew(acquisition));
            var revision = post(http, base, "/endpoint-revision", "{\"world\":\"world\",\"lease\":\"lease\",\"epoch\":1}");
            long number = JsonParser.parseString(revision).getAsJsonObject().get("endpoint_revision").getAsLong();
            post(http, base, "/endpoint-update", "{\"world\":\"world\",\"lease\":\"lease\",\"epoch\":1,\"revision\":" + number + ",\"endpoint\":\"test.e4mc.link\"}");
            var endpoint = client.endpoint("world", 2);
            var reconnect = new ReconnectController();
            var session = reconnect.start("world", 1, System.nanoTime());
            check(reconnect.connect(session, endpoint, System.nanoTime(), System.currentTimeMillis()) != null);
            Files.writeString(preparedA.resolve("level.dat"), "progress");
            check(gate.beginGracefulStop(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis()));
            // The server stop is simulated here; Minecraft itself is not launched by this test.
            try { transfer.publishAfterStopped(acquisition, preparedA, finalArchive, journal, "op", "folder", "drive-test-token"); throw new AssertionError("lost commit reply was accepted"); }
            catch (java.io.IOException expected) { }
            check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED && Files.exists(journal));
            check(client.snapshot("world").object().fileId().equals("file-1"));
            try { transfer.recoverStopped(acquisition.attempt(), journal); throw new AssertionError("lost release reply was accepted"); }
            catch (java.io.IOException expected) { }
            check(Files.exists(journal));
            transfer.recoverStopped(acquisition.attempt(), journal);
            check(gate.phase() == LocalHostGate.Phase.IDLE && !Files.exists(journal));
            var nextGate = new LocalHostGate();
            var next = new LeaseCoordinator(nextGate, client, System::nanoTime, System::currentTimeMillis);
            var nextAcquisition = next.begin("world", "next-lease", "owner", 30, 2);
            check(next.acquire(nextAcquisition));
            check(client.snapshot("world").generation() == 1);
            var nextTransfer = new SharedWorldTransfer(nextGate, client, storage, System::nanoTime, System::currentTimeMillis);
            nextTransfer.prepare(nextAcquisition, null, archiveB, preparedB, "drive-test-token");
            check(Files.readString(preparedB.resolve("level.dat")).equals("progress"));
            check(nextGate.beginHosting(nextAcquisition.attempt(), System.nanoTime(), System.currentTimeMillis()));
            Files.writeString(preparedB.resolve("level.dat"), "progress-2");
            check(nextGate.beginGracefulStop(nextAcquisition.attempt(), System.nanoTime(), System.currentTimeMillis()));
            nextTransfer.publishAfterStopped(nextAcquisition, preparedB, finalArchive2, journal2, "op-2", "folder", "drive-test-token");
            check(nextGate.phase() == LocalHostGate.Phase.IDLE && !Files.exists(journal2));
            check(client.snapshot("world").generation() == 2);
            var thirdGate = new LocalHostGate();
            var third = new LeaseCoordinator(thirdGate, client, System::nanoTime, System::currentTimeMillis);
            var thirdAcquisition = third.begin("world", "third-lease", "owner", 30, 2);
            check(third.acquire(thirdAcquisition));
            post(http, base, "/test-expire", "{}");
            try { third.renew(thirdAcquisition); throw new AssertionError("expired lease renewed"); }
            catch (java.io.IOException expected) { }
            check(thirdGate.phase() == LocalHostGate.Phase.STOP_REQUIRED);
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
            Files.deleteIfExists(initial.resolve("level.dat"));
            Files.deleteIfExists(initial);
            Files.deleteIfExists(preparedA.resolve("level.dat"));
            Files.deleteIfExists(preparedA);
            Files.deleteIfExists(preparedB.resolve("level.dat"));
            Files.deleteIfExists(preparedB);
            Files.deleteIfExists(archiveA);
            Files.deleteIfExists(finalArchive);
            Files.deleteIfExists(archiveB);
            Files.deleteIfExists(remote);
            Files.deleteIfExists(remote2);
            Files.deleteIfExists(journal);
            Files.deleteIfExists(journal2);
            Files.deleteIfExists(finalArchive2);
            Files.deleteIfExists(store);
            Files.delete(directory);
        }
    }

    private static final class MessageDigestHolder {
        static byte[] sha256(byte[] data) throws java.io.IOException {
            try { return MessageDigest.getInstance("SHA-256").digest(data); }
            catch (java.security.NoSuchAlgorithmException unavailable) { throw new java.io.IOException(unavailable); }
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
