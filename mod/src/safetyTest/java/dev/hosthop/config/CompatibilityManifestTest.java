package dev.hosthop.config;

import java.util.Map;
import java.util.Set;

public final class CompatibilityManifestTest {
    public static void main(String[] args) throws Exception {
        var host = new CompatibilityManifest(Map.of("a", "1", "client", "2"), Set.of("client"), Map.of("mod.toml", "a".repeat(64)));
        var guest = new CompatibilityManifest(Map.of("a", "1"), Set.of(), Map.of("mod.toml", "b".repeat(64)));
        var roundtrip = CompatibilityManifest.parse(host.json());
        var difference = roundtrip.compare(guest);
        if (!difference.mods().isEmpty() || !difference.configs().equals(java.util.List.of("mod.toml"))) throw new AssertionError("comparison failed");
        var wrong = new CompatibilityManifest(Map.of("a", "2"), Set.of(), Map.of());
        if (roundtrip.compare(wrong).mods().size() != 1 || roundtrip.compare(wrong).configs().size() != 1) throw new AssertionError("mismatch missed");
        try { CompatibilityManifest.parse("{\"mods\":{\"../evil\":\"1\"},\"ignored\":[],\"configs\":{}}"); throw new AssertionError("invalid mod accepted"); }
        catch (java.io.IOException expected) { }
        String devHost = CompatibilityManifest.entry("hosthop", "0.2.0-dev.202611111530", null);
        String devGuest = CompatibilityManifest.entry("hosthop", "0.2.0-dev.202611111531", null);
        String packaged = CompatibilityManifest.entry("hosthop", "0.2.0-dev.202611111530", "c".repeat(64));
        if (!devHost.equals(devGuest)) throw new AssertionError("development builds from different minutes must match");
        if (devHost.equals(packaged)) throw new AssertionError("development build must differ from a packaged jar");
        if (!CompatibilityManifest.entry("other", "1.0", null).equals("1.0")) throw new AssertionError("other directory mods must keep their version");
        var devManifest = new CompatibilityManifest(Map.of("hosthop", devHost), Set.of(), Map.of());
        if (!CompatibilityManifest.parse(devManifest.json()).compare(new CompatibilityManifest(Map.of("hosthop", devGuest), Set.of(), Map.of())).mods().isEmpty())
            throw new AssertionError("development manifests must compare equal");
        // Mod differences carry both versions for the warning screen.
        var hostMods = new CompatibilityManifest(Map.of("map", "2.0#" + "a".repeat(64), "lib", "1.0#" + "b".repeat(64)), Set.of(), Map.of());
        var guestMods = new CompatibilityManifest(Map.of("lib", "1.0#" + "c".repeat(64), "shader", "3.1"), Set.of(), Map.of());
        var changes = hostMods.compare(guestMods).modChanges();
        if (changes.size() != 3) throw new AssertionError("mod changes missed");
        var lib = changes.stream().filter(c -> c.id().equals("lib")).findFirst().orElseThrow();
        if (!lib.sameVersionDifferentFile() || !CompatibilityManifest.ModChange.version(lib.hostVersion()).equals("1.0")) throw new AssertionError("version detail wrong");
        var map = changes.stream().filter(c -> c.id().equals("map")).findFirst().orElseThrow();
        if (map.localVersion() != null || map.sameVersionDifferentFile()) throw new AssertionError("missing mod detail wrong");

        // Config rules: exact path, folder and prefix, honoured whichever side has them, and kept in the JSON.
        var hostConfigs = new CompatibilityManifest(Map.of(), Set.of(), Map.of("world.toml", "1".repeat(64), "minimap/settings.json", "2".repeat(64),
                "shader-options.txt", "3".repeat(64), "client-only.toml", "4".repeat(64)), Set.of("client-only.toml"));
        var guestConfigs = new CompatibilityManifest(Map.of(), Set.of(), Map.of("world.toml", "9".repeat(64)), Set.of("minimap/", "shader-*"));
        var configDiff = CompatibilityManifest.parse(hostConfigs.json()).compare(CompatibilityManifest.parse(guestConfigs.json()));
        if (!configDiff.configs().equals(java.util.List.of("world.toml"))) throw new AssertionError("config rules not applied: " + configDiff.configs());
        // A manifest from an older version has no ignoredConfigs field.
        if (!CompatibilityManifest.parse("{\"mods\":{},\"ignored\":[],\"configs\":{}}").ignoredConfigs().isEmpty()) throw new AssertionError("old manifest rejected");
        try { CompatibilityManifest.parse("{\"mods\":{},\"ignored\":[],\"configs\":{},\"ignoredConfigs\":[\"../x\"]}"); throw new AssertionError("unsafe rule accepted"); }
        catch (java.io.IOException expected) { }

        // Ticked items are appended once to this PC's ignore lists.
        var game = java.nio.file.Files.createTempDirectory("hosthop-compat-test-");
        CompatibilityManifest.ignoreMods(game, java.util.List.of("map", "shader"));
        CompatibilityManifest.ignoreMods(game, java.util.List.of("map"));
        CompatibilityManifest.ignoreConfigs(game, java.util.List.of("minimap/settings.json"));
        var modLines = java.nio.file.Files.readAllLines(game.resolve("config/hosthop/compatibility-ignore.txt"));
        if (modLines.stream().filter(line -> line.equals("map")).count() != 1 || !modLines.contains("shader")) throw new AssertionError("mod ignore list wrong");
        if (!java.nio.file.Files.readAllLines(game.resolve("config/hosthop/compatibility-ignore-configs.txt")).contains("minimap/settings.json"))
            throw new AssertionError("config ignore list wrong");
        try { CompatibilityManifest.ignoreMods(game, java.util.List.of("Bad ID")); throw new AssertionError("invalid mod ID accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("Compatibility manifest checks passed");
    }
}
