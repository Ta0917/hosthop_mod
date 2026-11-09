package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.world.BackupRestore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read and restore a historical generation from the isolated test world. */
public final class BackupRestoreLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected invitation, credentials and new directory");
        var invitation = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invitation.world())) throw new IOException("Not the isolated test world");
        var root = Files.createDirectory(Path.of(args[2]));
        Files.createDirectory(root.resolve("saves"));
        var credentials = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
        var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
        var history = authority.history(invitation.world());
        if (history.size() < 2) throw new IOException("No historical snapshot");
        var selected = history.get(1);
        String name = BackupRestore.restore(selected, new GoogleDriveSnapshotStore(), credentials.access().accessToken(), root);
        if (!Files.isRegularFile(root.resolve("saves").resolve(name).resolve("level.dat"))) throw new IOException("World data missing");
        if (authority.history(invitation.world()).get(0).generation() != history.get(0).generation()) throw new IOException("Shared head changed");
        System.out.println("HOSTHOP_BACKUP_RESTORE_LIVE restored=true sharedHeadUnchanged=true");
    }
}
