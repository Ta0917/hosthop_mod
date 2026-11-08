package dev.hosthop.relay.mixin;

import dev.hosthop.relay.E4mcRelayBridge;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "link.e4mc.QuiclimeSession", remap = false)
public abstract class E4mcSessionMixin {
    @Inject(method = "<init>(Lio/netty/channel/ChannelHandler;Lio/netty/channel/EventLoopGroup;)V", at = @At("TAIL"), remap = false, require = 0)
    private void hosthop$begin(ChannelHandler handler, EventLoopGroup group, CallbackInfo ci) { try { E4mcRelayBridge.begin(this); } catch (RuntimeException ignored) { } }
    @Inject(method = "stop()V", at = @At("HEAD"), remap = false, require = 0)
    private void hosthop$stop(CallbackInfo ci) { try { E4mcRelayBridge.end(this); } catch (RuntimeException ignored) { } }
    @Inject(method = "fail(Ljava/lang/Throwable;)V", at = @At("HEAD"), remap = false, require = 0)
    private void hosthop$fail(Throwable error, CallbackInfo ci) { try { E4mcRelayBridge.end(this); } catch (RuntimeException ignored) { } }
}
