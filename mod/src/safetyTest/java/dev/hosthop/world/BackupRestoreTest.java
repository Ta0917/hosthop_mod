package dev.hosthop.world;

import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.snapshot.BinaryDelta;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import dev.hosthop.storage.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class BackupRestoreTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("hosthop-backup-restore-");
        var game = Files.createDirectory(root.resolve("game")); Files.createDirectory(game.resolve("saves"));
        var world = Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "old");
        var base = root.resolve("base.zip"); SnapshotWriter.create(world, base);
        Files.writeString(world.resolve("level.dat"), "new");
        var target = root.resolve("target.zip"); SnapshotWriter.create(world, target);
        var baseRef = ref("base", base);
        var delta = root.resolve("delta.bin"); BinaryDelta.create(base, baseRef, target, delta);
        SnapshotStore storage = new SnapshotStore() {
            public ObjectRef upload(Path path, String folder, String token) { throw new AssertionError("restore uploaded data"); }
            public SnapshotVerifier.Result download(ObjectRef object, Path destination, String token) throws IOException {
                Files.copy(object.fileId().equals("base") ? base : target, destination); return SnapshotVerifier.verify(destination);
            }
            public void downloadRaw(ObjectRef object, Path destination, String token) throws IOException {
                Files.copy(object.fileId().equals("delta") ? delta : base, destination);
            }
        };
        String old = BackupRestore.restore(new LeaseAuthorityClient.Snapshot(1, baseRef), storage, "token", game);
        String latest = BackupRestore.restore(new LeaseAuthorityClient.Snapshot(2, ref("delta", delta)), storage, "token", game);
        if (!Files.readString(game.resolve("saves").resolve(old).resolve("level.dat")).equals("old")
                || !Files.readString(game.resolve("saves").resolve(latest).resolve("level.dat")).equals("new") || old.equals(latest))
            throw new AssertionError("wrong generation restored");
        System.out.println("Backup generation restore checks passed");
    }
    private static ObjectRef ref(String id, Path file) throws Exception {
        return new ObjectRef(id, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))), Files.size(file));
    }
}
