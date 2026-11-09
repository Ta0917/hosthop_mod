package dev.hosthop.world;

import dev.hosthop.config.Invitation;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import dev.hosthop.snapshot.StoppedWorldLock;
import dev.hosthop.storage.WindowsSecretStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Export verified stopped local data to a new world without modifying cloud state. */
public final class LocalRecoveryExport {
    private LocalRecoveryExport() {}
    public record Exported(String name, boolean clean) {}

    public static Exported export(Path gameDirectory, Path configDirectory, Invitation invitation) throws IOException {
        if (gameDirectory == null || configDirectory == null || invitation == null) throw new IOException("Invalid recovery request");
        Path config = configDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(config) || !Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid recovery configuration");
        Path lockPath = config.resolve("runtime.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            java.nio.channels.FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("HostHop is already running", busy); }
            if (lock == null) throw new IOException("HostHop is already running");
            try (lock) {
                var pending = new WindowsSecretStore(config.resolve("pending-host.dat"));
                var evidence = pending.load();
                var stopped = PendingHostRecovery.findForExport(evidence, invitation.world(), invitation.principal(), gameDirectory);
                if (stopped == null) throw new IOException("No recoverable local session");
                Path game = gameDirectory.toRealPath(), saves = game.resolve("saves");
                if (Files.isSymbolicLink(saves) || !Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS)
                        || !saves.toRealPath().startsWith(game)) throw new IOException("Invalid saves directory");
                String id = UUID.randomUUID().toString().replace("-", "");
                String name = "hosthop_recovery_" + id;
                Path archive = stopped.session().resolve("recovery-export-" + id + ".zip");
                try (var worldLock = StoppedWorldLock.acquire(stopped.world())) { SnapshotWriter.create(stopped.world(), archive); }
                SnapshotVerifier.restore(archive, saves.resolve(name));
                return new Exported(name, "stopped".equals(evidence.get("stage")));
            }
        }
    }
}
