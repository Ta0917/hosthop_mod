package dev.hosthop.snapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The parallel writer must produce exactly the bytes the earlier single-threaded ZipOutputStream did; otherwise
 * archives would stop lining up with bases written before it, and every delta would grow.
 */
final class ParallelZipIdentityCheck {
    private ParallelZipIdentityCheck() {}

    static void run() throws Exception {
        var root = Files.createTempDirectory("hosthop-zip-identity-");
        try {
            var world = Files.createDirectories(root.resolve("world"));
            Files.createDirectories(world.resolve("region"));
            Files.createDirectories(world.resolve("DIM-1/region"));
            var random = new Random(42);
            Files.write(world.resolve("level.dat"), bytes(random, 3_000, true));
            Files.write(world.resolve("empty.dat"), new byte[0]);
            Files.write(world.resolve("region/r.0.0.mca"), bytes(random, 3 * 1024 * 1024 + 17, false));
            Files.write(world.resolve("region/r.0.1.mca"), bytes(random, 2 * 1024 * 1024, true));
            Files.write(world.resolve("DIM-1/region/r.-1.0.mca"), bytes(random, 700_000, false));
            for (int i = 0; i < 40; i++) Files.write(world.resolve("region/r." + i + ".5.mca"), bytes(random, 1000 + i * 4099, i % 2 == 0));
            long saved = SnapshotWriter.inMemoryLimit;
            for (long limit : new long[] {saved, 1024 * 1024}) {
                // The second pass streams the larger files instead of holding them in memory.
                SnapshotWriter.inMemoryLimit = limit;
                var parallel = root.resolve("parallel-" + limit + ".zip");
                try { SnapshotWriter.create(world, parallel); }
                finally { SnapshotWriter.inMemoryLimit = saved; }
                var reference = root.resolve("reference-" + limit + ".zip");
                writeReference(parallel, reference);
                long mismatch = Files.mismatch(parallel, reference);
                if (mismatch != -1) throw new AssertionError("Parallel archive differs from ZipOutputStream at byte " + mismatch);
            }
            // Restoring spreads files over threads; every file must come back exactly.
            var archive = root.resolve("parallel-" + saved + ".zip");
            var restored = root.resolve("restored");
            SnapshotVerifier.restore(archive, restored);
            try (var walk = Files.walk(world)) {
                for (var file : walk.filter(Files::isRegularFile).toList()) {
                    var copy = restored.resolve(world.relativize(file).toString());
                    if (Files.mismatch(file, copy) != -1) throw new AssertionError("Restored file differs: " + file.getFileName());
                }
            }
            // A damaged file anywhere must fail both checks and leave no partly restored world behind.
            var damaged = root.resolve("damaged.zip");
            Files.copy(archive, damaged);
            try (var channel = java.nio.channels.FileChannel.open(damaged, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
                long position = Files.size(damaged) / 3;
                var one = java.nio.ByteBuffer.allocate(1); channel.read(one, position);
                channel.write(java.nio.ByteBuffer.wrap(new byte[] {(byte) (one.array()[0] ^ 0x5a)}), position);
            }
            try { SnapshotVerifier.verify(damaged); throw new AssertionError("Damaged archive verified"); }
            catch (java.io.IOException expected) { }
            var rejected = root.resolve("rejected");
            try { SnapshotVerifier.restore(damaged, rejected); throw new AssertionError("Damaged archive restored"); }
            catch (java.io.IOException expected) { }
            if (Files.exists(rejected)) throw new AssertionError("Partly restored world left behind");
        } finally {
            try (var walk = Files.walk(root)) {
                for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /** What SnapshotWriter wrote before it compressed in parallel, fed the same entries in the same order. */
    private static void writeReference(Path parallel, Path reference) throws Exception {
        try (var source = new ZipFile(parallel.toFile());
             var zip = new ZipOutputStream(Files.newOutputStream(reference))) {
            zip.setLevel(1);
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var original = entries.nextElement();
                var entry = new ZipEntry(original.getName());
                entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                try (var input = source.getInputStream(original)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private static byte[] bytes(Random random, int size, boolean compressible) {
        byte[] data = new byte[size];
        if (compressible) for (int i = 0; i < size; i++) data[i] = (byte) ((i / 64) % 7 == 0 ? random.nextInt(256) : i % 13);
        else random.nextBytes(data);
        return data;
    }
}
