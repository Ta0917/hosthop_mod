package dev.hosthop.client;

import com.mojang.brigadier.Command;
import dev.hosthop.HostHop;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Manual backup uses the same serialized and lease-checked path as scheduled backups. */
@EventBusSubscriber(modid = HostHop.MOD_ID, value = Dist.CLIENT)
public final class HostHopBackupCommand {
    private HostHopBackupCommand() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hosthop").then(Commands.literal("backup").executes(context -> {
            boolean started = HostWorldRuntime.requestManualCheckpoint();
            context.getSource().sendSuccess(() -> Component.translatable(started ? "screen.hosthop.checkpoint_started" : "screen.hosthop.checkpoint_busy"), false);
            return started ? Command.SINGLE_SUCCESS : 0;
        })));
    }
}
