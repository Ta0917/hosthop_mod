package dev.hosthop.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public final class SaveStatusTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("lock-child")) {
            try (var channel = FileChannel.open(java.nio.file.Path.of(args[1]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                System.out.println("LOCKED"); System.out.flush(); System.in.read();
            }
            return;
        }
        var config = Files.createTempDirectory("hosthop-save-status-");
        if (SaveStatus.read(config) != null) throw new AssertionError();
        SaveStatus.write(config, SaveStatus.SAVING);
        if (!SaveStatus.RECOVERY.equals(SaveStatus.read(config))) throw new AssertionError("stale save shown as active");
        try (var channel = FileChannel.open(config.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!SaveStatus.SAVING.equals(SaveStatus.read(config))) throw new AssertionError("active save not shown");
            if (SaveStatus.readProgress(config) != -1) throw new AssertionError("unstarted transfer has progress");
            SaveStatus.writeProgress(config, 42);
            if (!SaveStatus.SAVING.equals(SaveStatus.read(config)) || SaveStatus.readProgress(config) != 42)
                throw new AssertionError("active progress not retained");
            SaveStatus.writeProgress(config, 100);
            if (SaveStatus.readProgress(config) != 100) throw new AssertionError("transfer completion not retained");
        }
        if (!SaveStatus.RECOVERY.equals(SaveStatus.read(config))) throw new AssertionError("abandoned save not flagged");
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        var javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", executable);
        var child = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"),
                SaveStatusTest.class.getName(), "lock-child", config.resolve("runtime.lock").toString()).redirectErrorStream(true).start();
        try {
            var line = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return child.inputReader().readLine(); } catch (IOException error) { throw new RuntimeException(error); }
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!"LOCKED".equals(line) || !SaveStatus.SAVING.equals(SaveStatus.read(config))) throw new AssertionError("other process not detected");
            SaveStatus.writeProgress(config, 73);
            if (SaveStatus.readProgress(config) != 73) throw new AssertionError("other process progress not shown");
        } finally { child.getOutputStream().close(); if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly(); }
        if (!SaveStatus.RECOVERY.equals(SaveStatus.read(config))) throw new AssertionError("stopped process not detected");
        SaveStatus.write(config, SaveStatus.SAVED);
        if (!SaveStatus.SAVED.equals(SaveStatus.read(config)) || SaveStatus.readProgress(config) != -1) throw new AssertionError("completion not retained");
        Files.writeString(config.resolve("pending-host.dat"), "newer session");
        if (!SaveStatus.RECOVERY.equals(SaveStatus.read(config))) throw new AssertionError("older completion masked pending host");
        Files.delete(config.resolve("pending-host.dat"));
        try { SaveStatus.write(config, "untrusted-value"); throw new AssertionError("invalid status accepted"); }
        catch (IOException expected) { }
        if (!SaveStatus.SAVED.equals(SaveStatus.read(config))) throw new AssertionError("status was overwritten");
        System.out.println("Persistent save status checks passed");
    }
}
