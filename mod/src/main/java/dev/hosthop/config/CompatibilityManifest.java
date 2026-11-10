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
public record CompatibilityManifest(Map<String,String> mods, Set<String> ignoredMods, Map<String,String> configs) {
    public record Differences(java.util.List<String> mods, java.util.List<String> configs) {}
    public static CompatibilityManifest capture(Path game) throws IOException {
        Path config = game.resolve("config"), policy = config.resolve("hosthop").resolve("compatibility-ignore.txt");
        Set<String> ignored = new TreeSet<>();
        if (Files.exists(policy, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(policy) || Files.size(policy) > 65536) throw new IOException("Invalid compatibility policy");
            for (String line : Files.readAllLines(policy)) {
                String id = line.strip();
                if (id.isEmpty() || id.startsWith("#")) continue;
                if (!id.matches("[a-z0-9_]{1,64}")) throw new IOException("Invalid ignored mod ID");
                ignored.add(id);
            }
        }
        Map<String,String> mods = new TreeMap<>();
        for (var mod : ModList.get().getMods()) {
            String id = mod.getModId(), version = mod.getVersion().toString();
            if (!id.matches("[a-z0-9_]{1,64}") || version.length() > 128 || version.isBlank()) throw new IOException("Invalid mod metadata");
            Path file = mod.getOwningFile().getFile().getFilePath();
            if (file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) version += "#" + hash(file);
            if (mods.put(id, version) != null) throw new IOException("Duplicate mod ID");
        }
        Map<String,String> configs = new TreeMap<>();
        if (Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(config)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    Path relative = config.relativize(path);
                    if (relative.getNameCount() == 0 || relative.getName(0).toString().equalsIgnoreCase("hosthop")) continue;
                    String name = relative.toString().replace('\\', '/');
                    if (name.length() > 240 || name.chars().anyMatch(Character::isISOControl) || Files.size(path) > 8_388_608 || configs.size() >= 512)
                        throw new IOException("Config inventory exceeds limits");
                    configs.put(name, hash(path));
                }
            }
        }
        return new CompatibilityManifest(Map.copyOf(mods), Set.copyOf(ignored), Map.copyOf(configs));
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
        result.add("mods", m); result.add("ignored", ignored); result.add("configs", c);
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
            return new CompatibilityManifest(Map.copyOf(mods),Set.copyOf(ignored),Map.copyOf(configs));
        } catch (RuntimeException invalid) { throw new IOException("Invalid compatibility manifest", invalid); }
    }
    public Differences compare(CompatibilityManifest other) {
        var ids = new TreeSet<>(mods.keySet()); ids.addAll(other.mods.keySet()); ids.removeAll(ignoredMods); ids.removeAll(other.ignoredMods);
        var modChanges = new ArrayList<String>();
        for (String id : ids) if (!java.util.Objects.equals(mods.get(id), other.mods.get(id))) modChanges.add(id);
        var configChanges = new ArrayList<String>();
        for (var entry : configs.entrySet()) if (!java.util.Objects.equals(entry.getValue(), other.configs.get(entry.getKey()))) configChanges.add(entry.getKey());
        return new Differences(java.util.List.copyOf(modChanges), java.util.List.copyOf(configChanges));
    }
}
