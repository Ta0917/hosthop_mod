package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/** Opt-in disk-only test with an archive larger than the signed 32-bit boundary. */
public final class LargeWorldOfflineCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a new test directory");
        Path root = Files.createDirectory(Path.of(args[0]));
        Path world = Files.createDirectory(root.resolve("world"));
        Path data = world.resolve("region.dat"), base = root.resolve("base.zip"), target = root.resolve("target.zip");
        Path delta = root.resolve("delta.bin"), restored = root.resolve("restored.zip");
        byte[] block = new byte[4 * 1024 * 1024];
        new Random(4127).nextBytes(block);
        long start = System.nanoTime();
        try (var out = FileChannel.open(data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int i = 0; i < 514; i++) {
                var buffer = ByteBuffer.wrap(block);
                while (buffer.hasRemaining()) out.write(buffer);
            }
            out.force(true);
        }
        SnapshotWriter.create(world, base);
        try (var file = FileChannel.open(data, StandardOpenOption.WRITE)) {
            file.position(100L * 1024 * 1024);
            file.write(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
            file.force(true);
        }
        SnapshotWriter.create(world, target);
        if (Files.size(base) <= Integer.MAX_VALUE || Files.size(target) <= Integer.MAX_VALUE) throw new AssertionError("Archive did not exceed 2 GiB");
        var hash = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(base)) { byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) != -1) hash.update(b, 0, n); }
        BinaryDelta.create(base, new ObjectRef("offline-base", HexFormat.of().formatHex(hash.digest()), Files.size(base)), target, delta);
        BinaryDelta.apply(delta, base, restored);
        if (Files.mismatch(target, restored) != -1) throw new AssertionError("Large reconstruction differs");
        System.out.printf("HOSTHOP_LARGE_WORLD_OFFLINE archive=%d delta=%d seconds=%.1f exact=true%n",
                Files.size(target), Files.size(delta), (System.nanoTime() - start) / 1e9);
        // Only delete files created by this test after success; retain failed runs for inspection.
        for (Path file : new Path[] { restored, delta, target, base, data }) Files.delete(file);
        Files.delete(world);
        Files.delete(root);
    }
}
