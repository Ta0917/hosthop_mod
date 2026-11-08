package dev.hosthop.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** A cancellable waiting screen that follows the current authenticated host. */
public final class HostHopReconnectScreen extends Screen {
    private static final long MAX_WAIT = TimeUnit.MINUTES.toNanos(5);
    private final GuestReconnect reconnect;
    private final long started = System.nanoTime();
    private int ticks;
    private boolean checking;

    HostHopReconnectScreen(GuestReconnect reconnect) {
        super(Component.translatable("screen.hosthop.reconnecting"));
        this.reconnect = reconnect;
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 - 100, height / 2 + 30, 200, 20).build());
    }

    @Override public void tick() {
        if (!GuestReconnect.active(reconnect)) return;
        if (System.nanoTime() - started > MAX_WAIT) { onClose(); return; }
        if (checking || ++ticks < 40) return;
        ticks = 0; checking = true;
        CompletableFuture.supplyAsync(() -> {
            try { return reconnect.authority.hostStatus(reconnect.invitation.world(), 2).endpoint(); }
            catch (Exception failure) { return null; }
        }).thenAccept(endpoint -> Minecraft.getInstance().execute(() -> {
            checking = false;
            var minecraft = Minecraft.getInstance();
            if (!GuestReconnect.active(reconnect) || minecraft.gui.screen() != this || endpoint == null || endpoint.epoch() < reconnect.minimumEpoch) return;
            var attempt = reconnect.controller.connect(reconnect.session, endpoint, System.nanoTime(), System.currentTimeMillis());
            if (attempt == null) return;
            reconnect.connecting(endpoint, attempt);
            var server = new ServerData("HostHop " + reconnect.invitation.world(), endpoint.address(), ServerData.Type.OTHER);
            ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(endpoint.address()), server, false, null);
        }));
    }

    @Override public void onClose() {
        reconnect.cancel();
        Minecraft.getInstance().gui.setScreen(reconnect.parent);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 12, -1);
    }
}
