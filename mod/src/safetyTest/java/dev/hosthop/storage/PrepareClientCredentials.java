package dev.hosthop.storage;

import dev.hosthop.config.Invitation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

/** One-time, explicitly invoked configuration; refuses existing destination files. */
public final class PrepareClientCredentials {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected invitation, existing Drive credential and new destination");
        var invite = Invitation.decode(Files.readString(Path.of(args[0])).trim());
        var previous = new WindowsSecretStore(Path.of(args[1])).load();
        String refresh = previous.get("drive_refresh_token"), folder = previous.get("drive_folder_id");
        if (refresh == null || !invite.folder().equals(folder)) throw new IOException("Drive credentials do not match the invitation");
        Path destination = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.isDirectory(destination.getParent(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Credential directory is unavailable");
        var contents = new HashMap<>(invite.secrets());
        contents.put("drive_refresh_token", refresh);
        contents.put("drive_folder_id", folder);
        Files.createFile(destination);
        var store = new WindowsSecretStore(destination);
        store.save(contents);
        if (!store.load().equals(contents)) throw new IOException("Saved credential verification failed");
        System.out.println("HostHop test invitation configured securely");
    }
}
