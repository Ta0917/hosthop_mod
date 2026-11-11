package dev.hosthop.client;

import dev.hosthop.HostHop;
import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.relay.E4mcRelayBridge;
import dev.hosthop.relay.RelayPublisher;
import dev.hosthop.snapshot.LiveWorldCopy;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.storage.SnapshotStore;
import dev.hosthop.world.SaveFailureMonitor;
import dev.hosthop.world.CheckpointSave;
import dev.hosthop.world.PendingHostRecovery;
import dev.hosthop.world.ServerStopSignal;
import dev.hosthop.world.SessionCleanup;
import dev.hosthop.world.SharedWorldTransfer;
import dev.hosthop.world.HostPlayerIdentity;
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
    private static final class LocalProcessBusy extends IOException {
        LocalProcessBusy() { super("Another HostHop process is running"); }
    }
    private static final AtomicReference<HostWorldRuntime> ACTIVE = new AtomicReference<>();
    /** Lease is 90 s; renew every 15 s, check every 5 s, and stop once less than 20 s would remain. */
    private static final long RENEW_TICK = 5, RENEW_INTERVAL = 15, STOP_MARGIN = 20, RELAY_RECONNECT_SECONDS = 60;
    /** The newest released sessions stay on disk as a download cache for the next host. */
    private static final int KEEP_RELEASED_SESSIONS = 2;
    private volatile long lastRenewed;
    private volatile boolean renewalRetrying;
    private static volatile String lastStatus = "screen.hosthop.initial";
    private static volatile int uploadPercent = -1;
    private static volatile int downloadPercent = -1;
    private final Invitation invitation;
    private final DriveCredentialManager credentials;
    private final String initialName;
    private final Consumer<String> status;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final LocalHostGate gate = new LocalHostGate();
    private final LeaseAuthorityClient authority;
    private final LeaseCoordinator coordinator;
    private final SharedWorldTransfer transfer;
    private final SnapshotStore storage;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "hosthop-world"));
    private final ExecutorService relayWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "hosthop-relay"));
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "hosthop-lease"));
    private final AtomicBoolean failed = new AtomicBoolean(), cancelled = new AtomicBoolean(), finishing = new AtomicBoolean();
    private final AtomicBoolean cleaned = new AtomicBoolean();
    private final AtomicBoolean lanStarted = new AtomicBoolean();
    private final AtomicBoolean checkpointInFlight = new AtomicBoolean();
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
        this.authority = authority; this.storage = storage;
        coordinator = new LeaseCoordinator(gate, authority, System::nanoTime, System::currentTimeMillis);
        transfer = new SharedWorldTransfer(gate, authority, storage, System::nanoTime, System::currentTimeMillis,
                percent -> {
                    if (uploadPercent == (int) percent) return;
                    uploadPercent = (int) percent;
                    try { dev.hosthop.world.SaveStatus.writeProgress(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("hosthop"), (int) percent); }
                    catch (IOException failure) { com.mojang.logging.LogUtils.getLogger().warn("HostHop could not persist upload progress"); }
                }, percent -> downloadPercent = (int) percent);
        // Uploads of large worlds can outlast one access token; refresh it as they go.
        dev.hosthop.storage.DriveTokenSource tokens = () -> credentials.access().accessToken();
        if (storage instanceof GoogleDriveSnapshotStore drive) drive.setTokenSource(tokens);
        transfer.setTokenSource(tokens);
    }

    public static boolean start(Invitation invitation, DriveCredentialManager credentials, String initialName, Consumer<String> status) {
        return startWithStore(invitation, credentials, initialName, status, new GoogleDriveSnapshotStore(),
                new LeaseAuthorityClient(java.net.URI.create(invitation.authority()), invitation.memberToken()));
    }
    static boolean startWithStore(Invitation invitation, DriveCredentialManager credentials, String initialName,
                                  Consumer<String> status, SnapshotStore storage, LeaseAuthorityClient authority) {
        var runtime = new HostWorldRuntime(invitation, credentials, initialName, status, storage, authority);
        if (!ACTIVE.compareAndSet(null, runtime)) { runtime.shutdownExecutors(); return false; }
        uploadPercent = -1;
        downloadPercent = -1;
        runtime.worker.execute(runtime::prepare);
        return true;
    }
    public static boolean active() { return ACTIVE.get() != null; }
    private static volatile boolean quitAfterSave;
    /** True from the moment the world is open until the final upload and cleanup are finished. */
    public static boolean closeBlocked() { var runtime = ACTIVE.get(); return runtime != null && runtime.server != null && !runtime.cleaned.get(); }
    /** True while the world has stopped and the final snapshot is still being published. */
    public static boolean savingInProgress() { var runtime = ACTIVE.get(); return runtime != null && (runtime.finishing.get() || (runtime.server != null && !runtime.server.isRunning())) && !runtime.cleaned.get(); }
    /** Leave the world normally; the process closes by itself once the save has succeeded. */
    public static void requestQuitAfterSave() {
        var runtime = ACTIVE.get();
        if (runtime == null) return;
        quitAfterSave = true;
        var minecraft = runtime.minecraft;
        if (runtime.server != null && !runtime.finishing.get() && minecraft.level != null)
            minecraft.execute(() -> { if (minecraft.level != null) minecraft.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE); });
    }
    public static boolean hostingReady() { var runtime = ACTIVE.get(); return runtime != null && runtime.relayReady && !runtime.failed.get(); }
    public static String lastStatus() { return lastStatus; }
    public static int uploadPercent() { return uploadPercent; }
    public static int downloadPercent() { return downloadPercent; }
    public static void cancelPreparing() { var runtime = ACTIVE.get(); if (runtime != null && !runtime.launchRequested) runtime.cancelled.set(true); }

    private void prepare() {
        try {
            if (!E4mcRelayBridge.supported()) { tell("screen.hosthop.e4mc_required"); cleanup(); return; }
            tell("screen.hosthop.preparing");
            stage = "drive authorization";
            GoogleDriveAuthorization.Tokens token;
            try {
                token = credentials.access();
            } catch (DriveCredentialManager.ReauthorizationRequired expired) { tell("screen.hosthop.reauthorize_drive"); cleanup(); return; }
            catch (DriveCredentialManager.AuthorizationRequired missing) { tell("screen.hosthop.authorize_first"); cleanup(); return; }
            catch (IOException unavailable) { tell("screen.hosthop.drive_access_failed"); cleanup(); return; }
            if (!invitation.folder().equals(token.folderId())) throw new IOException("Drive folder does not match invitation");
            Path config = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("hosthop");
            stage = "local session lock";
            Files.createDirectories(config);
            processChannel = FileChannel.open(config.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { processLock = processChannel.tryLock(); }
            catch (OverlappingFileLockException inUse) { throw new LocalProcessBusy(); }
            if (processLock == null) throw new LocalProcessBusy();
            pendingPath = config.resolve("pending-host.dat");
            var pending = new WindowsSecretStore(pendingPath);
            if (pending.exists()) {
                // These stages are written before Minecraft can be launched. A later
                // stage may contain unsaved play and must never be discarded this way.
                try {
                    var evidence = pending.load();
                    if (!PendingHostRecovery.abortUnstarted(evidence, invitation.world(), invitation.principal(), authority::abort, authority::cancelAcquisition)
                            && !recoverCommittedHost(evidence, token.accessToken())
                            && !recoverStoppedHost(evidence, token.accessToken())) {
                        tell("screen.hosthop.recovery_required"); cleanup(); return;
                    }
                    Files.deleteIfExists(pendingPath);
                } catch (IOException | InterruptedException | RuntimeException unresolved) {
                    if (unresolved instanceof InterruptedException) Thread.currentThread().interrupt();
                    tell("screen.hosthop.recovery_required"); cleanup(); return;
                }
            }
            if (cancelled.get() || !minecraft.isRunning()) { tell("screen.hosthop.cancelled"); cleanup(); return; }
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
            stage = "publishing compatibility";
            var compatibility = dev.hosthop.config.CompatibilityManifest.capture(game);
            authority.publishCompatibility(invitation.world(), id, acquisition.epoch(), compatibility.json());
            stage = "restoring world";
            scheduleRenewal(acquisition, false);
            if (cancelled.get() || !minecraft.isRunning()) { cancelled.set(true); abortBeforeStart(); return; }
            transfer.prepare(acquisition, initial, sessionDir.resolve("initial.zip"), worldPath, token.accessToken(), sessionDir.getParent());
            if (cancelled.get() || !minecraft.isRunning()) { cancelled.set(true); abortBeforeStart(); return; }
            stage = "preparing host player identity";
            HostPlayerIdentity.prepare(worldPath);
            pending.save(pendingData("starting", id, levelId));
            stage = "starting Minecraft";
            monitor = new SaveFailureMonitor();
            relay = new RelayPublisher(authority, acquisition, gate, relayWorker, () -> {
                relayReady = true; tell("screen.hosthop.host_ready");
            }, error -> fail("screen.hosthop.host_failed"), renewer, RELAY_RECONNECT_SECONDS);
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
            if (acquisition != null && acquisition.epoch() > 0 && !launchRequested) {
                abortBeforeStart();
                if (error instanceof SharedWorldTransfer.BaseAccessRequired && lastStatus.equals("screen.hosthop.host_failed"))
                    tell("screen.hosthop.base_access_required");
                else if (error instanceof dev.hosthop.snapshot.DiskSpace.InsufficientSpace && lastStatus.equals("screen.hosthop.host_failed"))
                    tell("screen.hosthop.disk_insufficient");
                else if (error instanceof dev.hosthop.storage.GoogleDriveSnapshotStore.QuotaExceeded && lastStatus.equals("screen.hosthop.host_failed"))
                    tell("screen.hosthop.drive_insufficient");
            } else {
                tell(error instanceof LocalProcessBusy ? "screen.hosthop.local_process_busy"
                        : error instanceof dev.hosthop.snapshot.DiskSpace.InsufficientSpace ? "screen.hosthop.disk_insufficient"
                        : error instanceof dev.hosthop.storage.GoogleDriveSnapshotStore.QuotaExceeded ? "screen.hosthop.drive_insufficient" : "screen.hosthop.host_failed");
                cleanup();
            }
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
    private boolean recoverCommittedHost(Map<String, String> evidence, String driveToken) throws IOException, InterruptedException {
        var candidate = PendingHostRecovery.findCommitted(evidence, invitation.world(), invitation.principal(), minecraft.gameDirectory.toPath());
        if (candidate == null) return false;
        try (var lock = dev.hosthop.snapshot.StoppedWorldLock.acquire(candidate.world())) {
            var recovery = gate.beginRecovery(candidate.record());
            transfer.recoverStopped(recovery, candidate.journal(), true, driveToken, invitation.folder(),
                    "stopped".equals(evidence.get("stage")) || "unclean-confirmed".equals(evidence.get("stage")));
            Files.delete(pendingPath);
            Files.delete(candidate.journal());
        }
        SessionCleanup.markReleased(candidate.journal().getParent());
        return true;
    }

    /** Retry an interrupted snapshot only while its original lease is still active. */
    private boolean recoverStoppedHost(Map<String, String> evidence, String driveToken) throws IOException, InterruptedException {
        var stopped = PendingHostRecovery.findStopped(evidence, invitation.world(), invitation.principal(), minecraft.gameDirectory.toPath());
        if (stopped == null) stopped = PendingHostRecovery.findConfirmedUnclean(evidence, invitation.world(), invitation.principal(), minecraft.gameDirectory.toPath());
        if (stopped == null) return false;
        Path journal = stopped.session().resolve("publication.dat");
        // A journal may identify a commit already in flight; never replace it.
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return false;
        LeaseCoordinator.Acquisition recovered = null;
        try (var lock = dev.hosthop.snapshot.StoppedWorldLock.acquire(stopped.world())) {
            var attempt = coordinator.begin(invitation.world(), stopped.lease(), invitation.principal(), 90, 5);
            try {
                if (coordinator.recoverAcquisition(attempt) && attempt.epoch() == stopped.epoch()
                        && attempt.generation() == stopped.generation()
                        && gate.resumeStopped(attempt.attempt(), System.nanoTime(), System.currentTimeMillis())) recovered = attempt;
            } catch (LeaseAuthorityClient.LeaseExpired expired) { /* The original grant has expired. */ }
            if (recovered == null && gate.phase() != LocalHostGate.Phase.ACQUIRING)
                throw new IOException("Stopped host lease did not match recovery evidence");
            if (recovered == null) {
                gate.requireStop(attempt.attempt());
                if (!gate.confirmStopped(attempt.attempt())) throw new IOException("Recovery gate could not be reset");
            } else acquisition = recovered;
        }
        if (recovered == null) {
            tell("screen.hosthop.saving_world");
            transfer.publishExpiredStopped(stopped, invitation.world(), invitation.principal(), invitation.folder(), driveToken);
            Files.delete(pendingPath);
            Files.delete(journal);
            SessionCleanup.markReleased(stopped.session());
            return true;
        }
        var renewal = scheduleRenewal(recovered, true);
        try {
            Path archive = stopped.session().resolve("retry-" + UUID.randomUUID() + ".zip");
            transfer.publishAfterStopped(recovered, stopped.world(), archive, journal, UUID.randomUUID().toString(),
                    invitation.folder(), driveToken, true);
            Files.delete(pendingPath);
            Files.delete(journal);
        } finally { renewal.cancel(false); }
        SessionCleanup.markReleased(stopped.session());
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

    /**
     * Runs every RENEW_TICK seconds. A healthy lease is renewed every RENEW_INTERVAL seconds; after a
     * transport failure it is retried on every tick for as long as the current lease leaves STOP_MARGIN
     * seconds, so one network blip no longer ends the session.
     */
    private void renew(LeaseCoordinator.Acquisition target) {
        try {
            if (target == null || acquisition != target) return;
            long now = System.nanoTime();
            if (!renewalRetrying && now - lastRenewed < TimeUnit.SECONDS.toNanos(RENEW_INTERVAL)) return;
            boolean renewed = coordinator.renewTolerant(target);
            if (acquisition != target) return;
            if (renewed) {
                if (renewalRetrying) com.mojang.logging.LogUtils.getLogger().info("HostHop lease renewal recovered");
                renewalRetrying = false;
                lastRenewed = now;
                return;
            }
            if (renewablePhase()) fail("screen.hosthop.lease_failed");
        } catch (LeaseAuthorityClient.LeaseRejected | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (acquisition == target) fail("screen.hosthop.lease_failed");
        } catch (IOException transportFailure) {
            if (acquisition != target) return;
            renewalRetrying = true;
            if (gate.leaseUsableFor(target.attempt(), STOP_MARGIN, System.nanoTime(), System.currentTimeMillis()))
                com.mojang.logging.LogUtils.getLogger().warn("HostHop lease renewal failed ({}); retrying while the lease is still valid",
                        transportFailure.getClass().getSimpleName());
            else if (renewablePhase()) fail("screen.hosthop.lease_failed");
        }
    }
    private boolean renewablePhase() {
        var phase = gate.phase();
        return phase != LocalHostGate.Phase.IDLE && phase != LocalHostGate.Phase.RELEASE_REQUIRED && phase != LocalHostGate.Phase.ABORTING;
    }
    /** renewNow renews on the first tick, for a recovered grant whose remaining time is unknown. */
    private java.util.concurrent.ScheduledFuture<?> scheduleRenewal(LeaseCoordinator.Acquisition target, boolean renewNow) {
        renewalRetrying = renewNow;
        lastRenewed = System.nanoTime();
        return renewer.scheduleWithFixedDelay(() -> renew(target), RENEW_TICK, RENEW_TICK, TimeUnit.SECONDS);
    }

    private void abortBeforeStart() {
        try {
            if (acquisition != null && acquisition.epoch() > 0 && coordinator.abortBeforeHosting(acquisition)) {
                Files.deleteIfExists(pendingPath);
                SessionCleanup.markReleased(sessionDir);
                tell(cancelled.get() ? "screen.hosthop.cancelled" : "screen.hosthop.host_failed");
            } else tell("screen.hosthop.recovery_required");
        } catch (Exception failure) { tell("screen.hosthop.recovery_required"); }
        cleanup();
    }

    /** Disk cleanup must never turn a successful save into a failure. */
    private void removeOldSessions() {
        try {
            int removed = SessionCleanup.removeReleased(minecraft.gameDirectory.toPath(), KEEP_RELEASED_SESSIONS);
            if (removed > 0) com.mojang.logging.LogUtils.getLogger().info("HostHop removed {} old local session folders", removed);
        } catch (IOException | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("HostHop could not remove old local sessions ({})", failure.getClass().getSimpleName());
        }
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
        long interval = TimeUnit.MINUTES.toSeconds(5);
        boolean timerSmoke = !net.neoforged.fml.loading.FMLEnvironment.isProduction()
                && Boolean.getBoolean("hosthop.checkpointTimerSmoke") && !Boolean.getBoolean("hosthop.checkpointTimerUseProduction");
        renewer.scheduleWithFixedDelay(() -> queueCheckpoint(false), timerSmoke ? 15 : interval, interval, TimeUnit.SECONDS);
    }

    public static boolean requestManualCheckpoint() {
        var runtime = ACTIVE.get();
        return runtime != null && runtime.queueCheckpoint(true);
    }
    public static boolean checkpointActive() { var runtime = ACTIVE.get(); return runtime != null && runtime.checkpointInFlight.get(); }

    private boolean queueCheckpoint(boolean manual) {
        if (failed.get() || finishing.get() || !relayReady || acquisition == null) return false;
        if (!checkpointInFlight.compareAndSet(false, true)) {
            if (!manual) com.mojang.logging.LogUtils.getLogger().warn("HostHop scheduled checkpoint skipped while the previous one is active");
            return false;
        }
        try {
            worker.execute(() -> {
                try { if (createCheckpoint() && manual) tell("screen.hosthop.checkpoint_saved"); }
                catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    com.mojang.logging.LogUtils.getLogger().warn("HostHop checkpoint failed ({})", failure.getClass().getSimpleName());
                    tell("screen.hosthop.checkpoint_failed");
                } finally { checkpointInFlight.set(false); }
            });
        } catch (RuntimeException rejected) { checkpointInFlight.set(false); return false; }
        return true;
    }

    private boolean createCheckpoint() throws Exception {
        var current = server; var lease = acquisition;
        if (current == null || lease == null || !current.isRunning() || !gate.mayContinueHosting(lease.attempt(), System.nanoTime(), System.currentTimeMillis())) return false;
        boolean full = authority.fullCheckpointDue(invitation.world());
        if (!net.neoforged.fml.loading.FMLEnvironment.isProduction() && Boolean.getBoolean("hosthop.forceFullCheckpointSmoke")) {
            full = true;
            System.clearProperty("hosthop.forceFullCheckpointSmoke");
        }
        Path dir = Files.createDirectory(sessionDir.resolve("checkpoint-" + UUID.randomUUID()));
        try {
            Path copy = dir.resolve("world");
            captureCheckpoint(current, lease, copy);
            if (!checkpointAllowed(current, lease)) return false;
            String token = credentials.access().accessToken();
            var result = transfer.publishCheckpoint(lease, copy, dir, invitation.folder(), token, full);
            com.mojang.logging.LogUtils.getLogger().info("HostHop checkpoint saved ({})", result.kind());
            if ("full".equals(result.kind())) {
                Path retained = sessionDir.resolve("checkpoint-base-" + result.id() + ".zip");
                Files.move(dir.resolve("checkpoint.zip"), retained);
                Path previous = transfer.adoptCheckpointBase(result, retained);
                if (previous != null && previous.getParent().equals(sessionDir)
                        && previous.getFileName().toString().matches("checkpoint-base-[0-9a-f-]{36}\\.zip"))
                    Files.deleteIfExists(previous);
            }
            pruneCheckpoints(lease, credentials.access().accessToken());
            return true;
        } finally {
            try { removeCheckpointWorkspace(dir); }
            catch (IOException cleanupFailure) { com.mojang.logging.LogUtils.getLogger().warn("HostHop could not remove checkpoint workspace"); }
        }
    }

    private boolean checkpointAllowed(IntegratedServer current, LeaseCoordinator.Acquisition lease) {
        return !finishing.get() && !failed.get() && current.isRunning()
                && gate.mayContinueHosting(lease.attempt(), System.nanoTime(), System.currentTimeMillis());
    }

    private void captureCheckpoint(IntegratedServer current, LeaseCoordinator.Acquisition lease, Path copy) throws Exception {
        long started = System.nanoTime();
        long retryUntil = started + TimeUnit.SECONDS.toNanos(30);
        long deadline = started + TimeUnit.MINUTES.toNanos(5);
        while (checkpointAllowed(current, lease) && System.nanoTime() - retryUntil < 0) {
            if (tryCaptureCheckpoint(current, lease, copy, retryUntil, deadline)) return;
            Thread.sleep(1000);
        }
        throw new IOException("Host was busy or stopped before checkpoint capture");
    }

    private boolean tryCaptureCheckpoint(IntegratedServer current, LeaseCoordinator.Acquisition lease, Path copy,
                                         long retryUntil, long deadline) throws Exception {
        var captured = new CompletableFuture<LiveWorldCopy>();
        current.execute(() -> {
            try {
                if (captured.isCancelled() || !checkpointAllowed(current, lease)) throw new IOException("Host stopped");
                if (!CheckpointSave.ready(current)) {
                    captured.complete(null);
                    return;
                }
                var snapshot = CheckpointSave.capture(current, worldPath, monitor);
                if (!captured.complete(snapshot)) snapshot.close();
            } catch (Throwable failure) { captured.completeExceptionally(failure); }
        });
        try {
            LiveWorldCopy snapshot;
            while (true) {
                try { snapshot = captured.get(1, TimeUnit.SECONDS); break; }
                catch (java.util.concurrent.TimeoutException waiting) {
                    if (!checkpointAllowed(current, lease) || System.nanoTime() - retryUntil >= 0)
                        throw new IOException("Checkpoint save stopped");
                }
            }
            if (snapshot == null) return false;
            long copyStarted = System.nanoTime();
            snapshot.copyTo(copy, () -> checkpointAllowed(current, lease) && System.nanoTime() - deadline < 0);
            if (monitor.hasFailure()) throw new IOException("Minecraft checkpoint save failed");
            com.mojang.logging.LogUtils.getLogger().info("HostHop checkpoint background copy completed in {} ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - copyStarted));
            return true;
        } catch (IOException | java.util.concurrent.ExecutionException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("HostHop local checkpoint capture failed", failure);
            throw failure;
        } finally {
            if (!captured.cancel(false)) captured.thenAccept(snapshot -> {
                if (snapshot != null) snapshot.close();
            });
        }
    }

    private void pruneCheckpoints(LeaseCoordinator.Acquisition lease, String token) throws InterruptedException {
        if (storage instanceof GoogleDriveSnapshotStore drive) {
            try {
                for (var old : authority.checkpointTrashPending(invitation.world())) {
                    if (!gate.mayContinueHosting(lease.attempt(), System.nanoTime(), System.currentTimeMillis())) break;
                    drive.trash(old.object(), invitation.folder(), token);
                    authority.checkpointTrashDone(invitation.world(), old);
                }
                int keep = authority.checkpointRetention(invitation.world());
                for (var old : authority.checkpointPruneCandidates(invitation.world())) {
                    if (!gate.mayContinueHosting(lease.attempt(), System.nanoTime(), System.currentTimeMillis())) break;
                    if (!drive.canUseBase(old.object(), invitation.folder(), token)) continue;
                    try { authority.pruneCheckpoint(invitation.world(), old, keep); }
                    catch (IOException first) { authority.pruneCheckpoint(invitation.world(), old, keep); }
                    drive.trash(old.object(), invitation.folder(), token);
                    authority.checkpointTrashDone(invitation.world(), old);
                }
            } catch (IOException cleanupFailure) {
                com.mojang.logging.LogUtils.getLogger().warn("HostHop could not complete backup retention cleanup");
                tell("screen.hosthop.checkpoint_cleanup_failed");
            }
        }
    }

    private void removeCheckpointWorkspace(Path directory) throws IOException {
        Path expectedParent = sessionDir.toRealPath();
        Path actual = directory.toRealPath();
        if (Files.isSymbolicLink(directory) || !actual.getParent().equals(expectedParent)
                || !actual.getFileName().toString().matches("checkpoint-[0-9a-f-]{36}")) throw new IOException("Invalid checkpoint workspace");
        Files.walkFileTree(actual, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
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
        var keepAlive = new dev.hosthop.world.SaveKeepAlive();
        try { worker.execute(() -> {
            String shutdownStage = "server stop verification";
            try {
                candidate.getRunningThread().join(30_000);
                if (candidate.getRunningThread().isAlive() || !ServerStopSignal.completedFor(candidate) || monitor == null
                        || monitor.hasFailure())
                    throw new IOException("Server shutdown could not be verified");
                shutdownStage = "relay shutdown";
                for (int i = 0; i < 50 && relay.sessionOpen(); i++) Thread.sleep(100);
                CompletableFuture.runAsync(() -> {}, relayWorker).get(15, TimeUnit.SECONDS);
                if (relay.sessionOpen()) throw new IOException("Relay did not close");
                // A lost lease must block publication, but a verified stopped world
                // is still useful recovery evidence if the same lease remains valid.
                var evidence = pendingData("stopped", acquisition.lease(), worldPath.getFileName().toString());
                new WindowsSecretStore(pendingPath).save(evidence);
                if (failed.get() || gate.phase() != LocalHostGate.Phase.QUIESCING) {
                    // This attempt may no longer publish (for example after a relay or lease failure), but the
                    // world stopped cleanly and was recorded as such. Run the same recovery a restart would:
                    // it publishes under the lease if it is still active, or as an expired stopped host.
                    shutdownStage = "stopped-host recovery";
                    if (gate.phase() != LocalHostGate.Phase.STOP_REQUIRED || !gate.confirmStopped(acquisition.attempt()))
                        throw new IOException("Lease cannot publish");
                    tell("screen.hosthop.saving_world");
                    if (!recoverStoppedHost(evidence, credentials.access().accessToken())) throw new IOException("Stopped host could not be recovered");
                    Files.deleteIfExists(pendingPath);
                    SessionCleanup.markReleased(sessionDir);
                    tell("screen.hosthop.saved_world");
                    removeOldSessions();
                    return;
                }
                tell("screen.hosthop.saving_world");
                shutdownStage = "Drive authorization";
                String token = credentials.access().accessToken();
                Path journal = sessionDir.resolve("publication.dat");
                try {
                    shutdownStage = "snapshot publication";
                    transfer.publishAfterStopped(acquisition, worldPath, sessionDir.resolve("final.zip"), journal,
                            UUID.randomUUID().toString(), invitation.folder(), token, true);
                } catch (IOException | InterruptedException failure) {
                    if (Files.exists(journal) && gate.phase() == LocalHostGate.Phase.STOP_REQUIRED)
                        transfer.recoverStopped(acquisition.attempt(), journal, true, token, invitation.folder());
                    else if (Files.exists(journal) && gate.phase() == LocalHostGate.Phase.RELEASE_REQUIRED)
                        transfer.completeRelease(acquisition.attempt(), journal, PendingPublicationJournal.read(journal), true);
                    else throw failure;
                }
                Files.deleteIfExists(pendingPath);
                Files.deleteIfExists(journal);
                SessionCleanup.markReleased(sessionDir);
                tell("screen.hosthop.saved_world");
                removeOldSessions();
            } catch (Exception failure) {
                com.mojang.logging.LogUtils.getLogger().warn("HostHop shutdown failed during {} ({})", shutdownStage, failure.getClass().getSimpleName());
                tell(failure instanceof dev.hosthop.snapshot.DiskSpace.InsufficientSpace ? "screen.hosthop.disk_recovery"
                        : failure instanceof dev.hosthop.storage.GoogleDriveSnapshotStore.QuotaExceeded ? "screen.hosthop.drive_recovery" : "screen.hosthop.recovery_required");
            } finally { try { cleanup(); } finally { keepAlive.close(); } }
        }); } catch (RuntimeException rejected) {
            keepAlive.close();
            throw rejected;
        }
    }

    private void tell(String key) {
        lastStatus = key;
        if (key.equals("screen.hosthop.saving_world") || key.equals("screen.hosthop.saved_world")
                || key.equals("screen.hosthop.recovery_required") || key.equals("screen.hosthop.disk_recovery")
                || key.equals("screen.hosthop.drive_recovery")) {
            try { dev.hosthop.world.SaveStatus.write(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("hosthop"), key); }
            catch (IOException failure) { com.mojang.logging.LogUtils.getLogger().warn("HostHop could not persist save status"); }
        }
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
        if (quitAfterSave) {
            quitAfterSave = false;
            // Only close on success; a failed save must stay visible on the title screen.
            if ("screen.hosthop.saved_world".equals(lastStatus)) minecraft.execute(minecraft::stop);
        }
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
