package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import dev.hosthop.snapshot.SnapshotWriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verify a lost final response against an isolated Drive test folder. */
public final class DriveResumeLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected invitation, credentials and new directory");
        var invitation = Invitation.decode(Files.readString(Path.of(args[0])));
        if (!"alternate_host_test".equals(invitation.world())) throw new IOException("Not the isolated test world");
        var dir = Files.createDirectory(Path.of(args[2]));
        var world = Files.createDirectory(dir.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "isolated upload resume test");
        var archive = dir.resolve("snapshot.zip");
        SnapshotWriter.create(world, archive);
        var credentials = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])),
                new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
        var token = credentials.access().accessToken();
        var lost = new AtomicBoolean();
        var queried = new AtomicBoolean();
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var store = new GoogleDriveSnapshotStore(request -> {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String range = request.headers().firstValue("Content-Range").orElse("");
            if (range.startsWith("bytes */")) queried.set(true);
            if (request.method().equals("PUT") && range.startsWith("bytes 0-")
                    && response.statusCode() >= 200 && response.statusCode() < 300 && lost.compareAndSet(false, true))
                throw new IOException("injected lost response after Drive accepted upload");
            return new GoogleDriveSnapshotStore.Reply(response.statusCode(), response.headers(), response.body());
        });
        var result = store.upload(archive, invitation.folder(), token);
        if (!lost.get() || !queried.get() || result.size() != Files.size(archive)) throw new IOException("Upload did not resume");
        System.out.println("HOSTHOP_DRIVE_RESUME_LIVE recovered=true");
    }
}
