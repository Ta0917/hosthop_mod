package dev.hosthop.world;

import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.snapshot.BinaryDelta;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.storage.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** Restore a cloud generation to a new local world; never changes the shared head. */
public final class BackupRestore {
    private BackupRestore() {}

    public static String restore(LeaseAuthorityClient.Snapshot snapshot, SnapshotStore storage, String token, Path game) throws IOException, InterruptedException {
        if (snapshot == null || snapshot.generation() < 1 || storage == null || token == null) throw new IOException("Invalid backup");
        Path root = game.toRealPath();
        Path saves = root.resolve("saves");
        if (!Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS) || !saves.toRealPath().startsWith(root)) throw new IOException("Invalid saves directory");
        Path sessions = root.resolve("hosthop").resolve("sessions");
        Files.createDirectories(sessions);
        if (!sessions.toRealPath().startsWith(root)) throw new IOException("Invalid sessions directory");
        Path workspace = Files.createTempDirectory(sessions, "backup-restore-");
        Path object = workspace.resolve("object.bin"), archive = object;
        storage.downloadRaw(snapshot.object(), object, token);
        if (BinaryDelta.isDelta(object)) {
            var info = BinaryDelta.inspect(object);
            Path base = workspace.resolve("base.zip");
            storage.download(info.base(), base, token);
            archive = workspace.resolve("restored.zip");
            BinaryDelta.apply(object, base, archive);
        } else SnapshotVerifier.verify(object);
        String name = "hosthop_backup_" + snapshot.generation() + "_" + UUID.randomUUID().toString().replace("-", "");
        SnapshotVerifier.restore(archive, saves.resolve(name));
        return name;
    }
}
