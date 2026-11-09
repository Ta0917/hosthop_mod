package dev.hosthop.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class SaveKeepAliveTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("child")) {
            var guard = new SaveKeepAlive();
            var save = new Thread(() -> {
                try { Thread.sleep(600); Files.writeString(Path.of(args[1]), "saved"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                finally { guard.close(); }
            });
            save.setDaemon(true);
            save.start();
            return;
        }
        var dir = Files.createTempDirectory("hosthop-keepalive-test-");
        var marker = dir.resolve("saved.txt");
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        var child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                SaveKeepAliveTest.class.getName(), "child", marker.toString()).inheritIO().start();
        if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("Save helper did not finish"); }
        if (child.exitValue() != 0 || !Files.readString(marker).equals("saved")) throw new AssertionError("Save stopped when main exited");
        Files.delete(marker); Files.delete(dir);
        System.out.println("Background save keepalive checks passed");
    }
}
