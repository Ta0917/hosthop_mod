package dev.hosthop.world;

import com.sun.jna.Platform;
import dev.hosthop.config.Invitation;
import dev.hosthop.storage.WindowsSecretStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

public final class LocalRecoveryExportTest {
    public static void main(String[] args) throws Exception {
        if (!Platform.isWindows()) { System.out.println("Local recovery export skipped on non-Windows"); return; }
        var game = Files.createTempDirectory("hosthop-export-");
        var config = Files.createDirectories(game.resolve("config/hosthop"));
        var saves = Files.createDirectory(game.resolve("saves"));
        String id = UUID.randomUUID().toString();
        String level = "hosthop_" + id.replace("-", "");
        var world = Files.createDirectory(saves.resolve(level));
        Files.writeString(world.resolve("level.dat"), "stopped-world");
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.createDirectories(game.resolve("hosthop/sessions").resolve(id));
        var invite = Invitation.fromSecrets(Map.of("authority", "https://service.example", "world", "world", "principal", "owner",
                "member_token", "a".repeat(43), "oauth_client_id", "client.apps.googleusercontent.com",
                "oauth_client_secret", "client-secret", "drive_folder", "folder"));
        var vault = new WindowsSecretStore(config.resolve("pending-host.dat"));
        var pending = Map.of("world", "world", "principal", "owner", "lease", id, "session", id, "level", level,
                "epoch", "1", "generation", "1", "stage", "stopped");
        vault.save(pending);
        try (var channel = FileChannel.open(config.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            try { LocalRecoveryExport.export(game, config, invite); throw new AssertionError("active process accepted"); }
            catch (IOException expected) { }
        }
        try (var channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            try { LocalRecoveryExport.export(game, config, invite); throw new AssertionError("world in use accepted"); }
            catch (IOException expected) { }
        }
        var exported = LocalRecoveryExport.export(game, config, invite);
        if (!exported.clean() || !Files.readString(saves.resolve(exported.name()).resolve("level.dat")).equals("stopped-world")
                || !vault.load().equals(pending) || !Files.readString(world.resolve("level.dat")).equals("stopped-world"))
            throw new AssertionError("recovery export changed data");
        var unsafe = new java.util.HashMap<>(pending); unsafe.put("stage", "starting"); vault.save(unsafe);
        var unclean = LocalRecoveryExport.export(game, config, invite);
        if (unclean.clean() || !Files.readString(saves.resolve(unclean.name()).resolve("level.dat")).equals("stopped-world")
                || !vault.load().equals(unsafe)) throw new AssertionError("unclean recovery was not preserved separately");
        if (PendingHostRecovery.findStopped(unsafe, "world", "owner", game) != null)
            throw new AssertionError("unclean world accepted for automatic publication");
        System.out.println("Local recovery export checks passed");
    }
}
