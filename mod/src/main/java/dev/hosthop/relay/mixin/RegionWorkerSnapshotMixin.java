package dev.hosthop.relay.mixin;

import dev.hosthop.snapshot.LiveWorldCopy;
import dev.hosthop.world.CheckpointRegionWorker;
import dev.hosthop.world.CheckpointSave;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IOWorker.class)
abstract class RegionWorkerSnapshotMixin implements CheckpointRegionWorker {
    @Unique private static final int HOSTHOP_FOREGROUND = 0;
    @Shadow @Final private PriorityConsecutiveExecutor consecutiveExecutor;
    @Shadow @Final private SequencedMap<ChunkPos, ?> pendingWrites;
    @Shadow private void storePendingChunk() { throw new AssertionError(); }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hosthop$registerWorker(RegionStorageInfo info, Path directory, boolean sync, CallbackInfo callback) {
        CheckpointSave.register((IOWorker)(Object)this, directory);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void hosthop$unregisterWorker(CallbackInfo callback) {
        CheckpointSave.unregister((IOWorker)(Object)this);
    }

    @Override public CompletableFuture<Void> hosthop$drainReads() {
        return consecutiveExecutor.scheduleWithResult(HOSTHOP_FOREGROUND, result -> result.complete(null));
    }

    @Override public CompletableFuture<Void> hosthop$seal(LiveWorldCopy snapshot, Path directory) {
        // FOREGROUND is priority 0 in 26.2. Run after prior store submissions and before
        // subsequent ones can coalesce over the checkpoint's pending values.
        return consecutiveExecutor.scheduleWithResult(HOSTHOP_FOREGROUND, result -> {
            try {
                while (!pendingWrites.isEmpty() && snapshot.isOpen()) storePendingChunk();
                snapshot.sealRegionDirectory(directory);
                result.complete(null);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
    }
}
