package dev.hosthop.client;

import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.storage.WindowsSecretStore;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Shared retention policy; full snapshots remain protected as delta bases. */
public final class HostHopBackupSettingsScreen extends Screen {
    private final Screen parent;
    private int original = 4, keep = 4;
    private boolean requested, loaded, saving;
    private Component status = Component.translatable("screen.hosthop.history_loading");
    HostHopBackupSettingsScreen(Screen parent) { super(Component.translatable("screen.hosthop.retention_settings")); this.parent = parent; }

    @Override protected void init() {
        var minus = addRenderableWidget(Button.builder(Component.literal("−"), button -> { keep--; rebuildWidgets(); })
                .bounds(width / 2 - 90, height / 2 - 20, 55, 20).build());
        var plus = addRenderableWidget(Button.builder(Component.literal("+"), button -> { keep++; rebuildWidgets(); })
                .bounds(width / 2 + 35, height / 2 - 20, 55, 20).build());
        var save = addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.retention_save"), button -> save())
                .bounds(width / 2 - 100, height / 2 + 24, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 100, height / 2 + 50, 200, 20).build());
        minus.active = loaded && !saving && keep > 1;
        plus.active = loaded && !saving && keep < 28;
        save.active = loaded && !saving && keep != original;
        if (requested) return;
        requested = true;
        CompletableFuture.runAsync(() -> {
            try {
                var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
                var invite = Invitation.fromSecrets(vault.load());
                int value = new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken()).checkpointRetention(invite.world());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().gui.screen() != this) return;
                    original = keep = value; loaded = true; status = Component.translatable("screen.hosthop.retention_note"); rebuildWidgets();
                });
            } catch (Exception failure) { Minecraft.getInstance().execute(() -> { if (Minecraft.getInstance().gui.screen() == this) status = Component.translatable("screen.hosthop.history_failed"); }); }
        });
    }
    private void save() {
        if (keep < original) {
            var minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new ConfirmScreen(accepted -> { minecraft.gui.setScreen(this); if (accepted) persist(); },
                    Component.translatable("screen.hosthop.retention_confirm_title"), Component.translatable("screen.hosthop.retention_confirm_warning")));
        } else persist();
    }
    private void persist() {
        saving = true; status = Component.translatable("screen.hosthop.saving"); rebuildWidgets();
        int selected = keep;
        CompletableFuture.runAsync(() -> {
            try {
                var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
                var invite = Invitation.fromSecrets(vault.load());
                new LeaseAuthorityClient(URI.create(invite.authority()), invite.memberToken()).setCheckpointRetention(invite.world(), selected);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().gui.screen() != this) return;
                    original = selected; saving = false; status = Component.translatable("screen.hosthop.retention_saved"); rebuildWidgets();
                });
            } catch (Exception failure) { Minecraft.getInstance().execute(() -> { if (Minecraft.getInstance().gui.screen() == this) { saving = false; status = Component.translatable("screen.hosthop.history_failed"); rebuildWidgets(); } }); }
        });
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 84, -1);
        var lines = font.split(status, Math.max(1, width - 16));
        for (int i = 0; i < Math.min(lines.size(), 3); i++) graphics.centeredText(font, lines.get(i), width / 2, height / 2 - 67 + i * 10, -1);
        graphics.centeredText(font, Component.translatable("screen.hosthop.retention_count", keep), width / 2, height / 2 - 15, -1);
    }
}
