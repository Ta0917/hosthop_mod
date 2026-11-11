package dev.hosthop.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.neoforged.fml.ModList;

/** Public identifiers and hashes only; never reads HostHop credentials. */
public record CompatibilityManifest(Map<String,String> mods, Set<String> ignoredMods, Map<String,String> configs, Set<String> ignoredConfigs) {
    /**
     * Config files neither side compares. A rule is a path relative to config/, a folder ending in "/",
     * or a prefix ending in "*". Each side publishes its own rules, so a difference is skipped when either side ignores it.
     */
    public CompatibilityManifest(Map<String,String> mods, Set<String> ignoredMods, Map<String,String> configs) {
        this(mods, ignoredMods, configs, Set.of());
    }
    /** One differing mod; a version is null when that side does not have the mod. */
    public record ModChange(String id, String hostVersion, String localVersion) {
        /** The version without the file hash appended to packaged jars. */
        public static String version(String recorded) {
            if (recorded == null) return null;
            int hash = recorded.indexOf('#');
            return hash < 0 ? recorded : recorded.substring(0, hash);
        }
        /** Same version string, but the jar files themselves differ. */
        public boolean sameVersionDifferentFile() {
            return hostVersion != null && localVersion != null && version(hostVersion).equals(version(localVersion));
        }
    }
    public record Differences(java.util.List<ModChange> modChanges, java.util.List<String> configs) {
        public java.util.List<String> mods() { return modChanges.stream().map(ModChange::id).toList(); }
        public boolean isEmpty() { return modChanges.isEmpty() && configs.isEmpty(); }
    }
    private static Path policy(Path game) { return game.resolve("config").resolve("hosthop").resolve("compatibility-ignore.txt"); }
    private static Path configPolicy(Path game) { return game.resolve("config").resolve("hosthop").resolve("compatibility-ignore-configs.txt"); }
    private static boolean validConfigRule(String rule) {
        return rule != null && !rule.isBlank() && rule.length() <= 240 && rule.chars().noneMatch(Character::isISOControl)
                && !rule.startsWith("/") && !rule.contains("\\") && !rule.contains("..");
    }
    static boolean ignoredConfig(Set<String> rules, String name) {
        for (String rule : rules) {
            if (rule.equals(name)) return true;
            if (rule.endsWith("/") && name.startsWith(rule)) return true;
            if (rule.endsWith("*") && name.startsWith(rule.substring(0, rule.length() - 1))) return true;
        }
        return false;
    }

    /** Stop warning about these config files (paths relative to config/) on this computer. */
    public static void ignoreConfigs(Path game, java.util.Collection<String> names) throws IOException {
        if (names == null || names.isEmpty()) return;
        for (String name : names) if (!validConfigRule(name)) throw new IllegalArgumentException("Invalid config name");
        appendLines(configPolicy(game), names, "# Config files HostHop does not compare (path under config/, a folder ending in /, or a prefix ending in *)");
    }

