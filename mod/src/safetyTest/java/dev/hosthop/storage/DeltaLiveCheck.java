package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.snapshot.BinaryDelta;
import dev.hosthop.world.SharedWorldTransfer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Explicit live check against the isolated alternate-account test world only. */
public final class DeltaLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected invitation, credentials and new output directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invite.world())) throw new IOException("Not the isolated test world");
        var directory = Files.createDirectory(Path.of(args[2]));
        var credentials = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret()));
        String token = credentials.access().accessToken();
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        var storage = new GoogleDriveSnapshotStore();
        var previous = authority.snapshot(invite.world());
        var firstGate = new LocalHostGate();
        var firstCoordinator = new LeaseCoordinator(firstGate, authority, System::nanoTime, System::currentTimeMillis);
        var first = firstCoordinator.begin(invite.world(), UUID.randomUUID().toString(), invite.principal(), 90, 5);
        if (!firstCoordinator.acquire(first) || first.generation() != previous.generation()) throw new IOException("Unexpected test generation");
        Path firstDir = Files.createDirectory(directory.resolve("first"));
        Path firstWorld = firstDir.resolve("world");
        var transfer = new SharedWorldTransfer(firstGate, authority, storage, System::nanoTime, System::currentTimeMillis);
        transfer.prepare(first, null, firstDir.resolve("initial.zip"), firstWorld, token);
        Files.writeString(firstWorld.resolve("session.lock"), "lock");
        if (!firstGate.beginHosting(first.attempt(), System.nanoTime(), System.currentTimeMillis())) throw new IOException("Cannot start test host");
        String marker = UUID.randomUUID().toString();
        Files.writeString(firstWorld.resolve("hosthop-delta-test.txt"), marker);
        if (!firstGate.beginGracefulStop(first.attempt(), System.nanoTime(), System.currentTimeMillis())) throw new IOException("Cannot stop test host");
        transfer.publishAfterStopped(first, firstWorld, firstDir.resolve("final.zip"), firstDir.resolve("publication.dat"),
                UUID.randomUUID().toString(), invite.folder(), token);
        var current = authority.snapshot(invite.world());
        if (current.generation() != previous.generation() + 1 || !BinaryDelta.isDelta(firstDir.resolve("delta.bin")))
            throw new IOException("Delta was not published");
        System.out.println("HOSTHOP_DELTA_LIVE: delta published; verifying from a fresh client");
        var nextGate = new LocalHostGate();
        var nextCoordinator = new LeaseCoordinator(nextGate, authority, System::nanoTime, System::currentTimeMillis);
        var next = nextCoordinator.begin(invite.world(), UUID.randomUUID().toString(), invite.principal(), 90, 5);
        if (!nextCoordinator.acquire(next)) throw new IOException("Cannot acquire next test host");
        Path secondDir = Files.createDirectory(directory.resolve("second"));
        Path secondWorld = secondDir.resolve("world");
        new SharedWorldTransfer(nextGate, authority, storage, System::nanoTime, System::currentTimeMillis)
                .prepare(next, null, secondDir.resolve("initial.zip"), secondWorld, token);
        if (!Files.readString(secondWorld.resolve("hosthop-delta-test.txt")).equals(marker)) throw new IOException("Delta restore failed");
        if (!nextCoordinator.abortBeforeHosting(next)) throw new IOException("Cannot release test host");
        System.out.printf("HOSTHOP_DELTA_LIVE: full=%d delta=%d restored=true%n", Files.size(firstDir.resolve("final.zip")), current.object().size());
    }
}
