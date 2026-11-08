package dev.hosthop.snapshot;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FilterInputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Verifies offline backup format 1. It does not make a live-world snapshot. */
public final class SnapshotVerifier {
    private static final String MANIFEST = "hosthop-manifest.json";
    private static final long MAX_MANIFEST = 16L * 1024 * 1024;
    private static final long MAX_TOTAL = 64L * 1024 * 1024 * 1024;
    private static final int MAX_FILES = 500_000;
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SnapshotVerifier() {}
    private record Metadata(long size, String sha256) {}
    private record Verified(Map<String, Metadata> files, Map<String, ZipArchiveEntry> entries, Result result) {}
    public record Result(int files, long uncompressedBytes) {}

    /** Verification does not prevent subsequent archive modification; caller must protect the file. */
    public static Result verify(Path path) throws IOException {
        if (Files.size(path) > MAX_TOTAL) throw new IOException("Archive too large");
        try (var archive = ZipFile.builder().setPath(path).get()) {
            return verifyArchive(archive).result();
        }
    }

    /** Restores only to a new directory. Partial output is retained on failure. */
    public static Result restore(Path path, Path destination) throws IOException {
        if (Files.size(path) > MAX_TOTAL) throw new IOException("Archive too large");
        Path root = destination.toAbsolutePath().normalize();
        try (var archive = ZipFile.builder().setPath(path).get()) {
            var verified = verifyArchive(archive);
            Files.createDirectory(root);
            byte[] buffer = new byte[1024 * 1024];
            for (var item : verified.files().entrySet()) {
                var parts = item.getKey().split("/");
                Path parent = root;
                for (int i = 0; i < parts.length - 1; i++) {
                    parent = parent.resolve(parts[i]);
                    try { Files.createDirectory(parent); }
                    catch (java.nio.file.FileAlreadyExistsException exists) {
                        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid restore directory", exists);
                    }
                }
                Path output = parent.resolve(parts[parts.length - 1]);
                var digest = sha256();
                long count = 0;
                try (var input = archive.getInputStream(verified.entries().get("world/" + item.getKey()));
                     var target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        count += read;
                        if (count > item.getValue().size()) throw new IOException("Archive entry exceeds declared size");
                        digest.update(buffer, 0, read);
                        target.write(buffer, 0, read);
                    }
                }
                if (count != item.getValue().size() || !HexFormat.of().formatHex(digest.digest()).equals(item.getValue().sha256()))
                    throw new IOException("Restored content mismatch");
            }
            return verified.result();
        }
    }

    private static Verified verifyArchive(ZipFile archive) throws IOException {
            var names = new HashSet<String>();
            var entries = new HashMap<String, ZipArchiveEntry>();
            var enumeration = archive.getEntries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                int type = entry.getUnixMode() & 0170000;
                if (entry.isUnixSymlink() || (type != 0 && type != 0100000) || !archive.canReadEntryData(entry))
                    throw new IOException("Unsupported archive entry type");
                if (entries.size() >= MAX_FILES + 1) throw new IOException("Too many archive entries");
                if (!names.add(entry.getName())) throw new IOException("Duplicate archive entry");
                entries.put(entry.getName(), entry);
            }
            var manifest = entries.get(MANIFEST);
            if (manifest == null || manifest.isDirectory() || manifest.getSize() < 0 || manifest.getSize() > MAX_MANIFEST)
                throw new IOException("Invalid manifest");
            Map<String, Metadata> files;
            try (var input = new LimitedInput(archive.getInputStream(manifest), MAX_MANIFEST)) { files = readManifest(input); }
            if (entries.size() != files.size() + 1) throw new IOException("Unexpected archive entry");
            long total = 0;
            byte[] buffer = new byte[1024 * 1024];
            for (var item : files.entrySet()) {
                var entry = entries.get("world/" + item.getKey());
                var metadata = item.getValue();
                if (entry == null || entry.isDirectory() || entry.getSize() != metadata.size()) throw new IOException("Missing or invalid archive entry");
                total += metadata.size();
                if (total > MAX_TOTAL) throw new IOException("Snapshot too large");
                var digest = sha256();
                long count = 0;
                try (var input = archive.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        count += read;
                        if (count > metadata.size()) throw new IOException("Archive entry exceeds declared size");
                        digest.update(buffer, 0, read);
                    }
                }
                if (count != metadata.size() || !HexFormat.of().formatHex(digest.digest()).equals(metadata.sha256()))
                    throw new IOException("Archive content mismatch");
            }
            return new Verified(files, entries, new Result(files.size(), total));
    }

    private static Map<String, Metadata> readManifest(InputStream input) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new JsonReader(new InputStreamReader(input, decoder))) {
            reader.setLenient(false);
            var keys = new HashSet<String>();
            Map<String, Metadata> files = null;
            long format = -1;
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!keys.add(key)) throw new IOException("Duplicate manifest field");
                if (key.equals("format")) format = readLong(reader);
                else if (key.equals("files")) files = readFiles(reader);
                else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || format != 1 || files == null) throw new IOException("Unsupported manifest");
            return files;
        } catch (IllegalStateException | NumberFormatException invalid) {
            throw new IOException("Invalid manifest", invalid);
        }
    }

    private static Map<String, Metadata> readFiles(JsonReader reader) throws IOException {
        var files = new HashMap<String, Metadata>();
        var folded = new HashSet<String>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            String key = folded(name);
            if (!validPath(name) || !folded.add(key) || files.size() >= MAX_FILES) throw new IOException("Invalid or conflicting path");
            reader.beginObject();
            long size = -1;
            String hash = null;
            var keys = new HashSet<String>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!keys.add(field)) throw new IOException("Duplicate file field");
                if (field.equals("size")) size = readLong(reader);
                else if (field.equals("sha256")) {
                    if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid digest");
                    hash = reader.nextString();
                } else reader.skipValue();
            }
            reader.endObject();
            if (size < 0 || size > MAX_TOTAL || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid file metadata");
            files.put(name, new Metadata(size, hash));
        }
        reader.endObject();
        for (String key : folded) {
            int slash = key.indexOf('/');
            while (slash >= 0) {
                if (folded.contains(key.substring(0, slash))) throw new IOException("File conflicts with directory");
                slash = key.indexOf('/', slash + 1);
            }
        }
        return files;
    }

    private static long readLong(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected integer");
        String value = reader.nextString();
        if (!value.matches("0|[1-9][0-9]*")) throw new IOException("Invalid integer");
        return Long.parseLong(value);
    }

    private static String folded(String path) { return path.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT); }

    private static boolean validPath(String path) {
        if (path.isEmpty() || path.chars().anyMatch(c -> Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0)) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(" ") || part.endsWith(".")) return false;
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (RESERVED.contains(stem)) return false;
        }
        return true;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
    }

    private static final class LimitedInput extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedInput(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++count > limit) throw new IOException("Manifest too large");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            if (read > 0 && (count += read) > limit) throw new IOException("Manifest too large");
            return read;
        }
    }
}
