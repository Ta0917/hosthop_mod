package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class BinaryDeltaTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("hosthop-delta-test-");
        var world = Files.createDirectory(root.resolve("world"));
        byte[] region = new byte[5 * 1024 * 1024]; new java.util.Random(6).nextBytes(region);
        Files.write(world.resolve("region.dat"), region);
        Files.writeString(world.resolve("player.dat"), "before");
        var base = root.resolve("base.zip"); SnapshotWriter.create(world, base);
        var baseRef = new ObjectRef("base-file", hash(base), Files.size(base));
        Files.writeString(world.resolve("player.dat"), "after");
        Files.writeString(world.resolve("new.dat"), "new item");
        var updated = root.resolve("updated.zip"); SnapshotWriter.create(world, updated);
        var patch = root.resolve("delta.bin"); BinaryDelta.create(base, baseRef, updated, patch);
        if (Files.size(patch) >= Files.size(updated) / 2) throw new AssertionError("small change produced a large delta");
        if (!BinaryDelta.inspect(patch).base().equals(baseRef)) throw new AssertionError("base reference mismatch");
        var restored = root.resolve("restored.zip"); BinaryDelta.apply(patch, base, restored);
        if (Files.mismatch(updated, restored) != -1) throw new AssertionError("delta reconstruction mismatch");
        var wrongBase = root.resolve("wrong.zip"); Files.copy(base, wrongBase);
        try (var channel = java.nio.channels.FileChannel.open(wrongBase, java.nio.file.StandardOpenOption.WRITE)) { channel.write(java.nio.ByteBuffer.wrap(new byte[]{0})); }
        try { BinaryDelta.apply(patch, wrongBase, root.resolve("bad-base-output.zip")); throw new AssertionError("wrong base accepted"); }
        catch (IOException expected) { }
        var corrupt = root.resolve("corrupt.bin"); Files.copy(patch, corrupt);
        try (var channel = java.nio.channels.FileChannel.open(corrupt, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
            long pos = Files.size(corrupt) - 1; var one = java.nio.ByteBuffer.allocate(1); channel.read(one, pos);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) (one.array()[0] ^ 1)}), pos);
        }
        try { BinaryDelta.apply(corrupt, base, root.resolve("bad-delta-output.zip")); throw new AssertionError("corrupt delta accepted"); }
        catch (IOException expected) { }
        System.out.println("Binary delta reconstruction checks passed");
    }
    private static String hash(java.nio.file.Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
