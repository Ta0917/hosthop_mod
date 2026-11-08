package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.DriveCredentialManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLPaths;

/** Client entry point for registering an invitation and joining an active host. */
@OnlyIn(Dist.CLIENT)
public final class HostHopScreen extends Screen {
    private final Screen parent;
    private final AtomicLong request = new AtomicLong();
    private Component status = Component.translatable("screen.hosthop.initial");
    private Button register, authorize, join;
    private volatile DriveCredentialManager activeAuthorization;

    public HostHopScreen(Screen parent) { super(Component.translatable("screen.hosthop.title")); this.parent = parent; }

    @Override protected void init() {
        int x = width / 2 - 105;
        register = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.register"), button -> registerInvitation())
                .bounds(x, height / 2 - 35, 210, 20).build());
        authorize = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.authorize"), button -> authorizeDrive())
                .bounds(x, height / 2 - 9, 210, 20).build());
        join = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.join"), button -> joinHost())
                .bounds(x, height / 2 + 17, 210, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height / 2 + 50, 210, 20).build());
    }

    private void authorizeDrive() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.authorizing");
        CompletableFuture.runAsync(() -> {
            try {
                var vault = new WindowsSecretStore(secretPath());
                var invitation = Invitation.fromSecrets(vault.load());
                var authorization = new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret());
                var manager = new DriveCredentialManager(vault, authorization);
                activeAuthorization = manager;
                manager.authorize(invitation.folder(), uri -> Minecraft.getInstance().execute(() -> {
                    if (id == request.get() && Minecraft.getInstance().gui.screen() == this) Util.getPlatform().openUri(uri);
                    else manager.cancelAuthorization();
                }));
                finish(id, "screen.hosthop.authorized");
            } catch (Exception failure) { finish(id, "screen.hosthop.authorize_failed"); }
            finally { activeAuthorization = null; }
        });
    }

    private static Path secretPath() { return FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"); }

    private void registerInvitation() {
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.saving");
        CompletableFuture.runAsync(() -> {
            try {
                var invitation = Invitation.decode(clipboard);
                Path path = secretPath();
                Files.createDirectories(path.getParent());
                var vault = new WindowsSecretStore(path);
                if (vault.exists()) {
                    var existing = Invitation.fromSecrets(vault.load());
                    if (!existing.secrets().equals(invitation.secrets())) throw new IllegalStateException("Already configured");
                } else vault.save(invitation.secrets());
                finish(id, "screen.hosthop.registered");
            } catch (Exception failure) { finish(id, "screen.hosthop.register_failed"); }
        });
    }

    private void joinHost() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.connecting");
        CompletableFuture.runAsync(() -> {
            try {
                var invitation = Invitation.fromSecrets(new WindowsSecretStore(secretPath()).load());
                var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
                var endpoint = authority.endpoint(invitation.world(), 2);
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    if (id != request.get() || minecraft.gui.screen() != this) return;
                    if (!endpoint.deadline().isUsable(System.nanoTime(), System.currentTimeMillis())) {
                        busy(false, "screen.hosthop.join_failed");
                        return;
                    }
                    var server = new ServerData("HostHop " + invitation.world(), endpoint.address(), ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(endpoint.address()), server, false, null);
                });
            } catch (Exception failure) { finish(id, "screen.hosthop.join_failed"); }
        });
    }

    private void finish(long id, String message) {
        Minecraft.getInstance().execute(() -> {
            if (id == request.get() && Minecraft.getInstance().gui.screen() == this) busy(false, message);
        });
    }
    private void busy(boolean value, String message) { register.active = !value; authorize.active = !value; join.active = !value; status = Component.translatable(message); }

    @Override public void onClose() {
        request.incrementAndGet();
        var authorization = activeAuthorization;
        if (authorization != null) authorization.cancelAuthorization();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 85, -1);
        graphics.centeredText(font, status, width / 2, height / 2 - 60, -1);
    }
}
