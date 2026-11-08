package dev.hosthop.storage;

import com.google.gson.JsonParser;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicitly invoked test: one small synthetic snapshot in a user-selected test folder. */
public final class DriveLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected configuration and new test directory");
        var config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        String folder = config.get("drive_folder").getAsString();
        var directory = Files.createDirectory(Path.of(args[1]));
        var vault = new WindowsSecretStore(directory.resolve("credentials.dat"));
        var authorization = new GoogleDriveAuthorization(config.get("oauth_client_id").getAsString(), config.get("oauth_client_secret").getAsString());
        var manager = new DriveCredentialManager(vault, authorization);
        System.out.println("HOSTHOP_DRIVE_LIVE: Complete Google authorization and select the test folder in your browser.");
        manager.authorize(folder, uri -> {
            try { Desktop.getDesktop().browse(uri); }
            catch (Exception failure) { throw new IllegalStateException("Could not open browser", failure); }
        });
        var tokens = manager.access();
        var world = Files.createDirectory(directory.resolve("synthetic-world"));
        Files.writeString(world.resolve("marker.txt"), "HostHop isolated Drive integration test\n");
        var original = directory.resolve("original.zip");
        SnapshotWriter.create(world, original);
        var storage = new GoogleDriveSnapshotStore();
        var uploaded = storage.upload(original, folder, tokens.accessToken());
        var downloaded = directory.resolve("downloaded.zip");
        storage.download(uploaded, downloaded, manager.access().accessToken());
        var restore = directory.resolve("restored");
        SnapshotVerifier.restore(downloaded, restore);
        if (!Files.readString(restore.resolve("marker.txt")).equals("HostHop isolated Drive integration test\n"))
            throw new IllegalStateException("Restored marker does not match");
        System.out.println("HOSTHOP_DRIVE_LIVE: synthetic snapshot uploaded, downloaded and restored successfully");
    }
}