    private static Set<String> readLines(Path file, java.util.function.Predicate<String> valid) throws IOException {
        Set<String> values = new TreeSet<>();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return values;
        if (Files.isSymbolicLink(file) || Files.size(file) > 65536) throw new IOException("Invalid compatibility policy");
        for (String line : Files.readAllLines(file)) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (!valid.test(value)) throw new IOException("Invalid compatibility policy entry");
            values.add(value);
        }
        return values;
    }

    private static void appendLines(Path policy, java.util.Collection<String> values, String header) throws IOException {
        Files.createDirectories(policy.getParent());
        if (Files.isSymbolicLink(policy)) throw new IOException("Invalid compatibility policy");
        var lines = new ArrayList<String>();
        var present = new TreeSet<String>();
        if (Files.exists(policy, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(policy) > 65536) throw new IOException("Invalid compatibility policy");
            for (String line : Files.readAllLines(policy)) { lines.add(line); present.add(line.strip()); }
        } else lines.add(header);
        for (String value : new TreeSet<>(values)) if (present.add(value)) lines.add(value);
        Path temporary = Files.createTempFile(policy.getParent(), ".compatibility-ignore-", ".tmp");
        try {
            Files.write(temporary, lines);
            Files.move(temporary, policy, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * Configs that only affect this player's own client: NeoForge CLIENT configs, plus everything belonging
     * to a mod the player chose to ignore. Configs a mod writes in its own format cannot be identified.
     */
    private static Set<String> automaticConfigRules(Path config, Set<String> ignoredMods) {
        Set<String> rules = new TreeSet<>();
        try {
            var known = new ArrayList<>(net.neoforged.fml.config.ModConfigs.getConfigSet(net.neoforged.fml.config.ModConfig.Type.CLIENT));
            for (String id : ignoredMods) known.addAll(net.neoforged.fml.config.ModConfigs.getModConfigs(id));
            Path root = config.toAbsolutePath().normalize();
            for (var modConfig : known) {
                Path full = modConfig.getFullPath();
                if (full == null) continue;
                Path absolute = full.toAbsolutePath().normalize();
                if (absolute.startsWith(root)) rules.add(root.relativize(absolute).toString().replace('\\', '/'));
            }
        } catch (RuntimeException unavailable) {
            com.mojang.logging.LogUtils.getLogger().warn("HostHop could not list client configs ({})", unavailable.getClass().getSimpleName());
        }
        for (String id : ignoredMods) { rules.add(id + "/"); rules.add(id + "-*"); rules.add(id + ".*"); }
        rules.removeIf(rule -> !validConfigRule(rule));
        return rules;
    }

    /**
     * Stop warning about these mods on this computer. They are also left out of the manifest this
     * player publishes while hosting, so guests are not warned about them either.
     */
    public static void ignoreMods(Path game, java.util.Collection<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) if (id == null || !id.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid mod ID");
        appendLines(policy(game), ids, "# Mods whose differences HostHop does not warn about (one mod ID per line)");
    }

    public static CompatibilityManifest capture(Path game) throws IOException {
        Path config = game.resolve("config");
        Set<String> ignored = readLines(policy(game), id -> id.matches("[a-z0-9_]{1,64}"));
        Set<String> ignoredConfigs = readLines(configPolicy(game), CompatibilityManifest::validConfigRule);
        ignoredConfigs.addAll(automaticConfigRules(config, ignored));
        Map<String,String> mods = new TreeMap<>();
        for (var mod : ModList.get().getMods()) {
            String id = mod.getModId(), version = mod.getVersion().toString();
            if (!id.matches("[a-z0-9_]{1,64}") || version.length() > 128 || version.isBlank()) throw new IOException("Invalid mod metadata");
            Path file = mod.getOwningFile().getFile().getFilePath();
            String fileHash = file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? hash(file) : null;
            if (mods.put(id, entry(id, version, fileHash)) != null) throw new IOException("Duplicate mod ID");
        }
        Map<String,String> configs = new TreeMap<>();
        if (Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(config)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    Path relative = config.relativize(path);
                    if (relative.getNameCount() == 0 || relative.getName(0).toString().equalsIgnoreCase("hosthop")) continue;
                    String name = relative.toString().replace('\\', '/');
                    if (ignoredConfig(ignoredConfigs, name)) continue;
                    if (name.length() > 240 || name.chars().anyMatch(Character::isISOControl) || Files.size(path) > 8_388_608 || configs.size() >= 512)
                        throw new IOException("Config inventory exceeds limits");
                    configs.put(name, hash(path));
                }
            }
        }
        if (ignoredConfigs.size() > 1024) throw new IOException("Too many ignored configs");
        return new CompatibilityManifest(Map.copyOf(mods), Set.copyOf(ignored), Map.copyOf(configs), Set.copyOf(ignoredConfigs));
    }
    /**
     * HostHop run from the development workspace is loaded from directories, so it has no file hash and its version
     * carries a per-minute build stamp. Record a fixed value instead so two development clients still match each other,
     * while a development client and a packaged jar still differ.
     */
    static String entry(String id, String version, String fileHash) {
        if (fileHash != null) return version + "#" + fileHash;
        return id.equals(dev.hosthop.HostHop.MOD_ID) ? "development" : version;
    }
    private static String hash(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) if (count != 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public String json() {
        JsonObject result = new JsonObject(), m = new JsonObject(), c = new JsonObject();
        mods.forEach(m::addProperty); configs.forEach(c::addProperty);
        var ignored = new com.google.gson.JsonArray(); ignoredMods.stream().sorted().forEach(ignored::add);
        var skippedConfigs = new com.google.gson.JsonArray(); ignoredConfigs.stream().sorted().forEach(skippedConfigs::add);
        result.add("mods", m); result.add("ignored", ignored); result.add("configs", c); result.add("ignoredConfigs", skippedConfigs);
        return result.toString();
    }
    public static CompatibilityManifest parse(String json) throws IOException {
        if (json == null || json.length() > 100000) throw new IOException("Invalid compatibility manifest");
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            Map<String,String> mods = new TreeMap<>(), configs = new TreeMap<>(); Set<String> ignored = new TreeSet<>();
            var m = root.getAsJsonObject("mods"); var c = root.getAsJsonObject("configs"); var i = root.getAsJsonArray("ignored");
            if (m.size() > 1024 || c.size() > 512 || i.size() > 1024) throw new IOException("Manifest too large");
            for (var e : m.entrySet()) { String v=e.getValue().getAsString(); if (!e.getKey().matches("[a-z0-9_]{1,64}") || v.isBlank() || v.length()>193) throw new IOException("Invalid mod"); mods.put(e.getKey(),v); }
            for (var e : c.entrySet()) { String v=e.getValue().getAsString(), k=e.getKey(); if (k.length()>240 || k.isBlank() || k.chars().anyMatch(Character::isISOControl) || !v.matches("[0-9a-f]{64}")) throw new IOException("Invalid config"); configs.put(k,v); }
            for (var e : i) { String id=e.getAsString(); if (!id.matches("[a-z0-9_]{1,64}") || !ignored.add(id)) throw new IOException("Invalid ignore entry"); }
            // Absent in manifests from older versions.
            Set<String> skipped = new TreeSet<>();
            var ic = root.getAsJsonArray("ignoredConfigs");
            if (ic != null) {
                if (ic.size() > 1024) throw new IOException("Manifest too large");
                for (var e : ic) { String rule=e.getAsString(); if (!validConfigRule(rule) || !skipped.add(rule)) throw new IOException("Invalid ignored config"); }
            }
            return new CompatibilityManifest(Map.copyOf(mods),Set.copyOf(ignored),Map.copyOf(configs),Set.copyOf(skipped));
        } catch (RuntimeException invalid) { throw new IOException("Invalid compatibility manifest", invalid); }
    }
    public Differences compare(CompatibilityManifest other) {
        var ids = new TreeSet<>(mods.keySet()); ids.addAll(other.mods.keySet()); ids.removeAll(ignoredMods); ids.removeAll(other.ignoredMods);
        var modChanges = new ArrayList<ModChange>();
        for (String id : ids) if (!java.util.Objects.equals(mods.get(id), other.mods.get(id))) modChanges.add(new ModChange(id, mods.get(id), other.mods.get(id)));
        var configChanges = new ArrayList<String>();
        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            if (ignoredConfig(ignoredConfigs, name) || ignoredConfig(other.ignoredConfigs, name)) continue;
            if (!java.util.Objects.equals(entry.getValue(), other.configs.get(name))) configChanges.add(name);
        }
        return new Differences(java.util.List.copyOf(modChanges), java.util.List.copyOf(configChanges));
    }
}
