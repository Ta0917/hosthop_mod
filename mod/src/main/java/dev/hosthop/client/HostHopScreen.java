package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.snapshot.BinaryDelta;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.neoforged.fml.loading.FMLPaths;

/** Client entry point for registering an invitation and joining an active host. */
public final class HostHopScreen extends Screen {
    private final Screen parent;
    private final AtomicLong request = new AtomicLong();
    private Component status = Component.translatable("screen.hosthop.initial");
    private Button register, authorize, authorizeSnapshot, join, history;
    private EditBox initialWorld;
    private boolean startedHere;
    private boolean watchingSave;
    private volatile DriveCredentialManager activeAuthorization;

    public HostHopScreen(Screen parent) { super(Component.translatable("screen.hosthop.title")); this.parent = parent; }

    @Override protected void init() {
        int x = width / 2 - 105;
        initialWorld = addRenderableWidget(new EditBox(font, x, height / 2 - 70, 210, 20, Component.translatable("screen.hosthop.initial_world")));
        initialWorld.setMaxLength(128);
        initialWorld.setHint(Component.translatable("screen.hosthop.initial_world"));
        register = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.register"), button -> registerInvitation())
                .bounds(x, height / 2 - 44, 210, 20).build());
        authorize = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.authorize"), button -> authorizeDrive())
                .bounds(x, height / 2 - 18, 210, 20).build());
        authorizeSnapshot = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.authorize_snapshot"), button -> authorizeSnapshot())
                .bounds(x, height / 2 + 8, 210, 20).build());
        join = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.join"), button -> joinHost())
                .bounds(x, height / 2 + 34, 130, 20).build());
        history = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.history"), button -> Minecraft.getInstance().gui.setScreen(new HostHopHistoryScreen(this)))
                .bounds(x + 134, height / 2 + 34, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height / 2 + 62, 210, 20).build());
        status = Component.translatable(HostWorldRuntime.lastStatus());
        watchingSave = HostWorldRuntime.active() && HostWorldRuntime.lastStatus().equals("screen.hosthop.saving_world");
        if (watchingSave) busy(true, "screen.hosthop.saving_world");
    }

    @Override public void tick() {
        super.tick();
        if (watchingSave && !HostWorldRuntime.lastStatus().equals("screen.hosthop.saving_world")) {
            watchingSave = false;
            busy(false, HostWorldRuntime.lastStatus());
        }
    }

    private void authorizeSnapshot() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.authorizing_snapshot");
        CompletableFuture.runAsync(() -> {
            try {
                var vault = new WindowsSecretStore(secretPath());
                var invitation = Invitation.fromSecrets(vault.load());
                var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
                var snapshot = authority.snapshot(invitation.world());
                var manager = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
                activeAuthorization = manager;
                manager.authorizeFile(snapshot.object().fileId(), invitation.folder(), uri -> Minecraft.getInstance().execute(() -> {
                    if (id == request.get() && Minecraft.getInstance().gui.screen() == this) Util.getPlatform().openUri(uri);
                    else manager.cancelAuthorization();
                }));
                var store = new GoogleDriveSnapshotStore();
                var token = manager.access().accessToken();
                if (store.isDelta(snapshot.object(), token)) {
                    Path sessions = Minecraft.getInstance().gameDirectory.toPath().resolve("hosthop").resolve("sessions");
                    Files.createDirectories(sessions);
                    Path cached = Files.createTempDirectory(sessions, "authorization-").resolve("remote-object.bin");
                    store.downloadRaw(snapshot.object(), cached, token);
                    var base = BinaryDelta.inspect(cached).base();
                    if (!store.canRead(base, token)) {
                        Minecraft.getInstance().execute(() -> {
                            if (id == request.get() && Minecraft.getInstance().gui.screen() == this) busy(true, "screen.hosthop.authorizing_base");
                        });
                        manager.authorizeFile(base.fileId(), invitation.folder(), uri -> Minecraft.getInstance().execute(() -> {
                            if (id == request.get() && Minecraft.getInstance().gui.screen() == this) Util.getPlatform().openUri(uri);
                            else manager.cancelAuthorization();
                        }));
                    }
                }
                finish(id, "screen.hosthop.snapshot_authorized");
            } catch (DriveCredentialManager.AccountMismatch failure) { finish(id, "screen.hosthop.account_mismatch"); }
            catch (Exception failure) { finish(id, "screen.hosthop.snapshot_authorize_failed"); }
            finally { activeAuthorization = null; }
        });
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
        String initial = initialWorld.getValue();
        busy(true, "screen.hosthop.connecting");
        CompletableFuture.runAsync(() -> {
            try {
                var invitation = Invitation.fromSecrets(new WindowsSecretStore(secretPath()).load());
                var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
                var host = authority.hostStatus(invitation.world(), 2);
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    if (id != request.get() || minecraft.gui.screen() != this) return;
                    // A previous local pre-launch attempt may still own the lease.
                    // Let the runtime inspect its protected journal before treating
                    // an unpublished host as an indefinitely running remote host.
                    boolean localPending = Files.exists(secretPath().getParent().resolve("pending-host.dat"));
                    if (!host.held() || (host.endpoint() == null && localPending)) {
                        var vault = new WindowsSecretStore(secretPath());
                        var authorization = new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret());
                        var credentials = new DriveCredentialManager(vault, authorization);
                        startedHere = HostWorldRuntime.start(invitation, credentials, initial, message -> {
                            if (minecraft.gui.screen() == this) busy(message.equals("screen.hosthop.preparing") || message.equals("screen.hosthop.saving_world"), message);
                        });
                        if (!startedHere) busy(false, "screen.hosthop.already_active");
                        return;
                    }
                    var endpoint = host.endpoint();
                    if (endpoint == null) { busy(false, "screen.hosthop.host_waiting"); return; }
                    if (!endpoint.deadline().isUsable(System.nanoTime(), System.currentTimeMillis())) {
                        busy(false, "screen.hosthop.join_failed");
                        return;
                    }
                    var server = new ServerData("HostHop " + invitation.world(), endpoint.address(), ServerData.Type.OTHER);
                    GuestReconnect.track(invitation, authority, endpoint, this);
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
    private void busy(boolean value, String message) { register.active = !value; authorize.active = !value; authorizeSnapshot.active = !value; join.active = !value; history.active = !value; initialWorld.active = !value; status = Component.translatable(message); }

    @Override public void onClose() {
        request.incrementAndGet();
        if (startedHere) HostWorldRuntime.cancelPreparing();
        var authorization = activeAuthorization;
        if (authorization != null) authorization.cancelAuthorization();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        String version = net.neoforged.fml.ModList.get().getModContainerById("hosthop")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("?");
        graphics.centeredText(font, Component.translatable("screen.hosthop.version", version), width / 2, 12, -1);
        graphics.centeredText(font, title, width / 2, height / 2 - 117, -1);
        var saving = HostWorldRuntime.lastStatus().equals("screen.hosthop.saving_world") ? HostHopStatusOverlay.message() : null;
        graphics.centeredText(font, saving == null ? status : saving, width / 2, height / 2 - 96, -1);
    }
}
