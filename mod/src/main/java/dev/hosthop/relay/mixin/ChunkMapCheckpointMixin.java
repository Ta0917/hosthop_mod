package dev.hosthop.relay.mixin;

import dev.hosthop.world.CheckpointChunkMap;
import dev.hosthop.world.CheckpointSave;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMap.class)
abstract class ChunkMapCheckpointMixin implements CheckpointChunkMap {
    @Unique private static final int HOSTHOP_SAVE_BACKLOG_LIMIT = 64;
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final private LongSet toDrop;
    @Shadow @Final private Queue<Runnable> unloadQueue;

    @Override public boolean hosthop$readyForCheckpoint() {
        int backlog = pendingUnloads.size() + toDrop.size();
        if (backlog > HOSTHOP_SAVE_BACKLOG_LIMIT || unloadQueue.size() > HOSTHOP_SAVE_BACKLOG_LIMIT) return false;
        for (ChunkHolder holder : visibleChunkMap.values()) {
            if (!holder.wasAccessibleSinceLastSave()) continue;
            if (!holder.isReadyForSaving()) return false;
            var chunk = holder.getLatestChunk();
            if (chunk != null && chunk.isUnsaved() && ++backlog > HOSTHOP_SAVE_BACKLOG_LIMIT) return false;
        }
        return true;
    }

    @Redirect(method = "saveAllChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;synchronize(Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> hosthop$deferCheckpointWrites(ChunkMap chunks, boolean flush) {
        return CheckpointSave.deferringWrites() ? CompletableFuture.completedFuture(null) : chunks.synchronize(flush);
    }
}
