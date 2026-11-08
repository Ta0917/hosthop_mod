package dev.hosthop.probe.mixin;

import dev.hosthop.probe.CapturedEndpoint;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Version-specific experiment against the inspected e4mc 6.2.3 JAR. */
@Mixin(targets = "link.e4mc.QuiclimeSession$2$1", remap = false)
public abstract class AssignmentMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Llink/e4mc/QuiclimeSession$ControlMessageCodec$ControlMessage;)V", at = @At("HEAD"), remap = false)
    private void hosthop$capture(ChannelHandlerContext ctx, @Coerce Object message, CallbackInfo ci) throws ReflectiveOperationException {
        if (!message.getClass().getName().equals("link.e4mc.QuiclimeSession$ControlMessageCodec$DomainAssignmentCompleteMessageClientbound")) return;
        var field = message.getClass().getDeclaredField("domain");
        if (!field.trySetAccessible()) throw new IllegalStateException("Cannot inspect assignment");
        Object value = field.get(message);
        if (!(value instanceof String address) || address.isBlank()) throw new IllegalStateException("Empty assignment");
        CapturedEndpoint.address = address;
    }
}
