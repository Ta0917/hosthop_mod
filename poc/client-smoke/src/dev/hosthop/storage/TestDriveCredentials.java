package dev.hosthop.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Test-only dummy credentials; never included in a normal build. */
public final class TestDriveCredentials {
    private TestDriveCredentials() {}
    public static DriveCredentialManager create(Path path) throws IOException {
        var vault = new WindowsSecretStore(path);
        vault.save(Map.of("drive_refresh_token", "test-refresh", "drive_folder_id", "folder"));
        var auth = new GoogleDriveAuthorization("test-client", "test-secret", request ->
                "{\"access_token\":\"drive-test-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8));
        return new DriveCredentialManager(vault, auth);
    }
}
