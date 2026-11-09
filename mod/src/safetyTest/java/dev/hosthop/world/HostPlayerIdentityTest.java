package dev.hosthop.world;

import java.nio.file.Files;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

public final class HostPlayerIdentityTest {
    public static void main(String[] args) throws Exception {
        var world = Files.createTempDirectory("hosthop-identity-");
        var root = new CompoundTag();
        var data = new CompoundTag();
        data.putString("singleplayer_uuid", "previous-host");
        data.putString("LevelName", "test-world");
        root.put("Data", data);
        NbtIo.writeCompressed(root, world.resolve("level.dat"));
        var players = Files.createDirectory(world.resolve("players"));
        var player = players.resolve("current-host.dat");
        Files.writeString(player, "preserve player data");
        HostPlayerIdentity.prepare(world);
        var actual = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.create(1024 * 1024));
        data.remove("singleplayer_uuid");
        if (!root.equals(actual) || !Files.readString(player).equals("preserve player data")) throw new AssertionError();
        HostPlayerIdentity.prepare(world);
        if (!root.equals(NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.create(1024 * 1024)))) throw new AssertionError();
        System.out.println("Host player identity checks passed");
    }
}
