package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.snapshot.BinaryDelta;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Explicit consent to read the isolated delta and base using the other account. */
public final class DeltaCrossAccountLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected invitation, other credentials, producer credentials and new directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invite.world())) throw new IOException("Not the isolated test world");
        var directory = Files.createDirectory(Path.of(args[3]));
        var manager = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret()));
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        var snapshot = authority.snapshot(invite.world());
        var store = new GoogleDriveSnapshotStore();
        Consumer<URI> open = uri -> {
            try { Desktop.getDesktop().browse(uri); }
            catch (IOException failure) { throw new IllegalStateException("Browser could not be opened", failure); }
        };
        String token = manager.access().accessToken();
        String producer = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[2])),
                new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret())).access().accessToken();
        var identity = new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret());
        if (identity.accountId(token).equals(identity.accountId(producer))) throw new IOException("Test requires two different accounts");
        if (!store.canRead(snapshot.object(), token)) {
            System.out.println("HOSTHOP_DELTA_CROSS: authorize current delta");
            manager.authorizeFile(snapshot.object().fileId(), invite.folder(), open);
            token = manager.access().accessToken();
        }
        if (!store.isDelta(snapshot.object(), token)) throw new IOException("Test snapshot is not a delta");
        Path delta = directory.resolve("delta.bin"); store.downloadRaw(snapshot.object(), delta, token);
        var base = BinaryDelta.inspect(delta).base();
        if (!store.canRead(base, token)) {
            System.out.println("HOSTHOP_DELTA_CROSS: authorize base snapshot");
            manager.authorizeFile(base.fileId(), invite.folder(), open);
            token = manager.access().accessToken();
        }
        Path baseline = directory.resolve("base.zip"), restored = directory.resolve("restored.zip");
        if (identity.accountId(token).equals(identity.accountId(producer))) throw new IOException("Different account was not retained");
        store.download(base, baseline, token);
        BinaryDelta.apply(delta, baseline, restored);
        System.out.println("HOSTHOP_DELTA_CROSS: other account restored verified snapshot");
    }
}
