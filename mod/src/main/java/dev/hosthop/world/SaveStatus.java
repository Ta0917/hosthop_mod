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
        if (!ALLOWED.contains(value)) throw new IOException("Invalid save status");
        writeContent(config, value);
    }

    /** Progress is advisory; the saved status still changes only after publication and release. */
    public static void writeProgress(Path config, int percent) throws IOException {
        if (percent < 0 || percent > 100) throw new IOException("Invalid save progress");
        writeContent(config, SAVING + "\n" + percent);
    }

    private static void writeContent(Path config, String value) throws IOException {
        if (Files.isSymbolicLink(config) || !Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS))
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
        boolean pending = Files.exists(config.resolve("pending-host.dat"), LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) return pending ? RECOVERY : null;
        if (Files.size(status) > 256) throw new IOException("Invalid save status");
        String value = Files.readString(status, StandardCharsets.UTF_8);
        int newline = value.indexOf('\n');
        if (newline >= 0) {
            String progress = value.substring(newline + 1);
            value = value.substring(0, newline);
            if (!SAVING.equals(value) || !progress.matches("0|[1-9][0-9]?|100")) throw new IOException("Invalid save progress");
        }
        if (!ALLOWED.contains(value)) throw new IOException("Invalid save status");
        // A completion from an earlier session cannot describe a newer pending host.
        if (SAVED.equals(value) && pending) return RECOVERY;
        if (!SAVING.equals(value)) return value;
        // A stale saving marker must not be shown as a successful or ongoing save.
        Path runtime = config.resolve("runtime.lock");
        if (!Files.isRegularFile(runtime, LinkOption.NOFOLLOW_LINKS)) return RECOVERY;
        try (var channel = FileChannel.open(runtime, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var lock = channel.tryLock()) { return lock == null ? SAVING : RECOVERY; }
            catch (OverlappingFileLockException inUse) { return SAVING; }
        }
    }

    public static int readProgress(Path config) throws IOException {
        if (!SAVING.equals(read(config))) return -1;
        Path status = config.resolve("save-status.txt");
        String value = Files.readString(status, StandardCharsets.UTF_8);
        int newline = value.indexOf('\n');
        if (newline < 0) return -1;
        String progress = value.substring(newline + 1);
        if (!value.substring(0, newline).equals(SAVING) || !progress.matches("0|[1-9][0-9]?|100")) return -1;
        return Integer.parseInt(progress);
    }
}
