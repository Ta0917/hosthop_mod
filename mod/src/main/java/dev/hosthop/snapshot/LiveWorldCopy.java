package dev.hosthop.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Copies a world while its owning server is paused after a synchronous save. */
public final class LiveWorldCopy {
    public static final class SourceChanged extends IOException {
        private SourceChanged(String message) { super(message); }
    }
    private record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) {}
    private LiveWorldCopy() {}

    /** Caller must keep the server paused until this method returns. */
    public static void copy(Path source, Path destination) throws IOException {
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid source world");
        Path root = source.toRealPath(), parent = destination.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid destination");
        Path output = parent.toRealPath().resolve(destination.getFileName()).normalize();
        if (output.startsWith(root)) throw new IOException("Backup must be outside world");
        Map<Path, Stamp> before = scan(root);
        long total = 0;
        for (var stamp : before.values()) {
            if (stamp.size() > Long.MAX_VALUE - total) throw new IOException("World too large");
            total += stamp.size();
        }
        DiskSpace.require(parent, total);
        Files.createDirectory(output);
        for (Path relative : before.keySet()) {
            Path target = output.resolve(relative).normalize();
            if (!target.startsWith(output)) throw new IOException("Invalid world path");
            Files.createDirectories(target.getParent());
            Files.copy(root.resolve(relative), target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        }
        var after = scan(root);
        if (!before.equals(after)) {
            var changes = new java.util.HashSet<>(before.keySet()); changes.addAll(after.keySet());
            for (Path path : changes) if (!Objects.equals(before.get(path), after.get(path))) {
                var a = before.get(path); var b = after.get(path);
                String reason = a == null ? "added" : b == null ? "removed" : a.size() != b.size() ? "size" : !a.modified().equals(b.modified()) ? "timestamp" : "identity";
                throw new SourceChanged("World changed while being copied: " + path + " (" + reason + ")");
            }
            throw new SourceChanged("World changed while being copied");
        }
        if (!before.keySet().equals(scan(output).keySet())) throw new IOException("World copy incomplete");
    }

    private static Map<Path, Stamp> scan(Path root) throws IOException {
        Map<Path, Stamp> files = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther() || !dir.toRealPath().startsWith(root)) throw new IOException("Invalid directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Invalid world file");
                Path relative = root.relativize(file);
                if (relative.toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                if (files.size() >= 500_000 || !SnapshotVerifier.validPath(relative.toString().replace(java.io.File.separatorChar, '/'))) throw new IOException("Invalid world path");
                files.put(relative, new Stamp(attrs.size(), attrs.lastModifiedTime(), Objects.toString(attrs.fileKey(), "")));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
