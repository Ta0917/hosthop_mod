package dev.hosthop.client;

import dev.hosthop.distribution.LocalDistribution;
import dev.hosthop.distribution.ModManifest;
import dev.hosthop.distribution.PublisherKey;
import dev.hosthop.storage.WindowsSecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Client glue for mod distribution: local paths, the publisher's own key and the consent prompt. */
final class ModSync {
    private ModSync() {}

    static LocalDistribution local() { return new LocalDistribution(FMLPaths.GAMEDIR.get()); }

    /** Runs while the game starts: notes what the loader loaded and refreshes the loader if distribution is in use. */
    static void onStartup() {
        var local = local();
        local.recordStartup(FMLPaths.MODSDIR.get());
        try {
            if (local.trustedKey().isPresent()) LocalDistribution.installLoader(FMLPaths.MODSDIR.get());
        } catch (IOException | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("HostHop could not check the distributed mod loader ({})", failure.getClass().getSimpleName());
        }
    }

    private static Path keyPath() { return FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("publisher-key.dat"); }

    static boolean hasOwnKey() { return Files.exists(keyPath()); }

    /** The publisher's key pair, created on first use and kept encrypted for this Windows account. */
    static KeyPair ownKey() throws IOException {
        var vault = new WindowsSecretStore(keyPath());
        if (vault.exists()) {
            var secrets = vault.load();
            return new KeyPair(PublisherKey.decodePublic(secrets.get("public")), PublisherKey.decodePrivate(secrets.get("private")));
        }
        var pair = PublisherKey.generate();
        Files.createDirectories(keyPath().getParent());
        vault.save(Map.of("public", PublisherKey.encodePublic(pair.getPublic()), "private", PublisherKey.encodePrivate(pair.getPrivate())));
        return pair;
    }

    /** Asks before running mods this player has not accepted yet; accept receives the IDs to remember. */
    static Screen consent(Screen parent, List<ModManifest.Entry> pending, String fingerprint, Runnable accepted, Runnable declined) {
        var list = Component.empty();
        int shown = Math.min(6, pending.size());
        for (int i = 0; i < shown; i++) {
            var mod = pending.get(i);
            list.append(Component.translatable("screen.hosthop.mods_consent_entry", mod.id(), mod.version(), mod.file()));
            list.append("\n");
        }
        if (pending.size() > shown) list.append(Component.translatable("screen.hosthop.required_mod_more", pending.size() - shown)).append("\n");
        var message = Component.translatable("screen.hosthop.mods_consent_message", list, fingerprint);
        return new ConfirmScreen(yes -> {
            Minecraft.getInstance().gui.setScreen(parent);
            if (yes) accepted.run(); else declined.run();
        }, Component.translatable("screen.hosthop.mods_consent_title"), message,
                Component.translatable("screen.hosthop.mods_consent_accept"), Component.translatable("gui.cancel"));
    }
}
