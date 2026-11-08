package dev.hosthop.storage;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit read-only check using an already authorized test credential. */
public final class DriveQuotaLiveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected configuration and credential file");
        var config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var auth = new GoogleDriveAuthorization(config.get("oauth_client_id").getAsString(), config.get("oauth_client_secret").getAsString());
        var manager = new DriveCredentialManager(new WindowsSecretStore(Path.of(args[1])), auth);
        new GoogleDriveSnapshotStore().checkUploadCapacity(1, manager.access().accessToken());
        System.out.println("HOSTHOP_DRIVE_QUOTA_LIVE: quota request accepted");
    }
}
