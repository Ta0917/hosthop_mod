package dev.hosthop.lease;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Local recovery evidence. Create before sending a commit and retain until it
 * is safely resolved. A corrupt or partially written file must block recovery.
 * Directory durability, access control and integration are caller concerns.
 */
public final class PendingPublicationJournal {
    private static final int MAGIC = 0x48485031;
    private static final long MAX_BYTES = 32_768;

    private PendingPublicationJournal() {}

    /** Never replaces existing evidence; an interrupted creation is left for inspection. */
    public static void create(Path path, PublicationRecord record) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeUTF(record.worldId());
            out.writeUTF(record.operationId());
            out.writeUTF(record.owner());
            out.writeUTF(record.leaseId());
            out.writeLong(record.epoch());
            out.writeLong(record.previousGeneration());
            out.writeUTF(record.digest());
            out.writeLong(record.resultGeneration());
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length + 32 > MAX_BYTES) throw new IOException("Publication record too large");
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var data = ByteBuffer.wrap(payload);
            while (data.hasRemaining()) file.write(data);
            data = ByteBuffer.wrap(sha256(payload));
            while (data.hasRemaining()) file.write(data);
            file.force(true);
        }
    }

    /** Missing, invalid, truncated and oversized files are errors, not proof of no commit. */
    public static PublicationRecord read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IOException("Publication record too large");
        byte[] bytes;
        try (var in = Files.newInputStream(path)) {
            bytes = in.readNBytes((int) MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Publication record too large");
        if (bytes.length < 36) throw new IOException("Truncated publication record");
        var payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(sha256(payload), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length)))
            throw new IOException("Publication record checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw new IOException("Unknown publication journal version");
            var record = new PublicationRecord(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                    in.readLong(), in.readLong(), in.readUTF(), in.readLong());
            if (in.read() != -1) throw new IOException("Trailing publication journal data");
            return record;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid publication record", invalid);
        }
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
    }
}
