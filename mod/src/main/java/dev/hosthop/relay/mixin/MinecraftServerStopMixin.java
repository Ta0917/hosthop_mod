package dev.hosthop.relay.mixin;

import dev.hosthop.world.ServerStopSignal;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStopMixin {
    @Inject(method = "stopServer", at = @At("RETURN"), require = 0)
    private void hosthop$stopped(CallbackInfo ci) { ServerStopSignal.completed((MinecraftServer) (Object) this); }
}
