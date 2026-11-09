package dev.hosthop.snapshot;

import java.nio.file.Files;

public final class LiveWorldCopyTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("hosthop-live-copy-");
        var world = Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.createDirectory(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "region");
        var copy = root.resolve("copy");
        LiveWorldCopy.copy(world, copy);
        if (!Files.readString(copy.resolve("level.dat")).equals("level")
                || !Files.readString(copy.resolve("region/r.0.0.mca")).equals("region")
                || Files.exists(copy.resolve("session.lock"))) throw new AssertionError("World copy incorrect");
        try { LiveWorldCopy.copy(world, world.resolve("nested")); throw new AssertionError("copy inside world accepted"); }
        catch (java.io.IOException expected) { }
        try { LiveWorldCopy.copy(world, copy); throw new AssertionError("existing copy overwritten"); }
        catch (java.io.IOException expected) { }
        System.out.println("Live world copy checks passed");
    }
}
