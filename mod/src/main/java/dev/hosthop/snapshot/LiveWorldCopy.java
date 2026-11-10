package dev.hosthop.snapshot;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** An immutable view of a drained Minecraft 26.2 save. RegionFile writes preserve unread pages. */
public final class LiveWorldCopy implements AutoCloseable {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.mca");
    private static final int PAGE_SIZE = 16 * 1024;
    private static final long MAX_METADATA = 16L << 20;
    private static final long MAX_PAGES_BYTES = 64L << 20;
    private static final AtomicReference<LiveWorldCopy> ACTIVE = new AtomicReference<>();

    private final Path root;
    private final Map<Path, Region> regions = new ConcurrentHashMap<>();
    private final Map<Path, byte[]> metadata = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong metadataBytes = new AtomicLong();
    private final AtomicLong files = new AtomicLong();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicBoolean copying = new AtomicBoolean();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private CompletableFuture<Void> seals = CompletableFuture.completedFuture(null);

    private LiveWorldCopy(Path root) { this.root = root; }

    /**
     * Must run on the server thread after non-region saves finish. Each skipped storage
     * directory is then captured by a fence on its Minecraft IO executor.
     */
    public static LiveWorldCopy capture(Path source, Set<Path> storageDirectories, Set<Path> queuedData, long deadlineNanos) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        if (!root.equals(root.toRealPath())) throw new IOException("World path must be canonical");
        LiveWorldCopy snapshot = new LiveWorldCopy(root);
        Map<Path, Set<String>> temporaryPrefixes = new HashMap<>();
        for (Path path : queuedData) {
            if (!path.startsWith(root)) throw new IOException("SavedData file outside checkpoint world");
            temporaryPrefixes.computeIfAbsent(path.getParent(), ignored -> new java.util.HashSet<>()).add(path.getFileName().toString());
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                checkDeadline();
                if (attrs.isOther() || !path.equals(path.toRealPath())) throw new IOException("Invalid world directory");
                if (storageDirectories.contains(path)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                checkDeadline();
                if (!root.relativize(path).equals(Path.of("session.lock")) && !queuedData.contains(path) && !writeTemporary(path))
                    snapshot.record(path, attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (error instanceof java.nio.file.NoSuchFileException && (queuedData.contains(path) || writeTemporary(path)))
                    return FileVisitResult.CONTINUE;
                throw error;
            }

            private boolean writeTemporary(Path path) {
                String name = path.getFileName().toString();
                String suffix = ".neoforge-tmp";
                if (!name.endsWith(suffix)) return false;
                var prefixes = temporaryPrefixes.get(path.getParent());
                if (prefixes == null) return false;
                for (String prefix : prefixes) {
                    if (!name.startsWith(prefix)) continue;
                    int end = name.length() - suffix.length();
                    if (prefix.length() < end && name.substring(prefix.length(), end).chars().allMatch(c -> c >= '0' && c <= '9'))
                        return true;
                }
                return false;
            }

            private void checkDeadline() throws IOException {
                if (System.nanoTime() - deadlineNanos >= 0) throw new IOException("Checkpoint inventory exceeded time budget");
            }
        });
        if (!snapshot.metadata.containsKey(Path.of("level.dat"))) throw new IOException("Missing level.dat");
        if (System.nanoTime() - deadlineNanos >= 0) throw new IOException("Checkpoint inventory exceeded time budget");
        if (!ACTIVE.compareAndSet(null, snapshot)) throw new IOException("Another world capture is active");
        return snapshot;
    }

    /** Run on the storage's ordered IO executor after its queued checkpoint writes finish. */
    public void sealRegionDirectory(Path directory) throws IOException {
        checkActive(() -> true);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!directory.startsWith(root) || !directory.equals(directory.toRealPath()))
            throw new IOException("Invalid checkpoint region directory");
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                checkActive(() -> true);
                record(path, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            }
        }
    }

    /** Run in SavedDataStorage's write chain, before it can replace any of these files again. */
    public void sealSavedData(Set<Path> paths) throws IOException {
        for (Path path : paths) {
            checkActive(() -> true);
            if (!path.startsWith(root) || !path.getParent().equals(path.getParent().toRealPath()))
                throw new IOException("Invalid checkpoint SavedData path");
            record(path, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private void record(Path path, BasicFileAttributes attrs) throws IOException {
        Path relative = root.relativize(path);
        long size = attrs.size();
        if (!attrs.isRegularFile() || attrs.isOther()
                || !SnapshotVerifier.validPath(relative.toString().replace(java.io.File.separatorChar, '/'))
                || files.incrementAndGet() > 500_000 || totalBytes.addAndGet(size) < 0)
            throw new IOException("Invalid checkpoint source file");
        if (isRegion(relative)) {
            regions.put(path, new Region(size));
        } else {
            if (metadataBytes.addAndGet(size) > MAX_METADATA) throw new IOException("Checkpoint metadata budget exceeded");
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes((int)size + 1);
            }
            if (bytes.length != size) throw new IOException("Checkpoint metadata changed during capture");
            metadata.put(relative, bytes);
        }
    }

    public void setFences(CompletableFuture<Void> seals) { this.seals = seals; }

    public boolean isOpen() { return ACTIVE.get() == this && failure.get() == null; }

    private static boolean isRegion(Path relative) {
        if (relative.getParent() == null || !REGION_NAME.matcher(relative.getFileName().toString()).matches()) return false;
        return switch (relative.getParent().getFileName().toString()) {
            case "region", "entities", "poi" -> true;
            default -> false;
        };
    }

    /** Wraps every positional write in RegionFile, including headers, clear, and close padding. */
    public static int writeRegion(Path path, FileChannel file, ByteBuffer bytes, long offset) throws IOException {
        LiveWorldCopy snapshot = ACTIVE.get();
        if (snapshot == null || snapshot.failure.get() != null) return file.write(bytes, offset);
        Region region = snapshot.regions.get(path.toAbsolutePath().normalize());
        if (region == null) return file.write(bytes, offset);
        synchronized (region) {
            if (snapshot.failure.get() == null && bytes.hasRemaining()) {
                try {
                    long end = Math.min(region.size, offset + bytes.remaining());
                    for (long page = Math.max(region.copied, offset / PAGE_SIZE * PAGE_SIZE); page < end; page += PAGE_SIZE) {
                        if (region.original.containsKey(page)) continue;
                        int count = (int)Math.min(PAGE_SIZE, region.size - page);
                        if (snapshot.retainedBytes.addAndGet(count) > MAX_PAGES_BYTES)
                            throw new IOException("Checkpoint region journal budget exceeded");
                        var original = ByteBuffer.allocate(count);
                        readPage(file, original, page);
                        region.original.put(page, original.array());
                    }
                } catch (IOException problem) {
                    // Abort the checkpoint while allowing the live save to proceed.
                    snapshot.failure.compareAndSet(null, problem);
                }
            }
            return file.write(bytes, offset);
        }
    }

    /** Run once on the checkpoint worker. The caller owns cleanup of an incomplete destination. */
    public void copyTo(Path destination, BooleanSupplier keepGoing) throws IOException, InterruptedException {
        Path parent = destination.toAbsolutePath().getParent().toRealPath();
        Path output = parent.resolve(destination.getFileName());
        if (output.startsWith(root)) throw new IOException("Backup must be outside world");
        if (!copying.compareAndSet(false, true)) throw new IOException("Checkpoint copy already started");
        while (true) {
            checkActive(keepGoing);
            try { seals.get(1, TimeUnit.SECONDS); break; }
            catch (TimeoutException pending) { /* Recheck the lease and copy deadline. */ }
            catch (ExecutionException failed) { throw new IOException("Checkpoint write fence failed", failed.getCause()); }
        }
        DiskSpace.require(parent, totalBytes.get());
        Files.createDirectory(output);
        for (var entry : metadata.entrySet()) {
            checkActive(keepGoing);
            Path target = output.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }

        var buffer = ByteBuffer.allocate(PAGE_SIZE);
        for (var entry : regions.entrySet()) {
            checkActive(keepGoing);
            Path source = entry.getKey();
            Path target = output.resolve(root.relativize(source));
            Files.createDirectories(target.getParent());
            Region region = entry.getValue();
            try (var input = FileChannel.open(source, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var out = new BufferedOutputStream(Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW), 256 * 1024)) {
                for (long page = 0; page < region.size; page += PAGE_SIZE) {
                    if (page % (4L << 20) == 0) checkActive(keepGoing);
                    int count = (int)Math.min(PAGE_SIZE, region.size - page);
                    byte[] original;
                    synchronized (region) {
                        IOException problem = failure.get();
                        if (problem != null) throw problem;
                        original = region.original.remove(page);
                        if (original == null) {
                            buffer.clear().limit(count);
                            readPage(input, buffer, page);
                        } else retainedBytes.addAndGet(-count);
                        region.copied = page + PAGE_SIZE;
                    }
                    out.write(original == null ? buffer.array() : original, 0, count);
                }
            }
        }
        checkActive(keepGoing);
    }

    private void checkActive(BooleanSupplier keepGoing) throws IOException {
        IOException problem = failure.get();
        if (problem != null) throw problem;
        if (ACTIVE.get() != this || !keepGoing.getAsBoolean()) throw new IOException("Checkpoint capture cancelled");
    }

    private static void readPage(FileChannel file, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int count = file.read(buffer, offset);
            if (count <= 0) throw new EOFException("Could not read checkpoint region page");
            offset += count;
        }
    }

    @Override public void close() { ACTIVE.compareAndSet(this, null); }

    private static final class Region {
        final long size;
        final Map<Long, byte[]> original = new HashMap<>();
        long copied;

        Region(long size) { this.size = size; }
    }
}
