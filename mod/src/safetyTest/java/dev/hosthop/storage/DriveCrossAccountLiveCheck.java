package dev.hosthop.storage;

import com.google.gson.JsonParser;
import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import java.awt.Desktop;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Explicit live test with separate credentials; does not change any world generation. */
public final class DriveCrossAccountLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--owner-only".equals(args[0])) {
            var invite = Invitation.decode(Files.readString(Path.of(args[1])).trim());
            var auth = new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret());
            var owner = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[2])), auth);
            identity(owner.access().accessToken());
            System.out.println("HOSTHOP_CROSS_ACCOUNT: owner identity comparison available");
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("Expected invitation, owner credential, archive and new directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])).trim());
        if (!"latest_copy_test".equals(invite.world())) throw new IllegalArgumentException("Unexpected test world");
        Path directory = Files.createDirectory(Path.of(args[3]));
        var auth = new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret());
        var owner = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])), auth);
        var alternate = new DriveCredentialManager(new WindowsSecretStore(directory.resolve("credentials.dat")), auth);
        String ownerIdentity = identity(owner.access().accessToken());
        var opener = (java.util.function.Consumer<URI>) uri -> {
            try { Desktop.getDesktop().browse(uri); }
            catch (Exception failure) { throw new IllegalStateException("Browser could not be opened", failure); }
        };
        System.out.println("HOSTHOP_CROSS_ACCOUNT: authorize the alternate account and select the shared folder");
        alternate.authorize(invite.folder(), opener);
        String alternateIdentity = identity(alternate.access().accessToken());
        if (ownerIdentity.equals(alternateIdentity)) throw new IllegalStateException("The same Google account was selected");
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        var snapshot = authority.snapshot(invite.world());
        System.out.println("HOSTHOP_CROSS_ACCOUNT: authorize the same alternate account and select the latest backup");
        alternate.authorizeFile(snapshot.object().fileId(), invite.folder(), opener);
        if (!alternateIdentity.equals(identity(alternate.access().accessToken()))) throw new IllegalStateException("A different account was selected for the snapshot");
        var store = new GoogleDriveSnapshotStore();
        Path downloaded = directory.resolve("downloaded.zip");
        store.download(snapshot.object(), downloaded, alternate.access().accessToken());
        if (Files.mismatch(Path.of(args[2]), downloaded) != -1) throw new IllegalStateException("Downloaded snapshot differs");
        var synthetic = Files.createDirectory(directory.resolve("synthetic-world"));
        Files.writeString(synthetic.resolve("marker.txt"), "HostHop cross-account write check\n");
        var archive = directory.resolve("synthetic.zip");
        SnapshotWriter.create(synthetic, archive);
        var uploaded = store.upload(archive, invite.folder(), alternate.access().accessToken());
        Path check = directory.resolve("synthetic-downloaded.zip");
        store.download(uploaded, check, alternate.access().accessToken());
        SnapshotVerifier.verify(check);
        if (Files.mismatch(archive, check) != -1) throw new IllegalStateException("Written test snapshot differs");
        System.out.println("HOSTHOP_CROSS_ACCOUNT: different account read latest and wrote a verified test snapshot");
    }

    private static String identity(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/about?fields=user%28permissionId%29"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(15)).GET().build();
        var response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 65536) throw new IllegalStateException("Google account identity unavailable");
        var user = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("user");
        if (user == null || !user.has("permissionId")) throw new IllegalStateException("Google account identity unavailable");
        String id = user.get("permissionId").getAsString();
        if (id.isBlank() || id.length() > 256) throw new IllegalStateException("Invalid Google account identity");
        return id;
    }
}
