package dev.hosthop.probe.mixin;

import dev.hosthop.probe.CapturedEndpoint;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "link.e4mc.QuiclimeSession", remap = false)
public abstract class StopMixin {
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void hosthop$begin(ChannelHandler handler, EventLoopGroup group, CallbackInfo ci) {
        CapturedEndpoint.begin(this);
    }

    @Inject(method = "stop()V", at = @At("HEAD"), remap = false)
    private void hosthop$clear(CallbackInfo ci) {
        if (CapturedEndpoint.end(this)) System.out.println("HOSTHOP_PROBE assignment cleared on stop");
    }

    @Inject(method = "fail(Ljava/lang/Throwable;)V", at = @At("HEAD"), remap = false)
    private void hosthop$clearOnFailure(Throwable error, CallbackInfo ci) {
        if (CapturedEndpoint.end(this)) System.out.println("HOSTHOP_PROBE assignment cleared on failure");
    }
}
