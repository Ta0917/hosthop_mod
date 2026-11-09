package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.snapshot.BinaryDelta;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only access check for an isolated test world's delta and its base. */
public final class DeltaAccessLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected invitation, two credentials and new directory");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invite.world())) throw new IOException("Not the isolated test world");
        var auth = new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret());
        String token = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])), auth).access().accessToken();
        String other = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[2])), auth).access().accessToken();
        if (identity(token).equals(identity(other))) throw new IOException("The same Google account was selected");
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        var snapshot = authority.snapshot(invite.world());
        var store = new GoogleDriveSnapshotStore();
        if (!store.isDelta(snapshot.object(), token)) throw new IOException("Current test snapshot is not a delta");
        Path directory = Files.createDirectory(Path.of(args[3]));
        Path delta = directory.resolve("delta.bin");
        var progress = new java.util.ArrayList<Long>();
        store.downloadRaw(snapshot.object(), delta, token, progress::add);
        if (progress.isEmpty() || progress.get(0) != 0 || progress.get(progress.size() - 1) != 100)
            throw new IOException("Download progress did not complete");
        var base = BinaryDelta.inspect(delta).base();
        if (!store.canRead(base, token)) throw new IOException("Original account cannot read its delta base");
        if (store.isDelta(base, token)) throw new IOException("Delta base is not a full snapshot");
        System.out.println("HOSTHOP_DELTA_ACCESS: ownDelta=true ownBase=true otherDelta=" + store.canRead(snapshot.object(), other)
                + " otherBase=" + store.canRead(base, other));
    }
    private static String identity(String token) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/about?fields=user%28permissionId%29"))
                .header("Authorization", "Bearer " + token).timeout(java.time.Duration.ofSeconds(15)).GET().build();
        var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 65536) throw new IOException("Account identity unavailable");
        return com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("user").get("permissionId").getAsString();
    }
}
