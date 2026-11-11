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
        System.out.println("Compatibility manifest checks passed");
    }
}
