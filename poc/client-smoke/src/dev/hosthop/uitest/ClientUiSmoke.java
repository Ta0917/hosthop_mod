package dev.hosthop.uitest;

import dev.hosthop.client.HostHopScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in smoke test on an isolated client; never packaged in the regular mod. */
@EventBusSubscriber(modid = "hosthop", value = Dist.CLIENT)
public final class ClientUiSmoke {
    private static int stage;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("hosthop.uiSmoke")) return;
        var minecraft = Minecraft.getInstance();
        if (stage == 0) {
            stage = 1;
            minecraft.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        } else if (stage == 1 && minecraft.gui.screen() instanceof JoinMultiplayerScreen screen) {
            stage = 2;
            var button = screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals("HostHop"))
                    .map(Button.class::cast).findFirst().orElseThrow(() -> new IllegalStateException("HostHop entry missing"));
            button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                @Override public int input() { return 257; }
                @Override public int modifiers() { return 0; }
            });
        } else if (stage == 2) {
            stage = 3;
            if (!(minecraft.gui.screen() instanceof HostHopScreen)) throw new IllegalStateException("HostHop screen did not open");
            try { dev.hosthop.client.ReconnectUiSmoke.verify(minecraft.gui.screen()); }
            catch (Exception failure) { throw new IllegalStateException("Reconnect UI test failed", failure); }
            System.out.println("HOSTHOP_UI_SMOKE multiplayer entry and screen verified=true");
            minecraft.stop();
        }
    }
}
