package dev.hosthop.client;

import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import dev.hosthop.world.SaveKeepAlive;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in isolated test: close the actual client before a stopped-world backup begins. */
@EventBusSubscriber(modid = "hosthop", value = Dist.CLIENT)
public final class BackgroundExitSmoke {
    private static boolean started;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var minecraft = Minecraft.getInstance();
        if (!Boolean.getBoolean("hosthop.backgroundExitSmoke") || started || minecraft.gui.screen() == null) return;
        started = true;
        var dir = minecraft.gameDirectory.toPath().resolve("hosthop-background-exit-test");
        Files.createDirectory(dir);
        var world = Files.createDirectory(dir.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "isolated background save");
        var keepAlive = new SaveKeepAlive();
        var worker = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (!SaveKeepAlive.waitingForCompletion() && System.nanoTime() - deadline < 0) Thread.sleep(50);
                if (!SaveKeepAlive.waitingForCompletion()) throw new IllegalStateException("Client did not reach the post-window save wait");
                // Vanilla's post-main watchdog would kill the JVM after 15 seconds.
                Thread.sleep(17000);
                var archive = dir.resolve("snapshot.zip");
                SnapshotWriter.create(world, archive);
                SnapshotVerifier.verify(archive);
                Files.writeString(dir.resolve("result.txt"), "verified");
                System.out.println("HOSTHOP_BACKGROUND_EXIT_SMOKE saved after client exit=true");
            } catch (Throwable failure) {
                try { Files.writeString(dir.resolve("failure.txt"), failure.toString()); } catch (Exception ignored) { }
                failure.printStackTrace();
            } finally { keepAlive.close(); }
        }, "hosthop-background-exit-test");
        worker.setDaemon(true);
        worker.start();
        minecraft.stop();
    }
}
