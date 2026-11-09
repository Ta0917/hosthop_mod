package dev.hosthop.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Prevent an integrated server from loading the previous host's player data. */
public final class HostPlayerIdentity {
    private HostPlayerIdentity() {}

    public static void prepare(Path world) throws IOException {
        Path level = world.resolve("level.dat");
        if (!Files.isRegularFile(level, LinkOption.NOFOLLOW_LINKS) || Files.size(level) > 64L * 1024 * 1024)
            throw new IOException("Invalid level data");
        var root = NbtIo.readCompressed(level, NbtAccounter.create(64L * 1024 * 1024));
        var data = root.getCompound("Data").orElseThrow(() -> new IOException("Missing level data"));
        if (!data.contains("singleplayer_uuid")) return;
        data.remove("singleplayer_uuid");
        Path temporary = Files.createTempFile(world, ".hosthop-level-", ".dat");
        try {
            NbtIo.writeCompressed(root, temporary);
            var verified = NbtIo.readCompressed(temporary, NbtAccounter.create(64L * 1024 * 1024));
            if (!root.equals(verified)) throw new IOException("Level data verification failed");
            Files.move(temporary, level, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
