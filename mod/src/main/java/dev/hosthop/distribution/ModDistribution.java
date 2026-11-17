package dev.hosthop.distribution;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Fetching and publishing the distributed mods. Blocking; run off the game thread. */
public final class ModDistribution {
    private ModDistribution() {}

    public sealed interface Outcome {}
    /** No publisher key is registered on this PC, so distribution is off. */
    public record NotConfigured() implements Outcome {}
    public record NothingPublished() implements Outcome {}
    public record UpToDate(List<ModManifest.Entry> mods) implements Outcome {}
    /** Installed and ready, but this game started with a different set. */
    public record RestartRequired(List<ModManifest.Entry> mods) implements Outcome {}
    /** Installed, but the same mods are also in the mods folder by hand, so the distributed copies are not loaded. */
    public record Duplicates(List<String> files) implements Outcome {}
    /** The list contains mods this player has not agreed to run; nothing was downloaded. */
    public record NeedsConsent(ModManifest manifest, List<ModManifest.Entry> pending, String fingerprint) implements Outcome {}

    /**
     * Brings this PC to the publisher's newest list. Mods the player accepted before update without asking; a new
     * mod always needs consent first. openBrowser is used only when Drive access to the bundle is still missing.
     */
    public static Outcome sync(LocalDistribution local, Invitation invitation, LeaseAuthorityClient authority,
                               DriveCredentialManager credentials, Consumer<URI> openBrowser, Path modsDirectory) throws IOException, InterruptedException {
        var key = local.trustedKey();
        if (key.isEmpty()) return new NotConfigured();
        var signed = authority.distributedMods(invitation.world());
        if (signed == null) return new NothingPublished();
        var plan = local.plan(invitation.world(), signed.manifest(), signed.signature());
        var manifest = plan.manifest();
        if (!plan.needConsent().isEmpty()) return new NeedsConsent(manifest, plan.needConsent(), PublisherKey.fingerprint(key.get()));
        if (plan.changed()) {
            if (manifest.bundle() == null) local.install(manifest, null);
            else {
                var store = new GoogleDriveSnapshotStore();
                String token = credentials.access().accessToken();
                if (!store.canRead(manifest.bundle().fileId(), token)) {
                    credentials.authorizeFile(manifest.bundle().fileId(), invitation.folder(), openBrowser);
                    token = credentials.access().accessToken();
                }
                Path staging = Files.createDirectories(local.root().resolve("staging"));
                Path bundle = staging.resolve("bundle-" + System.nanoTime() + ".zip");
                try {
                    var bundleRef = manifest.bundle();
                    // downloadRaw checks size and SHA-256 against the signed list while receiving.
                    store.downloadRaw(new GoogleDriveSnapshotStore.ObjectRef(bundleRef.fileId(), bundleRef.sha256(), bundleRef.size()), bundle, token);
                    local.install(manifest, bundle);
                } finally { Files.deleteIfExists(bundle); }
            }
        } else local.markAccepted(manifest);
        var duplicates = LocalDistribution.duplicatesInModsFolder(modsDirectory, manifest);
        if (!duplicates.isEmpty()) return new Duplicates(duplicates);
        return local.restartRequired() ? new RestartRequired(manifest.mods()) : new UpToDate(manifest.mods());
    }

    /** The JARs in the publish folder, described from their own metadata. */
    public static List<ModManifest.Entry> describeFolder(Path folder, Map<String, Path> filesByHash) throws IOException {
        var mods = new ArrayList<ModManifest.Entry>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return mods;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.jar")) {
            for (Path jar : stream) {
                var mod = LocalDistribution.describe(jar);
                mods.add(mod);
                filesByHash.put(mod.sha256(), jar);
                if (mods.size() > ModManifest.MAX_MODS) throw new IOException("Too many mods to distribute");
            }
        }
        mods.sort(java.util.Comparator.comparing(ModManifest.Entry::id));
        return mods;
    }

    /**
     * Publishes the publish folder as the world's new list: one bundle on Drive (overwriting the previous one so
     * members need no new browser consent) and the signed list on the service. An empty folder withdraws all mods.
     */
    public static ModManifest publish(LocalDistribution local, Invitation invitation, LeaseAuthorityClient authority,
                                      DriveCredentialManager credentials, PrivateKey privateKey, PublicKey publicKey) throws IOException, InterruptedException {
        var files = new HashMap<String, Path>();
        var mods = describeFolder(local.publishFolder(), files);
        var current = authority.distributedMods(invitation.world());
        long revision = Math.max(local.state().revision(), current == null ? 0 : current.revision()) + 1;
        String previousBundle = null;
        if (current != null && PublisherKey.verify(publicKey, current.manifest(), current.signature())) {
            var previous = ModManifest.parse(current.manifest());
            if (previous.bundle() != null) previousBundle = previous.bundle().fileId();
        }
        ModManifest.Bundle bundle = null;
        if (!mods.isEmpty()) {
            Path staging = Files.createDirectories(local.root().resolve("staging"));
            Path archive = staging.resolve("publish-" + System.nanoTime() + ".zip");
            try {
                LocalDistribution.writeBundle(mods, files, archive);
                long size = Files.size(archive);
                if (size > ModManifest.MAX_BUNDLE) throw new IOException("Mods are too large to distribute");
                String digest = LocalDistribution.sha256(archive);
                var store = new GoogleDriveSnapshotStore();
                String token = credentials.access().accessToken();
                GoogleDriveSnapshotStore.ObjectRef uploaded;
                if (previousBundle == null) uploaded = store.uploadOpaque(archive, null, invitation.folder(), token);
                else {
                    try { uploaded = store.uploadOpaque(archive, previousBundle, invitation.folder(), token); }
                    catch (GoogleDriveSnapshotStore.SlotUnavailable gone) { uploaded = store.uploadOpaque(archive, null, invitation.folder(), token); }
                }
                if (!digest.equals(uploaded.sha256()) || uploaded.size() != size) throw new IOException("Uploaded bundle does not match");
                bundle = new ModManifest.Bundle(uploaded.fileId(), digest, size);
            } finally { Files.deleteIfExists(archive); }
        }
        var manifest = new ModManifest(invitation.world(), revision, invitation.principal(), bundle, mods);
        manifest.validate();
        String text = manifest.json();
        authority.publishMods(invitation.world(), revision, text, PublisherKey.sign(privateKey, text));
        local.consent(mods.stream().map(ModManifest.Entry::id).toList());
        local.installFromFiles(manifest, files);
        return manifest;
    }
}
