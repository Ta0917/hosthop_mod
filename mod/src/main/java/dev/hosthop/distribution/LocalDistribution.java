package dev.hosthop.distribution;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * This PC's side of mod distribution, kept in {@code <game>/hosthop-mods}: the registered publisher key, the newest
 * revision accepted, the mods the player agreed to, the verified JARs and the list the loader reads at startup.
 * Nothing here touches the network.
 */
public final class LocalDistribution {
    public static final String DIRECTORY = "hosthop-mods";
    public static final String LOADER_FILE = "hosthop-mod-loader.jar";
    public static final String LOADER_RESOURCE = "/hosthop/hosthop-mod-loader.jar";
    private static final String HEADER = "hosthop-active 1";
    private static final Pattern KEY = Pattern.compile("(modId|version)\\s*=\\s*\"([^\"]*)\".*");

    /** What was active and whether the loader was in place when this game started; null until recorded. */
    private static volatile List<String> startupActive;
    private static volatile boolean startupLoader;

    private final Path root;

    public record State(String publisherKey, long revision, Set<String> consented) {
        public State { consented = Set.copyOf(consented); }
    }
    /** needConsent are mods the player has not agreed to yet; changed means the installed set differs. */
    public record Plan(ModManifest manifest, List<ModManifest.Entry> needConsent, boolean changed) {}

    /** The list is not signed by the registered publisher, or names another world. */
    public static final class Untrusted extends IOException { Untrusted(String message) { super(message); } }
    /** The service offered a list older than one already accepted here. */
    public static final class RolledBack extends IOException { RolledBack() { super("The distributed mod list is older than one already accepted"); } }
    /** A different publisher key is already registered; replacing it must be a deliberate, manual step. */
    public static final class KeyConflict extends IOException { KeyConflict() { super("A different publisher key is already registered"); } }

    public LocalDistribution(Path gameDirectory) { this.root = gameDirectory.resolve(DIRECTORY); }
    public Path root() { return root; }
    public Path publishFolder() { return root.getParent().resolve("hosthop-publish"); }

    // ---- trust and consent ----

