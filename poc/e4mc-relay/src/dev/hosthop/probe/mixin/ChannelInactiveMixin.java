package dev.hosthop.probe.mixin;

import dev.hosthop.probe.CapturedEndpoint;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "link.e4mc.QuiclimeSession$1", remap = false)
public abstract class ChannelInactiveMixin {
    @Inject(method = "channelInactive", at = @At("HEAD"), remap = false)
    private void hosthop$clearOnInactive(ChannelHandlerContext context, CallbackInfo ci) throws ReflectiveOperationException {
        Object session = CapturedEndpoint.outer(this, "this$0");
        if (CapturedEndpoint.end(session)) System.out.println("HOSTHOP_PROBE assignment cleared on channel inactive");
    }
}
