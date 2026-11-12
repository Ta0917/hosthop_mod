package dev.hosthop.snapshot;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Content-defined binary delta against one immutable, complete snapshot. */
public final class BinaryDelta {
    private static final byte[] MAGIC = "HHDIFF01".getBytes(StandardCharsets.US_ASCII);
    private static final int MIN = 32 * 1024, MAX = 256 * 1024, MASK = 65535, MAX_CHUNKS = 200_000;
    private static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    private static final long[] GEAR = gear();
    private record Chunk(long offset, int length) {}
    private record Part(boolean fromBase, int length, long offset) {}
    public record Info(ObjectRef base, long targetSize, String targetSha256) {}
    private record Parsed(Info info, List<Part> parts, long literalsAt, long literalsSize) {}
    @FunctionalInterface private interface ChunkSink { void accept(byte[] data, int length, long offset) throws IOException; }
    private BinaryDelta() {}

    public static boolean isDelta(Path file) throws IOException {
        try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return hasMagic(in.readNBytes(MAGIC.length));
        }
    }
    public static boolean hasMagic(byte[] prefix) { return java.util.Arrays.equals(prefix, MAGIC); }

    /** Build a delta without altering either source. Full snapshots must already be verified. */
    public static void create(Path base, ObjectRef baseRef, Path target, Path delta) throws IOException {
        create(base, baseRef, target, delta, null);
    }

    /** As {@link #create(Path, ObjectRef, Path, Path)}, reporting 0..1 of the bytes read. */
    public static void create(Path base, ObjectRef baseRef, Path target, Path delta, java.util.function.DoubleConsumer progress) throws IOException {
        validateRef(baseRef);
        if (Files.size(base) != baseRef.size()) throw new IOException("Base snapshot mismatch");
        var targetBefore = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long targetSize = targetBefore.size();
        if (targetSize <= 0 || targetSize > MAX_BYTES) throw new IOException("Invalid target size");
        // Each file is read once, hashing and cutting it into chunks in the same pass, and the two are read at
        // the same time. Only the target's unmatched chunks are read again, to copy them into the delta.
        var bytes = new ByteProgress(progress, baseRef.size() + targetSize);
        var baseIndex = new java.util.concurrent.FutureTask<Map<String, Chunk>>(() -> {
            Map<String, Chunk> index = new HashMap<>();
            MessageDigest baseHash = digest();
            int[] baseChunks = {0};
            chunks(base, bytes, (data, length, offset) -> {
                if (++baseChunks[0] > MAX_CHUNKS) throw new IOException("Too many base chunks");
                baseHash.update(data, 0, length);
                index.putIfAbsent(chunkHash(data, length), new Chunk(offset, length));
            });
            if (!HexFormat.of().formatHex(baseHash.digest()).equals(baseRef.sha256())) throw new IOException("Base snapshot mismatch");
            return index;
        });
        var baseThread = new Thread(baseIndex, "hosthop-delta-base");
        baseThread.setDaemon(true);
        baseThread.start();
        record TargetChunk(String hash, int length, long offset) {}
        var targetChunks = new ArrayList<TargetChunk>();
        MessageDigest targetHash = digest();
        Map<String, Chunk> index;
        try {
            chunks(target, bytes, (data, length, offset) -> {
                if (targetChunks.size() >= MAX_CHUNKS) throw new IOException("Too many delta chunks");
                targetHash.update(data, 0, length);
                targetChunks.add(new TargetChunk(chunkHash(data, length), length, offset));
            });
            index = baseIndex.get();
        } catch (InterruptedException interrupted) {
            baseIndex.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Delta creation interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("Delta base indexing failed", failed.getCause());
        } catch (IOException failure) {
            baseIndex.cancel(true);
            throw failure;
        }
        byte[] targetDigest = targetHash.digest();
        Path literal = Files.createTempFile(delta.toAbsolutePath().getParent(), ".hosthop-literal-", ".tmp");
        var parts = new ArrayList<Part>();
        long literalSize = 0;
        try {
            try (var source = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var out = Files.newOutputStream(literal, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[MAX];
                for (var chunk : targetChunks) {
                    Chunk found = index.get(chunk.hash());
                    if (found != null && found.length() == chunk.length()) { parts.add(new Part(true, chunk.length(), found.offset())); continue; }
                    readFully(source, buffer, chunk.length(), chunk.offset(), "Target changed during delta creation");
                    out.write(buffer, 0, chunk.length());
                    parts.add(new Part(false, chunk.length(), literalSize));
                    literalSize += chunk.length();
                }
            }
            // The copied chunks are read after hashing, so callers must still check the delta with verifyRebuild.
            var targetAfter = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (targetAfter.size() != targetSize || !targetAfter.lastModifiedTime().equals(targetBefore.lastModifiedTime())
                    || !java.util.Objects.equals(targetAfter.fileKey(), targetBefore.fileKey()))
                throw new IOException("Target changed during delta creation");
            try (var channel = FileChannel.open(delta, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var out = new DataOutputStream(new java.io.BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel), 1024 * 1024));
                 var in = Files.newInputStream(literal, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                out.write(MAGIC);
                byte[] id = baseRef.fileId().getBytes(StandardCharsets.US_ASCII);
                out.writeShort(id.length); out.write(id); out.write(HexFormat.of().parseHex(baseRef.sha256())); out.writeLong(baseRef.size());
                out.write(targetDigest); out.writeLong(targetSize); out.writeInt(parts.size());
                for (Part part : parts) { out.writeByte(part.fromBase() ? 1 : 0); out.writeInt(part.length()); out.writeLong(part.offset()); }
                in.transferTo(out); out.flush(); channel.force(true);
            }
            inspect(delta);
            bytes.finish();
        } finally { Files.deleteIfExists(literal); }
    }

    /**
     * Checks, without writing anything, that the delta rebuilds exactly {@code target}: the bytes it rebuilds from
     * {@code base} and the target's own bytes, hashed in parallel, must have the same SHA-256 and length. This checks
     * the same thing as rebuilding the archive to disk and comparing it byte by byte, in a fraction of the time.
     */
    public static void verifyRebuild(Path delta, Path base, Path target, java.util.function.DoubleConsumer progress) throws IOException {
        Parsed parsed = parse(delta);
        long targetSize = Files.size(target);
        if (Files.size(base) != parsed.info().base().size() || targetSize != parsed.info().targetSize())
            throw new IOException("Delta verification mismatch");
        var bytes = new ByteProgress(progress, 2 * targetSize);
        var targetDigest = new java.util.concurrent.FutureTask<String>(() -> hash(target, bytes));
        var thread = new Thread(targetDigest, "hosthop-delta-check");
        thread.setDaemon(true);
        thread.start();
        String rebuilt;
        try (var original = FileChannel.open(base, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var patch = FileChannel.open(delta, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var digest = digest(); byte[] buffer = new byte[MAX]; long total = 0;
            for (Part part : parsed.parts()) {
                var source = part.fromBase() ? original : patch;
                long position = part.fromBase() ? part.offset() : parsed.literalsAt() + part.offset();
                readFully(source, buffer, part.length(), position, "Truncated delta input");
                digest.update(buffer, 0, part.length());
                bytes.add(part.length());
                total += part.length();
            }
            if (total != targetSize) throw new IOException("Delta verification mismatch");
            rebuilt = HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            targetDigest.cancel(true);
            throw failure;
        }
        String expected;
        try { expected = targetDigest.get(); }
        catch (InterruptedException interrupted) {
            targetDigest.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Delta check interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("Delta check failed", failed.getCause());
        }
        if (!rebuilt.equals(expected) || !rebuilt.equals(parsed.info().targetSha256())) throw new IOException("Delta verification mismatch");
        bytes.finish();
    }

    private static void readFully(FileChannel source, byte[] buffer, int length, long position, String failure) throws IOException {
        var view = ByteBuffer.wrap(buffer, 0, length);
        while (view.hasRemaining()) {
            int read = source.read(view, position);
            if (read <= 0) throw new IOException(failure);
            position += read;
        }
    }

    public static Info inspect(Path delta) throws IOException { return parse(delta).info(); }

    /** Bytes that always cover the base reference at the start of a delta. */
    public static final int HEADER_PREFIX = 8 + 2 + 200 + 32 + 8;

    /** Reads only the base reference from the first bytes of a delta; the rest of the file is not checked. */
    public static ObjectRef baseFromHeader(byte[] prefix) throws IOException {
        if (prefix == null || prefix.length < MAGIC.length || !hasMagic(java.util.Arrays.copyOf(prefix, MAGIC.length))) throw new IOException("Invalid delta magic");
        try (var in = new DataInputStream(new java.io.ByteArrayInputStream(prefix, MAGIC.length, prefix.length - MAGIC.length))) {
            int idSize = in.readUnsignedShort();
            if (idSize < 1 || idSize > 200) throw new IOException("Invalid base identifier");
            byte[] idBytes = in.readNBytes(idSize), baseHash = in.readNBytes(32);
            if (idBytes.length != idSize || baseHash.length != 32) throw new IOException("Truncated delta header");
            var base = new ObjectRef(new String(idBytes, StandardCharsets.US_ASCII), HexFormat.of().formatHex(baseHash), in.readLong());
            validateRef(base);
            return base;
        } catch (java.io.EOFException truncated) { throw new IOException("Truncated delta header", truncated); }
    }

    /** Rebuild the exact original full ZIP, then verify both its SHA-256 and all world files. */
    public static void apply(Path delta, Path base, Path target) throws IOException {
        apply(delta, base, target, null);
    }

    /** As {@link #apply(Path, Path, Path)}, reporting 0..1; the final archive check reports 1. */
    public static void apply(Path delta, Path base, Path target, java.util.function.DoubleConsumer progress) throws IOException {
        Parsed parsed = parse(delta);
        // Hashing the base and writing the target; verifying the rebuilt archive is the rest.
        var done = new ByteProgress(ByteProgress.range(progress, 0, 0.7), Files.size(base) + parsed.info().targetSize());
        if (Files.size(base) != parsed.info().base().size() || !hash(base, done).equals(parsed.info().base().sha256()))
            throw new IOException("Delta base mismatch");
        try (var original = FileChannel.open(base, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var patch = FileChannel.open(delta, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var digest = digest(); byte[] bytes = new byte[128 * 1024];
            for (Part part : parsed.parts()) {
                var source = part.fromBase() ? original : patch;
                long position = part.fromBase() ? part.offset() : parsed.literalsAt() + part.offset();
                int remaining = part.length();
                while (remaining > 0) {
                    int length = Math.min(bytes.length, remaining);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
                    int read = source.read(buffer, position);
                    if (read <= 0) throw new IOException("Truncated delta input");
                    digest.update(bytes, 0, read);
                    var written = ByteBuffer.wrap(bytes, 0, read);
                    while (written.hasRemaining()) output.write(written);
                    position += read; remaining -= read;
                    done.add(read);
                }
            }
            output.force(true);
            if (output.size() != parsed.info().targetSize() || !HexFormat.of().formatHex(digest.digest()).equals(parsed.info().targetSha256()))
                throw new IOException("Reconstructed snapshot mismatch");
        }
        SnapshotVerifier.verify(target);
        if (progress != null) progress.accept(1.0);
    }

    private static Parsed parse(Path delta) throws IOException {
        long fileSize = Files.size(delta);
        if (fileSize <= MAGIC.length || fileSize > MAX_BYTES) throw new IOException("Invalid delta size");
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(delta, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            if (!java.util.Arrays.equals(in.readNBytes(MAGIC.length), MAGIC)) throw new IOException("Invalid delta magic");
            int idSize = in.readUnsignedShort();
            if (idSize < 1 || idSize > 200) throw new IOException("Invalid base identifier");
            byte[] idBytes = in.readNBytes(idSize), baseHash = in.readNBytes(32);
            if (idBytes.length != idSize || baseHash.length != 32) throw new IOException("Truncated delta header");
            long baseSize = in.readLong(); byte[] targetHash = in.readNBytes(32);
            if (targetHash.length != 32) throw new IOException("Truncated delta header");
            long targetSize = in.readLong(); int count = in.readInt();
            ObjectRef base = new ObjectRef(new String(idBytes, StandardCharsets.US_ASCII), HexFormat.of().formatHex(baseHash), baseSize);
            validateRef(base);
            if (targetSize <= 0 || targetSize > MAX_BYTES || count < 1 || count > MAX_CHUNKS) throw new IOException("Invalid delta target");
            var parts = new ArrayList<Part>(count); long total = 0, literals = 0;
            for (int i = 0; i < count; i++) {
                int type = in.readUnsignedByte(), length = in.readInt(); long offset = in.readLong();
                if ((type != 0 && type != 1) || length < 1 || length > MAX || offset < 0 || length > targetSize - total)
                    throw new IOException("Invalid delta chunk");
                if (type == 1 && (offset > baseSize || length > baseSize - offset)) throw new IOException("Invalid base chunk");
                if (type == 0) {
                    if (offset != literals || length > MAX_BYTES - literals) throw new IOException("Invalid literal chunk");
                    literals += length;
                }
                total += length; parts.add(new Part(type == 1, length, offset));
            }
            long header = MAGIC.length + 2L + idSize + 32 + 8 + 32 + 8 + 4 + 13L * count;
            if (total != targetSize || header > fileSize || literals != fileSize - header) throw new IOException("Invalid delta length");
            return new Parsed(new Info(base, targetSize, HexFormat.of().formatHex(targetHash)), List.copyOf(parts), header, literals);
        } catch (RuntimeException invalid) { throw new IOException("Invalid delta", invalid); }
    }

    private static void chunks(Path file, ByteProgress progress, ChunkSink sink) throws IOException {
        try (var in = new BufferedInputStream(Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[1024 * 1024], chunk = new byte[MAX]; int length = 0, read; long offset = 0, rolling = 0;
            while ((read = in.read(buffer)) != -1) { progress.add(read); for (int i = 0; i < read; i++) {
                int b = buffer[i] & 255; chunk[length++] = buffer[i]; rolling = (rolling << 1) + GEAR[b];
                if (length >= MIN && ((rolling & MASK) == 0 || length == MAX)) {
                    sink.accept(chunk, length, offset); offset += length; length = 0; rolling = 0;
                }
            } }
            if (length > 0) sink.accept(chunk, length, offset);
        }
    }
    private static String chunkHash(byte[] bytes, int length) throws IOException {
        var d = digest(); d.update(bytes, 0, length); return HexFormat.of().formatHex(d.digest());
    }
    private static String hash(Path file, ByteProgress progress) throws IOException {
        var d = digest(); try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) != -1) { d.update(b, 0, n); progress.add(n); }
        } return HexFormat.of().formatHex(d.digest());
    }
    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static void validateRef(ObjectRef ref) throws IOException {
        if (ref == null || ref.fileId() == null || !ref.fileId().matches("[A-Za-z0-9_-]{1,200}") || ref.sha256() == null
                || !ref.sha256().matches("[0-9a-f]{64}") || ref.size() <= 0 || ref.size() > MAX_BYTES) throw new IOException("Invalid delta base");
    }
    private static long[] gear() {
        long[] table = new long[256]; long x = 0x243f6a8885a308d3L;
        for (int i = 0; i < table.length; i++) { x += 0x9e3779b97f4a7c15L; long z = x; z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L; z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL; table[i] = z ^ (z >>> 31); }
        return table;
    }
}
