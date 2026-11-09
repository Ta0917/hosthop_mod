package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Opt-in offline verification against explicitly supplied stopped snapshot copies. */
public final class BinaryDeltaFileCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected base snapshot, changed snapshot and new output directory");
        Path base = Path.of(args[0]), target = Path.of(args[1]), dir = Files.createDirectory(Path.of(args[2]));
        SnapshotVerifier.verify(base); SnapshotVerifier.verify(target);
        var ref = new ObjectRef("offline-base", hash(base), Files.size(base));
        long start = System.nanoTime();
        Path delta = dir.resolve("delta.bin"), result = dir.resolve("restored.zip");
        BinaryDelta.create(base, ref, target, delta);
        long created = System.nanoTime();
        BinaryDelta.apply(delta, base, result);
        if (Files.mismatch(target, result) != -1) throw new AssertionError("Restored snapshot differs");
        System.out.printf("HOSTHOP_DELTA_FILE_CHECK full=%d delta=%d createSeconds=%.2f totalSeconds=%.2f exact=true%n",
                Files.size(target), Files.size(delta), (created-start)/1e9, (System.nanoTime()-start)/1e9);
    }
    private static String hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) { byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
