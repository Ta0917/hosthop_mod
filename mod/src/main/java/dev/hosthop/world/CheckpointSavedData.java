package dev.hosthop.world;

import dev.hosthop.snapshot.LiveWorldCopy;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface CheckpointSavedData {
    Set<Path> hosthop$pendingPaths();
    CompletableFuture<Void> hosthop$seal(LiveWorldCopy snapshot, Set<Path> paths);
}
