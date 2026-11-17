package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.distribution.LocalDistribution;
import dev.hosthop.distribution.ModDistribution;
import dev.hosthop.distribution.ModManifest;
import dev.hosthop.distribution.PublisherKey;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.WindowsSecretStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.neoforged.fml.loading.FMLPaths;

/** Registering the publisher's key, checking for updates and, for the publisher, distributing mods. */
final class HostHopModsScreen extends Screen {
    private final Screen parent;
    private final AtomicLong request = new AtomicLong();
    private Component status = Component.translatable("screen.hosthop.mods_initial");
    private Button registerKey, copyKey, check, openFolder, publish;
    private volatile DriveCredentialManager activeAuthorization;

    HostHopModsScreen(Screen parent) { super(Component.translatable("screen.hosthop.mods_title")); this.parent = parent; }

    @Override protected void init() {
        int x = width / 2 - 105;
        registerKey = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.mods_register_key"), button -> registerKey())
                .bounds(x, height / 2 - 40, 210, 20).build());
        check = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.mods_check"), button -> check())
                .bounds(x, height / 2 - 14, 210, 20).build());
        copyKey = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.mods_copy_key"), button -> copyKey())
                .bounds(x, height / 2 + 22, 210, 20).build());
        openFolder = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.mods_open_folder"), button -> openFolder())
                .bounds(x, height / 2 + 48, 103, 20).build());
        publish = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.mods_publish"), button -> confirmPublish())
                .bounds(x + 107, height / 2 + 48, 103, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(x, height / 2 + 84, 210, 20).build());
        try {
            var key = ModSync.local().trustedKey();
            if (key.isPresent()) status = Component.translatable("screen.hosthop.mods_key_registered", PublisherKey.fingerprint(key.get()));
        } catch (IOException invalid) { status = Component.translatable("screen.hosthop.mods_state_invalid"); }
    }

    private static Invitation invitation() throws IOException {
        return Invitation.fromSecrets(new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat")).load());
    }

    private static DriveCredentialManager credentials(Invitation invitation) {
        return new DriveCredentialManager(new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat")),
                new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
    }

    private java.util.function.Consumer<URI> browser(long id, DriveCredentialManager manager) {
        return uri -> Minecraft.getInstance().execute(() -> {
            if (id == request.get() && Minecraft.getInstance().gui.screen() == this) {
                busy(true, "screen.hosthop.mods_authorizing");
                Util.getPlatform().openUri(uri);
            } else manager.cancelAuthorization();
        });
    }

    private void registerKey() {
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.saving");
        CompletableFuture.runAsync(() -> {
            try {
                var key = ModSync.local().trust(clipboard);
                LocalDistribution.installLoader(FMLPaths.MODSDIR.get());
                finish(id, Component.translatable("screen.hosthop.mods_key_registered", PublisherKey.fingerprint(key)));
            } catch (LocalDistribution.KeyConflict conflict) { finish(id, Component.translatable("screen.hosthop.mods_key_conflict"));
            } catch (Exception failure) { finish(id, Component.translatable("screen.hosthop.mods_key_invalid")); }
        });
    }

    /** For the publisher: creates the key on first use, trusts it here too, and copies the public part to share. */
    private void copyKey() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.saving");
        CompletableFuture.runAsync(() -> {
            try {
                var pair = ModSync.ownKey();
                String code = PublisherKey.encodePublic(pair.getPublic());
                ModSync.local().trust(code);
                LocalDistribution.installLoader(FMLPaths.MODSDIR.get());
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().keyboardHandler.setClipboard(code));
                finish(id, Component.translatable("screen.hosthop.mods_key_copied", PublisherKey.fingerprint(pair.getPublic())));
            } catch (LocalDistribution.KeyConflict conflict) { finish(id, Component.translatable("screen.hosthop.mods_key_conflict"));
            } catch (Exception failure) { finish(id, Component.translatable("screen.hosthop.mods_key_failed")); }
        });
    }

    private void openFolder() {
        try {
            var folder = Files.createDirectories(ModSync.local().publishFolder());
            Util.getPlatform().openPath(folder);
        } catch (IOException failure) { busy(false, "screen.hosthop.mods_folder_failed"); }
    }

    private void confirmPublish() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.mods_reading_folder");
        CompletableFuture.runAsync(() -> {
            try {
                if (!ModSync.hasOwnKey()) { finish(id, Component.translatable("screen.hosthop.mods_no_own_key")); return; }
                var mods = ModDistribution.describeFolder(ModSync.local().publishFolder(), new java.util.HashMap<>());
                Minecraft.getInstance().execute(() -> {
                    var minecraft = Minecraft.getInstance();
                    if (id != request.get() || minecraft.gui.screen() != this) return;
                    minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
                        minecraft.gui.setScreen(this);
                        if (accepted) publish(); else busy(false, "screen.hosthop.mods_initial");
                    }, Component.translatable("screen.hosthop.mods_publish_title"), mods.isEmpty()
                            ? Component.translatable("screen.hosthop.mods_publish_empty")
                            : Component.translatable("screen.hosthop.mods_publish_confirm", list(mods))));
                });
            } catch (Exception failure) { finish(id, Component.translatable("screen.hosthop.mods_folder_invalid", failure.getMessage())); }
        });
    }

    private void publish() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.mods_publishing");
        CompletableFuture.runAsync(() -> {
            try {
                var invitation = invitation();
                var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
                var pair = ModSync.ownKey();
                var local = ModSync.local();
                local.trust(PublisherKey.encodePublic(pair.getPublic()));
                LocalDistribution.installLoader(FMLPaths.MODSDIR.get());
                var manifest = ModDistribution.publish(local, invitation, authority, credentials(invitation), pair.getPrivate(), pair.getPublic());
                finish(id, Component.translatable(local.restartRequired() ? "screen.hosthop.mods_published_restart" : "screen.hosthop.mods_published",
                        manifest.mods().size(), manifest.revision()));
            } catch (LeaseAuthorityClient.NotPublisher denied) { finish(id, Component.translatable("screen.hosthop.mods_not_publisher"));
            } catch (LocalDistribution.KeyConflict conflict) { finish(id, Component.translatable("screen.hosthop.mods_key_conflict"));
            } catch (DriveCredentialManager.AuthorizationRequired failure) { finish(id, Component.translatable("screen.hosthop.authorize_first"));
            } catch (Exception failure) {
                com.mojang.logging.LogUtils.getLogger().warn("HostHop mod publication failed", failure);
                finish(id, Component.translatable("screen.hosthop.mods_publish_failed"));
            }
        });
    }

    private void check() {
        long id = request.incrementAndGet();
        busy(true, "screen.hosthop.mods_checking");
        CompletableFuture.runAsync(() -> {
            try {
                var invitation = invitation();
                var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
                var manager = credentials(invitation);
                activeAuthorization = manager;
                var outcome = ModDistribution.sync(ModSync.local(), invitation, authority, manager, browser(id, manager), FMLPaths.MODSDIR.get());
                if (outcome instanceof ModDistribution.NeedsConsent needs) {
                    Minecraft.getInstance().execute(() -> {
                        if (id != request.get() || Minecraft.getInstance().gui.screen() != this) return;
                        Minecraft.getInstance().gui.setScreen(ModSync.consent(this, needs.pending(), needs.fingerprint(), () -> {
                            try { ModSync.local().consent(needs.pending().stream().map(ModManifest.Entry::id).toList()); check(); }
                            catch (IOException failure) { busy(false, "screen.hosthop.mods_state_invalid"); }
                        }, () -> busy(false, "screen.hosthop.mods_declined")));
                    });
                    return;
                }
                finish(id, describe(outcome));
            } catch (Exception failure) { finish(id, failureMessage(failure)); }
            finally { activeAuthorization = null; }
        });
    }

    static Component describe(ModDistribution.Outcome outcome) {
        return switch (outcome) {
            case ModDistribution.NotConfigured ignored -> Component.translatable("screen.hosthop.mods_not_configured");
            case ModDistribution.NothingPublished ignored -> Component.translatable("screen.hosthop.mods_nothing");
            case ModDistribution.UpToDate current -> Component.translatable("screen.hosthop.mods_up_to_date", current.mods().size());
            case ModDistribution.RestartRequired restart -> Component.translatable("screen.hosthop.mods_restart");
            case ModDistribution.Duplicates duplicates -> Component.translatable("screen.hosthop.mods_duplicates", String.join(", ", duplicates.files()));
            case ModDistribution.NeedsConsent needs -> Component.translatable("screen.hosthop.mods_declined");
        };
    }

    static Component failureMessage(Exception failure) {
        if (failure instanceof LocalDistribution.Untrusted) return Component.translatable("screen.hosthop.mods_untrusted");
        if (failure instanceof LocalDistribution.RolledBack) return Component.translatable("screen.hosthop.mods_rolled_back");
        if (failure instanceof DriveCredentialManager.AuthorizationRequired) return Component.translatable("screen.hosthop.authorize_first");
        if (failure instanceof DriveCredentialManager.ReauthorizationRequired) return Component.translatable("screen.hosthop.reauthorize_drive");
        com.mojang.logging.LogUtils.getLogger().warn("HostHop mod update check failed ({})", failure.getClass().getSimpleName());
        return Component.translatable("screen.hosthop.mods_check_failed");
    }

    private static Component list(List<ModManifest.Entry> mods) {
        var text = Component.empty();
        int shown = Math.min(5, mods.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) text.append(", ");
            text.append(mods.get(i).id() + " " + mods.get(i).version());
        }
        if (mods.size() > shown) text.append(Component.translatable("screen.hosthop.required_mod_more", mods.size() - shown));
        return text;
    }

    private void finish(long id, Component message) {
        Minecraft.getInstance().execute(() -> {
            if (id == request.get() && Minecraft.getInstance().gui.screen() == this) { busy(false, "screen.hosthop.mods_initial"); status = message; }
        });
    }

    private void busy(boolean value, String message) {
        registerKey.active = !value; copyKey.active = !value; check.active = !value; openFolder.active = !value; publish.active = !value;
        status = Component.translatable(message);
    }

    @Override public void onClose() {
        request.incrementAndGet();
        var authorization = activeAuthorization;
        if (authorization != null) authorization.cancelAuthorization();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 100, -1);
        var lines = font.split(status, Math.max(1, width - 12));
        for (int i = 0; i < Math.min(lines.size(), 4); i++) graphics.centeredText(font, lines.get(i), width / 2, height / 2 - 86 + i * 10, -1);
        graphics.centeredText(font, Component.translatable("screen.hosthop.mods_publisher_section"), width / 2, height / 2 + 10, 0xFFA0A0A0);
    }
}
