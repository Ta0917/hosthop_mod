package dev.hosthop.world;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.storage.SnapshotStore;
import dev.hosthop.storage.WindowsSecretStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Reverts by publishing a new generation under an exclusive lease; older history remains. */
public final class SharedBackupRestore {
    private SharedBackupRestore() {}

    public static void restore(Invitation invitation, LeaseAuthorityClient authority, SnapshotStore storage, String token,
                               Path gameDirectory, Path configDirectory, LeaseAuthorityClient.Snapshot snapshot,
                               LeaseAuthorityClient.Checkpoint checkpoint) throws IOException, InterruptedException {
        restore(invitation, authority, storage, token, gameDirectory, configDirectory, snapshot, checkpoint, (object, current) -> current);
    }

    public static void restore(Invitation invitation, LeaseAuthorityClient authority, SnapshotStore storage, String token,
                               Path gameDirectory, Path configDirectory, LeaseAuthorityClient.Snapshot snapshot,
                               LeaseAuthorityClient.Checkpoint checkpoint, BackupRestore.AccessGrant grant) throws IOException, InterruptedException {
        if (invitation == null || authority == null || storage == null || token == null || (snapshot == null) == (checkpoint == null))
            throw new IllegalArgumentException("Invalid restore request");
        Path game = gameDirectory.toRealPath(), config = configDirectory.toRealPath();
        if (!config.startsWith(game) || Files.isSymbolicLink(configDirectory)) throw new IOException("Invalid configuration");
        var pending = new WindowsSecretStore(config.resolve("pending-host.dat"));
        try (var channel = FileChannel.open(config.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            java.nio.channels.FileLock processLock;
            try { processLock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("HostHop is already running", busy); }
            if (processLock == null) throw new IOException("HostHop is already running");
            try (processLock) {
                if (pending.exists()) throw new IOException("Unresolved host recovery");
                String id = UUID.randomUUID().toString(), level = "hosthop_" + id.replace("-", "");
                Path sessions = game.resolve("hosthop").resolve("sessions");
                Files.createDirectories(sessions);
                if (!sessions.toRealPath().startsWith(game)) throw new IOException("Invalid session directory");
                Path session = Files.createDirectory(sessions.resolve(id));
                var evidence = new HashMap<String, String>();
                evidence.put("world", invitation.world()); evidence.put("principal", invitation.principal());
                evidence.put("lease", id); evidence.put("session", id); evidence.put("level", level); evidence.put("stage", "acquiring");
                pending.save(evidence);
                var gate = new LocalHostGate();
                var coordinator = new LeaseCoordinator(gate, authority, System::nanoTime, System::currentTimeMillis);
                var acquisition = coordinator.begin(invitation.world(), id, invitation.principal(), 90, 5);
                try {
                    if (!coordinator.acquire(acquisition)) throw new IOException("Restore lease rejected");
                } catch (IOException failure) {
                    try {
                        if (!coordinator.recoverAcquisition(acquisition)) throw failure;
                    } catch (IOException unresolved) {
                        try {
                            authority.cancelAcquisition(invitation.world(), invitation.principal(), id);
                            Files.deleteIfExists(config.resolve("pending-host.dat"));
                        } catch (Exception cancellation) { unresolved.addSuppressed(cancellation); }
                        throw unresolved;
                    }
                }
                SaveStatus.write(config, SaveStatus.SAVING);
                evidence.put("stage", "preparing"); evidence.put("epoch", Long.toString(acquisition.epoch()));
                evidence.put("generation", Long.toString(acquisition.generation())); pending.save(evidence);
                var renewer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "hosthop-restore-lease"); t.setDaemon(true); return t; });
                renewer.scheduleWithFixedDelay(() -> {
                    try { coordinator.renew(acquisition); }
                    catch (Exception failure) { gate.requireStop(acquisition.attempt()); }
                }, 15, 15, TimeUnit.SECONDS);
                boolean recordedStop = false;
                try {
                    String restored = snapshot != null ? BackupRestore.restore(snapshot, storage, token, game, grant)
                            : BackupRestore.restore(checkpoint, storage, token, game, grant);
                    Path saves = game.resolve("saves"), world = saves.resolve(level);
                    Files.move(saves.resolve(restored), world);
                    Files.createFile(world.resolve("session.lock"));
                    evidence.put("stage", "stopped"); pending.save(evidence); recordedStop = true;
                    if (!gate.resumeStopped(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis())) throw new IOException("Restore lease expired");
                    var transfer = new SharedWorldTransfer(gate, authority, storage, System::nanoTime, System::currentTimeMillis);
                    Path journal = session.resolve("publication.dat");
                    transfer.publishAfterStopped(acquisition, world, session.resolve("restore.zip"), journal,
                            UUID.randomUUID().toString(), invitation.folder(), token, true);
                    Files.delete(config.resolve("pending-host.dat"));
                    Files.delete(journal);
                    SaveStatus.write(config, SaveStatus.SAVED);
                } finally {
                    renewer.shutdownNow();
                    if (!recordedStop) {
                        try { if (coordinator.abortBeforeHosting(acquisition)) Files.deleteIfExists(config.resolve("pending-host.dat")); }
                        catch (Exception ignored) { }
                    }
                    if (pending.exists()) {
                        try { SaveStatus.write(config, SaveStatus.RECOVERY); } catch (IOException ignored) { }
                    }
                }
            }
        }
    }
}
