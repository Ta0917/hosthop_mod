package dev.hosthop.probe.mixin;

import dev.hosthop.probe.CapturedEndpoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "link.e4mc.QuiclimeSession", remap = false)
public abstract class StopMixin {
    @Inject(method = "stop()V", at = @At("HEAD"), remap = false)
    private void hosthop$clear(CallbackInfo ci) {
        CapturedEndpoint.address = null;
        System.out.println("HOSTHOP_PROBE assignment cleared on stop");
    }
}
