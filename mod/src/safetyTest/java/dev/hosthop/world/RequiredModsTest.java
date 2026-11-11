package dev.hosthop.world;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RequiredModsTest {
    public static void main(String[] args) throws Exception {
        var world = Files.createTempDirectory("hosthop-required-test-");
        var config = Files.createTempDirectory("hosthop-required-config-");

        if (RequiredMods.read(world) != null) throw new AssertionError("absent record must read as null");
        var recorded = Map.of("create", "6.0.4", "jei", "19.1.0", "biomesoplenty", "21.0.0");
        RequiredMods.write(world, recorded);
        if (!RequiredMods.read(world).equals(recorded)) throw new AssertionError("record round trip failed");

        // Same or newer versions pass; a missing mod or an older version is refused.
        var installed = Map.of("create", "6.1.0", "jei", "19.1.0", "biomesoplenty", "21.0.0", "xaerominimap", "25.0");
        if (!RequiredMods.check(recorded, id -> Optional.ofNullable(installed.get(id)), Set.of()).isEmpty())
            throw new AssertionError("equal or newer versions must pass");
        var lacking = Map.of("create", "6.0.3", "jei", "19.1.0");
        var problems = RequiredMods.check(recorded, id -> Optional.ofNullable(lacking.get(id)), Set.of());
        if (problems.size() != 2) throw new AssertionError("missing and older mods must be reported: " + problems);
        var absent = problems.stream().filter(p -> p.id().equals("biomesoplenty")).findFirst().orElseThrow();
        var older = problems.stream().filter(p -> p.id().equals("create")).findFirst().orElseThrow();
        if (absent.installed() != null || !older.installed().equals("6.0.3") || !older.required().equals("6.0.4"))
            throw new AssertionError("problem details wrong");

        // A deliberate removal listed in config/hosthop/allow-removed-mods.txt is accepted; an older version still is not.
        Files.writeString(config.resolve("allow-removed-mods.txt"), "# removed on purpose\nbiomesoplenty\ncreate\n");
        var allowed = RequiredMods.allowedRemovals(config);
        var remaining = RequiredMods.check(recorded, id -> Optional.ofNullable(lacking.get(id)), allowed);
        if (remaining.size() != 1 || !remaining.get(0).id().equals("create")) throw new AssertionError("removal list applied wrongly: " + remaining);

        Files.writeString(world.resolve(RequiredMods.FILE), "{\"format\":1,\"mods\":{\"../x\":\"1\"}}");
        try { RequiredMods.read(world); throw new AssertionError("invalid record accepted"); }
        catch (java.io.IOException expected) { }
        System.out.println("Required mod checks passed");
    }
}
