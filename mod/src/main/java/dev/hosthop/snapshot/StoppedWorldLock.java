package dev.hosthop.snapshot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds Minecraft's existing world lock while reading a stopped world. */
public final class StoppedWorldLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private StoppedWorldLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    public static StoppedWorldLock acquire(Path world) throws IOException {
        var channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("World is in use");
            return new StoppedWorldLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException nested) { failure.addSuppressed(nested); }
            throw new IOException("World is in use or cannot be locked", failure);
        }
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
