package dev.hosthop.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.JsonObject;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Windows DPAPI storage tied to the current Windows account. */
public final class WindowsSecretStore {
    private static final int MAX_BYTES = 100_000;
    private static final byte[] ENTROPY = "HostHop credentials v1".getBytes(StandardCharsets.UTF_8);
    private final Path path;

    public WindowsSecretStore(Path path) {
        if (path == null) throw new IllegalArgumentException("Missing secret path");
        this.path = path.toAbsolutePath().normalize();
    }

    public boolean exists() { return !Files.notExists(path, LinkOption.NOFOLLOW_LINKS); }

    /** Keys and values are encrypted before touching disk. Parent directory must exist. */
    public void save(Map<String, String> secrets) throws IOException {
        requireWindows();
        if (secrets == null || secrets.size() > 32) throw new IllegalArgumentException("Invalid secrets");
        var json = new JsonObject();
        for (var entry : secrets.entrySet()) {
            if (!validKey(entry.getKey()) || !validValue(entry.getValue())) throw new IllegalArgumentException("Invalid secret entry");
            json.addProperty(entry.getKey(), entry.getValue());
        }
        byte[] plaintext = json.toString().getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_BYTES) throw new IOException("Secret store too large");
        byte[] ciphertext;
        try { ciphertext = Crypt32Util.cryptProtectData(plaintext, ENTROPY, 1, "HostHop", null); }
        catch (RuntimeException failure) { throw new IOException("Cannot encrypt credentials", failure); }
        finally { java.util.Arrays.fill(plaintext, (byte) 0); }
        if (ciphertext.length > MAX_BYTES) throw new IOException("Secret store too large");
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IOException("Invalid secret store location");
        Path temporary = Files.createTempFile(parent, ".hosthop-secret-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var data = ByteBuffer.wrap(ciphertext);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IOException("Atomic credential storage unavailable", unsupported); }
        } finally {
            Files.deleteIfExists(temporary);
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    public Map<String, String> load() throws IOException {
        requireWindows();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) throw new IOException("Invalid secret store");
        byte[] ciphertext;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { ciphertext = input.readNBytes(MAX_BYTES + 1); }
        if (ciphertext.length > MAX_BYTES) throw new IOException("Secret store too large");
        byte[] plaintext;
        try { plaintext = Crypt32Util.cryptUnprotectData(ciphertext, ENTROPY, 1, null); }
        catch (RuntimeException failure) { throw new IOException("Cannot decrypt credentials", failure); }
        finally { java.util.Arrays.fill(ciphertext, (byte) 0); }
        try (var reader = new JsonReader(new StringReader(new String(plaintext, StandardCharsets.UTF_8)))) {
            reader.setLenient(false);
            var result = new HashMap<String, String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!validKey(key) || result.containsKey(key) || result.size() >= 32 || reader.peek() != JsonToken.STRING)
                    throw new IOException("Invalid credential data");
                String value = reader.nextString();
                if (!validValue(value)) throw new IOException("Invalid credential data");
                result.put(key, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid credential data");
            return Map.copyOf(result);
        } catch (IllegalStateException invalid) { throw new IOException("Invalid credential data", invalid); }
        finally { java.util.Arrays.fill(plaintext, (byte) 0); }
    }

    private static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{1,80}"); }
    private static boolean validValue(String value) { return value != null && !value.isBlank() && value.length() <= 4096 && value.chars().noneMatch(Character::isISOControl); }
    private static void requireWindows() throws IOException { if (!Platform.isWindows()) throw new IOException("Secure credential storage requires Windows"); }
}
