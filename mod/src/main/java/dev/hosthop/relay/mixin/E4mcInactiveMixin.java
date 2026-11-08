package dev.hosthop.relay.mixin;

import dev.hosthop.relay.E4mcRelayBridge;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "link.e4mc.QuiclimeSession$1", remap = false)
public abstract class E4mcInactiveMixin {
    @Inject(method = "channelInactive", at = @At("HEAD"), remap = false, require = 0)
    private void hosthop$inactive(ChannelHandlerContext context, CallbackInfo ci) {
        try { E4mcRelayBridge.end(E4mcRelayBridge.outer(this, "this$0")); }
        catch (ReflectiveOperationException | RuntimeException ignored) { /* Leave endpoint unpublished on mismatch. */ }
    }
}
