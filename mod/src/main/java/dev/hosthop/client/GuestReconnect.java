package dev.hosthop.client;

import dev.hosthop.HostHop;
import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.reconnect.ReconnectController;
import dev.hosthop.relay.mixin.DisconnectedScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Tracks only connections explicitly started from the HostHop screen. */
@EventBusSubscriber(modid = HostHop.MOD_ID, value = Dist.CLIENT)
public final class GuestReconnect {
    private static GuestReconnect current;
    final Invitation invitation;
    final LeaseAuthorityClient authority;
    final Screen parent;
    ReconnectController controller = new ReconnectController();
    ReconnectController.Session session;
    ReconnectController.Attempt attempt;
    private String address;
    private long epoch;
    long minimumEpoch;
    private Connection connection;
    private boolean loggedOut, reconnecting;
    private long waitStarted;
    /** Differences the player already saw and joined with; a reconnect stops only on new ones. */
    final java.util.Set<String> acceptedMods, acceptedConfigs;

    private GuestReconnect(Invitation invitation, LeaseAuthorityClient authority, LeaseAuthorityClient.Endpoint endpoint, Screen parent,
                           java.util.Set<String> acceptedMods, java.util.Set<String> acceptedConfigs) {
        this.invitation = invitation; this.authority = authority; this.parent = parent;
        this.acceptedMods = java.util.Set.copyOf(acceptedMods); this.acceptedConfigs = java.util.Set.copyOf(acceptedConfigs);
        address = endpoint.address(); epoch = endpoint.epoch();
    }

    static void track(Invitation invitation, LeaseAuthorityClient authority, LeaseAuthorityClient.Endpoint endpoint, Screen parent,
                      java.util.Set<String> acceptedMods, java.util.Set<String> acceptedConfigs) {
        current = new GuestReconnect(invitation, authority, endpoint, parent, acceptedMods, acceptedConfigs);
    }
    static boolean active(GuestReconnect value) { return value != null && current == value; }
    long waitStarted() { return waitStarted; }
    void connecting(LeaseAuthorityClient.Endpoint endpoint, ReconnectController.Attempt attempt) {
        address = endpoint.address(); epoch = endpoint.epoch(); this.attempt = attempt; reconnecting = true;
    }
    void cancel() {
        if (session != null) controller.cancel(session);
        if (current == this) { current = null; GuestPrefetch.stop(); }
    }

    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        var value = current;
        if (value == null) return;
        var data = Minecraft.getInstance().getCurrentServer();
        if (data == null || !value.address.equals(data.ip)) { value.cancel(); return; }
        value.connection = event.getConnection(); value.loggedOut = false;
        if (value.reconnecting && !value.controller.connected(value.session, value.attempt)) { value.cancel(); return; }
        value.reconnecting = false; value.attempt = null;
        value.waitStarted = 0;
        GuestPrefetch.start(value.invitation, value.authority);
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        var value = current;
        if (value != null && value.connection != null && value.connection == event.getConnection()) value.loggedOut = true;
    }

    @SubscribeEvent public static void screenOpening(ScreenEvent.Opening event) {
        var value = current;
        if (value == null || (!value.loggedOut && !value.reconnecting)) return;
        var next = event.getNewScreen();
        if (!(next instanceof DisconnectedScreen disconnected)) {
            if (value.reconnecting && event.getCurrentScreen() instanceof ConnectScreen && next instanceof HostHopReconnectScreen) {
                value.cancel(); event.setNewScreen(value.parent);
            } else if (value.loggedOut || (value.reconnecting && event.getCurrentScreen() instanceof ConnectScreen)) value.cancel();
            return;
        }
        if (!(disconnected instanceof DisconnectedScreenAccessor accessor)
                || !(accessor.hosthop$details().reason().getContents() instanceof TranslatableContents reason)
                || !transientReason(reason, value.reconnecting && event.getCurrentScreen() instanceof ConnectScreen)) { value.cancel(); return; }
        if (value.reconnecting) {
            if (!value.controller.failed(value.session, value.attempt, ReconnectController.Failure.TRANSIENT, System.nanoTime())
                    || value.controller.state() == ReconnectController.State.STOPPED) { value.cancel(); return; }
        } else {
            value.waitStarted = System.nanoTime();
            value.controller = new ReconnectController();
            value.session = value.controller.start(value.invitation.world(), value.epoch, System.nanoTime());
            value.minimumEpoch = "multiplayer.disconnect.server_shutdown".equals(reason.getKey()) && value.epoch < Long.MAX_VALUE
                    ? value.epoch + 1 : value.epoch;
        }
        value.loggedOut = false; value.reconnecting = false; value.connection = null; value.attempt = null;
        event.setNewScreen(new HostHopReconnectScreen(value));
    }

    static boolean transientReason(String key) {
        return "disconnect.endOfStream".equals(key) || "disconnect.timeout".equals(key)
                || "multiplayer.disconnect.server_shutdown".equals(key);
    }

    static boolean transientReason(TranslatableContents reason, boolean connecting) {
        if (transientReason(reason.getKey())) return true;
        if (!connecting || !"disconnect.genericReason".equals(reason.getKey()) || reason.getArgs().length != 1
                || !(reason.getArgs()[0] instanceof String detail)) return false;
        String text = detail.toLowerCase(java.util.Locale.ROOT);
        return text.contains("connection refused") || text.contains("connection timed out")
                || text.contains("connection reset") || text.contains("network is unreachable")
                || text.contains("no route to host") || text.contains("unknown host");
    }
}
