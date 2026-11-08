package dev.hosthop.storage;

import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

public final class WindowsSecretStoreTest {
    public static void main(String[] args) throws Exception {
        if (!Platform.isWindows()) { System.out.println("Windows secret store check skipped on non-Windows"); return; }
        var directory = Files.createTempDirectory("hosthop-secret-test-");
        var file = directory.resolve("secrets.dat");
        try {
            var store = new WindowsSecretStore(file);
            store.save(Map.of("refresh", "test-secret-one", "member", "test-secret-two"));
            check(!new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("test-secret"));
            check(store.load().get("refresh").equals("test-secret-one"));
            store.save(Map.of("refresh", "new-secret"));
            check(store.load().equals(Map.of("refresh", "new-secret")));
            store.save(Map.of("drive_refresh_token", "refresh", "drive_folder_id", "folder", "member", "member-secret"));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var auth = new GoogleDriveAuthorization("client-id", "client-secret", request -> {
                calls.incrementAndGet();
                return "{\"access_token\":\"access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8);
            });
            var manager = new DriveCredentialManager(store, auth);
            check(manager.access().accessToken().equals("access") && manager.access().folderId().equals("folder") && calls.get() == 1);
            check(store.load().get("drive_refresh_token").equals("new-refresh") && store.load().get("member").equals("member-secret"));
            Files.write(file, new byte[] { 1, 2, 3 });
            try { store.load(); throw new AssertionError("corrupted secrets accepted"); }
            catch (IOException expected) { }
            System.out.println("Windows encrypted credential checks passed");
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
