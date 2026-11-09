package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class LocalSnapshotCacheTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("hosthop-cache-test-");
        var sessions = Files.createDirectory(root.resolve("sessions"));
        var previous = Files.createDirectory(sessions.resolve("previous"));
        var current = Files.createDirectory(sessions.resolve("current"));
        var source = previous.resolve("final.zip");
        var target = current.resolve("initial.zip");
        byte[] content = "authoritative snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] altered = content.clone(); altered[0] ^= 1;
        var expected = new ObjectRef("object", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), content.length);
        Files.write(source, altered);
        if (LocalSnapshotCache.copyMatching(sessions, target, expected) || Files.exists(target)) throw new AssertionError("stale cache accepted");
        Files.write(source, content);
        if (!LocalSnapshotCache.copyMatching(sessions, target, expected) || Files.mismatch(source, target) != -1) throw new AssertionError("matching cache missed");
        try { LocalSnapshotCache.copyMatching(sessions, target, expected); throw new AssertionError("destination overwritten"); }
        catch (IOException expectedFailure) { }
        if (Files.mismatch(source, target) != -1) throw new AssertionError("existing snapshot changed");
        System.out.println("Local snapshot cache checks passed");
    }
}
