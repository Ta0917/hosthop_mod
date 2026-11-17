package dev.hosthop.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Adds the mods HostHop accepted from the world's publisher. It sits in the mods folder as an early service, because
 * only those run before mods are discovered. HostHop itself verifies the publisher's signature and asks the player
 * before writing the list; this class only loads exactly the files listed, each checked against its SHA-256 again.
 * It depends on nothing but the loader, so the same file keeps working across HostHop updates.
 */
public final class DistributedModLocator implements IModFileCandidateLocator {
    static final String HEADER = "hosthop-active 1";
    private static final long MAX_JAR = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;

    @Override public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        Path root = context.gameDirectory().resolve("hosthop-mods");
        for (Path jar : verified(root, context.gameDirectory().resolve("mods"))) pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
    }

    /**
     * The listed JARs whose content still matches; anything unexpected is skipped and logged, never loaded. A mod
     * that is also installed by hand in the mods folder is skipped too: two files with one mod ID stop the game.
     */
    public static List<Path> verified(Path root, Path modsFolder) {
        var manual = manualModIds(modsFolder);
        Path list = root.resolve("active.txt");
        if (!Files.isRegularFile(list, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> lines;
        try {
            if (Files.size(list) > 16384) throw new IOException("list too large");
            lines = Files.readAllLines(list, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            ILaunchContext.LOGGER.warn("HostHop distributed mod list unreadable; loading none ({})", failure.getMessage());
            return List.of();
        }
        if (lines.isEmpty() || !HEADER.equals(lines.get(0)) || lines.size() - 1 > MAX_ENTRIES) {
            ILaunchContext.LOGGER.warn("HostHop distributed mod list has an unknown format; loading none");
            return List.of();
        }
        var jars = new java.util.ArrayList<Path>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            if (!line.matches("[0-9a-f]{64}")) {
                ILaunchContext.LOGGER.warn("HostHop distributed mod list has an invalid entry; loading none");
                return List.of();
            }
            Path jar = root.resolve("jars").resolve(line + ".jar");
            try {
                if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.size(jar) > MAX_JAR || !line.equals(sha256(jar))) {
                    ILaunchContext.LOGGER.warn("HostHop distributed mod {} is missing or changed; skipped", line.substring(0, 12));
                    continue;
                }
            } catch (IOException failure) {
                ILaunchContext.LOGGER.warn("HostHop distributed mod {} unreadable; skipped", line.substring(0, 12));
                continue;
            }
            var overlap = new java.util.TreeSet<>(modIds(jar));
            overlap.retainAll(manual);
            if (!overlap.isEmpty()) {
                ILaunchContext.LOGGER.warn("HostHop distributed mod {} skipped: {} is also in the mods folder", line.substring(0, 12), overlap);
                continue;
            }
            jars.add(jar);
        }
        ILaunchContext.LOGGER.info("HostHop adding {} distributed mod(s)", jars.size());
        return jars;
    }

    /** Mod IDs of the JARs placed in the mods folder by hand. */
    public static java.util.Set<String> manualModIds(Path modsFolder) {
        var ids = new java.util.TreeSet<String>();
        if (modsFolder == null || !Files.isDirectory(modsFolder)) return ids;
        try (var stream = Files.newDirectoryStream(modsFolder, "*.jar")) {
            for (Path jar : stream) if (!jar.getFileName().toString().equals("hosthop-mod-loader.jar")) ids.addAll(modIds(jar));
        } catch (IOException | RuntimeException failure) {
            ILaunchContext.LOGGER.warn("HostHop could not list the mods folder ({})", failure.getMessage());
        }
        return ids;
    }

    /** The IDs declared in a JAR's [[mods]] sections; dependency sections are ignored. Unreadable JARs declare none. */
    public static java.util.Set<String> modIds(Path jar) {
        var ids = new java.util.TreeSet<String>();
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) return ids;
            String toml;
            try (var input = zip.getInputStream(entry)) { toml = new String(input.readNBytes(262144), StandardCharsets.UTF_8); }
            boolean inMods = false;
            for (String raw : toml.split("\\R")) {
                String line = raw.strip();
                if (line.startsWith("[")) { inMods = line.replace(" ", "").startsWith("[[mods]]"); continue; }
                var match = MOD_ID.matcher(line);
                if (inMods && match.matches()) ids.add(match.group(1));
            }
        } catch (IOException | RuntimeException unreadable) { /* not a mod JAR */ }
        return ids;
    }

    private static final java.util.regex.Pattern MOD_ID = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\".*");

    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    @Override public String toString() { return "hosthop distributed mods"; }
}
