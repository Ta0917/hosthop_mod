package dev.hosthop.client;

import dev.hosthop.HostHop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Held in front of the player until the shared world has been uploaded, so closing the
 * window can never cut the save short. It has no cancel or escape path while saving.
 */
@EventBusSubscriber(modid = HostHop.MOD_ID, value = Dist.CLIENT)
public final class HostHopSavingScreen extends Screen {
    HostHopSavingScreen() { super(Component.translatable("screen.hosthop.saving_title")); }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return true; }

    @Override public void tick() {
        if (HostWorldRuntime.savingInProgress()) return;
        // Finished or failed: the final status stays visible on the title screen.
        Minecraft.getInstance().gui.setScreen(new TitleScreen());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 24, -1);
        var message = HostHopStatusOverlay.message();
        if (message != null) graphics.centeredText(font, message, width / 2, height / 2 - 8, -1);
        graphics.centeredText(font, Component.translatable("screen.hosthop.saving_wait"), width / 2, height / 2 + 12, 0xFFAAAAAA);
    }

    @SubscribeEvent public static void opening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof TitleScreen && HostWorldRuntime.savingInProgress())
            event.setNewScreen(new HostHopSavingScreen());
    }
}
