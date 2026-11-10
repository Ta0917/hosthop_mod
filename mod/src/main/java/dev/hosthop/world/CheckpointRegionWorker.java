package dev.hosthop.world;

import dev.hosthop.snapshot.LiveWorldCopy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface CheckpointRegionWorker {
    CompletableFuture<Void> hosthop$drainReads();
    CompletableFuture<Void> hosthop$seal(LiveWorldCopy snapshot, Path directory);
}
