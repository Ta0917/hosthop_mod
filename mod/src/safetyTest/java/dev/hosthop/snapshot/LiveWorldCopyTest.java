package dev.hosthop.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class LiveWorldCopyTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("hosthop-live-copy-");
        var world = Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("session.lock"), "lock");
        var regionDir = Files.createDirectory(world.resolve("region"));
        var region = regionDir.resolve("r.0.0.mca");
        var external = regionDir.resolve("c.0.0.mcc");
        var data = Files.createDirectory(world.resolve("data"));
        var pendingData = data.resolve("map.dat");
        var tempData = data.resolve("map.dat123.neoforge-tmp");
        Files.writeString(pendingData, "previous map");
        Files.writeString(tempData, "in-flight temp file");
        byte[] expected = new byte[80_001];
        new Random(123).nextBytes(expected);
        Files.write(region, expected);
        Files.writeString(external, "external chunk");
        var copy = root.resolve("copy");
        try (var snapshot = LiveWorldCopy.capture(world, Set.of(regionDir), Set.of(pendingData), System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
             var channel = FileChannel.open(region, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            Files.writeString(pendingData, "checkpoint map");
            snapshot.sealSavedData(Set.of(pendingData));
            Files.writeString(pendingData, "next map");
            // A queued write before the directory fence belongs to the checkpoint.
            expected[32_768] = 17;
            LiveWorldCopy.writeRegion(region, channel, ByteBuffer.wrap(new byte[]{17}), 32_768);
            snapshot.sealRegionDirectory(regionDir);
            // Overwrite the header, overlap a saved page, cross a page boundary, and extend the file.
            LiveWorldCopy.writeRegion(region, channel, ByteBuffer.wrap(new byte[8192]), 0);
            LiveWorldCopy.writeRegion(region, channel, ByteBuffer.wrap(new byte[16384]), 8192);
            LiveWorldCopy.writeRegion(region, channel, ByteBuffer.wrap(new byte[16384]), 79_000);
            Files.writeString(world.resolve("level.dat"), "next save");
            Files.delete(external);
            Files.writeString(regionDir.resolve("r.1.0.mca"), "new region");
            reject(() -> snapshot.copyTo(world.resolve("nested"), () -> true));
            snapshot.copyTo(copy, () -> true);
            reject(() -> snapshot.copyTo(copy, () -> true));
        }
        if (!Files.readString(copy.resolve("level.dat")).equals("level")
                || !Arrays.equals(Files.readAllBytes(copy.resolve("region/r.0.0.mca")), expected)
                || !Files.readString(copy.resolve("region/c.0.0.mcc")).equals("external chunk")
                || !Files.readString(copy.resolve("data/map.dat")).equals("checkpoint map")
                || Files.exists(copy.resolve("data/map.dat123.neoforge-tmp"))
                || Files.exists(copy.resolve("region/r.1.0.mca"))
                || Files.exists(copy.resolve("session.lock"))) throw new AssertionError("World copy incorrect");
        try (var snapshot = LiveWorldCopy.capture(world, Set.of(), Set.of(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
            reject(() -> snapshot.copyTo(root.resolve("cancelled"), () -> false));
        }
        reject(() -> LiveWorldCopy.capture(world, Set.of(), Set.of(), System.nanoTime()));
        System.out.println("Live world copy checks passed");
    }

    private interface Checked { void run() throws Exception; }

    private static void reject(Checked checked) throws Exception {
        try { checked.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Invalid checkpoint operation was accepted");
    }
}
