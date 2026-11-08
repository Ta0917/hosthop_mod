package dev.hosthop.client;

import dev.hosthop.HostHop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = HostHop.MOD_ID, value = Dist.CLIENT)
public final class MultiplayerEntry {
    private MultiplayerEntry() {}

    @SubscribeEvent
    public static void addButton(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) return;
        event.addListener(Button.builder(Component.literal("HostHop"), button -> Minecraft.getInstance().gui.setScreen(new HostHopScreen(screen)))
                .bounds(Math.max(4, screen.width - 86), 4, 82, 20).build());
    }
}
