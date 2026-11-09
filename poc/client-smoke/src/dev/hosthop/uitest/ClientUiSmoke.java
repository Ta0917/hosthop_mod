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
            var history = minecraft.gui.screen().children().stream().filter(child -> child instanceof Button b
                    && b.getMessage().getString().equals("History")).map(Button.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalStateException("History button missing"));
            history.onPress(new net.minecraft.client.input.InputWithModifiers() {
                @Override public int input() { return 257; }
                @Override public int modifiers() { return 0; }
            });
            if (!(minecraft.gui.screen() instanceof dev.hosthop.client.HostHopHistoryScreen)) throw new IllegalStateException("History screen did not open");
            minecraft.gui.screen().onClose();
            if (!(minecraft.gui.screen() instanceof HostHopScreen)) throw new IllegalStateException("History back failed");
            var hostScreen = minecraft.gui.screen();
            var recover = hostScreen.children().stream().filter(child -> child instanceof Button b
                    && b.getMessage().getString().equals("Recover to shared")).map(Button.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Recovery button missing"));
            recover.onPress(new net.minecraft.client.input.InputWithModifiers() {
                @Override public int input() { return 257; }
                @Override public int modifiers() { return 0; }
            });
            if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen)) throw new IllegalStateException("Recovery confirmation missing");
            minecraft.gui.setScreen(hostScreen);
            try { dev.hosthop.client.ReconnectUiSmoke.verify(minecraft.gui.screen()); }
            catch (Exception failure) { throw new IllegalStateException("Reconnect UI test failed", failure); }
            System.out.println("HOSTHOP_UI_SMOKE multiplayer entry and screen verified=true");
            minecraft.stop();
        }
    }
}
