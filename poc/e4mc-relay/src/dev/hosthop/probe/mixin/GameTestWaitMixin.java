package dev.hosthop.probe.mixin;

import dev.hosthop.probe.E4mcRelayProbe;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.MultipleTestTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep the empty test server ticking until the network experiment has finished. */
@Mixin(GameTestServer.class)
public abstract class GameTestWaitMixin {
    @Redirect(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/gametest/framework/MultipleTestTracker;isDone()Z"))
    private boolean hosthop$waitForProbe(MultipleTestTracker tracker) {
        if (!E4mcRelayProbe.finished) return false;
        if (E4mcRelayProbe.failure != null) throw new IllegalStateException("Relay probe failed", E4mcRelayProbe.failure);
        return tracker.isDone();
    }
}
