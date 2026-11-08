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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a backup from a fully stopped world copy; it cannot stop Minecraft. */
public final class SnapshotWriter {
    private record FileData(Path path, String name, long size, String digest) {}
    private SnapshotWriter() {}

    /** Never replaces an archive; an interrupted write is left for inspection. */
    public static SnapshotVerifier.Result create(Path world, Path destination) throws IOException {
        if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid world directory");
        Path root = world.toRealPath();
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Invalid archive destination");
        Path realTarget = parent.toRealPath().resolve(target.getFileName()).normalize();
        if (realTarget.startsWith(root)) throw new IOException("Archive must be outside the world");
        var paths = scan(root);
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
                while ((count = input.read(hashBuffer)) != -1) digest.update(hashBuffer, 0, count);
            }
            checkUnchanged(before, attributes(path));
            files.add(new FileData(path, name, before.size(), HexFormat.of().formatHex(digest.digest())));
        }
        if (!paths.equals(scan(root))) throw new IOException("World file set changed");
        byte[] manifest = manifest(files);
        try (var channel = FileChannel.open(realTarget, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var zip = new ZipOutputStream(Channels.newOutputStream(channel))) {
            zip.setLevel(1);
            zip.putNextEntry(new ZipEntry("hosthop-manifest.json"));
            zip.write(manifest);
            zip.closeEntry();
            byte[] buffer = new byte[1024 * 1024];
            for (var file : files) {
                var before = attributes(file.path());
                zip.putNextEntry(new ZipEntry("world/" + file.name()));
                var digest = digest();
                long count = 0;
                try (var input = Files.newInputStream(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        count += read;
                        if (count > file.size()) throw new IOException("World file grew during backup");
                        digest.update(buffer, 0, read);
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
                checkUnchanged(before, attributes(file.path()));
                if (count != file.size() || !HexFormat.of().formatHex(digest.digest()).equals(file.digest()))
                    throw new IOException("World file changed during backup");
            }
            if (!paths.equals(scan(root))) throw new IOException("World file set changed during backup");
            zip.finish();
            zip.flush();
            channel.force(true);
        }
        return SnapshotVerifier.verify(realTarget);
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
