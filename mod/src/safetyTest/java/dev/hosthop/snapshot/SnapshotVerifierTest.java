package dev.hosthop.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SnapshotVerifierTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--create")) {
            var result = SnapshotWriter.create(Path.of(args[1]), Path.of(args[2]));
            System.out.println("Snapshot created: " + result.files() + " files, " + result.uncompressedBytes() + " bytes");
            return;
        }
        if (args.length == 2) {
            var result = SnapshotVerifier.restore(Path.of(args[0]), Path.of(args[1]));
            System.out.println("Snapshot restored: " + result.files() + " files, " + result.uncompressedBytes() + " bytes");
            return;
        }
        if (args.length == 1) {
            var result = SnapshotVerifier.verify(Path.of(args[0]));
            System.out.println("Snapshot verified: " + result.files() + " files, " + result.uncompressedBytes() + " bytes");
            return;
        }
        var directory = Files.createTempDirectory("hosthop-snapshot-test-");
        var file = directory.resolve("snapshot.zip");
        var restored = directory.resolve("restored");
        try {
            byte[] data = "test world".getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            String item = "{\"size\":" + data.length + ",\"sha256\":\"" + hash + "\"}";
            create(file, "{\"format\":1,\"files\":{\"level.dat\":" + item + "}}", "world/level.dat", data);
            var result = SnapshotVerifier.verify(file);
            check(result.files() == 1 && result.uncompressedBytes() == data.length);
            check(SnapshotVerifier.restore(file, restored).files() == 1);
            check(java.util.Arrays.equals(data, Files.readAllBytes(restored.resolve("level.dat"))));
            try { SnapshotVerifier.restore(file, restored); throw new AssertionError("existing restore overwritten"); }
            catch (java.nio.file.FileAlreadyExistsException expected) { }
            create(file, "{\"format\":1,\"files\":{\"level.dat\":" + item + "}}", "world/level.dat", "test worle".getBytes(StandardCharsets.UTF_8));
            fails(file);
            var rejected = directory.resolve("rejected");
            try { SnapshotVerifier.restore(file, rejected); throw new AssertionError("corrupt archive restored"); }
            catch (IOException expected) { check(!Files.exists(rejected)); }
            create(file, "{\"format\":1,\"files\":{\"../bad\":" + item + "}}", "world/../bad", data);
            fails(file);
            create(file, "{\"format\":1,\"format\":1,\"files\":{\"level.dat\":" + item + "}}", "world/level.dat", data);
            fails(file);
            create(file, "{\"format\":1,\"files\":{\"dir/CON.dat\":" + item + "}}", "world/dir/CON.dat", data);
            fails(file);
            create(file, "{\"format\":1,\"files\":{\"a\":" + item + ",\"A/b\":" + item + "}}", "world/a", data);
            fails(file);
            create(file, "{\"format\":1,\"files\":{\"level.dat\":{\"size\":1e1,\"sha256\":\"" + hash + "\"}}}", "world/level.dat", data);
            fails(file);
            try (var zip = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file)) {
                zip.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("hosthop-manifest.json"));
                zip.write(("{\"format\":1,\"files\":{\"level.dat\":" + item + "}}").getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
                var symlink = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("world/level.dat");
                symlink.setUnixMode(0120777);
                zip.putArchiveEntry(symlink);
                zip.write(data);
                zip.closeArchiveEntry();
            }
            fails(file);
            writerRoundTrip(directory);
            System.out.println("SnapshotVerifier safety checks passed");
        } finally {
            Files.deleteIfExists(restored.resolve("level.dat"));
            Files.deleteIfExists(restored);
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    private static void writerRoundTrip(Path directory) throws Exception {
        Path world = directory.resolve("writer-world");
        Path nested = world.resolve("dimension");
        Path archive = directory.resolve("writer.zip");
        Path restore = directory.resolve("writer-restore");
        Files.createDirectory(world);
        Files.createDirectory(nested);
        Files.writeString(world.resolve("level.dat"), "settings");
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.writeString(nested.resolve("data.dat"), "content");
        try {
            try (var locked = StoppedWorldLock.acquire(world)) {
                try { StoppedWorldLock.acquire(world); throw new AssertionError("locked world accepted"); }
                catch (IOException expected) { }
                check(SnapshotWriter.create(world, archive).files() == 2);
            }
            check(SnapshotVerifier.restore(archive, restore).files() == 2);
            check(Files.readString(restore.resolve("dimension/data.dat")).equals("content"));
            try { SnapshotWriter.create(world, archive); throw new AssertionError("backup overwritten"); }
            catch (java.nio.file.FileAlreadyExistsException expected) { }
            try { SnapshotWriter.create(world, world.resolve("nested.zip")); throw new AssertionError("backup inside world"); }
            catch (IOException expected) { }
        } finally {
            Files.deleteIfExists(restore.resolve("dimension/data.dat"));
            Files.deleteIfExists(restore.resolve("dimension"));
            Files.deleteIfExists(restore.resolve("level.dat"));
            Files.deleteIfExists(restore);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(nested.resolve("data.dat"));
            Files.deleteIfExists(nested);
            Files.deleteIfExists(world.resolve("level.dat"));
            Files.deleteIfExists(world.resolve("session.lock"));
            Files.deleteIfExists(world);
        }
    }

    private static void create(Path file, String manifest, String name, byte[] data) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("hosthop-manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }
    }
    private static void fails(Path file) throws IOException {
        try { SnapshotVerifier.verify(file); throw new AssertionError("invalid snapshot accepted"); }
        catch (IOException expected) { }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
