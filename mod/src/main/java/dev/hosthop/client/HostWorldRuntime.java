package dev.hosthop.client;

import dev.hosthop.HostHop;
import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.relay.E4mcRelayBridge;
import dev.hosthop.relay.RelayPublisher;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.storage.SnapshotStore;
import dev.hosthop.world.SaveFailureMonitor;
import dev.hosthop.world.PendingHostRecovery;
import dev.hosthop.world.ServerStopSignal;
import dev.hosthop.world.SharedWorldTransfer;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Runs the shared-world host lifecycle without blocking the game thread. */
@EventBusSubscriber(modid = HostHop.MOD_ID, value = Dist.CLIENT)
public final class HostWorldRuntime {
    private static final AtomicReference<HostWorldRuntime> ACTIVE = new AtomicReference<>();
    private static volatile String lastStatus = "screen.hosthop.initial";
    private final Invitation invitation;
    private final DriveCredentialManager credentials;
    private final String initialName;
    private final Consumer<String> status;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final LocalHostGate gate = new LocalHostGate();
    private final LeaseAuthorityClient authority;
    private final LeaseCoordinator coordinator;
    private final SharedWorldTransfer transfer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "hosthop-world"));
    private final ExecutorService relayWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "hosthop-relay"));
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "hosthop-lease"));
    private final AtomicBoolean failed = new AtomicBoolean(), cancelled = new AtomicBoolean(), finishing = new AtomicBoolean();
    private final AtomicBoolean cleaned = new AtomicBoolean();
    private final AtomicBoolean lanStarted = new AtomicBoolean();
    private volatile LeaseCoordinator.Acquisition acquisition;
    private volatile IntegratedServer server;
    private volatile RelayPublisher relay;
    private volatile boolean relayReady, launchRequested;
    private volatile String stage = "initial";
    private SaveFailureMonitor monitor;
    private FileChannel processChannel;
    private FileLock processLock;
    private Path pendingPath, worldPath, sessionDir;

    private HostWorldRuntime(Invitation invitation, DriveCredentialManager credentials, String initialName, Consumer<String> status,
                             SnapshotStore storage, LeaseAuthorityClient authority) {
        this.invitation = invitation; this.credentials = credentials; this.initialName = initialName; this.status = status;
        this.authority = authority;
        coordinator = new LeaseCoordinator(gate, authority, System::nanoTime, System::currentTimeMillis);
        transfer = new SharedWorldTransfer(gate, authority, storage, System::nanoTime, System::currentTimeMillis);
    }

    public static boolean start(Invitation invitation, DriveCredentialManager credentials, String initialName, Consumer<String> status) {
        return startWithStore(invitation, credentials, initialName, status, new GoogleDriveSnapshotStore(),
                new LeaseAuthorityClient(java.net.URI.create(invitation.authority()), invitation.memberToken()));
    }
    static boolean startWithStore(Invitation invitation, DriveCredentialManager credentials, String initialName,
                                  Consumer<String> status, SnapshotStore storage, LeaseAuthorityClient authority) {
        var runtime = new HostWorldRuntime(invitation, credentials, initialName, status, storage, authority);
        if (!ACTIVE.compareAndSet(null, runtime)) { runtime.shutdownExecutors(); return false; }
        runtime.worker.execute(runtime::prepare);
        return true;
    }
    public static boolean active() { return ACTIVE.get() != null; }
    public static boolean hostingReady() { var runtime = ACTIVE.get(); return runtime != null && runtime.relayReady && !runtime.failed.get(); }
    public static String lastStatus() { return lastStatus; }
    public static void cancelPreparing() { var runtime = ACTIVE.get(); if (runtime != null && !runtime.launchRequested) runtime.cancelled.set(true); }

    private void prepare() {
        try {
            if (!E4mcRelayBridge.supported()) { tell("screen.hosthop.e4mc_required"); cleanup(); return; }
            tell("screen.hosthop.preparing");
            stage = "drive authorization";
            GoogleDriveAuthorization.Tokens token;
            try {
                token = credentials.access();
            } catch (IOException unavailable) { tell("screen.hosthop.authorize_first"); cleanup(); return; }
            if (!invitation.folder().equals(token.folderId())) throw new IOException("Drive folder does not match invitation");
            Path config = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("hosthop");
            stage = "local session lock";
            Files.createDirectories(config);
            processChannel = FileChannel.open(config.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { processLock = processChannel.tryLock(); }
            catch (OverlappingFileLockException inUse) { throw new IOException("HostHop is already running", inUse); }
            if (processLock == null) throw new IOException("HostHop is already running");
            pendingPath = config.resolve("pending-host.dat");
            var pending = new WindowsSecretStore(pendingPath);
            if (pending.exists()) {
                // These stages are written before Minecraft can be launched. A later
                // stage may contain unsaved play and must never be discarded this way.
                try {
                    var evidence = pending.load();
                    if (!PendingHostRecovery.abortUnstarted(evidence, invitation.world(), invitation.principal(), authority::abort, authority::cancelAcquisition)
                            && !recoverCommittedHost(evidence)) {
                        tell("screen.hosthop.recovery_required"); cleanup(); return;
                    }
                    Files.deleteIfExists(pendingPath);
                } catch (IOException | InterruptedException | RuntimeException unresolved) {
                    if (unresolved instanceof InterruptedException) Thread.currentThread().interrupt();
                    tell("screen.hosthop.recovery_required"); cleanup(); return;
                }
            }
            String id = UUID.randomUUID().toString();
            Path game = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
            Path saves = game.resolve("saves");
            Files.createDirectories(saves);
            sessionDir = game.resolve("hosthop").resolve("sessions").resolve(id);
            Files.createDirectories(sessionDir.getParent());
            Files.createDirectory(sessionDir);
            String levelId = "hosthop_" + id.replace("-", "");
            worldPath = saves.resolve(levelId);
            Path initial = initialWorld(saves, initialName);
            acquisition = coordinator.begin(invitation.world(), id, invitation.principal(), 90, 5);
            stage = "recording pending host";
            pending.save(pendingData("acquiring", id, levelId));
            if (cancelled.get()) { Files.delete(pendingPath); cleanup(); return; }
            try {
                stage = "acquiring lease";
                if (!coordinator.acquire(acquisition)) throw new IOException("Lease not acquired");
            } catch (IOException first) {
                // A lost response may mean the lease was granted; never use another ID here.
                if (!coordinator.recoverAcquisition(acquisition)) throw first;
            }
            pending.save(pendingData("preparing", id, levelId));
            stage = "restoring world";
            renewer.scheduleWithFixedDelay(this::renew, 15, 15, TimeUnit.SECONDS);
            if (cancelled.get()) { abortBeforeStart(); return; }
            transfer.prepare(acquisition, initial, sessionDir.resolve("initial.zip"), worldPath, token.accessToken());
            if (cancelled.get()) { abortBeforeStart(); return; }
            pending.save(pendingData("starting", id, levelId));
            stage = "starting Minecraft";
            monitor = new SaveFailureMonitor();
            relay = new RelayPublisher(authority, acquisition, gate, relayWorker, () -> {
                relayReady = true; tell("screen.hosthop.host_ready");
            }, error -> fail("screen.hosthop.host_failed"));
            E4mcRelayBridge.install(relay);
            minecraft.execute(() -> {
                if (cancelled.get()) { worker.execute(this::abortBeforeStart); return; }
                if (!gate.beginHosting(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis())) { fail("screen.hosthop.host_failed"); cleanup(); return; }
                launchRequested = true;
                try {
                    minecraft.createWorldOpenFlows().openWorld(levelId, () -> failBeforeServer("screen.hosthop.host_failed"));
                } catch (RuntimeException error) { failBeforeServer("screen.hosthop.host_failed"); }
            });
        } catch (Exception error) {
            var cause = error.getCause();
            var root = cause == null ? null : cause.getCause();
            com.mojang.logging.LogUtils.getLogger().warn("HostHop preparation failed during {} ({} / {} / {})", stage,
                    error.getClass().getSimpleName(), cause == null ? "none" : cause.getClass().getSimpleName(),
                    root == null ? "none" : root.getClass().getSimpleName());
            if (acquisition != null && acquisition.epoch() > 0 && !launchRequested) abortBeforeStart();
            else { tell("screen.hosthop.host_failed"); cleanup(); }
        }
    }


    private static Path initialWorld(Path saves, String name) throws IOException {
        if (name == null || name.isBlank()) return null;
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains(":"))
            throw new IOException("Invalid world name");
        Path root = saves.toRealPath();
        Path source = root.resolve(name).normalize();
        if (!source.startsWith(root) || Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Initial world not found");
        return source;
    }

    /** Recover only a committed publication written after a verified server stop. */
    private boolean recoverCommittedHost(Map<String, String> evidence) throws IOException, InterruptedException {
        var candidate = PendingHostRecovery.findCommitted(evidence, invitation.world(), invitation.principal(), minecraft.gameDirectory.toPath());
        if (candidate == null) return false;
        try (var lock = dev.hosthop.snapshot.StoppedWorldLock.acquire(candidate.world())) {
            var recovery = gate.beginRecovery(candidate.record());
            transfer.recoverStopped(recovery, candidate.journal(), true);
            Files.delete(pendingPath);
            Files.delete(candidate.journal());
        }
        return true;
    }

    private Map<String, String> pendingData(String stage, String id, String levelId) {
        var values = new java.util.HashMap<String, String>();
        values.put("world", invitation.world()); values.put("lease", id); values.put("principal", invitation.principal());
        values.put("stage", stage); values.put("session", id); values.put("level", levelId);
        if (acquisition != null && acquisition.epoch() > 0) {
            values.put("epoch", Long.toString(acquisition.epoch())); values.put("generation", Long.toString(acquisition.generation()));
        }
        return values;
    }

    private void renew() {
        try {
            if (acquisition != null && !coordinator.renew(acquisition)
                    && gate.phase() != LocalHostGate.Phase.IDLE && gate.phase() != LocalHostGate.Phase.RELEASE_REQUIRED
                    && gate.phase() != LocalHostGate.Phase.ABORTING) fail("screen.hosthop.lease_failed");
        } catch (Exception failure) { fail("screen.hosthop.lease_failed"); }
    }

    private void abortBeforeStart() {
        try {
            if (acquisition != null && acquisition.epoch() > 0 && coordinator.abortBeforeHosting(acquisition)) {
                Files.deleteIfExists(pendingPath);
                tell(cancelled.get() ? "screen.hosthop.cancelled" : "screen.hosthop.host_failed");
            } else tell("screen.hosthop.recovery_required");
        } catch (Exception failure) { tell("screen.hosthop.recovery_required"); }
        cleanup();
    }

    private void failBeforeServer(String message) { fail(message); cleanup(); }
    private void fail(String message) {
        if (!failed.compareAndSet(false, true)) return;
        if (acquisition != null) gate.requireStop(acquisition.attempt());
        tell(message);
        var current = server;
        if (current != null) current.halt(false);
    }

    private void serverStarted(MinecraftServer candidate) {
        if (!(candidate instanceof IntegratedServer integrated) || worldPath == null
                || !candidate.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(worldPath.toAbsolutePath().normalize())) return;
        server = integrated;
        ServerStopSignal.watch(candidate);
        renewer.schedule(() -> { if (!relayReady && !failed.get()) fail("screen.hosthop.host_failed"); }, 60, TimeUnit.SECONDS);
    }

    private void publishLan() {
        var integrated = server;
        if (integrated == null || !lanStarted.compareAndSet(false, true)) return;
        minecraft.execute(() -> {
            if (failed.get() || gate.phase() != LocalHostGate.Phase.HOSTING) { integrated.halt(false); return; }
            try {
                if (!integrated.publishServer(MinecraftServer.MultiplayerScope.LAN, null, false, HttpUtil.getAvailablePort())) {
                    fail("screen.hosthop.host_failed"); return;
                }
            } catch (Exception failure) { fail("screen.hosthop.host_failed"); }
        });
    }

    private void beginShutdown() {
        if (acquisition != null && gate.phase() == LocalHostGate.Phase.HOSTING
                && !gate.beginGracefulStop(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis())) fail("screen.hosthop.lease_failed");
    }

    private void finish(MinecraftServer candidate) {
        if (candidate != server || !finishing.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                candidate.getRunningThread().join(30_000);
                if (candidate.getRunningThread().isAlive() || !ServerStopSignal.completedFor(candidate) || monitor == null
                        || monitor.hasFailure() || failed.get() || gate.phase() != LocalHostGate.Phase.QUIESCING)
                    throw new IOException("Server shutdown could not be verified");
                for (int i = 0; i < 50 && relay.sessionOpen(); i++) Thread.sleep(100);
                CompletableFuture.runAsync(() -> {}, relayWorker).get(15, TimeUnit.SECONDS);
                if (relay.sessionOpen() || gate.phase() != LocalHostGate.Phase.QUIESCING) throw new IOException("Relay did not close");
                tell("screen.hosthop.saving_world");
                String token = credentials.access().accessToken();
                Path journal = sessionDir.resolve("publication.dat");
                try {
                    transfer.publishAfterStopped(acquisition, worldPath, sessionDir.resolve("final.zip"), journal,
                            UUID.randomUUID().toString(), invitation.folder(), token, true);
                } catch (IOException | InterruptedException failure) {
                    if (Files.exists(journal) && gate.phase() == LocalHostGate.Phase.STOP_REQUIRED)
                        transfer.recoverStopped(acquisition.attempt(), journal, true);
                    else if (Files.exists(journal) && gate.phase() == LocalHostGate.Phase.RELEASE_REQUIRED)
                        transfer.completeRelease(acquisition.attempt(), journal, PendingPublicationJournal.read(journal), true);
                    else throw failure;
                }
                Files.deleteIfExists(pendingPath);
                Files.deleteIfExists(journal);
                tell("screen.hosthop.saved_world");
            } catch (Exception failure) {
                tell("screen.hosthop.recovery_required");
            } finally { cleanup(); }
        });
    }

    private void tell(String key) {
        lastStatus = key;
        minecraft.execute(() -> {
            status.accept(key);
            if (minecraft.level != null) minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable(key));
        });
    }
    private void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        if (relay != null) E4mcRelayBridge.uninstall(relay);
        if (monitor != null) { monitor.close(); monitor = null; }
        if (server != null) ServerStopSignal.clear(server);
        shutdownExecutors();
        try { if (processLock != null) processLock.release(); } catch (IOException ignored) { }
        try { if (processChannel != null) processChannel.close(); } catch (IOException ignored) { }
        ACTIVE.compareAndSet(this, null);
    }
    private void shutdownExecutors() { renewer.shutdownNow(); relayWorker.shutdown(); worker.shutdown(); }
    private static Thread daemon(Runnable task, String name) { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }

    @SubscribeEvent public static void started(ServerStartedEvent event) { var active = ACTIVE.get(); if (active != null) active.serverStarted(event.getServer()); }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { var active = ACTIVE.get(); if (active != null && active.server == event.getServer()) active.beginShutdown(); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { var active = ACTIVE.get(); if (active != null) active.finish(event.getServer()); }
    @SubscribeEvent public static void clientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        var active = ACTIVE.get();
        if (active != null && active.server != null && event.getConnection() != null && event.getConnection().isMemoryConnection()) active.publishLan();
    }
    @SubscribeEvent public static void clientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        var active = ACTIVE.get();
        if (active != null && active.server != null && event.getConnection() != null && event.getConnection().isMemoryConnection()) active.beginShutdown();
    }
}
