package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.world.PendingHostRecovery;
import dev.hosthop.world.SharedWorldTransfer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Opt-in live recovery check, restricted to the isolated alternate test world. */
public final class StoppedRecoveryLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected invitation, credentials and new directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invite.world())) throw new IOException("Not the isolated test world");
        var dir = Files.createDirectory(Path.of(args[2]));
        var credentials = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret()));
        var token = credentials.access().accessToken();
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        var storage = new GoogleDriveSnapshotStore();
        var gate = new LocalHostGate();
        var coordinator = new LeaseCoordinator(gate, authority, System::nanoTime, System::currentTimeMillis);
        var lease = coordinator.begin(invite.world(), UUID.randomUUID().toString(), invite.principal(), 30, 3);
        if (!coordinator.acquire(lease)) throw new IOException("Test lease unavailable");
        var world = dir.resolve("world");
        var transfer = new SharedWorldTransfer(gate, authority, storage, System::nanoTime, System::currentTimeMillis);
        transfer.prepare(lease, null, dir.resolve("initial.zip"), world, token);
        Files.writeString(world.resolve("session.lock"), "lock");
        if (!gate.beginHosting(lease.attempt(), System.nanoTime(), System.currentTimeMillis())) throw new IOException("Test lease expired during preparation");
        String marker = UUID.randomUUID().toString();
        Files.writeString(world.resolve("hosthop-stopped-recovery-test.txt"), marker);
        if (!gate.beginGracefulStop(lease.attempt(), System.nanoTime(), System.currentTimeMillis())) throw new IOException("Test host could not stop");
        // This is a filesystem-only test: no Minecraft server or relay was started.
        Thread.sleep(TimeUnit.SECONDS.toMillis(32));
        var stopped = new PendingHostRecovery.Stopped(world, dir, lease.lease(), lease.epoch(), lease.generation());
        transfer.publishExpiredStopped(stopped, invite.world(), invite.principal(), invite.folder(), token);
        var operation = PendingPublicationJournal.read(dir.resolve("publication.dat"));
        if (!authority.commitStatus(operation).equals(operation) || !authority.releaseStatus(operation).matches(operation))
            throw new IOException("Recovered publication receipt mismatch");
        var nextGate = new LocalHostGate();
        var nextCoordinator = new LeaseCoordinator(nextGate, authority, System::nanoTime, System::currentTimeMillis);
        var next = nextCoordinator.begin(invite.world(), UUID.randomUUID().toString(), invite.principal(), 90, 5);
        if (!nextCoordinator.acquire(next) || next.generation() != lease.generation() + 1) throw new IOException("Recovered generation unavailable");
        var nextDir = Files.createDirectory(dir.resolve("next"));
        var restored = nextDir.resolve("world");
        new SharedWorldTransfer(nextGate, authority, storage, System::nanoTime, System::currentTimeMillis)
                .prepare(next, null, nextDir.resolve("initial.zip"), restored, token);
        if (!Files.readString(restored.resolve("hosthop-stopped-recovery-test.txt")).equals(marker)) throw new IOException("Recovered data mismatch");
        if (!nextCoordinator.abortBeforeHosting(next)) throw new IOException("Test lease release failed");
        System.out.println("HOSTHOP_STOPPED_RECOVERY_LIVE restored=true");
    }
}
