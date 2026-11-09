package dev.hosthop.relay.mixin;

import dev.hosthop.world.SaveKeepAlive;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FML closes the mod classloader when Minecraft's main method returns. */
@Mixin(value = Main.class, remap = false)
public abstract class ClientMainExitMixin {
    @Inject(method = "main", at = @At("RETURN"), require = 1)
    private static void hosthop$finishSaveBeforeLoaderClose(String[] arguments, CallbackInfo ci) {
        SaveKeepAlive.awaitCompletion();
    }
}
