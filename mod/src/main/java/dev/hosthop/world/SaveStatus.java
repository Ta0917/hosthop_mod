package dev.hosthop.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** A non-sensitive, advisory status for a save whose window may already have closed. */
public final class SaveStatus {
    public static final String SAVING = "screen.hosthop.saving_world";
    public static final String SAVED = "screen.hosthop.saved_world";
    public static final String RECOVERY = "screen.hosthop.recovery_required";
    private static final Set<String> ALLOWED = Set.of(SAVING, SAVED, RECOVERY,
            "screen.hosthop.disk_recovery", "screen.hosthop.drive_recovery");
    private SaveStatus() {}

    public static void write(Path config, String value) throws IOException {
        if (!ALLOWED.contains(value) || Files.isSymbolicLink(config) || !Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid save status");
        Path target = config.resolve("save-status.txt");
        if (Files.isSymbolicLink(target)) throw new IOException("Invalid save status path");
        Path temp = Files.createTempFile(config, ".hosthop-status-", ".tmp");
        try {
            Files.writeString(temp, value, StandardCharsets.UTF_8, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    public static String read(Path config) throws IOException {
        Path status = config.resolve("save-status.txt");
        if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.size(status) > 256) throw new IOException("Invalid save status");
        String value = Files.readString(status, StandardCharsets.UTF_8);
        if (!ALLOWED.contains(value)) throw new IOException("Invalid save status");
        if (!SAVING.equals(value)) return value;
        // A stale saving marker must not be shown as a successful or ongoing save.
        Path runtime = config.resolve("runtime.lock");
        if (!Files.isRegularFile(runtime, LinkOption.NOFOLLOW_LINKS)) return RECOVERY;
        try (var channel = FileChannel.open(runtime, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var lock = channel.tryLock()) { return lock == null ? SAVING : RECOVERY; }
            catch (OverlappingFileLockException inUse) { return SAVING; }
        }
    }
}
