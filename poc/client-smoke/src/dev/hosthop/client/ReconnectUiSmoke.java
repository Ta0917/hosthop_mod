package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.ConservativeLeaseDeadline;
import dev.hosthop.lease.LeaseAuthorityClient;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Exercises the actual client screen event and mixin without connecting to a server. */
public final class ReconnectUiSmoke {
    public static void verify(Screen parent) throws Exception {
        var invite = Invitation.fromSecrets(Map.of("authority", "https://example.invalid", "world", "world", "principal", "owner",
                "member_token", "a".repeat(43), "oauth_client_id", "client", "oauth_client_secret", "secret", "drive_folder", "folder"));
        var authority = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken());
        long now = System.nanoTime(), millis = System.currentTimeMillis();
        var endpoint = new LeaseAuthorityClient.Endpoint("world", 1, 0, 1, "relay.us.e4mc.link",
                ConservativeLeaseDeadline.fromGrant(now, millis, 100, 130, 2));
        Field current = GuestReconnect.class.getDeclaredField("current"); current.setAccessible(true);
        Field loggedOut = GuestReconnect.class.getDeclaredField("loggedOut"); loggedOut.setAccessible(true);
        GuestReconnect.track(invite, authority, endpoint, parent);
        loggedOut.setBoolean(current.get(null), true);
        var disconnect = new DisconnectedScreen(parent, Component.literal("Disconnected"), Component.translatable("disconnect.endOfStream"));
        var opening = new ScreenEvent.Opening(parent, disconnect);
        GuestReconnect.screenOpening(opening);
        if (!(opening.getNewScreen() instanceof HostHopReconnectScreen)) throw new AssertionError("Transient disconnect did not start waiting");
        var tracked = (GuestReconnect) current.get(null);
        var refused = (net.minecraft.network.chat.contents.TranslatableContents) Component.translatable("disconnect.genericReason", "Connection refused").getContents();
        var rejected = (net.minecraft.network.chat.contents.TranslatableContents) Component.translatable("disconnect.genericReason", "Authentication failed").getContents();
        if (!GuestReconnect.transientReason(refused, true) || GuestReconnect.transientReason(refused, false)
                || GuestReconnect.transientReason(rejected, true)) throw new AssertionError("Connection errors classified incorrectly");
        var attempt = tracked.controller.connect(tracked.session, endpoint, System.nanoTime(), System.currentTimeMillis());
        if (!tracked.controller.connected(tracked.session, attempt)) throw new AssertionError("Reconnect was not accepted");
        loggedOut.setBoolean(tracked, true);
        var shutdown = new DisconnectedScreen(parent, Component.literal("Disconnected"), Component.translatable("multiplayer.disconnect.server_shutdown"));
        var secondOpening = new ScreenEvent.Opening(parent, shutdown);
        GuestReconnect.screenOpening(secondOpening);
        if (!(secondOpening.getNewScreen() instanceof HostHopReconnectScreen) || tracked.minimumEpoch != 2)
            throw new AssertionError("Second disconnect did not wait for the next host");
        tracked.cancel();
        GuestReconnect.track(invite, authority, endpoint, parent);
        loggedOut.setBoolean(current.get(null), true);
        var kicked = new DisconnectedScreen(parent, Component.literal("Disconnected"), Component.translatable("multiplayer.disconnect.banned"));
        var kickOpening = new ScreenEvent.Opening(parent, kicked);
        GuestReconnect.screenOpening(kickOpening);
        if (kickOpening.getNewScreen() != kicked || current.get(null) != null) throw new AssertionError("Kick must not reconnect");
        System.out.println("HOSTHOP_UI_SMOKE transient disconnect waits; kick remains visible=true");
    }
}
