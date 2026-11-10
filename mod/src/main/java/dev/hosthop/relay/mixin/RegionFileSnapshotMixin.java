package dev.hosthop.relay.mixin;

import dev.hosthop.snapshot.LiveWorldCopy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RegionFile.class)
abstract class RegionFileSnapshotMixin {
    @Shadow @Final private Path path;

    @Redirect(method = {"write", "writeHeader", "padToFullSector"},
            at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;write(Ljava/nio/ByteBuffer;J)I"),
            require = 4, allow = 4)
    private int hosthop$preserveSnapshotPage(FileChannel file, ByteBuffer bytes, long offset) throws IOException {
        return LiveWorldCopy.writeRegion(path, file, bytes, offset);
    }
}
