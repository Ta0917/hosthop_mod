package dev.hosthop.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/**
 * The mods a shared world cannot be opened without, decided from the world itself rather than by the player.
 *
 * While hosting, every mod that owns an entry in any of the server's registries (blocks, items, entities, biomes,
 * structures, enchantments and so on) is recorded with its version in a file inside the world. Client-only mods
 * register nothing there, so they never appear. Before the next player opens the world as host, the record is
 * checked: a missing mod, or an older version, stops hosting outright, because Minecraft would otherwise drop that
 * mod's blocks, items and entities on load and the loss would be published. The player's ignore lists do not apply.
 */
public final class RequiredMods {
    public static final String FILE = "hosthop-required-mods.json";
    private static final Set<String> NEVER_REQUIRED = Set.of("minecraft", dev.hosthop.HostHop.MOD_ID);

    /** installed is null when the mod is not present at all. */
    public record Problem(String id, String required, String installed) {}

    public static final class Missing extends IOException {
        private final List<Problem> problems;
        public Missing(List<Problem> problems) { super("Mods required by the shared world are missing or older"); this.problems = List.copyOf(problems); }
        public List<Problem> problems() { return problems; }
    }

    private RequiredMods() {}

    /** Mods owning content in the given registries, with their installed versions. */
    public static Map<String, String> collect(net.minecraft.core.RegistryAccess access, Function<String, Optional<String>> installedVersion) {
        Set<String> namespaces = new TreeSet<>();
        access.registries().forEach(entry -> {
            namespaces.add(entry.key().identifier().getNamespace());
            for (var key : entry.value().keySet()) namespaces.add(key.getNamespace());
        });
        Map<String, String> required = new TreeMap<>();
        for (String namespace : namespaces) {
            if (NEVER_REQUIRED.contains(namespace)) continue;
            // A namespace that is not a loaded mod comes from a datapack stored in the world itself.
            installedVersion.apply(namespace).ifPresent(version -> required.put(namespace, version));
        }
        return required;
    }

    public static Map<String, String> collect(net.minecraft.core.RegistryAccess access) {
        return collect(access, RequiredMods::installedVersion);
    }

    public static Optional<String> installedVersion(String id) {
        return net.neoforged.fml.ModList.get().getModContainerById(id).map(container -> container.getModInfo().getVersion().toString());
    }

    public static void write(Path world, Map<String, String> required) throws IOException {
        var mods = new JsonObject();
        new TreeMap<>(required).forEach(mods::addProperty);
        var root = new JsonObject();
        root.addProperty("format", 1);
        root.add("mods", mods);
        Path target = world.resolve(FILE);
        if (Files.isSymbolicLink(target)) throw new IOException("Invalid required mod record");
        Path temporary = Files.createTempFile(world, ".hosthop-required-", ".tmp");
        try {
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Null when the world was last hosted by a HostHop version that did not record it. */
    public static Map<String, String> read(Path world) throws IOException {
        Path file = world.resolve(FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(file) || Files.size(file) > 262_144) throw new IOException("Invalid required mod record");
        try {
            var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format").getAsInt() != 1) throw new IOException("Unknown required mod record format");
            Map<String, String> required = new TreeMap<>();
            for (var entry : root.getAsJsonObject("mods").entrySet()) {
                String id = entry.getKey(), version = entry.getValue().getAsString();
                if (!id.matches("[a-z0-9_.-]{1,64}") || version.isBlank() || version.length() > 128) throw new IOException("Invalid required mod record");
                required.put(id, version);
            }
            return required;
        } catch (RuntimeException invalid) { throw new IOException("Invalid required mod record", invalid); }
    }

    /**
     * Mods whose removal the group decided on deliberately; one ID per line in config/hosthop/allow-removed-mods.txt.
     * Their blocks, items and entities are lost when the world is opened without them.
     */
    public static Set<String> allowedRemovals(Path configDirectory) throws IOException {
        Path file = configDirectory.resolve("allow-removed-mods.txt");
        Set<String> ids = new TreeSet<>();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return ids;
        if (Files.isSymbolicLink(file) || Files.size(file) > 65536) throw new IOException("Invalid removal list");
        for (String line : Files.readAllLines(file)) {
            String id = line.strip();
            if (!id.isEmpty() && !id.startsWith("#")) ids.add(id);
        }
        return ids;
    }

    /** Missing mods and older versions; a newer version is accepted so the group can update. */
    public static List<Problem> check(Map<String, String> required, Function<String, Optional<String>> installedVersion, Set<String> allowedRemovals) {
        var problems = new ArrayList<Problem>();
        new TreeMap<>(required).forEach((id, version) -> {
            var installed = installedVersion.apply(id);
            if (installed.isEmpty()) {
                if (!allowedRemovals.contains(id)) problems.add(new Problem(id, version, null));
            } else if (new DefaultArtifactVersion(installed.get()).compareTo(new DefaultArtifactVersion(version)) < 0)
                problems.add(new Problem(id, version, installed.get()));
        });
        return problems;
    }
}
