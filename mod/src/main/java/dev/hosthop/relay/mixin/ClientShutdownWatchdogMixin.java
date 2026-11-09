package dev.hosthop.relay.mixin;

import com.mojang.blaze3d.platform.ClientShutdownWatchdog;
import dev.hosthop.client.HostWorldRuntime;
import dev.hosthop.world.SaveKeepAlive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not mistake a deliberately retained save worker for a hung client. */
@Mixin(value = ClientShutdownWatchdog.class, remap = false)
public abstract class ClientShutdownWatchdogMixin {
    @Inject(method = "startShutdownWatchdog", at = @At("HEAD"), cancellable = true, require = 1)
    private static void hosthop$allowBackgroundSave(String callsite, boolean forceShutdown, Minecraft minecraft,
                                                     GameConfig gameConfig, long mainThreadId, CallbackInfo ci) {
        if (SaveKeepAlive.suppressWatchdog(callsite, HostWorldRuntime.active())) ci.cancel();
    }
}
