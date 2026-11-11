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
        validateRef(baseRef);
        if (Files.size(base) != baseRef.size() || !hash(base).equals(baseRef.sha256())) throw new IOException("Base snapshot mismatch");
        long targetSize = Files.size(target);
        if (targetSize <= 0 || targetSize > MAX_BYTES) throw new IOException("Invalid target size");
        Map<String, Chunk> index = new HashMap<>();
        int[] baseChunks = {0};
        chunks(base, (bytes, length, offset) -> {
            if (++baseChunks[0] > MAX_CHUNKS) throw new IOException("Too many base chunks");
            index.putIfAbsent(chunkHash(bytes, length), new Chunk(offset, length));
        });
        Path literal = Files.createTempFile(delta.toAbsolutePath().getParent(), ".hosthop-literal-", ".tmp");
        var parts = new ArrayList<Part>();
        long[] literalSize = {0};
        MessageDigest targetHash = digest();
        try {
            try (var out = Files.newOutputStream(literal, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                chunks(target, (bytes, length, offset) -> {
                    if (parts.size() >= MAX_CHUNKS) throw new IOException("Too many delta chunks");
                    targetHash.update(bytes, 0, length);
                    Chunk found = index.get(chunkHash(bytes, length));
                    if (found != null && found.length() == length) parts.add(new Part(true, length, found.offset()));
                    else { parts.add(new Part(false, length, literalSize[0])); out.write(bytes, 0, length); literalSize[0] += length; }
                });
            }
            if (Files.size(target) != targetSize) throw new IOException("Target changed during delta creation");
            byte[] targetDigest = targetHash.digest();
            if (!HexFormat.of().formatHex(targetDigest).equals(hash(target))) throw new IOException("Target changed during delta creation");
            try (var channel = FileChannel.open(delta, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var out = new DataOutputStream(java.nio.channels.Channels.newOutputStream(channel));
                 var in = Files.newInputStream(literal, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                out.write(MAGIC);
                byte[] id = baseRef.fileId().getBytes(StandardCharsets.US_ASCII);
                out.writeShort(id.length); out.write(id); out.write(HexFormat.of().parseHex(baseRef.sha256())); out.writeLong(baseRef.size());
                out.write(targetDigest); out.writeLong(targetSize); out.writeInt(parts.size());
                for (Part part : parts) { out.writeByte(part.fromBase() ? 1 : 0); out.writeInt(part.length()); out.writeLong(part.offset()); }
                in.transferTo(out); out.flush(); channel.force(true);
            }
            inspect(delta);
        } finally { Files.deleteIfExists(literal); }
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
        Parsed parsed = parse(delta);
        if (Files.size(base) != parsed.info().base().size() || !hash(base).equals(parsed.info().base().sha256()))
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
                }
            }
            output.force(true);
            if (output.size() != parsed.info().targetSize() || !HexFormat.of().formatHex(digest.digest()).equals(parsed.info().targetSha256()))
                throw new IOException("Reconstructed snapshot mismatch");
        }
        SnapshotVerifier.verify(target);
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

    private static void chunks(Path file, ChunkSink sink) throws IOException {
        try (var in = new BufferedInputStream(Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[1024 * 1024], chunk = new byte[MAX]; int length = 0, read; long offset = 0, rolling = 0;
            while ((read = in.read(buffer)) != -1) for (int i = 0; i < read; i++) {
                int b = buffer[i] & 255; chunk[length++] = buffer[i]; rolling = (rolling << 1) + GEAR[b];
                if (length >= MIN && ((rolling & MASK) == 0 || length == MAX)) {
                    sink.accept(chunk, length, offset); offset += length; length = 0; rolling = 0;
                }
            }
            if (length > 0) sink.accept(chunk, length, offset);
        }
    }
    private static String chunkHash(byte[] bytes, int length) throws IOException {
        var d = digest(); d.update(bytes, 0, length); return HexFormat.of().formatHex(d.digest());
    }
    private static String hash(Path file) throws IOException {
        var d = digest(); try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) != -1) d.update(b, 0, n);
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
