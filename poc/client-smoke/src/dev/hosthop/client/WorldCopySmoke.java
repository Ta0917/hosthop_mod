package dev.hosthop.client;

import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opens only a disposable, separately prepared world copy in an isolated client. */
@EventBusSubscriber(modid = "hosthop", value = Dist.CLIENT)
public final class WorldCopySmoke {
    private static final long started = System.nanoTime();
    private static int stage;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("hosthop.worldSmoke") || stage == 99) return;
        var minecraft = Minecraft.getInstance();
        try {
            if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(3)) throw new IllegalStateException("world copy test timed out");
            if (stage == 0 && minecraft.gui.screen() instanceof TitleScreen) {
                stage = 1;
                minecraft.createWorldOpenFlows().openWorld("seed", () -> { throw new IllegalStateException("world open cancelled"); });
            } else if (stage == 1 && minecraft.player != null && minecraft.getSingleplayerServer() != null) {
                stage = 2;
                minecraft.disconnect(new TitleScreen(), false);
                System.out.println("HOSTHOP_WORLD_COPY_SMOKE world opened and stopped=true");
                stage = 99;
                minecraft.stop();
            }
        } catch (Throwable failure) {
            System.err.println("HOSTHOP_WORLD_COPY_SMOKE failure=" + failure.getClass().getSimpleName());
            stage = 99;
            minecraft.stop();
        }
    }
}
