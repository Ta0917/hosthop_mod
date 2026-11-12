package dev.hosthop.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes the same bytes as java.util.zip.ZipOutputStream at level 1 with every entry dated {@link #DOS_TIME}, but
 * takes entries that were already deflated, so they can be compressed in parallel. Keeping the bytes identical
 * keeps archives comparable with bases written before this class existed; otherwise every header would differ
 * and each delta would grow until the base was renewed, which members must then grant again.
 */
final class DeflatedZipWriter {
    /**
     * 2000-01-01 00:00 in MS-DOS date/time form, as ZipEntry.setTimeLocal stores it. A fixed, time-zone independent
     * entry time keeps the archive bytes identical for identical content; otherwise every local header carries the
     * current time and each changes the delta chunk it falls in. A restored world gets fresh file times anyway.
     */
    static final int DOS_TIME = ((2000 - 1980) << 25) | (1 << 21) | (1 << 16);
    private static final long MAGIC = 0xFFFFFFFFL;
    private static final int MAGIC_COUNT = 0xFFFF;
    private static final int FLAG = 8 | 0x800; // sizes in a trailing descriptor; UTF-8 names
    private static final int VERSION = 20;     // deflate

    /** One deflated entry: raw deflate data (no zlib wrapper) plus what the headers record about it. */
    record Deflated(byte[] data, int length, long crc, long size) {
        /** Compresses all of {@code input} into memory, passing each block read to {@code read}. */
        static Deflated of(InputStream input, BlockSink read) throws IOException {
            var buffer = new Buffer();
            long[] result = deflate(input, buffer, read);
            return new Deflated(buffer.array(), buffer.size(), result[0], result[1]);
        }
    }

    @FunctionalInterface interface BlockSink { void accept(byte[] data, int length) throws IOException; }

    private static final class Buffer extends java.io.ByteArrayOutputStream {
        Buffer() { super(64 * 1024); }
        byte[] array() { return buf; }
    }

    /**
     * Raw deflate at level 1, as ZipOutputStream does. zlib's output does not depend on how the input is split,
     * so feeding whole blocks gives the same bytes as ZipOutputStream's smaller writes. Returns {crc, size, csize}.
     */
    private static long[] deflate(InputStream input, OutputStream sink, BlockSink read) throws IOException {
        var crc = new CRC32();
        var deflater = new Deflater(1, true);
        try {
            byte[] in = new byte[1024 * 1024], out = new byte[256 * 1024];
            long size = 0, csize = 0;
            int count;
            while ((count = input.read(in)) != -1) {
                if (count == 0) continue;
                crc.update(in, 0, count); size += count;
                read.accept(in, count);
                deflater.setInput(in, 0, count);
                while (!deflater.needsInput()) {
                    int n = deflater.deflate(out, 0, out.length);
                    if (n > 0) { sink.write(out, 0, n); csize += n; }
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int n = deflater.deflate(out, 0, out.length);
                if (n > 0) { sink.write(out, 0, n); csize += n; }
            }
            return new long[] {crc.getValue(), size, csize};
        } finally { deflater.end(); }
    }

    private record Central(byte[] name, long crc, long csize, long size, long offset) {}

    private final OutputStream out;
    private final List<Central> entries = new ArrayList<>();
    private long written;

    DeflatedZipWriter(OutputStream out) { this.out = out; }

    void add(String name, Deflated entry) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFFFF - 46) throw new IOException("Entry name too long");
        long offset = written;
        writeHeader(nameBytes);
        write(entry.data(), 0, entry.length());
        writeDescriptor(nameBytes, offset, entry.crc(), entry.length(), entry.size());
    }

    /** For entries too large to hold in memory: compresses on the calling thread while writing. */
    void add(String name, InputStream input, BlockSink read) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFFFF - 46) throw new IOException("Entry name too long");
        long offset = written;
        writeHeader(nameBytes);
        var counted = new OutputStream() {
            @Override public void write(int b) throws IOException { out.write(b); written++; }
            @Override public void write(byte[] b, int off, int len) throws IOException { DeflatedZipWriter.this.write(b, off, len); }
        };
        long[] result = deflate(input, counted, read);
        writeDescriptor(nameBytes, offset, result[0], result[2], result[1]);
    }

    private void writeHeader(byte[] nameBytes) throws IOException {
        writeInt(0x04034b50L);
        writeShort(VERSION); writeShort(FLAG); writeShort(8); writeInt(DOS_TIME);
        writeInt(0); writeInt(0); writeInt(0);
        writeShort(nameBytes.length); writeShort(0);
        write(nameBytes, 0, nameBytes.length);
    }

    private void writeDescriptor(byte[] nameBytes, long offset, long crc, long csize, long size) throws IOException {
        writeInt(0x08074b50L); writeInt(crc);
        if (csize >= MAGIC || size >= MAGIC) { writeLong(csize); writeLong(size); }
        else { writeInt(csize); writeInt(size); }
        entries.add(new Central(nameBytes, crc, csize, size, offset));
    }

    void finish() throws IOException {
        long start = written;
        for (var e : entries) {
            int zip64 = (e.csize() >= MAGIC ? 8 : 0) + (e.size() >= MAGIC ? 8 : 0) + (e.offset() >= MAGIC ? 8 : 0);
            writeInt(0x02014b50L);
            if (zip64 > 0) { writeShort(45); writeShort(45); } else { writeShort(VERSION); writeShort(VERSION); }
            writeShort(FLAG); writeShort(8); writeInt(DOS_TIME); writeInt(e.crc());
            writeInt(Math.min(e.csize(), MAGIC)); writeInt(Math.min(e.size(), MAGIC));
            writeShort(e.name().length); writeShort(zip64 > 0 ? zip64 + 4 : 0);
            writeShort(0); writeShort(0); writeShort(0); writeInt(0);
            writeInt(Math.min(e.offset(), MAGIC));
            write(e.name(), 0, e.name().length);
            if (zip64 > 0) {
                writeShort(1); writeShort(zip64);
                if (e.size() >= MAGIC) writeLong(e.size());
                if (e.csize() >= MAGIC) writeLong(e.csize());
                if (e.offset() >= MAGIC) writeLong(e.offset());
            }
        }
        long length = written - start;
        int count = entries.size();
        boolean zip64 = length >= MAGIC || start >= MAGIC || count >= MAGIC_COUNT;
        if (zip64) {
            long end64 = written;
            writeInt(0x06064b50L); writeLong(56 - 12); writeShort(45); writeShort(45); writeInt(0); writeInt(0);
            writeLong(count); writeLong(count); writeLong(length); writeLong(start);
            writeInt(0x07064b50L); writeInt(0); writeLong(end64); writeInt(1);
        }
        int shownCount = zip64 && count >= MAGIC_COUNT ? MAGIC_COUNT : count;
        writeInt(0x06054b50L); writeShort(0); writeShort(0); writeShort(shownCount); writeShort(shownCount);
        writeInt(Math.min(length, MAGIC)); writeInt(Math.min(start, MAGIC)); writeShort(0);
        out.flush();
    }

    private void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); written += length; }
    private void writeShort(int v) throws IOException { out.write(v & 0xff); out.write((v >>> 8) & 0xff); written += 2; }
    private void writeInt(long v) throws IOException {
        out.write((int) (v & 0xff)); out.write((int) ((v >>> 8) & 0xff)); out.write((int) ((v >>> 16) & 0xff)); out.write((int) ((v >>> 24) & 0xff));
        written += 4;
    }
    private void writeLong(long v) throws IOException { writeInt(v & 0xFFFFFFFFL); writeInt(v >>> 32); }
}
