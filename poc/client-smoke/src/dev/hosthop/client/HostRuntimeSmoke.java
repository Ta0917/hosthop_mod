package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.TestAuthority;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.SnapshotStore;
import dev.hosthop.storage.TestDriveCredentials;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.storage.GoogleDriveAuthorization;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import dev.hosthop.relay.E4mcRelayBridge;

/** Opt-in real integrated-server handoff check using isolated data and local fake storage. */
@EventBusSubscriber(modid = "hosthop", value = Dist.CLIENT)
public final class HostRuntimeSmoke {
    private static final BlockPos MARKER = new BlockPos(1, 100, 1);
    private static final long START = System.nanoTime();
    private static CompletableFuture<Context> setup;
    private static Context context;
    private static CompletableFuture<Boolean> block;
    private static int stage;
    private static final Object FAKE_RELAY = new Object();
    private static boolean fakeRelayStarted;

    @SubscribeEvent public static void serverStopped(ServerStoppedEvent event) {
        if (fakeRelayStarted) { E4mcRelayBridge.end(FAKE_RELAY); fakeRelayStarted = false; }
    }

    private record Context(Process bridge, Invitation invitation, DriveCredentialManager credentials, SnapshotStore storage, LeaseAuthorityClient authority) {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("hosthop.hostSmoke") || stage == 99) return;
        var minecraft = Minecraft.getInstance();
        try {
            if (System.nanoTime() - START > TimeUnit.MINUTES.toNanos(3)) throw new IllegalStateException("host smoke timed out at stage " + stage);
            if (Boolean.getBoolean("hosthop.fakeRelaySmoke") && (stage == 2 || stage == 5) && !fakeRelayStarted
                    && minecraft.getSingleplayerServer() != null && minecraft.player != null) {
                fakeRelayStarted = true;
                E4mcRelayBridge.begin(FAKE_RELAY);
                E4mcRelayBridge.assigned(FAKE_RELAY, "hosthop-test.e4mc.link");
            }
            if (stage == 0) {
                stage = 1;
                setup = CompletableFuture.supplyAsync(() -> {
                    try { return setup(minecraft.gameDirectory.toPath()); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
            } else if (stage == 1 && setup.isDone()) {
                context = setup.join();
                if (!HostWorldRuntime.startWithStore(context.invitation(), context.credentials(), "seed", key -> {}, context.storage(), context.authority()))
                    throw new IllegalStateException("first host did not start");
                stage = 2;
            } else if (stage == 2 && HostWorldRuntime.hostingReady() && minecraft.player != null) {
                var server = minecraft.getSingleplayerServer();
                if (server == null) return;
                if (server.getWorldData().getSinglePlayerUUID() != null) throw new IllegalStateException("previous host identity was retained");
                block = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        server.overworld().getChunk(0, 0);
                        if (Boolean.getBoolean("hosthop.verifyCloseSmoke")) block.complete(server.overworld().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK));
                        else { server.overworld().setBlock(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3); block.complete(true); }
                    }
                    catch (Throwable failure) { block.completeExceptionally(failure); }
                });
                stage = 3;
            } else if (stage == 3 && block.isDone()) {
                if (!block.join()) throw new IllegalStateException("closed-host world change was not restored");
                if (Boolean.getBoolean("hosthop.closeHostSmoke")) { stage = 99; minecraft.stop(); return; }
                minecraft.disconnect(new TitleScreen(), false);
                stage = 4;
            } else if (stage == 4 && !HostWorldRuntime.active()) {
                String expected = Boolean.getBoolean("hosthop.interruptedSmoke") ? "screen.hosthop.recovery_required" : "screen.hosthop.saved_world";
                if (!HostWorldRuntime.lastStatus().equals(expected)) throw new IllegalStateException("first save result unexpected: " + HostWorldRuntime.lastStatus());
                if (Boolean.getBoolean("hosthop.verifyCloseSmoke")) {
                    Files.writeString(minecraft.gameDirectory.toPath().resolve("hosthop-close-restore-result.txt"), "verified");
                    System.out.println("HOSTHOP_CLOSE_RESTORE_SMOKE world change restored=true");
                    finish(minecraft); return;
                }
                if (Boolean.getBoolean("hosthop.requireDeltaSmoke")) {
                    Path sessions = minecraft.gameDirectory.toPath().resolve("hosthop/sessions");
                    try (var entries = Files.list(sessions)) {
                        if (entries.noneMatch(path -> Files.isRegularFile(path.resolve("delta.bin"))))
                            throw new IllegalStateException("first host did not publish a delta");
                    }
                    // Simulate another machine with no local base or delta cache.
                    Path previous = Files.createDirectory(minecraft.gameDirectory.toPath().resolve("hosthop-prior-sessions"));
                    try (var entries = Files.list(sessions)) {
                        for (var entry : entries.toList()) Files.move(entry, previous.resolve(entry.getFileName()));
                    }
                }
                if (!HostWorldRuntime.startWithStore(context.invitation(), context.credentials(), "", key -> {}, context.storage(), context.authority()))
                    throw new IllegalStateException("second host did not start");
                stage = 5;
            } else if (stage == 5 && HostWorldRuntime.hostingReady() && minecraft.player != null) {
                var server = minecraft.getSingleplayerServer();
                if (server == null) return;
                if (server.getWorldData().getSinglePlayerUUID() != null) throw new IllegalStateException("previous host identity was retained after restore");
                block = new CompletableFuture<>();
                server.execute(() -> {
                    try { server.overworld().getChunk(0, 0); block.complete(server.overworld().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK)); }
                    catch (Throwable failure) { block.completeExceptionally(failure); }
                });
                stage = 6;
            } else if (stage == 6 && block.isDone()) {
                if (!block.join()) throw new IllegalStateException("world change was not restored");
                minecraft.disconnect(new TitleScreen(), false);
                stage = 7;
            } else if (stage == 7 && !HostWorldRuntime.active()) {
                if (!HostWorldRuntime.lastStatus().equals("screen.hosthop.saved_world")) throw new IllegalStateException("second save failed: " + HostWorldRuntime.lastStatus());
                if (Boolean.getBoolean("hosthop.requireDeltaSmoke"))
                    Files.writeString(minecraft.gameDirectory.toPath().resolve("hosthop-delta-host-result.txt"), "verified");
                System.out.println("HOSTHOP_HOST_SMOKE two integrated hosts restored world change=true");
                finish(minecraft);
            } else if ((stage == 2 || stage == 5) && !HostWorldRuntime.active()) {
                throw new IllegalStateException("host startup failed: " + HostWorldRuntime.lastStatus());
            }
        } catch (Throwable failure) {
            if (Boolean.getBoolean("hosthop.requireDeltaSmoke")) {
                try { Files.writeString(minecraft.gameDirectory.toPath().resolve("hosthop-delta-host-failure.txt"), failure.toString()); }
                catch (Exception ignored) { }
            }
            if (Boolean.getBoolean("hosthop.verifyCloseSmoke")) {
                try { Files.writeString(minecraft.gameDirectory.toPath().resolve("hosthop-close-restore-failure.txt"), failure.toString()); }
                catch (Exception ignored) { }
            }
            System.err.println("HOSTHOP_HOST_SMOKE failure=" + failure.getMessage());
            failure.printStackTrace();
            finish(minecraft);
        }
    }

    private static Context setup(Path gameDir) throws Exception {
        if (Boolean.getBoolean("hosthop.realSmoke")) {
            var invitation = Invitation.decode(Files.readString(Path.of(System.getProperty("hosthop.realInvite"))).trim());
            var vault = new WindowsSecretStore(Path.of(System.getProperty("hosthop.realCredentials")));
            var credentials = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
            var drive = new GoogleDriveSnapshotStore();
            SnapshotStore store = drive;
            if (Boolean.getBoolean("hosthop.closeHostSmoke")) store = new SnapshotStore() {
                @Override public void checkUploadCapacity(long bytes, String token) throws java.io.IOException, InterruptedException { drive.checkUploadCapacity(bytes, token); }
                @Override public GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folder, String token) throws java.io.IOException, InterruptedException {
                    Thread.sleep(17_000);
                    return drive.upload(archive, folder, token);
                }
                @Override public SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path path, String token) throws java.io.IOException, InterruptedException {
                    return drive.download(object, path, token);
                }
            };
            return new Context(null, invitation, credentials, store,
                    new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken()));
        }
        Path temp = gameDir.resolve("hosthop-smoke");
        Files.createDirectories(temp);
        Path keysPath = temp.resolve("test.p12");
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool";
        var keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        var generated = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-storetype", "PKCS12",
                "-keystore", keysPath.toString(), "-storepass", "test-password", "-keypass", "test-password", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!generated.waitFor(20, TimeUnit.SECONDS) || generated.exitValue() != 0) throw new IllegalStateException("test certificate failed");
        var builder = new ProcessBuilder("node", System.getProperty("hosthop.testBridge"), System.getProperty("hosthop.miniflare"), keysPath.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("HOSTHOP_TEST_NO_DROPS", "1");
        var bridge = builder.start();
        try {
            String line = CompletableFuture.supplyAsync(() -> {
                try { return bridge.inputReader().readLine(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).get(20, TimeUnit.SECONDS);
            if (line == null || !line.matches("PORT=[0-9]+")) throw new IllegalStateException("test service unavailable");
            URI base = URI.create("https://127.0.0.1:" + line.substring(5));
            var keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keysPath)) { keys.load(input, "test-password".toCharArray()); }
            var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
            var tls = SSLContext.getInstance("TLS"); tls.init(null, tm.getTrustManagers(), null);
            var http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build();
            var invitation = Invitation.fromSecrets(Map.of("authority", base.toString(), "world", "world", "principal", "owner",
                    "member_token", "a".repeat(43), "oauth_client_id", "test-client", "oauth_client_secret", "test-secret", "drive_folder", "folder"));
            var credentials = TestDriveCredentials.create(temp.resolve("credentials.dat"));
            SnapshotStore storage = new SnapshotStore() {
                private int count;
                private final java.util.Map<String, Path> objects = new java.util.HashMap<>();
                @Override public GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folder, String accessToken) throws java.io.IOException, InterruptedException {
                    if (Boolean.getBoolean("hosthop.closeHostSmoke")) Thread.sleep(17_000);
                    if (Boolean.getBoolean("hosthop.interruptedSmoke") && count == 0) {
                        count++;
                        throw new java.io.IOException("simulated interrupted upload");
                    }
                    String id = "file-" + ++count;
                    Path copy = temp.resolve(id + ".zip");
                    Files.copy(archive, copy); objects.put(id, copy);
                    long size = Files.size(copy);
                    String digest;
                    try (var input = Files.newInputStream(copy)) {
                        var hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[1024 * 1024]; int read;
                        while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
                        digest = HexFormat.of().formatHex(hash.digest());
                    } catch (java.security.NoSuchAlgorithmException unavailable) { throw new java.io.IOException(unavailable); }
                    try { post(http, base, "/test-object", "{\"fileId\":\"" + id + "\",\"digest\":\"" + digest + "\",\"size\":" + size + "}"); }
                    catch (InterruptedException interrupted) { throw interrupted; }
                    catch (Exception failure) { throw new java.io.IOException(failure); }
                    return new GoogleDriveSnapshotStore.ObjectRef(id, digest, size);
                }
                @Override public SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken) throws java.io.IOException {
                    Files.copy(objects.get(object.fileId()), destination); return SnapshotVerifier.verify(destination);
                }
            };
            return new Context(bridge, invitation, credentials, storage, TestAuthority.create(base, "a".repeat(43), tls));
        } catch (Exception failure) { bridge.destroyForcibly(); throw failure; }
    }

    private static void post(HttpClient http, URI base, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + "a".repeat(43))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) throw new IllegalStateException("test service request failed");
    }
    private static void finish(Minecraft minecraft) {
        stage = 99;
        if (context != null && context.bridge() != null) {
            try { context.bridge().getOutputStream().close(); } catch (Exception ignored) { }
            context.bridge().destroy();
        }
        minecraft.stop();
    }
}
