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
        if (args.length == 1) {
            var result = SnapshotVerifier.verify(Path.of(args[0]));
            System.out.println("Snapshot verified: " + result.files() + " files, " + result.uncompressedBytes() + " bytes");
            return;
        }
        var directory = Files.createTempDirectory("hosthop-snapshot-test-");
        var file = directory.resolve("snapshot.zip");
        try {
            byte[] data = "test world".getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            String item = "{\"size\":" + data.length + ",\"sha256\":\"" + hash + "\"}";
            create(file, "{\"format\":1,\"files\":{\"level.dat\":" + item + "}}", "world/level.dat", data);
            var result = SnapshotVerifier.verify(file);
            check(result.files() == 1 && result.uncompressedBytes() == data.length);
            create(file, "{\"format\":1,\"files\":{\"level.dat\":" + item + "}}", "world/level.dat", "test worle".getBytes(StandardCharsets.UTF_8));
            fails(file);
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
            System.out.println("SnapshotVerifier safety checks passed");
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
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
