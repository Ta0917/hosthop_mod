package dev.hosthop.client;

import dev.hosthop.HostHop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Keep the result visible after the integrated world returns to the title screen. */
@EventBusSubscriber(modid = HostHop.MOD_ID, value = Dist.CLIENT)
public final class HostHopStatusOverlay {
    private HostHopStatusOverlay() {}

    static Component message() {
        String key = HostWorldRuntime.lastStatus();
        if (key.equals("screen.hosthop.saving_world")) {
            int progress = HostWorldRuntime.uploadPercent();
            if (progress >= 100) return Component.translatable("screen.hosthop.upload_confirming");
            if (progress >= 0) return Component.translatable("screen.hosthop.uploading", progress);
            return Component.translatable(key);
        }
        if (key.equals("screen.hosthop.saved_world") || key.equals("screen.hosthop.recovery_required")
                || key.equals("screen.hosthop.disk_recovery") || key.equals("screen.hosthop.drive_recovery"))
            return Component.translatable(key);
        return null;
    }

    @SubscribeEvent public static void draw(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) return;
        var message = message();
        if (message != null) {
            var font = Minecraft.getInstance().font;
            var lines = font.split(message, Math.max(1, screen.width - 12));
            for (int i = 0; i < lines.size(); i++)
                event.getGuiGraphics().centeredText(font, lines.get(i), screen.width / 2, screen.height - 20 - lines.size() * 10 + i * 10, -1);
        }
    }
}
