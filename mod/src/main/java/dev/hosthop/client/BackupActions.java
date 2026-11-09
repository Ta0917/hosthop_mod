package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.WindowsSecretStore;
import dev.hosthop.world.SaveKeepAlive;
import dev.hosthop.world.SharedBackupRestore;
import dev.hosthop.world.BackupRestore;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

final class BackupActions {
    private BackupActions() {}

    static void restoreShared(Screen parent, LeaseAuthorityClient.Snapshot snapshot, LeaseAuthorityClient.Checkpoint checkpoint,
                              Consumer<Component> status) {
        var minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
            minecraft.gui.setScreen(parent);
            if (!accepted) return;
            status.accept(Component.translatable("screen.hosthop.history_restoring_shared"));
            var guard = new SaveKeepAlive();
            try {
                CompletableFuture.runAsync(() -> {
                    String result = "screen.hosthop.history_restore_shared_failed";
                    try {
                        var config = FMLPaths.CONFIGDIR.get().resolve("hosthop");
                        var vault = new WindowsSecretStore(config.resolve("credentials.dat"));
                        var invitation = Invitation.fromSecrets(vault.load());
                        var credentials = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
                        var drive = new GoogleDriveSnapshotStore();
                        SharedBackupRestore.restore(invitation, new LeaseAuthorityClient(URI.create(invitation.authority()), invitation.memberToken()),
                                drive, credentials.access().accessToken(), minecraft.gameDirectory.toPath(), config, snapshot, checkpoint,
                                accessGrant(credentials, drive, invitation.folder(), parent, status));
                        result = "screen.hosthop.history_restored_shared";
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    } finally { guard.close(); }
                    String key = result;
                    minecraft.execute(() -> { if (minecraft.gui.screen() == parent) status.accept(Component.translatable(key)); });
                });
            } catch (RuntimeException failure) { guard.close(); throw failure; }
        }, Component.translatable("screen.hosthop.history_restore_shared_title"), Component.translatable("screen.hosthop.history_restore_shared_warning")));
    }

    static BackupRestore.AccessGrant accessGrant(DriveCredentialManager credentials, GoogleDriveSnapshotStore drive, String folder,
                                                  Screen screen, Consumer<Component> status) {
        var minecraft = Minecraft.getInstance();
        return (object, token) -> {
            if (drive.canRead(object, token)) return token;
            minecraft.execute(() -> { if (minecraft.gui.screen() == screen) status.accept(Component.translatable("screen.hosthop.history_authorize_file")); });
            credentials.authorizeFile(object.fileId(), folder, uri -> minecraft.execute(() -> {
                if (minecraft.gui.screen() == screen) net.minecraft.util.Util.getPlatform().openUri(uri);
                else credentials.cancelAuthorization();
            }));
            return credentials.access().accessToken();
        };
    }
}
