package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.world.BackupRestore;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Recent committed snapshots can be restored to a separate local world. */
public final class HostHopHistoryScreen extends Screen {
    private final Screen parent;
    private List<LeaseAuthorityClient.Snapshot> snapshots = List.of();
    private Component status = Component.translatable("screen.hosthop.history_loading");
    private boolean requested;

    HostHopHistoryScreen(Screen parent) { super(Component.translatable("screen.hosthop.history_title")); this.parent = parent; }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 100, height / 2 + 70, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.checkpoint_history"),
                button -> Minecraft.getInstance().gui.setScreen(new HostHopCheckpointScreen(this)))
                .bounds(width / 2 + 25, height / 2 - 112, 95, 20).build());
        for (int i = 0; i < snapshots.size(); i++) {
            var snapshot = snapshots.get(i);
            long mib = (snapshot.object().size() + 1_048_575) / 1_048_576;
            addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.history_entry", snapshot.generation(), mib), button -> restore(snapshot))
                    .bounds(width / 2 - 100, height / 2 - 39 + i * 24, 143, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.history_restore_shared"),
                    button -> BackupActions.restoreShared(this, snapshot, null, value -> status = value))
                    .bounds(width / 2 + 47, height / 2 - 39 + i * 24, 53, 20).build());
        }
        if (requested) return;
        requested = true;
        CompletableFuture.runAsync(() -> {
            try {
                var path = FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat");
                var invite = Invitation.fromSecrets(new WindowsSecretStore(path).load());
                var result = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken()).history(invite.world());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().gui.screen() != this) return;
                    snapshots = result;
                    status = Component.translatable(result.isEmpty() ? "screen.hosthop.history_empty" : "screen.hosthop.history_note");
                    rebuildWidgets();
                });
            } catch (Exception failure) {
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().gui.screen() == this) status = Component.translatable("screen.hosthop.history_failed");
                });
            }
        });
    }
    private void restore(LeaseAuthorityClient.Snapshot snapshot) {
        var minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
            minecraft.gui.setScreen(this);
            if (!accepted) return;
            status = Component.translatable("screen.hosthop.history_restoring");
            CompletableFuture.runAsync(() -> {
                try {
                    var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
                    var invite = Invitation.fromSecrets(vault.load());
                    var credentials = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invite.oauthClientId(), invite.oauthClientSecret()));
                    var drive = new GoogleDriveSnapshotStore();
                    String name = BackupRestore.restore(snapshot, drive, credentials.access().accessToken(), minecraft.gameDirectory.toPath(),
                            BackupActions.accessGrant(credentials, drive, invite.folder(), this, message -> status = message));
                    minecraft.execute(() -> { if (minecraft.gui.screen() == this) status = Component.translatable("screen.hosthop.history_restored", name); });
                } catch (Exception failure) {
                    minecraft.execute(() -> { if (minecraft.gui.screen() == this) status = Component.translatable("screen.hosthop.history_restore_failed"); });
                }
            });
        }, Component.translatable("screen.hosthop.history_restore_title"), Component.translatable("screen.hosthop.history_restore_warning")));
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 88, -1);
        var lines = font.split(status, Math.max(1, width - 12));
        for (int i = 0; i < Math.min(lines.size(), 2); i++) graphics.centeredText(font, lines.get(i), width / 2, height / 2 - 65 + i * 10, -1);
    }
}
