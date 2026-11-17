package dev.hosthop.distribution;

import dev.hosthop.loader.DistributedModLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ModDistributionTest {
    public static void main(String[] args) throws Exception {
        // Keys: round trip, signatures bind the exact text.
        var pair = PublisherKey.generate();
        String code = PublisherKey.encodePublic(pair.getPublic());
        var decoded = PublisherKey.decodePublic(" " + code + "\n");
        String signature = PublisherKey.sign(PublisherKey.decodePrivate(PublisherKey.encodePrivate(pair.getPrivate())), "text");
        check(PublisherKey.verify(decoded, "text", signature), "signature rejected");
        check(!PublisherKey.verify(decoded, "text!", signature), "altered text accepted");
        check(!PublisherKey.verify(PublisherKey.generate().getPublic(), "text", signature), "other key accepted");
        fails(() -> PublisherKey.decodePublic("hosthop-key1:AAAA"), "short key accepted");
        fails(() -> PublisherKey.decodePublic("hosthop1:" + code.substring(13)), "invitation prefix accepted");

        // Publisher side: describe JARs and bundle them.
        var source = Files.createTempDirectory("hosthop-publish-test-");
        Path alpha = modJar(source.resolve("alpha-1.0.jar"), "alpha", "1.0", "a");
        Path beta = modJar(source.resolve("beta.jar"), "beta", "${file.jarVersion}", "b");
        Files.writeString(source.resolve("notes.txt"), "ignored");
        var files = new HashMap<String, Path>();
        var mods = ModDistribution.describeFolder(source, files);
        check(mods.size() == 2 && mods.get(0).id().equals("alpha") && mods.get(0).version().equals("1.0")
                && mods.get(1).version().equals("2.5"), "mod metadata not read: " + mods);
        Path plain = source.resolve("plain.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(plain))) { out.putNextEntry(new ZipEntry("x.class")); out.write(1); }
        fails(() -> LocalDistribution.describe(plain), "non-mod JAR accepted");
        Files.delete(plain);
        var bundleDir = Files.createTempDirectory("hosthop-bundle-test-");
        Path bundle = bundleDir.resolve("bundle.zip");
        LocalDistribution.writeBundle(mods, files, bundle);
        var bundleRef = new ModManifest.Bundle("fileId", LocalDistribution.sha256(bundle), Files.size(bundle));
        var manifest = new ModManifest("world", 2, "owner", bundleRef, mods);
        check(ModManifest.parse(manifest.json()).equals(manifest), "manifest round trip failed");
        fails(() -> new ModManifest("world", 2, "owner", bundleRef, List.of(mods.get(0), mods.get(0))).validate(), "duplicate mod accepted");
        fails(() -> new ModManifest("world", 2, "owner", bundleRef, List.of(new ModManifest.Entry("alpha", "1", "../x.jar", mods.get(0).sha256(), 1))).validate(), "path in file name accepted");
        fails(() -> new ModManifest("world", 2, "owner", null, mods).validate(), "list without bundle accepted");
        String text = manifest.json();
        String signed = PublisherKey.sign(pair.getPrivate(), text);

        // Member side: nothing is trusted before a key is registered, and a key cannot be silently replaced.
        var game = Files.createTempDirectory("hosthop-member-test-");
        var local = new LocalDistribution(game);
        fails(() -> local.plan("world", text, signed), "list accepted without a registered key");
        local.trust(code);
        local.trust(code);
        fails(() -> local.trust(PublisherKey.encodePublic(PublisherKey.generate().getPublic())), "publisher key replaced");
        fails(() -> local.plan("world", text, PublisherKey.sign(PublisherKey.generate().getPrivate(), text)), "foreign signature accepted");
        fails(() -> local.plan("world", text.replace("1.0", "6.6"), signed), "edited list accepted");
        fails(() -> local.plan("other", text, signed), "list for another world accepted");

        // New mods need consent before anything is installed.
        var plan = local.plan("world", text, signed);
        check(plan.needConsent().size() == 2 && plan.changed(), "consent not requested");
        fails(() -> local.install(manifest, bundle), "installed without consent");
        local.consent(List.of("alpha"));
        check(local.plan("world", text, signed).needConsent().size() == 1, "partial consent wrong");
        local.consent(List.of("beta"));
        check(local.restartRequired() == false, "restart required with nothing installed");

        // A bundle that differs from the signed list is refused and nothing is activated.
        Path extra = bundleDir.resolve("extra.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(extra))) {
            out.putNextEntry(new ZipEntry("alpha-1.0.jar")); out.write(Files.readAllBytes(alpha));
            out.putNextEntry(new ZipEntry("evil.jar")); out.write(1);
        }
        fails(() -> local.install(manifest, extra), "extra file installed");
        Path swapped = bundleDir.resolve("swapped.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(swapped))) {
            out.putNextEntry(new ZipEntry("alpha-1.0.jar")); out.write(Files.readAllBytes(beta));
            out.putNextEntry(new ZipEntry("beta.jar")); out.write(Files.readAllBytes(beta));
        }
        fails(() -> local.install(manifest, swapped), "swapped content installed");
        check(local.active().isEmpty(), "failed install activated mods");

        local.recordStartup(game.resolve("mods"));
        local.install(manifest, bundle);
        check(local.active().size() == 2 && !local.plan("world", text, signed).changed(), "install not active");
        check(local.restartRequired(), "restart not requested after install");
        var loaded = DistributedModLocator.verified(local.root(), game.resolve("mods"));
        check(loaded.size() == 2, "loader did not accept installed mods");

        // A hand-installed copy in the mods folder wins; the distributed one is skipped and reported.
        var modsFolder = Files.createDirectories(game.resolve("mods"));
        Files.copy(alpha, modsFolder.resolve("alpha-old.jar"));
        modJar(modsFolder.resolve("unrelated.jar"), "gamma", "1", "c");
        check(DistributedModLocator.verified(local.root(), modsFolder).size() == 1, "loader loaded a duplicate mod");
        check(LocalDistribution.duplicatesInModsFolder(modsFolder, manifest).equals(List.of("alpha-old.jar")), "duplicate not reported");
        check(DistributedModLocator.modIds(beta).equals(java.util.Set.of("beta")), "dependency IDs counted as mods");
        Files.delete(modsFolder.resolve("alpha-old.jar"));

        // The loader refuses a JAR that changed on disk after installation.
        Files.write(loaded.get(0), new byte[] {1, 2, 3});
        check(DistributedModLocator.verified(local.root(), modsFolder).size() == 1, "loader accepted a changed JAR");

        // An older revision is refused once a newer one was accepted; consented mods update without asking.
        var older = new ModManifest("world", 1, "owner", bundleRef, mods);
        fails(() -> local.plan("world", older.json(), PublisherKey.sign(pair.getPrivate(), older.json())), "rollback accepted");
        var withdrawn = new ModManifest("world", 3, "owner", null, List.of());
        var withdrawnPlan = local.plan("world", withdrawn.json(), PublisherKey.sign(pair.getPrivate(), withdrawn.json()));
        check(withdrawnPlan.needConsent().isEmpty() && withdrawnPlan.changed(), "withdrawal not planned");
        local.install(withdrawn, null);
        check(local.active().isEmpty() && DistributedModLocator.verified(local.root(), game.resolve("mods")).isEmpty(), "withdrawal left mods active");

        // The publisher installs from the files just published.
        var publisher = new LocalDistribution(Files.createTempDirectory("hosthop-owner-test-"));
        publisher.trust(code);
        publisher.consent(List.of("alpha", "beta"));
        publisher.installFromFiles(manifest, files);
        check(publisher.active().size() == 2, "publisher install failed");
        System.out.println("Mod distribution checks passed");
    }

    private static Path modJar(Path target, String id, String version, String content) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "2.5");
        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            out.write(("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version
                    + "\"\n[[dependencies." + id + "]]\nmodId=\"neoforge\"\nversion=\"9.9\"\n").getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry(id + "/Data.class"));
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return target;
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Action action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