    public State state() throws IOException {
        Path file = root.resolve("state.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new State(null, 0, Set.of());
        if (Files.isSymbolicLink(file) || Files.size(file) > 65536) throw new IOException("Invalid distribution state");
        try {
            var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            var consented = new TreeSet<String>();
            for (var id : json.getAsJsonArray("consented")) consented.add(id.getAsString());
            String key = json.has("publisher_key") ? json.get("publisher_key").getAsString() : null;
            if (key != null) PublisherKey.decodePublic(key);
            return new State(key, json.get("revision").getAsLong(), consented);
        } catch (RuntimeException invalid) { throw new IOException("Invalid distribution state", invalid); }
    }

    private void save(State state) throws IOException {
        var json = new JsonObject();
        if (state.publisherKey() != null) json.addProperty("publisher_key", state.publisherKey());
        json.addProperty("revision", state.revision());
        var consented = new JsonArray();
        new TreeSet<>(state.consented()).forEach(consented::add);
        json.add("consented", consented);
        writeAtomically(root.resolve("state.json"), json.toString());
    }

    public java.util.Optional<PublicKey> trustedKey() throws IOException {
        String code = state().publisherKey();
        return code == null ? java.util.Optional.empty() : java.util.Optional.of(PublisherKey.decodePublic(code));
    }

    /** Registers the publisher's key; the same key again is accepted, a different one is refused. */
    public PublicKey trust(String code) throws IOException {
        var key = PublisherKey.decodePublic(code);
        String normalized = PublisherKey.encodePublic(key);
        var state = state();
        if (state.publisherKey() != null) {
            if (!state.publisherKey().equals(normalized)) throw new KeyConflict();
            return key;
        }
        save(new State(normalized, 0, Set.of()));
        return key;
    }

    public void consent(Collection<String> ids) throws IOException {
        var state = state();
        var consented = new TreeSet<>(state.consented());
        consented.addAll(ids);
        save(new State(state.publisherKey(), state.revision(), consented));
    }

    /** Verifies a list from the service against the registered key, this world and the newest accepted revision. */
    public Plan plan(String world, String manifestText, String signature) throws IOException {
        var key = trustedKey().orElseThrow(() -> new Untrusted("No publisher key is registered"));
        if (!PublisherKey.verify(key, manifestText, signature)) throw new Untrusted("The mod list is not signed by the registered publisher");
        var manifest = ModManifest.parse(manifestText);
        if (!manifest.world().equals(world)) throw new Untrusted("The mod list belongs to another world");
        var state = state();
        if (manifest.revision() < state.revision()) throw new RolledBack();
        var pending = new ArrayList<ModManifest.Entry>();
        for (var mod : manifest.mods()) if (!state.consented().contains(mod.id())) pending.add(mod);
        return new Plan(manifest, pending, !hashes(manifest).equals(active()));
    }

    // ---- installing ----

    /** Installs every JAR from the downloaded bundle, checking each against the signed list, then switches the list. */
    public void install(ModManifest manifest, Path bundle) throws IOException {
        requireConsent(manifest);
        Path staging = Files.createDirectories(root.resolve("staging"));
        var expected = new java.util.HashMap<String, ModManifest.Entry>();
        for (var mod : manifest.mods()) expected.put(mod.file(), mod);
        var found = new TreeSet<String>();
        if (!manifest.mods().isEmpty()) {
            try (var zip = new ZipInputStream(Files.newInputStream(bundle, LinkOption.NOFOLLOW_LINKS))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    var mod = expected.get(entry.getName());
                    if (mod == null || entry.isDirectory() || !found.add(mod.file())) throw new IOException("Unexpected file in mod bundle");
                    Path temporary = Files.createTempFile(staging, "mod-", ".tmp");
                    try {
                        String digest = copyBounded(zip, temporary, mod.size());
                        if (Files.size(temporary) != mod.size() || !digest.equals(mod.sha256())) throw new IOException("Mod file does not match the signed list");
                        store(temporary, mod.sha256());
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
            if (!found.equals(expected.keySet())) throw new IOException("Mod bundle is incomplete");
        }
        activate(manifest);
    }

    /** For the publisher: installs from the local files that were just published, without downloading them again. */
    public void installFromFiles(ModManifest manifest, Map<String, Path> filesByHash) throws IOException {
        requireConsent(manifest);
        for (var mod : manifest.mods()) {
            Path source = filesByHash.get(mod.sha256());
            if (source == null || !mod.sha256().equals(sha256(source))) throw new IOException("Published file changed");
            Path temporary = Files.createTempFile(Files.createDirectories(root.resolve("staging")), "mod-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!mod.sha256().equals(sha256(temporary))) throw new IOException("Published file changed");
                store(temporary, mod.sha256());
            } finally { Files.deleteIfExists(temporary); }
        }
        activate(manifest);
    }

    /** The installed set already matches; remember the revision so an older list is refused later. */
    public void markAccepted(ModManifest manifest) throws IOException {
        var state = state();
        if (manifest.revision() > state.revision()) save(new State(state.publisherKey(), manifest.revision(), state.consented()));
    }

    private void requireConsent(ModManifest manifest) throws IOException {
        var consented = state().consented();
        for (var mod : manifest.mods()) if (!consented.contains(mod.id())) throw new IOException("Mod was not accepted: " + mod.id());
    }

    private void store(Path verified, String sha256) throws IOException {
        Path target = jar(sha256);
        Files.createDirectories(target.getParent());
        Files.move(verified, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void activate(ModManifest manifest) throws IOException {
        var text = new StringBuilder(HEADER).append('\n');
        for (String hash : hashes(manifest)) text.append(hash).append('\n');
        writeAtomically(root.resolve("active.txt"), text.toString());
        var state = state();
        save(new State(state.publisherKey(), Math.max(state.revision(), manifest.revision()), state.consented()));
        removeUnused();
    }

    /** JARs no longer listed; one loaded by this game stays locked on Windows and is removed on a later run. */
    private void removeUnused() {
        Path jars = root.resolve("jars");
        if (!Files.isDirectory(jars, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            var keep = new TreeSet<>(active());
            var loaded = startupActive;
            if (loaded != null) keep.addAll(loaded);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(jars, "*.jar")) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    if (!keep.contains(name.substring(0, name.length() - 4))) {
                        try { Files.deleteIfExists(file); } catch (IOException locked) { /* retried after the next start */ }
                    }
                }
            }
        } catch (IOException ignored) { /* cleanup only */ }
    }

    public List<String> active() throws IOException {
        Path file = root.resolve("active.txt");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) throw new IOException("Invalid active mod list");
        var hashes = new TreeSet<String>();
        for (String line : lines.subList(1, lines.size())) if (!line.isBlank()) hashes.add(line);
        return List.copyOf(hashes);
    }

    private static List<String> hashes(ModManifest manifest) {
        var hashes = new TreeSet<String>();
        for (var mod : manifest.mods()) hashes.add(mod.sha256());
        return List.copyOf(hashes);
    }

    private Path jar(String sha256) { return root.resolve("jars").resolve(sha256 + ".jar"); }

    // ---- startup ----

    /** Called once while the game starts, before anything here can change, to know what is actually loaded. */
    public void recordStartup(Path modsDirectory) {
        try { startupActive = active(); } catch (IOException invalid) { startupActive = List.of(); }
        startupLoader = Files.isRegularFile(modsDirectory.resolve(LOADER_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    /** True when the installed list is not what this game loaded, so a restart is needed to play with it. */
    public boolean restartRequired() throws IOException {
        var active = active();
        var loaded = startupActive;
        if (loaded == null) return !active.isEmpty();
        return !active.equals(loaded) || (!active.isEmpty() && !startupLoader);
    }

    /**
     * Puts HostHop's own loader into the mods folder, once a publisher key is registered. It comes from inside this
     * JAR, never from the network. A running game keeps the file locked, so an outdated copy is replaced next time.
     */
    public static boolean installLoader(Path modsDirectory) throws IOException {
        byte[] bundled;
        try (InputStream input = LocalDistribution.class.getResourceAsStream(LOADER_RESOURCE)) {
            if (input == null) throw new IOException("Loader missing from HostHop");
            bundled = input.readAllBytes();
        }
        Path target = modsDirectory.resolve(LOADER_FILE);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && java.util.Arrays.equals(Files.readAllBytes(target), bundled)) return false;
        Path temporary = Files.createTempFile(modsDirectory, ".hosthop-loader-", ".tmp");
        try {
            Files.write(temporary, bundled);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException locked) {
            return false;
        } finally { Files.deleteIfExists(temporary); }
    }

    // ---- publishing ----

    /** Describes a NeoForge mod JAR from its metadata; files that are not mods are refused. */
    public static ModManifest.Entry describe(Path jar) throws IOException {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a file: " + jar.getFileName());
        long size = Files.size(jar);
        if (size < 1 || size > ModManifest.MAX_JAR) throw new IOException("Mod file too large: " + jar.getFileName());
        String id, version;
        try (var file = new JarFile(jar.toFile())) {
            var entry = file.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null || entry.getSize() > 262144) throw new IOException("Not a NeoForge mod: " + jar.getFileName());
            String toml;
            try (var input = file.getInputStream(entry)) { toml = new String(input.readNBytes(262144), StandardCharsets.UTF_8); }
            var declared = declaredMods(toml);
            if (declared.isEmpty()) throw new IOException("No mod ID in " + jar.getFileName());
            if (declared.size() > 1) throw new IOException("Several mods in one file are not supported: " + jar.getFileName());
            var first = declared.entrySet().iterator().next();
            id = first.getKey();
            version = first.getValue();
            if (version.contains("${") || version.isEmpty()) {
                var manifest = file.getManifest();
                String implementation = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
                version = implementation == null || implementation.isBlank() ? "unknown" : implementation.strip();
            }
        }
        if (version.length() > 64) version = version.substring(0, 64);
        String name = jar.getFileName().toString().replaceAll("[^A-Za-z0-9._+-]", "_");
        if (name.length() > 124) name = name.substring(name.length() - 124);
        if (!name.endsWith(".jar")) name = name + ".jar";
        return new ModManifest.Entry(id, version, name, sha256(jar), size);
    }

    /** Mod IDs and versions from the [[mods]] sections of a neoforge.mods.toml; dependency sections are ignored. */
    static Map<String, String> declaredMods(String toml) {
        var mods = new java.util.LinkedHashMap<String, String>();
        boolean inMods = false;
        String current = null;
        for (String raw : toml.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[")) { inMods = line.replace(" ", "").startsWith("[[mods]]"); current = null; continue; }
            var match = KEY.matcher(line);
            if (!inMods || !match.matches()) continue;
            if (match.group(1).equals("modId")) { current = match.group(2); mods.putIfAbsent(current, ""); }
            else if (current != null) mods.put(current, match.group(2));
        }
        return mods;
    }

    /**
     * Files in the mods folder that also provide a distributed mod. The loader skips the distributed copy then,
     * because two files with one mod ID stop the game; the player must remove the hand-installed one.
     */
    public static List<String> duplicatesInModsFolder(Path modsDirectory, ModManifest manifest) {
        var ids = new TreeSet<String>();
        for (var mod : manifest.mods()) ids.add(mod.id());
        var duplicates = new ArrayList<String>();
        if (ids.isEmpty() || !Files.isDirectory(modsDirectory)) return duplicates;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDirectory, "*.jar")) {
            for (Path jar : stream) {
                if (jar.getFileName().toString().equals(LOADER_FILE)) continue;
                try (var file = new JarFile(jar.toFile())) {
                    var entry = file.getEntry("META-INF/neoforge.mods.toml");
                    if (entry == null) continue;
                    String toml;
                    try (var input = file.getInputStream(entry)) { toml = new String(input.readNBytes(262144), StandardCharsets.UTF_8); }
                    for (String id : declaredMods(toml).keySet()) if (ids.contains(id)) { duplicates.add(jar.getFileName().toString()); break; }
                } catch (IOException | RuntimeException unreadable) { /* not a mod JAR */ }
            }
        } catch (IOException ignored) { /* the loader still skips the distributed copy and logs it */ }
        duplicates.sort(null);
        return duplicates;
    }

    /** One ZIP with every JAR, uploaded as a single Drive file that is overwritten in place on the next publish. */
    public static void writeBundle(List<ModManifest.Entry> mods, Map<String, Path> filesByHash, Path target) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            zip.setLevel(1);
            for (var mod : mods) {
                zip.putNextEntry(new ZipEntry(mod.file()));
                try (var input = Files.newInputStream(filesByHash.get(mod.sha256()), LinkOption.NOFOLLOW_LINKS)) { input.transferTo(zip); }
                zip.closeEntry();
            }
        }
    }

    // ---- helpers ----

    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    /** Copies at most limit bytes and fails on more, so a compressed entry cannot fill the disk. */
    private static String copyBounded(InputStream input, Path target, long limit) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DigestOutputStream(Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), digest)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int read; (read = input.read(buffer)) > 0; ) {
                    total += read;
                    if (total > limit) throw new IOException("Mod file larger than the signed list says");
                    output.write(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("Invalid distribution file");
        Path temporary = Files.createTempFile(target.getParent(), ".hosthop-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
