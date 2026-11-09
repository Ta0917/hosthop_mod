package dev.hosthop.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public final class SaveStatusTest {
    public static void main(String[] args) throws Exception {
        var config = Files.createTempDirectory("hosthop-save-status-");
        if (SaveStatus.read(config) != null) throw new AssertionError();
        SaveStatus.write(config, SaveStatus.SAVING);
        if (!SaveStatus.RECOVERY.equals(SaveStatus.read(config))) throw new AssertionError("stale save shown as active");
        try (var channel = FileChannel.open(config.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!SaveStatus.SAVING.equals(SaveStatus.read(config))) throw new AssertionError("active save not shown");
        }
        if (!SaveStatus.RECOVERY.equals(SaveStatus.read(config))) throw new AssertionError("abandoned save not flagged");
        SaveStatus.write(config, SaveStatus.SAVED);
        if (!SaveStatus.SAVED.equals(SaveStatus.read(config))) throw new AssertionError("completion not retained");
        try { SaveStatus.write(config, "untrusted-value"); throw new AssertionError("invalid status accepted"); }
        catch (IOException expected) { }
        if (!SaveStatus.SAVED.equals(SaveStatus.read(config))) throw new AssertionError("status was overwritten");
        System.out.println("Persistent save status checks passed");
    }
}
