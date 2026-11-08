package dev.hosthop.relay.mixin;

import dev.hosthop.relay.E4mcRelayBridge;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "link.e4mc.QuiclimeSession$2$1", remap = false)
public abstract class E4mcAssignmentMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Llink/e4mc/QuiclimeSession$ControlMessageCodec$ControlMessage;)V", at = @At("HEAD"), remap = false, require = 0)
    private void hosthop$assigned(ChannelHandlerContext ctx, @Coerce Object message, CallbackInfo ci) {
        try {
            if (!message.getClass().getName().equals("link.e4mc.QuiclimeSession$ControlMessageCodec$DomainAssignmentCompleteMessageClientbound")) return;
            var field = message.getClass().getDeclaredField("domain");
            if (!field.trySetAccessible() || !(field.get(message) instanceof String address)) return;
            Object source = E4mcRelayBridge.outer(E4mcRelayBridge.outer(this, "this$1"), "this$0");
            E4mcRelayBridge.assigned(source, address);
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Fail closed if e4mc internals differ. */ }
    }
}
