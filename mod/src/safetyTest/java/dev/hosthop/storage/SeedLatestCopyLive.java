package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.lease.PublicationRecord;
import dev.hosthop.snapshot.SnapshotVerifier;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit one-time import of a verified copy into a dedicated, empty test world. */
public final class SeedLatestCopyLive {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected invitation, credential, archive, and new test directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])).trim());
        if (!"latest_copy_test".equals(invite.world())) throw new IOException("Unexpected target world");
        Path archive = Path.of(args[2]);
        var verified = SnapshotVerifier.verify(archive);
        if (verified.files() < 1) throw new IOException("Empty snapshot");
        Path directory = Files.createDirectory(Path.of(args[3]));
        var vault = new WindowsSecretStore(Path.of(args[1]));
        var auth = new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret());
        var credentials = new DriveCredentialManager(vault, auth);
        String token = credentials.access().accessToken();
        var store = new GoogleDriveSnapshotStore();
        store.checkUploadCapacity(Files.size(archive), token);
        var client = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        String lease = UUID.randomUUID().toString();
        var attempt = new WindowsSecretStore(directory.resolve("seed-pending.dat"));
        attempt.save(Map.of("world", invite.world(), "owner", invite.principal(), "lease", lease, "stage", "acquiring"));
        var grant = client.acquire(invite.world(), lease, 90, 5);
        if (grant.generation() != 0) {
            client.abort(invite.world(), invite.principal(), lease, grant.epoch(), grant.generation());
            throw new IOException("Target is not empty; no snapshot was uploaded");
        }
        attempt.save(Map.of("world", invite.world(), "owner", invite.principal(), "lease", lease,
                "stage", "uploading", "epoch", Long.toString(grant.epoch())));
        var failure = new AtomicReference<Throwable>();
        var finishing = new java.util.concurrent.atomic.AtomicBoolean();
        var main = Thread.currentThread();
        var renewal = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "hosthop-seed-renewal"); t.setDaemon(true); return t; });
        renewal.scheduleWithFixedDelay(() -> {
            try {
                var next = client.renew(invite.world(), lease, 90, grant.epoch(), 5);
                if (next.generation() != 0) throw new IOException("Unexpected generation during import");
            } catch (Exception error) { if (!finishing.get()) { failure.compareAndSet(null, error); main.interrupt(); } }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            System.out.println("HOSTHOP_LATEST_SEED: uploading verified world copy");
            var object = store.upload(archive, invite.folder(), token);
            if (failure.get() != null) throw new IOException("Lease renewal failed", failure.get());
            var operation = new PublicationRecord(invite.world(), UUID.randomUUID().toString(), invite.principal(), lease,
                    grant.epoch(), 0, object.sha256(), object.fileId(), object.size(), 1);
            Path journal = directory.resolve("publication.dat");
            PendingPublicationJournal.create(journal, operation);
            var receipt = client.commit(operation, credentials.access().accessToken());
            if (!receipt.equals(operation)) throw new IOException("Publication did not match");
            finishing.set(true);
            renewal.shutdownNow();
            var released = client.release(operation);
            if (!released.matches(operation)) throw new IOException("Release did not match");
            Files.delete(directory.resolve("seed-pending.dat"));
            Files.delete(journal);
            System.out.println("HOSTHOP_LATEST_SEED: upload committed; verifying downloaded copy");
            Path downloaded = directory.resolve("downloaded.zip");
            store.download(object, downloaded, credentials.access().accessToken());
            if (Files.mismatch(archive, downloaded) != -1) throw new IOException("Downloaded archive differs");
            System.out.println("HOSTHOP_LATEST_SEED: verified world archive uploaded and downloaded without changes");
        } finally { renewal.shutdownNow(); }
    }
}
