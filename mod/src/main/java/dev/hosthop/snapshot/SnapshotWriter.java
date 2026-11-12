package dev.hosthop.snapshot;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Creates a backup from a fully stopped world copy; it cannot stop Minecraft. */
public final class SnapshotWriter {
    private record FileData(Path path, String name, long size, String digest) {}
    private SnapshotWriter() {}

    /** Never replaces an archive; an interrupted write is left for inspection. */
    public static SnapshotVerifier.Result create(Path world, Path destination) throws IOException {
        return create(world, destination, null);
    }

    /** As {@link #create(Path, Path)}, reporting 0..1 of the work done; the final check reports 1. */
    public static SnapshotVerifier.Result create(Path world, Path destination, java.util.function.DoubleConsumer progress) throws IOException {
        if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid world directory");
        Path root = world.toRealPath();
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Invalid archive destination");
        Path realTarget = parent.toRealPath().resolve(target.getFileName()).normalize();
        if (realTarget.startsWith(root)) throw new IOException("Archive must be outside the world");
        var paths = scan(root);
        long expected = 0;
        for (Path path : paths) expected += Files.size(path);
        // Hashing and writing each read the world once; the final verification is the rest.
        var bytes = new ByteProgress(ByteProgress.range(progress, 0, 0.85), 2 * expected);
        var files = new ArrayList<FileData>();
        var folded = new HashSet<String>();
        long total = 0;
        byte[] hashBuffer = new byte[1024 * 1024];
        for (Path path : paths) {
            String name = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
            if (!SnapshotVerifier.validPath(name) || !folded.add(SnapshotVerifier.folded(name))) throw new IOException("Invalid or conflicting world path");
            var before = attributes(path);
            if (before.size() > SnapshotVerifier.MAX_TOTAL - total) throw new IOException("World too large");
            total += before.size();
            var digest = digest();
            try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(hashBuffer)) != -1) { digest.update(hashBuffer, 0, count); bytes.add(count); }
            }
            checkUnchanged(before, attributes(path));
            files.add(new FileData(path, name, before.size(), HexFormat.of().formatHex(digest.digest())));
        }
        if (!paths.equals(scan(root))) throw new IOException("World file set changed");
        byte[] manifest = manifest(files);
        // An uncompressed upper estimate plus ZIP/manifest overhead avoids relying on compression.
        DiskSpace.require(parent, total + total / 1000 + (long) files.size() * 1024 + manifest.length + 1024 * 1024);
        // Deflating is nearly all of the time, so files are compressed in parallel and written in order. The bytes
        // are the same as a single-threaded ZipOutputStream's (see DeflatedZipWriter).
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads, task -> {
            var thread = new Thread(task, "hosthop-compress");
            thread.setDaemon(true);
            return thread;
        });
        try (var channel = FileChannel.open(realTarget, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var stream = new java.io.BufferedOutputStream(Channels.newOutputStream(channel), 1024 * 1024)) {
            var zip = new DeflatedZipWriter(stream);
            zip.add("hosthop-manifest.json", DeflatedZipWriter.Deflated.of(new java.io.ByteArrayInputStream(manifest), (data, length) -> {}));
            var pending = new java.util.ArrayDeque<java.util.concurrent.Future<DeflatedZipWriter.Deflated>>();
            int submitted = 0;
            for (var file : files) {
                while (submitted < files.size() && pending.size() < threads * 2) {
                    var next = files.get(submitted++);
                    pending.add(next.size() > inMemoryLimit ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : pool.submit(() -> readChecked(next, bytes, input -> DeflatedZipWriter.Deflated.of(input, (data, length) -> {}))));
                }
                DeflatedZipWriter.Deflated deflated;
                try { deflated = pending.removeFirst().get(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Backup interrupted");
                } catch (java.util.concurrent.ExecutionException failed) {
                    if (failed.getCause() instanceof IOException io) throw io;
                    throw new IOException("Backup compression failed", failed.getCause());
                }
                if (deflated != null) zip.add("world/" + file.name(), deflated);
                else readChecked(file, bytes, input -> { zip.add("world/" + file.name(), input, (data, length) -> {}); return null; });
            }
            if (!paths.equals(scan(root))) throw new IOException("World file set changed during backup");
            zip.finish();
            stream.flush();
            channel.force(true);
        } finally { pool.shutdownNow(); }
        var result = SnapshotVerifier.verify(realTarget);
        if (progress != null) progress.accept(1.0);
        return result;
    }

    /** Larger files are compressed while being written instead of being held in memory. Tests lower it. */
    static volatile long inMemoryLimit = 256L * 1024 * 1024;

    @FunctionalInterface private interface Reader<T> { T read(java.io.InputStream input) throws IOException; }

    /** Reads a file once, failing if it is not exactly the file that was hashed into the manifest. */
    private static <T> T readChecked(FileData file, ByteProgress bytes, Reader<T> reader) throws IOException {
        var before = attributes(file.path());
        var digest = digest();
        long[] count = {0};
        T result;
        try (var raw = Files.newInputStream(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var input = new java.io.FilterInputStream(raw) {
                @Override public int read() throws IOException { throw new IOException("Unsupported single-byte read"); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int read = super.read(b, off, len);
                    if (read > 0) {
                        count[0] += read;
                        if (count[0] > file.size()) throw new IOException("World file grew during backup");
                        digest.update(b, off, read);
                        bytes.add(read);
                    }
                    return read;
                }
            };
            result = reader.read(input);
        }
        checkUnchanged(before, attributes(file.path()));
        if (count[0] != file.size() || !HexFormat.of().formatHex(digest.digest()).equals(file.digest()))
            throw new IOException("World file changed during backup");
        return result;
    }

    private static List<Path> scan(Path root) throws IOException {
        var paths = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther() || !dir.toRealPath().startsWith(root)) throw new IOException("Unsupported world directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther() || paths.size() >= SnapshotVerifier.MAX_FILES)
                    throw new IOException("Unsupported world file");
                // Minecraft's lock is process-local coordination, not world state.
                if (file.equals(root.resolve("session.lock"))) return FileVisitResult.CONTINUE;
                paths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        paths.sort(Comparator.comparing(Path::toString));
        return paths;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Unsupported world file");
        return attrs;
    }

    private static void checkUnchanged(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("World file changed");
    }

    private static byte[] manifest(List<FileData> files) throws IOException {
        var output = new StringWriter();
        try (var json = new JsonWriter(output)) {
            json.beginObject().name("format").value(1).name("files").beginObject();
            for (var file : files) json.name(file.name()).beginObject().name("size").value(file.size()).name("sha256").value(file.digest()).endObject();
            json.endObject().endObject();
        }
        byte[] bytes = output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > SnapshotVerifier.MAX_MANIFEST) throw new IOException("Manifest too large");
        return bytes;
    }

    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
    }
}
