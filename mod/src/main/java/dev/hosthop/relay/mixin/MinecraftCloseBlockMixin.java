package dev.hosthop.relay.mixin;

import dev.hosthop.client.HostWorldRuntime;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A window close request while hosting becomes "save, then quit" instead of ending the process mid-save. */
@Mixin(Minecraft.class)
public abstract class MinecraftCloseBlockMixin {
    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void hosthop$deferClose(boolean advanceGameTime, CallbackInfo ci) {
        var minecraft = (Minecraft) (Object) this;
        if (HostWorldRuntime.closeBlocked() && minecraft.getWindow().shouldClose()) {
            GLFW.glfwSetWindowShouldClose(minecraft.getWindow().handle(), false);
            HostWorldRuntime.requestQuitAfterSave();
        }
    }
}
