package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.world.SharedWorldTransfer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Seed the designated isolated cloud test world with an existing synthetic Minecraft world. */
public final class SeedGameWorldLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected invitation, credentials, seed and new directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invite.world())) throw new IOException("Not the isolated test world");
        Path source = Path.of(args[2]).toRealPath();
        if (!source.startsWith(Path.of("../.tools").toRealPath())) throw new IOException("Seed is not isolated test data");
        var dir = Files.createDirectory(Path.of(args[3]));
        var credentials = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret()));
        var token = credentials.access().accessToken();
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        var gate = new LocalHostGate();
        var coordinator = new LeaseCoordinator(gate, authority, System::nanoTime, System::currentTimeMillis);
        var acquisition = coordinator.begin(invite.world(), UUID.randomUUID().toString(), invite.principal(), 90, 5);
        if (!coordinator.acquire(acquisition)) throw new IOException("Test lease unavailable");
        var storage = new GoogleDriveSnapshotStore();
        var transfer = new SharedWorldTransfer(gate, authority, storage, System::nanoTime, System::currentTimeMillis);
        transfer.prepare(acquisition, null, dir.resolve("old.zip"), dir.resolve("old-world"), token);
        if (!gate.beginHosting(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis())
                || !gate.beginGracefulStop(acquisition.attempt(), System.nanoTime(), System.currentTimeMillis())) throw new IOException("Test lease expired");
        // Do not use the previous synthetic delta as a base for a different world.
        var fullTransfer = new SharedWorldTransfer(gate, authority, storage, System::nanoTime, System::currentTimeMillis);
        fullTransfer.publishAfterStopped(acquisition, source, dir.resolve("game.zip"), dir.resolve("publication.dat"),
                UUID.randomUUID().toString(), invite.folder(), token);
        System.out.println("HOSTHOP_GAME_SEED_LIVE ready=true");
    }
}
