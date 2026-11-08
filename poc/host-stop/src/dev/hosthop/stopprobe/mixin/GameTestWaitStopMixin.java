package dev.hosthop.stopprobe.mixin;

import dev.hosthop.stopprobe.HostStopProbe;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GameTestServer overrides the base wait method; needed only for this probe. */
@Mixin(GameTestServer.class)
public abstract class GameTestWaitStopMixin {
    @Inject(method = "waitUntilNextTick", at = @At("HEAD"), cancellable = true)
    private void hosthop$beforeWaiting(CallbackInfo ci) {
        if (HostStopProbe.beforeWaiting((MinecraftServer) (Object) this)) ci.cancel();
    }
}
