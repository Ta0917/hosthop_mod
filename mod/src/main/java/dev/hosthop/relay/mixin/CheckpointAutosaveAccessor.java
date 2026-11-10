package dev.hosthop.relay.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftServer.class)
public interface CheckpointAutosaveAccessor {
    @Accessor("ticksUntilAutosave")
    void hosthop$setTicksUntilAutosave(int ticks);

    @Invoker("computeNextAutosaveInterval")
    int hosthop$nextAutosaveInterval();
}
