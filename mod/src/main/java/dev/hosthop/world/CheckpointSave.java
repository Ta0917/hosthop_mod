package dev.hosthop.world;

import dev.hosthop.relay.mixin.CheckpointAutosaveAccessor;
import dev.hosthop.snapshot.LiveWorldCopy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.storage.IOWorker;

public final class CheckpointSave {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final ThreadLocal<Boolean> DEFER_WRITES = new ThreadLocal<>();
    private static final Map<IOWorker, Path> WORKERS = new WeakHashMap<>();

    private CheckpointSave() {}

    public static boolean deferringWrites() { return Boolean.TRUE.equals(DEFER_WRITES.get()); }

    public static synchronized void register(IOWorker worker, Path directory) {
        WORKERS.put(worker, directory.toAbsolutePath().normalize());
    }

    public static synchronized void unregister(IOWorker worker) { WORKERS.remove(worker); }

    public static boolean ready(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            if (!((CheckpointChunkMap)level.getChunkSource().chunkMap).hosthop$readyForCheckpoint()) return false;
        }
        return true;
    }

    /** The server thread owns the save boundary and captures the inventory before ticking again. */
    public static LiveWorldCopy capture(MinecraftServer server, Path world, SaveFailureMonitor monitor) throws IOException {
        long start = System.nanoTime();
        long saved = 0;
        Path root = world.toAbsolutePath().normalize();
        Map<Path, CheckpointRegionWorker> workers = new HashMap<>();
        synchronized (CheckpointSave.class) {
            for (var entry : WORKERS.entrySet()) {
                IOWorker worker = entry.getKey();
                if (worker != null && entry.getValue().startsWith(root)
                        && workers.put(entry.getValue(), (CheckpointRegionWorker)worker) != null)
                    throw new IOException("Multiple region writers for one checkpoint directory");
            }
        }
        int expectedWorkers = 0;
        for (var ignored : server.getAllLevels()) expectedWorkers += 3;
        if (workers.size() < expectedWorkers) throw new IOException("Checkpoint region workers unavailable");

        try {
            if (monitor == null || monitor.hasFailure()) throw new IOException("Minecraft checkpoint save failed");
            DEFER_WRITES.set(true);
            try {
                if (!server.saveEverything(true, true, true)) throw new IOException("Minecraft checkpoint save failed");
            } finally {
                DEFER_WRITES.remove();
            }

            Map<CheckpointSavedData, Set<Path>> dataStores = new HashMap<>();
            for (var level : server.getAllLevels()) {
                level.getPoiManager().flushAll();
                var store = (CheckpointSavedData)level.getDataStorage();
                dataStores.put(store, store.hosthop$pendingPaths());
            }
            var global = (CheckpointSavedData)server.getDataStorage();
            dataStores.put(global, global.hosthop$pendingPaths());
            Set<Path> pendingPaths = new HashSet<>();
            for (var paths : dataStores.values()) {
                for (Path path : paths) {
                    if (!pendingPaths.add(path)) throw new IOException("Multiple SavedData writers for one checkpoint file");
                }
            }
            if (monitor.hasFailure()) throw new IOException("Minecraft checkpoint save failed");
            saved = System.nanoTime();
            LiveWorldCopy snapshot = LiveWorldCopy.capture(root, workers.keySet(), pendingPaths, saved + TimeUnit.MILLISECONDS.toNanos(250));
            try {
                var fences = new ArrayList<CompletableFuture<Void>>();
                workers.forEach((path, worker) -> fences.add(worker.hosthop$seal(snapshot, path)));
                dataStores.forEach((store, paths) -> fences.add(store.hosthop$seal(snapshot, paths)));
                snapshot.setFences(CompletableFuture.allOf(fences.toArray(CompletableFuture[]::new)));
                // This save covers the usual autosave. Avoid running it a second time on the next tick.
                var autosave = (CheckpointAutosaveAccessor)server;
                autosave.hosthop$setTicksUntilAutosave(autosave.hosthop$nextAutosaveInterval());
                return snapshot;
            } catch (RuntimeException | Error failure) {
                snapshot.close();
                throw failure;
            }
        } finally {
            long finished = System.nanoTime();
            if (saved == 0) saved = finished;
            LOGGER.info("HostHop checkpoint server-thread time: save={} ms, inventory={} ms",
                    TimeUnit.NANOSECONDS.toMillis(saved - start), TimeUnit.NANOSECONDS.toMillis(finished - saved));
        }
    }
}
