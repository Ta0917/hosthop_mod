package dev.hosthop.stopprobe.mixin;

import dev.hosthop.stopprobe.HostStopProbe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class ServerStopProbeMixin {
    @Inject(method = "processPacketsAndTick", at = @At("HEAD"), cancellable = true)
    private void hosthop$beforePackets(boolean sprinting, CallbackInfo ci) {
        if (HostStopProbe.beforePackets((MinecraftServer) (Object) this)) ci.cancel();
    }

    @Inject(method = "processPacketsAndTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void hosthop$observePackets(boolean sprinting, CallbackInfo ci) {
        HostStopProbe.packetProcessingReached((MinecraftServer) (Object) this);
    }

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"), cancellable = true)
    private void hosthop$beforeWaiting(CallbackInfo ci) {
        if (HostStopProbe.beforeWaiting((MinecraftServer) (Object) this)) ci.cancel();
    }
}
