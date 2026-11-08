package dev.hosthop.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A best-effort preflight; other processes can still consume space afterward. */
public final class DiskSpace {
    private static final long RESERVE = 64L * 1024 * 1024;
    private DiskSpace() {}

    public static final class InsufficientSpace extends IOException {
        private InsufficientSpace() { super("Insufficient local disk space"); }
    }

    public static void require(Path directory, long bytes) throws IOException {
        if (directory == null || bytes < 0 || bytes > Long.MAX_VALUE - RESERVE) throw new IOException("Invalid disk space requirement");
        check(Files.getFileStore(directory).getUsableSpace(), bytes);
    }

    static void check(long usable, long bytes) throws IOException {
        if (usable < 0 || bytes < 0 || bytes > Long.MAX_VALUE - RESERVE) throw new IOException("Invalid disk space information");
        if (usable < bytes + RESERVE) throw new InsufficientSpace();
    }
}
