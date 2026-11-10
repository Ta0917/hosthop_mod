package dev.hosthop.relay.mixin;

import dev.hosthop.world.CheckpointRegionWorker;
import dev.hosthop.world.CheckpointSave;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityStorage.class)
abstract class EntityStorageCheckpointMixin {
    @Redirect(method = "flush", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;synchronize(Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> hosthop$deferCheckpointWrites(SimpleRegionStorage storage, boolean flush) {
        if (!CheckpointSave.deferringWrites()) return storage.synchronize(flush);
        // Intermediate flush(false) must drain entity reads before saveAll processes their results.
        return flush ? CompletableFuture.completedFuture(null) : ((CheckpointRegionWorker)storage.chunkScanner()).hosthop$drainReads();
    }
}
