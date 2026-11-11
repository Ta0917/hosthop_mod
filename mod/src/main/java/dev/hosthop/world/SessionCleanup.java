package dev.hosthop.world;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

/**
 * Removes the local data of host sessions that no longer matter for recovery.
 *
 * A session directory and its world copy are deleted only when the session carries the released marker,
 * which is written after the lease was confirmed released (or aborted before the server started). Sessions
 * without it, such as an unresolved host or a backup restore, are never touched. The newest released
 * sessions are kept because their archives serve as a download cache for the next host.
 */
public final class SessionCleanup {
    static final String MARKER = "released.marker";
    private static final Duration TEMPORARY_AGE = Duration.ofHours(1);
    private SessionCleanup() {}

    /** Best effort: a missing marker only means the session is kept. */
    public static void markReleased(Path session) {
        if (session == null) return;
        try {
            if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) return;
            Files.write(session.resolve(MARKER), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("HostHop could not mark the session as released ({})", failure.getClass().getSimpleName());
        }
    }

    /** Deletes released sessions beyond the newest {@code keep}, plus old join-time temporary folders. Returns how many were removed. */
    public static int removeReleased(Path gameDirectory, int keep) throws IOException {
        return removeReleased(gameDirectory, keep, Instant.now());
    }

    static int removeReleased(Path gameDirectory, int keep, Instant now) throws IOException {
        if (keep < 1) throw new IllegalArgumentException("At least one session must be kept");
        Path game = gameDirectory.toRealPath();
        Path sessions = game.resolve("hosthop").resolve("sessions");
        if (!Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS) || !sessions.toRealPath().startsWith(game)) return 0;
        record Released(Path session, String id, FileTime at) {}
        var released = new ArrayList<Released>();
        var temporary = new ArrayList<Path>();
        try (var children = Files.newDirectoryStream(sessions)) {
            for (Path child : children) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = child.getFileName().toString();
                if (name.startsWith("authorization-")) {
                    if (Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(now.minus(TEMPORARY_AGE))) temporary.add(child);
                    continue;
                }
                if (!isUuid(name)) continue;
                Path marker = child.resolve(MARKER);
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) continue;
                released.add(new Released(child, name, Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS)));
            }
        }
        released.sort(Comparator.comparing(Released::at).reversed());
        int removed = 0;
        for (int i = keep; i < released.size(); i++) {
            var item = released.get(i);
            Path world = game.resolve("saves").resolve("hosthop_" + item.id().replace("-", ""));
            if (Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS) || !world.toRealPath().startsWith(game)) continue;
                if (inUse(world)) continue;
                deleteTree(world, game);
            }
            deleteTree(item.session(), game);
            removed++;
        }
        for (Path directory : temporary) {
            deleteTree(directory, game);
            removed++;
        }
        return removed;
    }

    private static boolean isUuid(String name) {
        try { return UUID.fromString(name).toString().equals(name); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** A world Minecraft has open holds its session.lock. */
    private static boolean inUse(Path world) {
        if (!Files.exists(world.resolve("session.lock"), LinkOption.NOFOLLOW_LINKS)) return false;
        try (var lock = dev.hosthop.snapshot.StoppedWorldLock.acquire(world)) { return false; }
        catch (IOException busy) { return true; }
    }

    private static void deleteTree(Path root, Path game) throws IOException {
        if (Files.isSymbolicLink(root) || !root.toRealPath().startsWith(game)) throw new IOException("Refusing to delete outside the game directory");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
