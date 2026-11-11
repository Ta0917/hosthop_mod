package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reuses an exact, locally retained cloud snapshot; the authority remains the source of truth. */
public final class LocalSnapshotCache {
    private LocalSnapshotCache() {}

    /** Tests existing candidates without copying them or trusting an unverified filename. */
    public static boolean containsMatching(Path sessions, ObjectRef expected) throws IOException {
        if (sessions == null || expected == null || expected.size() <= 0 || expected.sha256() == null
                || !expected.sha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid cache request");
        if (!Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var children = Files.newDirectoryStream(sessions)) {
            for (Path session : children) {
                if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) continue;
                for (String name : new String[]{"final.zip", "initial.zip", "base.zip", "delta.bin", "remote-object.bin"}) {
                    Path source = session.resolve(name);
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != expected.size()) continue;
                    MessageDigest digest;
                    try { digest = MessageDigest.getInstance("SHA-256"); }
                    catch (NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
                    try (InputStream in = Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[1024 * 1024]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cache inspection interrupted");
                            digest.update(buffer, 0, n);
                        }
                    }
                    if (HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) return true;
                }
            }
        }
        return false;
    }

    public static boolean copyMatching(Path sessions, Path target, ObjectRef expected) throws IOException {
        if (sessions == null || target == null || expected == null || expected.size() <= 0
                || expected.sha256() == null || !expected.sha256().matches("[0-9a-f]{64}"))
            throw new IOException("Invalid cache request");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Snapshot destination already exists");
        if (!Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var children = Files.newDirectoryStream(sessions)) {
            for (Path session : children) {
                if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) continue;
                for (String name : new String[]{"final.zip", "initial.zip", "base.zip", "delta.bin", "remote-object.bin"}) {
                    Path source = session.resolve(name);
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != expected.size()) continue;
                    Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".hosthop-cache-", ".tmp");
                    try {
                        MessageDigest digest;
                        try { digest = MessageDigest.getInstance("SHA-256"); }
                        catch (NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
                        long total = 0;
                        try (InputStream in = Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                             OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                            byte[] buffer = new byte[1024 * 1024];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (n > expected.size() - total) break;
                                digest.update(buffer, 0, n); out.write(buffer, 0, n); total += n;
                            }
                        }
                        if (total == expected.size() && HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                            Files.move(temporary, target);
                            return true;
                        }
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        }
        return false;
    }
}
