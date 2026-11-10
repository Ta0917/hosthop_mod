package dev.hosthop.client;

import java.util.concurrent.CompletableFuture;
import dev.hosthop.reconnect.ReconnectWaitPolicy;
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
    private final GuestReconnect reconnect;
    private final long started;
    private int ticks;
    private boolean checking;
    private record Checked(dev.hosthop.lease.LeaseAuthorityClient.Endpoint endpoint, boolean changed) {}

    HostHopReconnectScreen(GuestReconnect reconnect) {
        super(Component.translatable("screen.hosthop.reconnecting"));
        this.reconnect = reconnect;
        this.started = reconnect.waitStarted();
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 - 100, height / 2 + 30, 200, 20).build());
    }

    @Override public void tick() {
        if (!GuestReconnect.active(reconnect)) return;
        long now = System.nanoTime();
        if (ReconnectWaitPolicy.expired(started, now)) { onClose(); return; }
        int interval = ReconnectWaitPolicy.pollTicks(started, now);
        if (checking || ++ticks < interval) return;
        ticks = 0; checking = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                var state = reconnect.authority.hostStatus(reconnect.invitation.world(), 2);
                var endpoint = state.endpoint();
                if (state.maintenance() || endpoint == null || endpoint.epoch() < reconnect.minimumEpoch) return new Checked(null, false);
                var remote = reconnect.authority.compatibility(reconnect.invitation.world(), endpoint.epoch());
                var local = dev.hosthop.config.CompatibilityManifest.capture(Minecraft.getInstance().gameDirectory.toPath());
                var diff = remote.compare(local);
                return new Checked(endpoint, !diff.mods().isEmpty() || !diff.configs().isEmpty());
            } catch (Exception failure) { return new Checked(null, false); }
        }).thenAccept(checked -> Minecraft.getInstance().execute(() -> {
            checking = false;
            var minecraft = Minecraft.getInstance();
            var endpoint = checked.endpoint();
            if (!GuestReconnect.active(reconnect) || minecraft.gui.screen() != this || endpoint == null || endpoint.epoch() < reconnect.minimumEpoch) return;
            if (checked.changed()) {
                reconnect.cancel();
                minecraft.gui.setScreen(reconnect.parent);
                if (reconnect.parent instanceof HostHopScreen screen) screen.compatibilityChanged();
                return;
            }
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
