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
        System.out.println("Compatibility manifest checks passed");
    }
}
