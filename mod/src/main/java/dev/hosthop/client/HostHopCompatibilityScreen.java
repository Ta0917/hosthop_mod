package dev.hosthop.client;

import dev.hosthop.config.CompatibilityManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Differences from the host are only a warning: client-only mods and their settings need not match, and
 * HostHop cannot tell them apart reliably. The player sees what differs, may join anyway, and may tick
 * mods or config files never to warn about again.
 */
final class HostHopCompatibilityScreen extends Screen {
    private static final int ROW = 22;
    private record Row(String key, boolean mod, Component label) {}
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final BiConsumer<Set<String>, Set<String>> join;
    private final Runnable cancel;
    private final Set<String> ignoredMods = new TreeSet<>(), ignoredConfigs = new TreeSet<>();
    private final boolean modsDiffer;
    private int page;

    /** join receives the mod IDs and config paths the player chose to stop warning about; the caller persists them. */
    HostHopCompatibilityScreen(Screen parent, CompatibilityManifest.Differences differences,
                               BiConsumer<Set<String>, Set<String>> join, Runnable cancel) {
        super(Component.translatable("screen.hosthop.compat_title"));
        this.parent = parent; this.join = join; this.cancel = cancel;
        modsDiffer = !differences.modChanges().isEmpty();
        for (var change : differences.modChanges()) rows.add(new Row(change.id(), true, label(change)));
        for (String config : differences.configs()) rows.add(new Row(config, false, Component.translatable("screen.hosthop.compat_config", config)));
    }

    private int top() { return 48; }
    private int bottom() { return height - 56; }
    private int pageSize() { return Math.max(1, (bottom() - top()) / ROW); }
    private int pages() { return Math.max(1, (rows.size() + pageSize() - 1) / pageSize()); }

    @Override protected void init() {
        page = Math.min(page, pages() - 1);
        int from = page * pageSize(), to = Math.min(rows.size(), from + pageSize());
        int left = Math.max(10, width / 2 - 170), rowWidth = Math.min(340, width - 20);
        for (int i = from; i < to; i++) {
            var row = rows.get(i);
            var selected = row.mod() ? ignoredMods : ignoredConfigs;
            addRenderableWidget(Checkbox.builder(row.label(), font)
                    .pos(left, top() + (i - from) * ROW).maxWidth(rowWidth)
                    .selected(selected.contains(row.key()))
                    .onValueChange((box, value) -> { if (value) selected.add(row.key()); else selected.remove(row.key()); })
                    .build());
        }
        if (pages() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
                    .bounds(width / 2 - 75, bottom() + 2, 20, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
                    .bounds(width / 2 + 55, bottom() + 2, 20, 20).build()).active = page < pages() - 1;
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.hosthop.compat_join"), button -> {
            Minecraft.getInstance().gui.setScreen(parent);
            join.accept(Set.copyOf(ignoredMods), Set.copyOf(ignoredConfigs));
        }).bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 + 4, height - 28, 150, 20).build());
    }

    private static Component label(CompatibilityManifest.ModChange change) {
        if (change.sameVersionDifferentFile())
            return Component.translatable("screen.hosthop.compat_mod_file", change.id(), CompatibilityManifest.ModChange.version(change.localVersion()));
        return Component.translatable("screen.hosthop.compat_mod", change.id(), version(change.hostVersion()), version(change.localVersion()));
    }
    private static Component version(String recorded) {
        String value = CompatibilityManifest.ModChange.version(recorded);
        return value == null ? Component.translatable("screen.hosthop.compat_missing") : Component.literal(value);
    }

    @Override public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
        cancel.run();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 8, -1);
        Component intro = Component.translatable(modsDiffer ? "screen.hosthop.compat_intro" : "screen.hosthop.compat_intro_configs");
        var lines = font.split(intro, Math.max(1, width - 20));
        for (int i = 0; i < Math.min(lines.size(), 2); i++) graphics.centeredText(font, lines.get(i), width / 2, 20 + i * 10, 0xFFFFFF55);
        if (pages() > 1)
            graphics.centeredText(font, Component.translatable("screen.hosthop.compat_page", page + 1, pages()), width / 2, bottom() + 8, -1);
        graphics.centeredText(font, Component.translatable("screen.hosthop.compat_hint"), width / 2, height - 42, 0xFFAAAAAA);
    }
}
