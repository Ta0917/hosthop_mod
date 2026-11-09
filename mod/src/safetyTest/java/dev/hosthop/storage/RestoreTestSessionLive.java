package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.lease.PublicationRecord;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import dev.hosthop.snapshot.StoppedWorldLock;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One-off recovery of a verified stopped test-world copy; never targets production worlds. */
public final class RestoreTestSessionLive {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected invitation, credentials, stopped world, new output directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"latest_copy_test".equals(invite.world())) throw new IOException("Unexpected target world");
        Path output = Files.createDirectory(Path.of(args[3]));
        Path archive = output.resolve("recovered.zip");
        try (var lock = StoppedWorldLock.acquire(Path.of(args[2]))) { SnapshotWriter.create(Path.of(args[2]), archive); }
        if (SnapshotVerifier.verify(archive).files() < 1) throw new IOException("Empty recovery archive");
        var credentials = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret()));
        var store = new GoogleDriveSnapshotStore();
        String token = credentials.access().accessToken();
        store.checkUploadCapacity(Files.size(archive), token);
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        String lease = UUID.randomUUID().toString();
        var pending = new WindowsSecretStore(output.resolve("recovery-pending.dat"));
        pending.save(Map.of("world", invite.world(), "owner", invite.principal(), "lease", lease, "stage", "acquiring"));
        var grant = authority.acquire(invite.world(), lease, 90, 5);
        if (grant.generation() != 1) {
            authority.abort(invite.world(), invite.principal(), lease, grant.epoch(), grant.generation());
            throw new IOException("Unexpected cloud generation");
        }
        pending.save(Map.of("world", invite.world(), "owner", invite.principal(), "lease", lease,
                "epoch", Long.toString(grant.epoch()), "generation", "1", "stage", "uploading"));
        var failure = new AtomicReference<Throwable>();
        var finishing = new AtomicBoolean();
        Thread main = Thread.currentThread();
        var renewal = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "hosthop-recovery-renewal"); t.setDaemon(true); return t; });
        renewal.scheduleWithFixedDelay(() -> {
            try {
                if (authority.renew(invite.world(), lease, 90, grant.epoch(), 5).generation() != 1)
                    throw new IOException("Cloud generation changed");
            } catch (Exception e) { if (!finishing.get()) { failure.compareAndSet(null, e); main.interrupt(); } }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            System.out.println("HOSTHOP_RECOVERY: uploading stopped session archive");
            var object = store.upload(archive, invite.folder(), token);
            if (failure.get() != null) throw new IOException("Lease renewal failed", failure.get());
            var operation = new PublicationRecord(invite.world(), UUID.randomUUID().toString(), invite.principal(), lease,
                    grant.epoch(), 1, object.sha256(), object.fileId(), object.size(), 2);
            Path journal = output.resolve("publication.dat");
            PendingPublicationJournal.create(journal, operation);
            if (!authority.commit(operation, credentials.access().accessToken()).equals(operation)) throw new IOException("Publication mismatch");
            finishing.set(true);
            renewal.shutdownNow();
            if (!authority.release(operation).matches(operation)) throw new IOException("Release mismatch");
            Files.delete(output.resolve("recovery-pending.dat"));
            Files.delete(journal);
            Path downloaded = output.resolve("downloaded.zip");
            store.download(object, downloaded, credentials.access().accessToken());
            if (Files.mismatch(archive, downloaded) != -1) throw new IOException("Downloaded archive mismatch");
            System.out.println("HOSTHOP_RECOVERY: saved and verified stopped session");
        } finally { renewal.shutdownNow(); }
    }
}
