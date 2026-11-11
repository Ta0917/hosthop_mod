package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import dev.hosthop.storage.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Opportunistic, hash-verified cache warming. Never publishes a shared generation. */
public final class SnapshotPrefetch {
    private static final long MAX_OBJECT = 1024L * 1024 * 1024;
    private SnapshotPrefetch() {}

    public static boolean warm(Path sessions, SnapshotStore storage, ObjectRef head, String token) throws IOException, InterruptedException {
        if (sessions == null || storage == null || head == null || token == null) throw new IllegalArgumentException("Missing prefetch input");
        if (head.size() < 1 || head.size() > MAX_OBJECT) return false;
        Files.createDirectories(sessions);
        if (!Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sessions)) throw new IOException("Invalid prefetch directory");
        Path directory = Files.createTempDirectory(sessions, "prefetch-");
        Path temporary = directory.resolve("download.part"), temporaryBase = directory.resolve("base.part");
        try {
            boolean already = LocalSnapshotCache.copyMatching(sessions, temporary, head);
            if (!already) {
                storage.downloadRaw(head, temporary, token);
                verify(temporary, head);
            }
            if (!BinaryDelta.isDelta(temporary)) {
                if (!already) Files.move(temporary, directory.resolve("initial.zip"), StandardCopyOption.ATOMIC_MOVE);
                return !already;
            }
            var base = BinaryDelta.inspect(temporary).base();
            if (!already) Files.move(temporary, directory.resolve("remote-object.bin"), StandardCopyOption.ATOMIC_MOVE);
            else Files.delete(temporary);
            if (base.size() < 1 || base.size() > MAX_OBJECT || LocalSnapshotCache.containsMatching(sessions, base)) return !already;
            storage.downloadRaw(base, temporaryBase, token);
            verify(temporaryBase, base);
            Files.move(temporaryBase, directory.resolve("base.zip"), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(temporaryBase);
            try { Files.delete(directory); } catch (java.nio.file.DirectoryNotEmptyException expected) { }
        }
    }

    private static void verify(Path path, ObjectRef expected) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected.size()) throw new IOException("Prefetch size mismatch");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Prefetch interrupted");
                    digest.update(buffer, 0, read);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) throw new IOException("Prefetch digest mismatch");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
