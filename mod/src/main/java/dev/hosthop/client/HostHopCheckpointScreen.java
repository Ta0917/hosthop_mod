package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.world.BackupRestore;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Recent periodic checkpoints; restoring always creates a separate local world. */
public final class HostHopCheckpointScreen extends Screen {
    private final Screen parent;
    private List<LeaseAuthorityClient.Checkpoint> checkpoints = List.of();
    private Component status = Component.translatable("screen.hosthop.history_loading");
    private boolean requested;
    private int page;
    HostHopCheckpointScreen(Screen parent) { super(Component.translatable("screen.hosthop.checkpoint_history")); this.parent = parent; }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose()).bounds(width / 2 - 100, height / 2 + 84, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.retention_settings"),
                button -> Minecraft.getInstance().gui.setScreen(new HostHopBackupSettingsScreen(this)))
                .bounds(width / 2 + 30, height / 2 - 112, 90, 20).build());
        for (int i = 0; i < 4 && page * 4 + i < checkpoints.size(); i++) {
            var item = checkpoints.get(page * 4 + i);
            String time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(item.createdAt()));
            var label = Component.translatable("screen.hosthop.checkpoint_entry", time, item.kind().equals("full") ? "F" : "Δ", (item.object().size() + 1_048_575) / 1_048_576);
            addRenderableWidget(Button.builder(label, button -> restore(item)).bounds(width / 2 - 100, height / 2 - 42 + i * 24, 105, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.history_restore_shared"),
                    button -> BackupActions.restoreShared(this, null, item, value -> status = value))
                    .bounds(width / 2 + 8, height / 2 - 42 + i * 24, 44, 20).build());
            var delete = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.checkpoint_delete"), button -> delete(item))
                    .bounds(width / 2 + 56, height / 2 - 42 + i * 24, 44, 20).build());
            delete.active = item.kind().equals("delta");
        }
        var previous = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.previous"), button -> { page--; rebuildWidgets(); }).bounds(width / 2 - 100, height / 2 + 58, 96, 20).build());
        var next = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.next"), button -> { page++; rebuildWidgets(); }).bounds(width / 2 + 4, height / 2 + 58, 96, 20).build());
        previous.active = page > 0; next.active = (page + 1) * 4 < checkpoints.size();
        if (requested) return;
        requested = true;
        CompletableFuture.runAsync(() -> {
            try {
                var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
                var invite = Invitation.fromSecrets(vault.load());
                var found = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken()).checkpointHistory(invite.world());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().gui.screen() != this) return;
                    checkpoints = found; status = Component.translatable(found.isEmpty() ? "screen.hosthop.history_empty" : "screen.hosthop.history_note");
                    rebuildWidgets();
                });
            } catch (Exception failure) { Minecraft.getInstance().execute(() -> { if (Minecraft.getInstance().gui.screen() == this) status = Component.translatable("screen.hosthop.history_failed"); }); }
        });
    }

    private void restore(LeaseAuthorityClient.Checkpoint checkpoint) {
        var minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
            minecraft.gui.setScreen(this);
            if (!accepted) return;
            status = Component.translatable("screen.hosthop.history_restoring");
            CompletableFuture.runAsync(() -> {
                try {
                    var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
                    var invitation = Invitation.fromSecrets(vault.load());
                    var credentials = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
                    var drive = new GoogleDriveSnapshotStore();
                    String name = BackupRestore.restore(checkpoint, drive, credentials.access().accessToken(), minecraft.gameDirectory.toPath(),
                            BackupActions.accessGrant(credentials, drive, invitation.folder(), this, message -> status = message));
                    minecraft.execute(() -> { if (minecraft.gui.screen() == this) status = Component.translatable("screen.hosthop.history_restored", name); });
                } catch (Exception failure) { minecraft.execute(() -> { if (minecraft.gui.screen() == this) status = Component.translatable("screen.hosthop.history_restore_failed"); }); }
            });
        }, Component.translatable("screen.hosthop.history_restore_title"), Component.translatable("screen.hosthop.history_restore_warning")));
    }
    private void delete(LeaseAuthorityClient.Checkpoint checkpoint) {
        var minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
            minecraft.gui.setScreen(this);
            if (!accepted) return;
            status = Component.translatable("screen.hosthop.checkpoint_deleting");
            var guard = new dev.hosthop.world.SaveKeepAlive();
            try { CompletableFuture.runAsync(() -> {
                boolean removed = false, trashed = false;
                try {
                    var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
                    var invitation = Invitation.fromSecrets(vault.load());
                    var credentials = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
                    String token = credentials.access().accessToken();
                    var drive = new GoogleDriveSnapshotStore();
                    if (!drive.canUseBase(checkpoint.object(), invitation.folder(), token)) throw new java.io.IOException("Checkpoint unavailable");
                    var authority = new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken());
                    try { authority.deleteCheckpoint(invitation.world(), checkpoint); }
                    catch (java.io.IOException first) { authority.deleteCheckpoint(invitation.world(), checkpoint); }
                    removed = true;
                    drive.trash(checkpoint.object(), invitation.folder(), token);
                    authority.checkpointTrashDone(invitation.world(), checkpoint);
                    trashed = true;
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                } finally { guard.close(); }
                boolean done = removed, cleaned = trashed;
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() != this) return;
                    if (done) { checkpoints = checkpoints.stream().filter(item -> !item.id().equals(checkpoint.id())).toList(); page = Math.min(page, Math.max(0, (checkpoints.size() - 1) / 4)); rebuildWidgets(); }
                    status = Component.translatable(done ? cleaned ? "screen.hosthop.checkpoint_deleted" : "screen.hosthop.checkpoint_delete_cleanup_failed" : "screen.hosthop.checkpoint_delete_failed");
                });
            }); } catch (RuntimeException failure) { guard.close(); throw failure; }
        }, Component.translatable("screen.hosthop.checkpoint_delete_title"), Component.translatable("screen.hosthop.checkpoint_delete_warning")));
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 88, -1);
        var lines = font.split(status, Math.max(1, width - 12));
        for (int i = 0; i < Math.min(lines.size(), 2); i++) graphics.centeredText(font, lines.get(i), width / 2, height / 2 - 65 + i * 10, -1);
    }
}
